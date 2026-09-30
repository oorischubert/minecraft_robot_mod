package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.EmptyBlockView;

/**
 * Block states baked into textured faces for the camera renderer, read from the same blockstate and
 * model JSON files the game client uses. Blocks the client draws with code instead of a model (chests,
 * beds, signs, heads) become their outline shape wearing their particle texture.
 */
final class MineBotCameraModels {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final MineBotCameraAssets assets;
    private final Map<BlockState, Quad[]> baked = new ConcurrentHashMap<>();
    private final Map<Identifier, Optional<ResolvedModel>> models = new ConcurrentHashMap<>();
    private final Map<Identifier, Optional<ItemModel>> items = new ConcurrentHashMap<>();

    MineBotCameraModels(MineBotCameraAssets assets) {
        this.assets = assets;
    }

    MineBotCameraAssets assets() {
        return this.assets;
    }

    Quad[] get(BlockState state) {
        Quad[] quads = this.baked.get(state);
        if (quads == null) {
            quads = this.bake(state);
            Quad[] raced = this.baked.putIfAbsent(state, quads);
            if (raced != null) {
                quads = raced;
            }
        }
        return quads;
    }

    private Quad[] bake(BlockState state) {
        if (state.isAir()) {
            return new Quad[0];
        }
        try {
            List<Quad> quads = new ArrayList<>();
            Identifier blockId = Registries.BLOCK.getId(state.getBlock());
            JsonObject definition = this.assets.json(blockId.getNamespace(), "blockstates/" + blockId.getPath() + ".json");
            String particle = null;
            if (definition != null) {
                for (Variant variant : selectVariants(definition, state)) {
                    ResolvedModel model = this.resolveModel(variant.model());
                    if (model == null) {
                        continue;
                    }
                    if (particle == null) {
                        particle = model.resolve("#particle");
                    }
                    this.addElements(model, variant, quads);
                }
            }
            if (quads.isEmpty() && state.hasBlockEntity() && particle != null) {
                this.addOutlineShape(state, this.texture(particle), quads);
            }
            return quads.toArray(Quad[]::new);
        } catch (RuntimeException exception) {
            MineBotMod.LOGGER.debug("MineBot camera could not bake {}", state, exception);
            return new Quad[0];
        }
    }

    /**
     * How an item looks lying on the ground, from its item definition (assets/<ns>/items/<id>.json): flat layer
     * textures for most items, block-style faces for block items. Null when the definition picks a model by code.
     */
    ItemModel item(Identifier itemModelId) {
        return this.items.computeIfAbsent(itemModelId, this::loadItem).orElse(null);
    }

    private Optional<ItemModel> loadItem(Identifier itemModelId) {
        try {
            JsonObject definition = this.assets.json(itemModelId.getNamespace(), "items/" + itemModelId.getPath() + ".json");
            String modelId = definition == null ? null : itemModelOf(definition.get("model"));
            ResolvedModel model = modelId == null ? null : this.resolveModel(Identifier.of(modelId));
            if (model == null) {
                return Optional.empty();
            }
            if (model.elements() != null) {
                List<Quad> quads = new ArrayList<>();
                this.addElements(model, Variant.NONE, quads);
                return Optional.of(new ItemModel(List.of(), quads.toArray(Quad[]::new)));
            }
            if (!model.generated()) {
                return Optional.empty();
            }
            List<MineBotCameraAssets.Texture> layers = new ArrayList<>();
            for (int layer = 0; model.textures().containsKey("layer" + layer); layer++) {
                layers.add(this.texture(model.resolve("#layer" + layer)));
            }
            return layers.isEmpty() ? Optional.empty() : Optional.of(new ItemModel(layers, new Quad[0]));
        } catch (RuntimeException exception) {
            MineBotMod.LOGGER.debug("MineBot camera could not read item model {}", itemModelId, exception);
            return Optional.empty();
        }
    }

    // Item definitions choose between models by game state; the fallback or first choice stands in for all of them.
    private static String itemModelOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject node = element.getAsJsonObject();
        String type = node.has("type") ? node.get("type").getAsString().replace("minecraft:", "") : "";
        return switch (type) {
            case "model" -> node.get("model").getAsString();
            case "condition" -> itemModelOf(node.get("on_false"));
            case "select", "range_dispatch" -> {
                if (node.has("fallback")) {
                    yield itemModelOf(node.get("fallback"));
                }
                JsonArray options = node.has("cases") ? node.getAsJsonArray("cases") : node.getAsJsonArray("entries");
                yield options == null || options.isEmpty() ? null : itemModelOf(options.get(0).getAsJsonObject().get("model"));
            }
            case "composite" -> {
                JsonArray parts = node.getAsJsonArray("models");
                yield parts == null || parts.isEmpty() ? null : itemModelOf(parts.get(0));
            }
            default -> null;
        };
    }

    /** Flat layers (a generated item sprite) or block-style faces in 0..1 item space. */
    record ItemModel(List<MineBotCameraAssets.Texture> layers, Quad[] quads) {
    }

    // ------------------------------------------------------------------ blockstate files

    private static List<Variant> selectVariants(JsonObject definition, BlockState state) {
        List<Variant> selected = new ArrayList<>();
        if (definition.has("variants")) {
            for (Map.Entry<String, JsonElement> entry : definition.getAsJsonObject("variants").entrySet()) {
                if (matchesVariantKey(entry.getKey(), state)) {
                    selected.add(parseVariant(entry.getValue()));
                    break;
                }
            }
        } else if (definition.has("multipart")) {
            for (JsonElement element : definition.getAsJsonArray("multipart")) {
                JsonObject part = element.getAsJsonObject();
                if (!part.has("when") || matchesCondition(part.getAsJsonObject("when"), state)) {
                    selected.add(parseVariant(part.get("apply")));
                }
            }
        }
        return selected;
    }

    private static boolean matchesVariantKey(String key, BlockState state) {
        if (key.isEmpty()) {
            return true;
        }
        for (String pair : key.split(",")) {
            int equals = pair.indexOf('=');
            if (equals < 0 || !pair.substring(equals + 1).equals(propertyValue(state, pair.substring(0, equals)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesCondition(JsonObject condition, BlockState state) {
        if (condition.has("OR")) {
            for (JsonElement option : condition.getAsJsonArray("OR")) {
                if (matchesCondition(option.getAsJsonObject(), state)) {
                    return true;
                }
            }
            return false;
        }
        if (condition.has("AND")) {
            for (JsonElement option : condition.getAsJsonArray("AND")) {
                if (!matchesCondition(option.getAsJsonObject(), state)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : condition.entrySet()) {
            String expected = entry.getValue().getAsString();
            boolean negate = expected.startsWith("!");
            String actual = propertyValue(state, entry.getKey());
            boolean matches = false;
            for (String option : (negate ? expected.substring(1) : expected).split("\\|")) {
                matches |= option.equals(actual);
            }
            if (matches == negate) {
                return false;
            }
        }
        return true;
    }

    private static String propertyValue(BlockState state, String name) {
        Property<?> property = state.getBlock().getStateManager().getProperty(name);
        return property == null ? null : valueName(state, property);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }

    // Weighted lists pick their first entry, so a block always looks the same from one frame to the next.
    private static Variant parseVariant(JsonElement element) {
        JsonObject variant = element.isJsonArray() ? element.getAsJsonArray().get(0).getAsJsonObject() : element.getAsJsonObject();
        return new Variant(
            Identifier.of(variant.get("model").getAsString()),
            variant.has("x") ? variant.get("x").getAsInt() : 0,
            variant.has("y") ? variant.get("y").getAsInt() : 0
        );
    }

    // ------------------------------------------------------------------ model files

    private ResolvedModel resolveModel(Identifier id) {
        return this.models.computeIfAbsent(id, this::loadModel).orElse(null);
    }

    private Optional<ResolvedModel> loadModel(Identifier id) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        boolean generated = false;
        Identifier current = id;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.getPath().equals("builtin/generated")) {
                generated = true;
                break;
            }
            JsonObject json = this.assets.json(current.getNamespace(), "models/" + current.getPath() + ".json");
            if (json == null) {
                if (depth == 0) {
                    return Optional.empty();
                }
                break;
            }
            if (json.has("textures")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                    textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                }
            }
            if (elements == null && json.has("elements")) {
                elements = json.getAsJsonArray("elements");
            }
            current = json.has("parent") ? Identifier.tryParse(json.get("parent").getAsString()) : null;
        }
        return Optional.of(new ResolvedModel(textures, elements, generated));
    }

    private MineBotCameraAssets.Texture texture(String name) {
        Identifier id = name == null ? null : Identifier.tryParse(name);
        return id == null ? MineBotCameraAssets.Texture.MISSING : this.assets.texture(id);
    }

    private void addElements(ResolvedModel model, Variant variant, List<Quad> quads) {
        if (model.elements() == null) {
            return;
        }
        for (JsonElement entry : model.elements()) {
            JsonObject element = entry.getAsJsonObject();
            double[] from = vector(element.getAsJsonArray("from"));
            double[] to = vector(element.getAsJsonArray("to"));
            ElementRotation rotation = element.has("rotation") ? ElementRotation.parse(element.getAsJsonObject("rotation")) : null;
            boolean shade = !element.has("shade") || element.get("shade").getAsBoolean();

            for (Map.Entry<String, JsonElement> faceEntry : element.getAsJsonObject("faces").entrySet()) {
                Direction side = Direction.byId(faceEntry.getKey());
                if (side == null) {
                    continue;
                }
                JsonObject face = faceEntry.getValue().getAsJsonObject();
                float[] uv = face.has("uv") ? uv(face.getAsJsonArray("uv")) : defaultUv(side, from, to);
                addIfPresent(quads, buildQuad(
                    side,
                    from,
                    to,
                    rotation,
                    variant,
                    this.texture(model.resolve(face.get("texture").getAsString())),
                    uv,
                    face.has("rotation") ? Math.floorMod(face.get("rotation").getAsInt(), 360) : 0,
                    face.has("tintindex") ? face.get("tintindex").getAsInt() : -1,
                    shade,
                    face.has("cullface") ? Direction.byId(face.get("cullface").getAsString()) : null,
                    quads.size()
                ));
            }
        }
    }

    private void addOutlineShape(BlockState state, MineBotCameraAssets.Texture texture, List<Quad> quads) {
        List<Box> boxes;
        try {
            boxes = state.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).getBoundingBoxes();
        } catch (RuntimeException exception) {
            boxes = List.of(new Box(0, 0, 0, 1, 1, 1));
        }
        for (Box box : boxes) {
            double[] from = {box.minX * 16, box.minY * 16, box.minZ * 16};
            double[] to = {box.maxX * 16, box.maxY * 16, box.maxZ * 16};
            for (Direction side : DIRECTIONS) {
                addIfPresent(quads, buildQuad(side, from, to, null, Variant.NONE, texture, defaultUv(side, from, to), 0, -1, true, null, quads.size()));
            }
        }
    }

    // ------------------------------------------------------------------ geometry

    private static void addIfPresent(List<Quad> quads, Quad quad) {
        if (quad != null) {
            quads.add(quad);
        }
    }

    /*
     * Returns null for a face with no area. Corner o of a face is where texture u and v start: u runs left to right and v top to bottom as you
     * look at the face from outside, which is the game's default UV layout for each side.
     */
    private static Quad buildQuad(
        Direction side,
        double[] f,
        double[] t,
        ElementRotation elementRotation,
        Variant variant,
        MineBotCameraAssets.Texture texture,
        float[] uv,
        int faceRotation,
        int tintIndex,
        boolean shade,
        Direction cullFace,
        int order
    ) {
        double[][] corners = switch (side) {
            case DOWN -> new double[][] {{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], f[2]}};
            case UP -> new double[][] {{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {f[0], t[1], t[2]}};
            case NORTH -> new double[][] {{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {t[0], f[1], f[2]}};
            case SOUTH -> new double[][] {{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {f[0], f[1], t[2]}};
            case WEST -> new double[][] {{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], f[2]}};
            case EAST -> new double[][] {{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], t[2]}};
        };
        double[] normal = {side.getOffsetX(), side.getOffsetY(), side.getOffsetZ()};

        for (double[] corner : corners) {
            if (elementRotation != null) {
                elementRotation.apply(corner, true);
            }
            variant.apply(corner, true);
        }
        if (elementRotation != null) {
            elementRotation.apply(normal, false);
        }
        variant.apply(normal, false);

        Direction rotatedCull = null;
        if (cullFace != null) {
            double[] cull = {cullFace.getOffsetX(), cullFace.getOffsetY(), cullFace.getOffsetZ()};
            variant.apply(cull, false);
            rotatedCull = Direction.fromVector((int) Math.round(cull[0]), (int) Math.round(cull[1]), (int) Math.round(cull[2]), null);
        }

        double length = Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
        double nx = normal[0] / length;
        double ny = normal[1] / length;
        double nz = normal[2] / length;
        // The game's per-side shading: top 1.0, bottom 0.5, north/south 0.8, east/west 0.6.
        float shadeFactor = shade ? (float) Math.min(1.0D, nx * nx * 0.6D + ny * ny * ((3.0D + ny) / 4.0D) + nz * nz * 0.8D) : 1.0F;

        double sx = (corners[1][0] - corners[0][0]) / 16;
        double sy = (corners[1][1] - corners[0][1]) / 16;
        double sz = (corners[1][2] - corners[0][2]) / 16;
        double tx = (corners[2][0] - corners[0][0]) / 16;
        double ty = (corners[2][1] - corners[0][1]) / 16;
        double tz = (corners[2][2] - corners[0][2]) / 16;
        double sLengthSq = sx * sx + sy * sy + sz * sz;
        double tLengthSq = tx * tx + ty * ty + tz * tz;
        if (sLengthSq < 1.0E-9D || tLengthSq < 1.0E-9D) {
            return null;
        }

        return new Quad(
            corners[0][0] / 16, corners[0][1] / 16, corners[0][2] / 16,
            sx, sy, sz, 1.0D / sLengthSq,
            tx, ty, tz, 1.0D / tLengthSq,
            nx, ny, nz,
            texture, uv[0], uv[1], uv[2], uv[3], faceRotation, tintIndex, shadeFactor, rotatedCull, order
        );
    }

    private static float[] defaultUv(Direction side, double[] f, double[] t) {
        return switch (side) {
            case DOWN -> new float[] {(float) f[0], (float) (16 - t[2]), (float) t[0], (float) (16 - f[2])};
            case UP -> new float[] {(float) f[0], (float) f[2], (float) t[0], (float) t[2]};
            case NORTH -> new float[] {(float) (16 - t[0]), (float) (16 - t[1]), (float) (16 - f[0]), (float) (16 - f[1])};
            case SOUTH -> new float[] {(float) f[0], (float) (16 - t[1]), (float) t[0], (float) (16 - f[1])};
            case WEST -> new float[] {(float) f[2], (float) (16 - t[1]), (float) t[2], (float) (16 - f[1])};
            case EAST -> new float[] {(float) (16 - t[2]), (float) (16 - t[1]), (float) (16 - f[2]), (float) (16 - f[1])};
        };
    }

    private static double[] vector(JsonArray array) {
        return new double[] {array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
    }

    private static float[] uv(JsonArray array) {
        return new float[] {array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }

    /** One textured face in block space (0..1); see buildQuad for the corner layout. sInv and tInv are 1 / squared edge length. */
    record Quad(
        double ox, double oy, double oz,
        double sx, double sy, double sz, double sInv,
        double tx, double ty, double tz, double tInv,
        double nx, double ny, double nz,
        MineBotCameraAssets.Texture texture,
        float u1, float v1, float u2, float v2,
        int rotation,
        int tintIndex,
        float shade,
        Direction cullFace,
        int order
    ) {
        /** Texture ARGB at face position s (along u) and t (along v), both 0..1. */
        int sample(double s, double t) {
            double[] uv = this.uv(s, t);
            return this.texture.sample(uv[0], uv[1]);
        }

        /** Texture coordinates (0..1) at face position s, t, after the face's UV rectangle and rotation. */
        double[] uv(double s, double t) {
            double a;
            double b;
            switch (this.rotation) {
                case 90 -> {
                    a = t;
                    b = 1.0D - s;
                }
                case 180 -> {
                    a = 1.0D - s;
                    b = 1.0D - t;
                }
                case 270 -> {
                    a = 1.0D - t;
                    b = s;
                }
                default -> {
                    a = s;
                    b = t;
                }
            }
            return new double[] {(this.u1 + a * (this.u2 - this.u1)) / 16.0D, (this.v1 + b * (this.v2 - this.v1)) / 16.0D};
        }
    }

    private record ResolvedModel(Map<String, String> textures, JsonArray elements, boolean generated) {
        String resolve(String reference) {
            String value = reference;
            for (int depth = 0; value != null && value.startsWith("#") && depth < 16; depth++) {
                value = this.textures.get(value.substring(1));
            }
            return value;
        }
    }

    /** A blockstate's x/y turn of a whole model, in steps of 90 degrees, about the block centre. */
    private record Variant(Identifier model, int x, int y) {
        static final Variant NONE = new Variant(null, 0, 0);

        // The game turns X first, then Y, both clockwise looking along the positive axis.
        void apply(double[] p, boolean point) {
            double c = point ? 8.0D : 0.0D;
            if (this.x != 0) {
                int cos = cos90(this.x);
                int sin = sin90(this.x);
                double y = p[1] - c;
                double z = p[2] - c;
                p[1] = y * cos + z * sin + c;
                p[2] = -y * sin + z * cos + c;
            }
            if (this.y != 0) {
                int cos = cos90(this.y);
                int sin = sin90(this.y);
                double x = p[0] - c;
                double z = p[2] - c;
                p[0] = x * cos - z * sin + c;
                p[2] = x * sin + z * cos + c;
            }
        }

        private static int cos90(int degrees) {
            return switch (Math.floorMod(degrees, 360)) {
                case 90, 270 -> 0;
                case 180 -> -1;
                default -> 1;
            };
        }

        private static int sin90(int degrees) {
            return switch (Math.floorMod(degrees, 360)) {
                case 90 -> 1;
                case 270 -> -1;
                default -> 0;
            };
        }
    }

    /** An element's own tilt about an origin, with the optional rescale that keeps crossed planes full width. */
    private record ElementRotation(double[] origin, char axis, double angle, boolean rescale) {
        static ElementRotation parse(JsonObject json) {
            return new ElementRotation(
                vector(json.getAsJsonArray("origin")),
                json.get("axis").getAsString().charAt(0),
                json.get("angle").getAsDouble(),
                json.has("rescale") && json.get("rescale").getAsBoolean()
            );
        }

        void apply(double[] p, boolean point) {
            if (this.angle == 0.0D) {
                return;
            }
            double radians = Math.toRadians(this.angle);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double scale = point && this.rescale ? 1.0D / cos : 1.0D;
            double ox = point ? this.origin[0] : 0.0D;
            double oy = point ? this.origin[1] : 0.0D;
            double oz = point ? this.origin[2] : 0.0D;
            double x = p[0] - ox;
            double y = p[1] - oy;
            double z = p[2] - oz;
            switch (this.axis) {
                case 'x' -> {
                    p[1] = (y * cos - z * sin) * scale + oy;
                    p[2] = (y * sin + z * cos) * scale + oz;
                }
                case 'y' -> {
                    p[0] = (x * cos + z * sin) * scale + ox;
                    p[2] = (-x * sin + z * cos) * scale + oz;
                }
                default -> {
                    p[0] = (x * cos - y * sin) * scale + ox;
                    p[1] = (x * sin + y * cos) * scale + oy;
                }
            }
        }
    }
}
