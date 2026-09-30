package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.entity.mob.SlimeEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.ChickenVariant;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.CowVariant;
import net.minecraft.entity.passive.MooshroomEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.PigVariant;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.ModelAndTexture;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerDataContainer;
import net.minecraft.village.VillagerProfession;

/**
 * Mobs, players, dropped items and falling blocks for the robot camera. Mob shapes are the game's own
 * entity models, exported from its model code to assets/minebot/camera/entity_models.json; textures come
 * from the client jar and player skins from Mojang's skin server. Everyone stands still, facing and looking
 * where they are. Entities without a model here stay plain boxes coloured by kind.
 */
final class MineBotCameraEntities {
    private static final String MODELS_RESOURCE = "assets/minebot/camera/entity_models.json";
    private static final double ZOMBIE_ARM_PITCH = -Math.PI / 2.25D;
    private static final String[] DEFAULT_SKINS = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};
    private static final String[] VILLAGER_LEVELS = {"stone", "iron", "gold", "emerald", "diamond"};

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final Map<String, Optional<MineBotCameraAssets.Texture>> SKINS = new ConcurrentHashMap<>();
    private static final Map<List<Identifier>, Optional<MineBotCameraAssets.Texture>> COMPOSITES = new ConcurrentHashMap<>();
    private static final Map<Integer, MineBotCameraAssets.Texture> BOX_TEXTURES = new ConcurrentHashMap<>();
    private static volatile Map<String, Part> models;

    private MineBotCameraEntities() {
    }

    // ------------------------------------------------------------------ capture (server thread)

    enum Kind { MODEL, ITEM, BLOCK, BOX }

    enum Pose { STANDING, ZOMBIE_ARMS }

    /** One model drawn with one texture. skinUrl, when set, replaces the textures and falls back to them with fallbackModel. */
    record Layer(String model, List<Identifier> textures, String skinUrl, String fallbackModel, int tint) {
        static Layer of(String model, Identifier... textures) {
            return new Layer(model, List.of(textures), null, null, -1);
        }
    }

    /** An entity as copied on the server thread: everything needed to pose and dress it elsewhere. */
    record Capture(
        Kind kind,
        double x,
        double y,
        double z,
        float bodyYaw,
        float headYaw,
        float headPitch,
        float scale,
        float modelScale,
        Pose pose,
        List<Layer> layers,
        Identifier itemModel,
        BlockState block,
        Box box,
        int color,
        int light
    ) {
    }

    private record Look(List<Layer> layers, Pose pose, float modelScale) {
    }

    static Capture capture(Entity entity, int color, int light) {
        Box box = entity.getBoundingBox();
        try {
            if (entity instanceof ItemEntity item) {
                ItemStack stack = item.getStack();
                Identifier model = stack.get(DataComponentTypes.ITEM_MODEL);
                BlockState block = stack.getItem() instanceof BlockItem blockItem ? blockItem.getBlock().getDefaultState() : null;
                return new Capture(
                    Kind.ITEM, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0, 1, 1, Pose.STANDING, List.of(),
                    model != null ? model : Registries.ITEM.getId(stack.getItem()), block, box, color, light
                );
            }
            if (entity instanceof FallingBlockEntity falling) {
                return new Capture(
                    Kind.BLOCK, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0, 1, 1, Pose.STANDING, List.of(),
                    null, falling.getBlockState(), box, color, light
                );
            }
            if (entity instanceof LivingEntity living) {
                Look look = lookOf(living);
                if (look != null) {
                    return new Capture(
                        Kind.MODEL,
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        living.getBodyYaw(),
                        MathHelper.wrapDegrees(living.getHeadYaw() - living.getBodyYaw()),
                        living.getPitch(),
                        living.getScale(),
                        look.modelScale(),
                        look.pose(),
                        look.layers(),
                        null,
                        null,
                        box,
                        color,
                        light
                    );
                }
            }
        } catch (RuntimeException exception) {
            MineBotMod.LOGGER.debug("MineBot camera could not read {}", entity, exception);
        }
        return new Capture(Kind.BOX, entity.getX(), entity.getY(), entity.getZ(), 0, 0, 0, 1, 1, Pose.STANDING, List.of(), null, null, box, color, light);
    }

    // Which exported model and textures each mob wears; mirrors the game's entity renderers.
    private static Look lookOf(LivingEntity entity) {
        EntityType<?> type = entity.getType();
        String baby = entity.isBaby() ? "_baby" : "";

        if (entity instanceof PlayerLikeEntity playerLike) {
            return playerLook(playerLike);
        }
        if (entity instanceof MineBotEntity robot) {
            String state = robot.isEvil() ? "evil" : robot.isConnected() ? "connected" : "idle";
            return new Look(List.of(Layer.of("biped", MineBotMod.id("textures/entity/minebot/" + robot.getSkin().id() + "_" + state + ".png"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.ZOMBIE) {
            return new Look(List.of(Layer.of("biped" + baby, entityTexture("zombie/zombie"))), Pose.ZOMBIE_ARMS, 1.0F);
        }
        if (type == EntityType.HUSK) {
            return new Look(List.of(Layer.of("husk" + baby, entityTexture("zombie/husk"))), Pose.ZOMBIE_ARMS, 1.0F);
        }
        if (type == EntityType.DROWNED) {
            List<Layer> layers = new ArrayList<>(List.of(Layer.of("drowned" + baby, entityTexture("zombie/drowned"))));
            if (baby.isEmpty()) {
                layers.add(Layer.of("drowned_outer", entityTexture("zombie/drowned_outer_layer")));
            }
            return new Look(layers, Pose.ZOMBIE_ARMS, 1.0F);
        }
        if (type == EntityType.ZOMBIE_VILLAGER && entity instanceof VillagerDataContainer villager) {
            return new Look(
                List.of(new Layer("zombie_villager" + baby, villagerTextures("zombie_villager", villager.getVillagerData(), entity.isBaby()), null, null, -1)),
                Pose.ZOMBIE_ARMS,
                1.0F
            );
        }
        if (type == EntityType.VILLAGER && entity instanceof VillagerDataContainer villager) {
            return new Look(
                List.of(new Layer("villager" + baby, villagerTextures("villager", villager.getVillagerData(), entity.isBaby()), null, null, -1)),
                Pose.STANDING,
                1.0F
            );
        }
        if (type == EntityType.SKELETON) {
            return new Look(List.of(Layer.of("skeleton", entityTexture("skeleton/skeleton"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.STRAY) {
            return new Look(
                List.of(Layer.of("skeleton", entityTexture("skeleton/stray")), Layer.of("skeleton_outer_025", entityTexture("skeleton/stray_overlay"))),
                Pose.STANDING,
                1.0F
            );
        }
        if (type == EntityType.WITHER_SKELETON) {
            return new Look(List.of(Layer.of("wither_skeleton", entityTexture("skeleton/wither_skeleton"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.BOGGED) {
            return new Look(
                List.of(Layer.of("bogged", entityTexture("skeleton/bogged")), Layer.of("bogged_outer", entityTexture("skeleton/bogged_overlay"))),
                Pose.STANDING,
                1.0F
            );
        }
        if (type == EntityType.PARCHED) {
            return new Look(List.of(Layer.of("parched", entityTexture("skeleton/parched"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.ENDERMAN) {
            return new Look(List.of(Layer.of("enderman", entityTexture("enderman/enderman"), entityTexture("enderman/enderman_eyes"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.CREEPER) {
            return new Look(List.of(Layer.of("creeper", entityTexture("creeper/creeper"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.SPIDER) {
            return new Look(List.of(Layer.of("spider", entityTexture("spider/spider"), entityTexture("spider_eyes"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.CAVE_SPIDER) {
            return new Look(List.of(Layer.of("cave_spider", entityTexture("spider/cave_spider"), entityTexture("spider_eyes"))), Pose.STANDING, 1.0F);
        }
        if (type == EntityType.MOOSHROOM && entity instanceof MooshroomEntity mooshroom) {
            return new Look(List.of(Layer.of("cow" + baby, entityTexture("cow/" + mooshroom.getVariant().asString() + "_mooshroom"))), Pose.STANDING, 1.0F);
        }
        if (entity instanceof CowEntity cow) {
            ModelAndTexture<CowVariant.Model> variant = cow.getVariant().value().modelAndTexture();
            String model = switch (variant.model()) {
                case COLD -> "cold_cow";
                case WARM -> "warm_cow";
                default -> "cow";
            };
            return new Look(List.of(Layer.of(model + baby, variant.asset().texturePath())), Pose.STANDING, 1.0F);
        }
        if (entity instanceof PigEntity pig) {
            ModelAndTexture<PigVariant.Model> variant = pig.getVariant().value().modelAndTexture();
            String model = variant.model() == PigVariant.Model.COLD ? "cold_pig" : "pig";
            return new Look(List.of(Layer.of(model + baby, variant.asset().texturePath())), Pose.STANDING, 1.0F);
        }
        if (entity instanceof ChickenEntity chicken) {
            ModelAndTexture<ChickenVariant.Model> variant = chicken.getVariant().value().modelAndTexture();
            String model = variant.model() == ChickenVariant.Model.COLD ? "cold_chicken" : "chicken";
            return new Look(List.of(Layer.of(model + baby, variant.asset().texturePath())), Pose.STANDING, 1.0F);
        }
        if (entity instanceof SheepEntity sheep) {
            List<Layer> layers = new ArrayList<>(List.of(Layer.of("sheep" + baby, entityTexture("sheep/sheep"))));
            if (!sheep.isSheared()) {
                layers.add(new Layer("sheep_wool" + baby, List.of(entityTexture("sheep/sheep_wool")), null, null, sheep.getColor().getEntityColor() & 0xFFFFFF));
            }
            return new Look(layers, Pose.STANDING, 1.0F);
        }
        if (type == EntityType.MAGMA_CUBE && entity instanceof SlimeEntity cube) {
            return new Look(List.of(Layer.of("magma_cube", entityTexture("slime/magmacube"))), Pose.STANDING, cube.getSize());
        }
        if (type == EntityType.SLIME && entity instanceof SlimeEntity slime) {
            Identifier texture = entityTexture("slime/slime");
            return new Look(List.of(Layer.of("slime", texture), Layer.of("slime_outer", texture)), Pose.STANDING, slime.getSize());
        }
        return null;
    }

    /*
     * Players and mannequins wear the Mojang skin their profile carries (online-mode servers, or a mannequin given one),
     * then a mannequin's texture override, else the game's default skin for their id.
     */
    private static Look playerLook(PlayerLikeEntity entity) {
        GameProfile profile;
        SkinTextures.SkinOverride override = SkinTextures.SkinOverride.EMPTY;
        if (entity instanceof PlayerEntity player) {
            profile = player.getGameProfile();
        } else {
            ProfileComponent component = entity.get(DataComponentTypes.PROFILE);
            profile = component == null ? null : component.getGameProfile();
            override = component == null ? SkinTextures.SkinOverride.EMPTY : component.getOverride();
        }
        UUID id = profile != null && profile.id() != null ? profile.id() : entity.getUuid();
        int index = Math.floorMod(id.hashCode(), DEFAULT_SKINS.length * 2);
        boolean defaultSlim = index < DEFAULT_SKINS.length;
        Identifier defaultSkin = entityTexture("player/" + (defaultSlim ? "slim/" : "wide/") + DEFAULT_SKINS[index % DEFAULT_SKINS.length]);
        if (override.body().isPresent()) {
            defaultSkin = override.body().get().texturePath();
        }
        if (override.model().isPresent()) {
            defaultSlim = override.model().get() == PlayerSkinType.SLIM;
        }
        String defaultModel = defaultSlim ? "player_slim" : "player";

        String url = null;
        boolean slim = false;
        for (Property property : profile == null || override.body().isPresent() ? List.<Property>of() : profile.properties().get("textures")) {
            try {
                JsonObject textures = JsonParser.parseString(new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8))
                    .getAsJsonObject()
                    .getAsJsonObject("textures");
                JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
                if (skin != null && skin.has("url")) {
                    url = skin.get("url").getAsString();
                    JsonObject metadata = skin.getAsJsonObject("metadata");
                    slim = metadata != null && metadata.has("model") && "slim".equals(metadata.get("model").getAsString());
                }
            } catch (RuntimeException exception) {
                MineBotMod.LOGGER.debug("MineBot camera could not read the skin of {}", entity, exception);
            }
        }
        if (url != null && !url.matches("https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]+")) {
            url = null;
        }
        Layer layer = url == null
            ? Layer.of(defaultModel, defaultSkin)
            : new Layer(slim ? "player_slim" : "player", List.of(defaultSkin), url.replace("http://", "https://"), defaultModel, -1);
        return new Look(List.of(layer), Pose.STANDING, 0.9375F);
    }

    private static List<Identifier> villagerTextures(String kind, VillagerData data, boolean baby) {
        List<Identifier> textures = new ArrayList<>();
        textures.add(entityTexture(kind + "/" + kind));
        data.type().getKey().ifPresent(key -> textures.add(key.getValue().withPath(path -> "textures/entity/" + kind + "/type/" + path + ".png")));
        if (!baby && !data.profession().matchesKey(VillagerProfession.NONE)) {
            data.profession().getKey().ifPresent(key -> textures.add(key.getValue().withPath(path -> "textures/entity/" + kind + "/profession/" + path + ".png")));
            if (!data.profession().matchesKey(VillagerProfession.NITWIT)) {
                textures.add(entityTexture(kind + "/profession_level/" + VILLAGER_LEVELS[MathHelper.clamp(data.level(), 1, VILLAGER_LEVELS.length) - 1]));
            }
        }
        return textures;
    }

    private static Identifier entityTexture(String path) {
        return Identifier.ofVanilla("textures/entity/" + path + ".png");
    }

    // ------------------------------------------------------------------ shapes (render thread)

    /** Colour for a block-style face's tint index, as the renderer works it out for blocks. */
    interface BlockTint {
        int color(BlockState state, int tintIndex);
    }

    /** One textured rectangle in world space. Texture u, v are affine in the face position s, t (both 0..1). */
    static final class Face {
        final double ox, oy, oz, sx, sy, sz, tx, ty, tz, sInv, tInv, nx, ny, nz;
        final double u0, v0, uS, vS, uT, vT;
        final MineBotCameraAssets.Texture texture;
        final int tint;
        final double shade;
        final boolean twoSided;

        Face(double[] o, double[] s, double[] t, double[] n, double[] uv, MineBotCameraAssets.Texture texture, int tint, double shade, boolean twoSided) {
            this.ox = o[0];
            this.oy = o[1];
            this.oz = o[2];
            this.sx = s[0];
            this.sy = s[1];
            this.sz = s[2];
            this.tx = t[0];
            this.ty = t[1];
            this.tz = t[2];
            this.sInv = 1.0D / (s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
            this.tInv = 1.0D / (t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
            this.nx = n[0];
            this.ny = n[1];
            this.nz = n[2];
            this.u0 = uv[0];
            this.v0 = uv[1];
            this.uS = uv[2];
            this.vS = uv[3];
            this.uT = uv[4];
            this.vT = uv[5];
            this.texture = texture;
            this.tint = tint;
            this.shade = shade;
            this.twoSided = twoSided;
        }
    }

    /** An entity's faces with their world bounds, ready for the tracer. */
    static final class Shape {
        final Face[] faces;
        final double minX, minY, minZ, maxX, maxY, maxZ;
        final int light;
        /** Pixel rectangle the shape can cover, set by the renderer; pixels outside it skip the shape. */
        int screenMinX, screenMinY, screenMaxX = Integer.MAX_VALUE, screenMaxY = Integer.MAX_VALUE;

        Shape(List<Face> faces, int light) {
            this.faces = faces.toArray(Face[]::new);
            this.light = light;
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (Face face : this.faces) {
                for (int corner = 0; corner < 4; corner++) {
                    double a = (corner & 1) != 0 ? 1.0D : 0.0D;
                    double b = (corner & 2) != 0 ? 1.0D : 0.0D;
                    double[] p = {face.ox + face.sx * a + face.tx * b, face.oy + face.sy * a + face.ty * b, face.oz + face.sz * a + face.tz * b};
                    for (int axis = 0; axis < 3; axis++) {
                        min[axis] = Math.min(min[axis], p[axis]);
                        max[axis] = Math.max(max[axis], p[axis]);
                    }
                }
            }
            double pad = 1.0E-4D;
            this.minX = min[0] - pad;
            this.minY = min[1] - pad;
            this.minZ = min[2] - pad;
            this.maxX = max[0] + pad;
            this.maxY = max[1] + pad;
            this.maxZ = max[2] + pad;
        }

        /** Distance along the ray to the bounds, or -1 when missed or wholly behind. */
        double enter(double ex, double ey, double ez, double dx, double dy, double dz) {
            double near = 0.0D;
            double far = Double.POSITIVE_INFINITY;
            if (dx != 0.0D) {
                double t1 = (this.minX - ex) / dx;
                double t2 = (this.maxX - ex) / dx;
                near = Math.max(near, Math.min(t1, t2));
                far = Math.min(far, Math.max(t1, t2));
            } else if (ex < this.minX || ex > this.maxX) {
                return -1.0D;
            }
            if (dy != 0.0D) {
                double t1 = (this.minY - ey) / dy;
                double t2 = (this.maxY - ey) / dy;
                near = Math.max(near, Math.min(t1, t2));
                far = Math.min(far, Math.max(t1, t2));
            } else if (ey < this.minY || ey > this.maxY) {
                return -1.0D;
            }
            if (dz != 0.0D) {
                double t1 = (this.minZ - ez) / dz;
                double t2 = (this.maxZ - ez) / dz;
                near = Math.max(near, Math.min(t1, t2));
                far = Math.min(far, Math.max(t1, t2));
            } else if (ez < this.minZ || ez > this.maxZ) {
                return -1.0D;
            }
            return far >= near ? near : -1.0D;
        }
    }

    static Shape build(Capture capture, MineBotCameraModels models, BlockTint tint, double cameraX, double cameraZ) {
        List<Face> faces = new ArrayList<>();
        try {
            switch (capture.kind()) {
                case MODEL -> addModel(capture, models.assets(), faces);
                case ITEM -> addItem(capture, models, tint, cameraX, cameraZ, faces);
                case BLOCK -> addBlockQuads(models.get(capture.block()), capture.block(), tint, capture.x() - 0.5D, capture.y(), capture.z() - 0.5D, 1.0D, faces);
                case BOX -> {
                }
            }
        } catch (RuntimeException exception) {
            MineBotMod.LOGGER.debug("MineBot camera could not draw an entity", exception);
            faces.clear();
        }
        if (faces.isEmpty()) {
            addBox(capture, faces);
        }
        return new Shape(faces, capture.light());
    }

    private static void addModel(Capture capture, MineBotCameraAssets assets, List<Face> faces) {
        Map<String, Part> parts = models(assets);
        // The game's placement: turn to face the body yaw, flip into model space (y down), lift by 1.501 blocks.
        Affine entity = Affine.translation(capture.x(), capture.y(), capture.z())
            .then(Affine.scaling(capture.scale(), capture.scale(), capture.scale()))
            .then(Affine.rotationY(Math.toRadians(180.0D - capture.bodyYaw())))
            .then(Affine.scaling(-1.0D, -1.0D, 1.0D))
            .then(Affine.scaling(capture.modelScale(), capture.modelScale(), capture.modelScale()))
            .then(Affine.translation(0.0D, -1.501D, 0.0D));

        for (int index = 0; index < capture.layers().size(); index++) {
            Layer layer = capture.layers().get(index);
            String modelName = layer.model();
            MineBotCameraAssets.Texture texture = null;
            if (layer.skinUrl() != null) {
                texture = skin(layer.skinUrl());
                if (texture == null) {
                    modelName = layer.fallbackModel();
                }
            }
            if (texture == null) {
                texture = composite(assets, layer.textures());
            }
            Part root = parts.get(modelName);
            if (texture == null || root == null) {
                if (index == 0) {
                    faces.clear();
                    return;
                }
                continue;
            }
            addPart(root, "root", entity, capture, texture, layer.tint(), faces);
        }
    }

    private static void addPart(Part part, String name, Affine parent, Capture capture, MineBotCameraAssets.Texture texture, int tint, List<Face> faces) {
        double pitch = part.pitch;
        double yaw = part.yaw;
        double roll = part.roll;
        if (name.equals("head")) {
            pitch += Math.toRadians(capture.headPitch());
            yaw += Math.toRadians(capture.headYaw());
        }
        if (capture.pose() == Pose.ZOMBIE_ARMS && (name.equals("right_arm") || name.equals("left_arm"))) {
            pitch = ZOMBIE_ARM_PITCH;
            yaw = name.equals("right_arm") ? -0.1D : 0.1D;
        }

        Affine local = parent
            .then(Affine.translation(part.ox / 16.0D, part.oy / 16.0D, part.oz / 16.0D))
            .then(Affine.rotationZYX(roll, yaw, pitch))
            .then(Affine.scaling(part.sx, part.sy, part.sz));

        for (float[] face : part.faces) {
            double[] p0 = local.apply(face[0] / 16.0D, face[1] / 16.0D, face[2] / 16.0D);
            double[] p1 = local.apply(face[5] / 16.0D, face[6] / 16.0D, face[7] / 16.0D);
            double[] p3 = local.apply(face[15] / 16.0D, face[16] / 16.0D, face[17] / 16.0D);
            double[] s = subtract(p1, p0);
            double[] t = subtract(p3, p0);
            if (lengthSquared(s) < 1.0E-12D || lengthSquared(t) < 1.0E-12D) {
                continue;
            }
            double[] normal = normalize(local.applyLinear(face[20], face[21], face[22]));
            double[] uv = {face[3], face[4], face[8] - face[3], face[9] - face[4], face[18] - face[3], face[19] - face[4]};
            faces.add(new Face(p0, s, t, normal, uv, texture, tint, sideShade(normal), false));
        }
        for (Map.Entry<String, Part> child : part.children.entrySet()) {
            addPart(child.getValue(), child.getKey(), local, capture, texture, tint, faces);
        }
    }

    // A dropped item lies on the ground as the game shows it: a flat picture (turned to face the robot) or a quarter-size block.
    private static void addItem(Capture capture, MineBotCameraModels models, BlockTint tint, double cameraX, double cameraZ, List<Face> faces) {
        MineBotCameraModels.ItemModel item = models.item(capture.itemModel());
        double size = 0.25D;
        if (item != null && item.quads().length > 0) {
            addBlockQuads(item.quads(), capture.block(), tint, capture.x() - size / 2, capture.y() + 0.1D, capture.z() - size / 2, size, faces);
            return;
        }
        if (item != null && !item.layers().isEmpty()) {
            double nx = cameraX - capture.x();
            double nz = cameraZ - capture.z();
            double length = Math.sqrt(nx * nx + nz * nz);
            if (length < 1.0E-6D) {
                nx = 0.0D;
                nz = 1.0D;
            } else {
                nx /= length;
                nz /= length;
            }
            double half = 0.25D;
            double centerY = capture.y() + 0.35D;
            double[] right = {nz, 0.0D, -nx};
            double[] origin = {capture.x() - right[0] * half, centerY + half, capture.z() - right[2] * half};
            faces.add(new Face(
                origin,
                new double[] {right[0] * half * 2, 0.0D, right[2] * half * 2},
                new double[] {0.0D, -half * 2, 0.0D},
                new double[] {nx, 0.0D, nz},
                new double[] {0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 1.0D},
                layered(item.layers()),
                -1,
                1.0D,
                true
            ));
            return;
        }
        if (capture.block() != null) {
            addBlockQuads(models.get(capture.block()), capture.block(), tint, capture.x() - size / 2, capture.y() + 0.1D, capture.z() - size / 2, size, faces);
        }
    }

    private static void addBlockQuads(MineBotCameraModels.Quad[] quads, BlockState state, BlockTint tint, double x, double y, double z, double size, List<Face> faces) {
        for (MineBotCameraModels.Quad quad : quads) {
            double[] uv0 = quad.uv(0.0D, 0.0D);
            double[] uvS = quad.uv(1.0D, 0.0D);
            double[] uvT = quad.uv(0.0D, 1.0D);
            faces.add(new Face(
                new double[] {x + quad.ox() * size, y + quad.oy() * size, z + quad.oz() * size},
                new double[] {quad.sx() * size, quad.sy() * size, quad.sz() * size},
                new double[] {quad.tx() * size, quad.ty() * size, quad.tz() * size},
                new double[] {quad.nx(), quad.ny(), quad.nz()},
                new double[] {uv0[0], uv0[1], uvS[0] - uv0[0], uvS[1] - uv0[1], uvT[0] - uv0[0], uvT[1] - uv0[1]},
                quad.texture(),
                quad.tintIndex() >= 0 && state != null ? tint.color(state, quad.tintIndex()) : -1,
                quad.shade(),
                false
            ));
        }
    }

    // Entities without a model: their hitbox in the colour of their kind, with a darker rim so the shape reads.
    private static void addBox(Capture capture, List<Face> faces) {
        Box box = capture.box();
        MineBotCameraAssets.Texture texture = BOX_TEXTURES.computeIfAbsent(capture.color(), MineBotCameraEntities::boxTexture);
        double[][] corners = {
            {box.minX, box.maxY, box.minZ}, {box.maxX, box.maxY, box.minZ}, {box.minX, box.maxY, box.maxZ}, {box.minX, box.minY, box.minZ},
        };
        double w = box.maxX - box.minX;
        double h = box.maxY - box.minY;
        double d = box.maxZ - box.minZ;
        double[] uv = {0.0D, 0.0D, 1.0D, 0.0D, 0.0D, 1.0D};
        // up, down, north, south, west, east
        faces.add(new Face(corners[0], new double[] {w, 0, 0}, new double[] {0, 0, d}, new double[] {0, 1, 0}, uv, texture, -1, sideShade(new double[] {0, 1, 0}), false));
        faces.add(new Face(corners[3], new double[] {w, 0, 0}, new double[] {0, 0, d}, new double[] {0, -1, 0}, uv, texture, -1, sideShade(new double[] {0, -1, 0}), false));
        faces.add(new Face(corners[0], new double[] {w, 0, 0}, new double[] {0, -h, 0}, new double[] {0, 0, -1}, uv, texture, -1, sideShade(new double[] {0, 0, -1}), false));
        faces.add(new Face(corners[2], new double[] {w, 0, 0}, new double[] {0, -h, 0}, new double[] {0, 0, 1}, uv, texture, -1, sideShade(new double[] {0, 0, 1}), false));
        faces.add(new Face(corners[0], new double[] {0, 0, d}, new double[] {0, -h, 0}, new double[] {-1, 0, 0}, uv, texture, -1, sideShade(new double[] {-1, 0, 0}), false));
        faces.add(new Face(corners[1], new double[] {0, 0, d}, new double[] {0, -h, 0}, new double[] {1, 0, 0}, uv, texture, -1, sideShade(new double[] {1, 0, 0}), false));
    }

    private static MineBotCameraAssets.Texture boxTexture(int color) {
        int size = 16;
        int[] argb = new int[size * size];
        int rim = 0xFF000000 | (((color >> 16) & 0xFF) * 55 / 100) << 16 | (((color >> 8) & 0xFF) * 55 / 100) << 8 | ((color & 0xFF) * 55 / 100);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean edge = x == 0 || y == 0 || x == size - 1 || y == size - 1;
                argb[y * size + x] = edge ? rim : 0xFF000000 | color;
            }
        }
        return new MineBotCameraAssets.Texture(size, size, argb);
    }

    // ------------------------------------------------------------------ textures

    /** The first texture with each later one painted over it (villager clothes, eyes). Null when the first is missing. */
    private static MineBotCameraAssets.Texture composite(MineBotCameraAssets assets, List<Identifier> ids) {
        return COMPOSITES.computeIfAbsent(ids, ignored -> {
            MineBotCameraAssets.Texture base = assets.image(ids.get(0).getNamespace(), ids.get(0).getPath());
            if (base == null) {
                return Optional.empty();
            }
            List<MineBotCameraAssets.Texture> overlays = new ArrayList<>();
            for (Identifier id : ids.subList(1, ids.size())) {
                MineBotCameraAssets.Texture overlay = assets.image(id.getNamespace(), id.getPath());
                if (overlay != null) {
                    overlays.add(overlay);
                }
            }
            return Optional.of(overlays.isEmpty() ? base : paint(base, overlays));
        }).orElse(null);
    }

    private static MineBotCameraAssets.Texture layered(List<MineBotCameraAssets.Texture> layers) {
        return layers.size() == 1 ? layers.get(0) : paint(layers.get(0), layers.subList(1, layers.size()));
    }

    private static MineBotCameraAssets.Texture paint(MineBotCameraAssets.Texture base, List<MineBotCameraAssets.Texture> overlays) {
        int[] argb = base.argb().clone();
        for (MineBotCameraAssets.Texture overlay : overlays) {
            for (int y = 0; y < base.height(); y++) {
                for (int x = 0; x < base.width(); x++) {
                    int top = overlay.sample((x + 0.5D) / base.width(), (y + 0.5D) / base.height());
                    int alpha = (top >>> 24) & 0xFF;
                    if (alpha == 0) {
                        continue;
                    }
                    int index = y * base.width() + x;
                    int under = argb[index];
                    int outAlpha = Math.max(alpha, (under >>> 24) & 0xFF);
                    int r = (((top >> 16) & 0xFF) * alpha + ((under >> 16) & 0xFF) * (255 - alpha)) / 255;
                    int g = (((top >> 8) & 0xFF) * alpha + ((under >> 8) & 0xFF) * (255 - alpha)) / 255;
                    int b = ((top & 0xFF) * alpha + (under & 0xFF) * (255 - alpha)) / 255;
                    argb[index] = outAlpha << 24 | r << 16 | g << 8 | b;
                }
            }
        }
        return new MineBotCameraAssets.Texture(base.width(), base.height(), argb);
    }

    /** A player's skin from Mojang's skin server, fetched once and kept; null when it cannot be had. */
    private static MineBotCameraAssets.Texture skin(String url) {
        return SKINS.computeIfAbsent(url, ignored -> {
            try {
                HttpResponse<byte[]> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofByteArray()
                );
                BufferedImage image = response.statusCode() == 200 ? MineBotCameraAssets.decode(response.body()) : null;
                if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                    return Optional.empty();
                }
                return Optional.of(normalizeSkin(image));
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                MineBotMod.LOGGER.debug("MineBot camera could not fetch skin {}", url, exception);
                return Optional.empty();
            }
        }).orElse(null);
    }

    // As the game does: old 64x32 skins get their left limbs mirrored from the right ones, and the base layer is made opaque.
    private static MineBotCameraAssets.Texture normalizeSkin(BufferedImage image) {
        int[] argb = new int[64 * 64];
        image.getRGB(0, 0, 64, image.getHeight(), argb, 0, 64);
        if (image.getHeight() == 32) {
            int[][] copies = {
                {4, 16, 16, 32, 4, 4}, {8, 16, 16, 32, 4, 4}, {0, 20, 24, 32, 4, 12}, {4, 20, 16, 32, 4, 12},
                {8, 20, 8, 32, 4, 12}, {12, 20, 16, 32, 4, 12}, {44, 16, -8, 32, 4, 4}, {48, 16, -8, 32, 4, 4},
                {40, 20, 0, 32, 4, 12}, {44, 20, -8, 32, 4, 12}, {48, 20, -16, 32, 4, 12}, {52, 20, -8, 32, 4, 12},
            };
            for (int[] copy : copies) {
                for (int row = 0; row < copy[5]; row++) {
                    for (int column = 0; column < copy[4]; column++) {
                        int from = (copy[1] + row) * 64 + copy[0] + column;
                        int to = (copy[1] + copy[3] + row) * 64 + copy[0] + copy[2] + (copy[4] - 1 - column);
                        argb[to] = argb[from];
                    }
                }
            }
        }
        opaque(argb, 0, 0, 32, 16);
        opaque(argb, 0, 16, 64, 32);
        opaque(argb, 16, 48, 48, 64);
        return new MineBotCameraAssets.Texture(64, 64, argb);
    }

    private static void opaque(int[] argb, int x1, int y1, int x2, int y2) {
        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                argb[y * 64 + x] |= 0xFF000000;
            }
        }
    }

    // ------------------------------------------------------------------ model data

    /** A model part: pivot (1/16 block), rotation (radians), scale, faces, and child parts. */
    private static final class Part {
        final double ox, oy, oz, pitch, yaw, roll, sx, sy, sz;
        final List<float[]> faces;
        final Map<String, Part> children;

        Part(JsonObject json) {
            double[] origin = triple(json.getAsJsonArray("o"), 0.0D);
            double[] rotation = triple(json.has("r") ? json.getAsJsonArray("r") : null, 0.0D);
            double[] scale = triple(json.has("s") ? json.getAsJsonArray("s") : null, 1.0D);
            this.ox = origin[0];
            this.oy = origin[1];
            this.oz = origin[2];
            this.pitch = rotation[0];
            this.yaw = rotation[1];
            this.roll = rotation[2];
            this.sx = scale[0];
            this.sy = scale[1];
            this.sz = scale[2];
            this.faces = new ArrayList<>();
            if (json.has("f")) {
                for (JsonElement element : json.getAsJsonArray("f")) {
                    JsonArray values = element.getAsJsonArray();
                    float[] face = new float[values.size()];
                    for (int index = 0; index < face.length; index++) {
                        face[index] = values.get(index).getAsFloat();
                    }
                    this.faces.add(face);
                }
            }
            this.children = new HashMap<>();
            if (json.has("c")) {
                for (Map.Entry<String, JsonElement> child : json.getAsJsonObject("c").entrySet()) {
                    this.children.put(child.getKey(), new Part(child.getValue().getAsJsonObject()));
                }
            }
        }

        private static double[] triple(JsonArray array, double fallback) {
            return array == null
                ? new double[] {fallback, fallback, fallback}
                : new double[] {array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble()};
        }
    }

    private static Map<String, Part> models(MineBotCameraAssets assets) {
        Map<String, Part> loaded = models;
        if (loaded == null) {
            synchronized (MineBotCameraEntities.class) {
                loaded = models;
                if (loaded == null) {
                    loaded = new HashMap<>();
                    byte[] bytes = assets.read(MODELS_RESOURCE);
                    if (bytes != null) {
                        for (Map.Entry<String, JsonElement> entry : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
                            loaded.put(entry.getKey(), new Part(entry.getValue().getAsJsonObject()));
                        }
                    } else {
                        MineBotMod.LOGGER.warn("MineBot camera is missing {}; entities will be drawn as boxes", MODELS_RESOURCE);
                    }
                    models = loaded;
                }
            }
        }
        return loaded;
    }

    // ------------------------------------------------------------------ geometry

    static double sideShade(double[] normal) {
        return Math.min(1.0D, normal[0] * normal[0] * 0.6D + normal[1] * normal[1] * ((3.0D + normal[1]) / 4.0D) + normal[2] * normal[2] * 0.8D);
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double lengthSquared(double[] v) {
        return v[0] * v[0] + v[1] * v[1] + v[2] * v[2];
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(lengthSquared(v));
        return length < 1.0E-12D ? new double[] {0.0D, 1.0D, 0.0D} : new double[] {v[0] / length, v[1] / length, v[2] / length};
    }

    /** A 3x3 linear map plus a translation, composed like the game's matrix stack. */
    private record Affine(double m00, double m01, double m02, double m10, double m11, double m12, double m20, double m21, double m22, double x, double y, double z) {
        static Affine translation(double x, double y, double z) {
            return new Affine(1, 0, 0, 0, 1, 0, 0, 0, 1, x, y, z);
        }

        static Affine scaling(double x, double y, double z) {
            return new Affine(x, 0, 0, 0, y, 0, 0, 0, z, 0, 0, 0);
        }

        static Affine rotationY(double angle) {
            double c = Math.cos(angle);
            double s = Math.sin(angle);
            return new Affine(c, 0, s, 0, 1, 0, -s, 0, c, 0, 0, 0);
        }

        /** The game's ModelPart rotation: X (pitch) first, then Y (yaw), then Z (roll). */
        static Affine rotationZYX(double roll, double yaw, double pitch) {
            double cx = Math.cos(pitch);
            double sx = Math.sin(pitch);
            double cy = Math.cos(yaw);
            double sy = Math.sin(yaw);
            double cz = Math.cos(roll);
            double sz = Math.sin(roll);
            Affine rx = new Affine(1, 0, 0, 0, cx, -sx, 0, sx, cx, 0, 0, 0);
            Affine ry = new Affine(cy, 0, sy, 0, 1, 0, -sy, 0, cy, 0, 0, 0);
            Affine rz = new Affine(cz, -sz, 0, sz, cz, 0, 0, 0, 1, 0, 0, 0);
            return rz.then(ry).then(rx);
        }

        /** This transform applied after the other: this(other(v)). */
        Affine then(Affine o) {
            return new Affine(
                this.m00 * o.m00 + this.m01 * o.m10 + this.m02 * o.m20,
                this.m00 * o.m01 + this.m01 * o.m11 + this.m02 * o.m21,
                this.m00 * o.m02 + this.m01 * o.m12 + this.m02 * o.m22,
                this.m10 * o.m00 + this.m11 * o.m10 + this.m12 * o.m20,
                this.m10 * o.m01 + this.m11 * o.m11 + this.m12 * o.m21,
                this.m10 * o.m02 + this.m11 * o.m12 + this.m12 * o.m22,
                this.m20 * o.m00 + this.m21 * o.m10 + this.m22 * o.m20,
                this.m20 * o.m01 + this.m21 * o.m11 + this.m22 * o.m21,
                this.m20 * o.m02 + this.m21 * o.m12 + this.m22 * o.m22,
                this.m00 * o.x + this.m01 * o.y + this.m02 * o.z + this.x,
                this.m10 * o.x + this.m11 * o.y + this.m12 * o.z + this.y,
                this.m20 * o.x + this.m21 * o.y + this.m22 * o.z + this.z
            );
        }

        double[] apply(double px, double py, double pz) {
            double[] v = this.applyLinear(px, py, pz);
            return new double[] {v[0] + this.x, v[1] + this.y, v[2] + this.z};
        }

        double[] applyLinear(double px, double py, double pz) {
            return new double[] {
                this.m00 * px + this.m01 * py + this.m02 * pz,
                this.m10 * px + this.m11 * py + this.m12 * pz,
                this.m20 * px + this.m21 * py + this.m22 * pz
            };
        }
    }
}
