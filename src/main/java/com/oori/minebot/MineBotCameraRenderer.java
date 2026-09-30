package com.oori.minebot;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeAccess;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.FoliageColors;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.ChunkLightingView;

/**
 * The robot camera, drawn on the server. On the game thread it copies the loaded blocks, light and
 * visible entities around the robot; a background thread then traces one line of sight per pixel from
 * the robot's eye and colours it with the first surface it meets, using Minecraft's own textures,
 * per-side shading, block and sky light, biome tints and distance fog. Mobs, players and items are
 * drawn as plain boxes coloured by kind. Nothing behind the first opaque surface can reach the image.
 */
final class MineBotCameraRenderer {
    static final int WIDTH = 640;
    static final int HEIGHT = 360;

    private static final double VERTICAL_FOV_DEGREES = 70.0D;
    private static final int VIEW_DISTANCE = 96;
    private static final double FOG_START = VIEW_DISTANCE * 0.8D;
    private static final int CROSSHAIR_ARM = 5;
    private static final int MAX_HITS_PER_CELL = 64;
    private static final double EDGE = 1.0E-7D;

    private static final int PLAYER_COLOR = 0x3D6FD1;
    private static final int MINEBOT_COLOR = 0x9AA3AD;
    private static final int HOSTILE_COLOR = 0xC8322A;
    private static final int ANIMAL_COLOR = 0x5DAA3C;
    private static final int ITEM_COLOR = 0xF0C43A;
    private static final int VEHICLE_COLOR = 0x8A5A32;
    private static final int OTHER_ENTITY_COLOR = 0xD0D0D0;

    private static final int DEFAULT_GRASS = 0x91BD59;
    private static final int DEFAULT_FOLIAGE = 0x77AB2F;
    private static final int DEFAULT_WATER = 0x3F76E4;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MineBot camera");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Block, Tint> TINTS = new ConcurrentHashMap<>();
    private static MineBotCameraModels models;

    private MineBotCameraRenderer() {
    }

    /** Must run on the server thread: the scene is copied now, the picture is drawn in the background. */
    static CompletableFuture<MineBotCameraBridge.SnapshotResult> requestSnapshot(MineBotEntity robot) {
        if (!(robot.getEntityWorld() instanceof ServerWorld world)) {
            return CompletableFuture.failedFuture(new IllegalStateException("MineBot camera is only available on a server world"));
        }

        long started = System.nanoTime();
        Scene scene;
        try {
            scene = Scene.capture(world, robot);
        } catch (RuntimeException exception) {
            MineBotMod.LOGGER.warn("MineBot camera could not copy the scene", exception);
            return CompletableFuture.failedFuture(new MineBotCommandException("camera_error", "The robot camera could not copy its surroundings"));
        }
        long captured = System.nanoTime();
        MineBotMod.LOGGER.debug("MineBot camera copied {} sections in {} ms", scene.sections.length, (captured - started) / 1_000_000);

        return MineBotCameraAssets.get().thenApplyAsync(assets -> {
            long renderStarted = System.nanoTime();
            MineBotCameraBridge.SnapshotResult result = render(scene, modelsFor(assets));
            MineBotMod.LOGGER.debug("MineBot camera drew a frame in {} ms", (System.nanoTime() - renderStarted) / 1_000_000);
            return result;
        }, EXECUTOR);
    }

    private static synchronized MineBotCameraModels modelsFor(MineBotCameraAssets assets) {
        if (models == null || models.assets() != assets) {
            models = new MineBotCameraModels(assets);
        }
        return models;
    }

    private static MineBotCameraBridge.SnapshotResult render(Scene scene, MineBotCameraModels models) {
        int[] pixels = new int[WIDTH * HEIGHT];

        double yaw = Math.toRadians(scene.yaw);
        double pitch = Math.toRadians(scene.pitch);
        // Minecraft's look vector, with right and up completing the camera frame.
        double fx = -Math.sin(yaw) * Math.cos(pitch);
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double ux = -rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy;
        double tanV = Math.tan(Math.toRadians(VERTICAL_FOV_DEGREES / 2.0D));
        double tanH = tanV * WIDTH / HEIGHT;

        List<MineBotCameraEntities.Shape> shapes = new ArrayList<>();
        for (MineBotCameraEntities.Capture capture : scene.entities) {
            int bx = MathHelper.floor(capture.x());
            int by = MathHelper.floor(capture.y());
            int bz = MathHelper.floor(capture.z());
            shapes.add(MineBotCameraEntities.build(
                capture,
                models,
                (state, tintIndex) -> blockTint(scene, state, tintIndex, bx, by, bz),
                scene.eyeX,
                scene.eyeZ
            ));
            screenBounds(shapes.get(shapes.size() - 1), scene, fx, fy, fz, rx, rz, ux, uy, uz, tanH, tanV);
        }
        for (MineBotCameraEntities.SignCapture sign : scene.signs) {
            MineBotCameraEntities.Shape shape = MineBotCameraEntities.buildSign(sign, models.assets());
            if (shape.faces.length > 0) {
                screenBounds(shape, scene, fx, fy, fz, rx, rz, ux, uy, uz, tanH, tanV);
                shapes.add(shape);
            }
        }

        IntStream.range(0, HEIGHT).parallel().forEach(row -> {
            Tracer tracer = new Tracer(scene, models, shapes);
            double sy = (1.0D - 2.0D * (row + 0.5D) / HEIGHT) * tanV;
            for (int column = 0; column < WIDTH; column++) {
                double sx = (2.0D * (column + 0.5D) / WIDTH - 1.0D) * tanH;
                double dx = fx + rx * sx + ux * sy;
                double dy = fy + uy * sy;
                double dz = fz + rz * sx + uz * sy;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                pixels[row * WIDTH + column] = tracer.trace(dx / length, dy / length, dz / length, column, row);
            }
        });

        drawCrosshair(pixels);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, WIDTH, HEIGHT, pixels, 0, WIDTH);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", png);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return new MineBotCameraBridge.SnapshotResult(WIDTH, HEIGHT, png.toByteArray());
    }

    // The pixels a shape's bounds project onto, so rays elsewhere skip it. Bounds reaching behind the eye cover the whole image.
    private static void screenBounds(
        MineBotCameraEntities.Shape shape,
        Scene scene,
        double fx,
        double fy,
        double fz,
        double rx,
        double rz,
        double ux,
        double uy,
        double uz,
        double tanH,
        double tanV
    ) {
        double minColumn = Double.POSITIVE_INFINITY;
        double maxColumn = Double.NEGATIVE_INFINITY;
        double minRow = Double.POSITIVE_INFINITY;
        double maxRow = Double.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 8; corner++) {
            double px = ((corner & 1) != 0 ? shape.maxX : shape.minX) - scene.eyeX;
            double py = ((corner & 2) != 0 ? shape.maxY : shape.minY) - scene.eyeY;
            double pz = ((corner & 4) != 0 ? shape.maxZ : shape.minZ) - scene.eyeZ;
            double depth = px * fx + py * fy + pz * fz;
            if (depth < 0.05D) {
                return;
            }
            double column = ((px * rx + pz * rz) / (depth * tanH) + 1.0D) * 0.5D * WIDTH;
            double row = (1.0D - (px * ux + py * uy + pz * uz) / (depth * tanV)) * 0.5D * HEIGHT;
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        shape.screenMinX = (int) Math.floor(minColumn) - 1;
        shape.screenMaxX = (int) Math.ceil(maxColumn) + 1;
        shape.screenMinY = (int) Math.floor(minRow) - 1;
        shape.screenMaxY = (int) Math.ceil(maxRow) + 1;
    }

    // An inverted plus at the centre marks where mine, place and use act, like the game's crosshair.
    private static void drawCrosshair(int[] pixels) {
        int cx = WIDTH / 2;
        int cy = HEIGHT / 2;
        for (int offset = -CROSSHAIR_ARM; offset <= CROSSHAIR_ARM; offset++) {
            invert(pixels, cx + offset, cy);
            if (offset != 0) {
                invert(pixels, cx, cy + offset);
            }
        }
    }

    private static void invert(int[] pixels, int x, int y) {
        int index = y * WIDTH + x;
        pixels[index] = ~pixels[index] & 0xFFFFFF;
    }

    // ------------------------------------------------------------------ tracing

    /** One per image row: scratch space for the surfaces a line of sight meets inside one block. */
    private static final class Tracer {
        private static final int QUAD = 0;
        private static final int FLUID = 1;
        private static final int ENTITY = 2;

        private final Scene scene;
        private final MineBotCameraModels models;
        private final List<MineBotCameraEntities.Shape> shapes;
        private final MineBotCameraAssets.Texture waterTexture;
        private final MineBotCameraAssets.Texture lavaTexture;
        private final Hit[] hits = new Hit[MAX_HITS_PER_CELL];
        private int hitCount;
        private final Hit[] entityHits = new Hit[MAX_HITS_PER_CELL];
        private int entityHitCount;

        private double red;
        private double green;
        private double blue;
        private double transmittance;

        Tracer(Scene scene, MineBotCameraModels models, List<MineBotCameraEntities.Shape> shapes) {
            this.scene = scene;
            this.models = models;
            this.shapes = shapes;
            this.waterTexture = models.assets().texture(Identifier.ofVanilla("block/water_still"));
            this.lavaTexture = models.assets().texture(Identifier.ofVanilla("block/lava_still"));
            for (int index = 0; index < this.hits.length; index++) {
                this.hits[index] = new Hit();
                this.entityHits[index] = new Hit();
            }
        }

        int trace(double dx, double dy, double dz, int column, int row) {
            Scene scene = this.scene;
            double ex = scene.eyeX;
            double ey = scene.eyeY;
            double ez = scene.eyeZ;

            this.collectEntities(ex, ey, ez, dx, dy, dz, column, row);
            int nextEntityHit = 0;

            int x = MathHelper.floor(ex);
            int y = MathHelper.floor(ey);
            int z = MathHelper.floor(ez);
            int stepX = dx > 0 ? 1 : -1;
            int stepY = dy > 0 ? 1 : -1;
            int stepZ = dz > 0 ? 1 : -1;
            double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dx);
            double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dy);
            double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0D / dz);
            double tMaxX = dx > 0 ? (x + 1 - ex) * tDeltaX : dx < 0 ? (ex - x) * tDeltaX : Double.POSITIVE_INFINITY;
            double tMaxY = dy > 0 ? (y + 1 - ey) * tDeltaY : dy < 0 ? (ey - y) * tDeltaY : Double.POSITIVE_INFINITY;
            double tMaxZ = dz > 0 ? (z + 1 - ez) * tDeltaZ : dz < 0 ? (ez - z) * tDeltaZ : Double.POSITIVE_INFINITY;

            this.red = 0.0D;
            this.green = 0.0D;
            this.blue = 0.0D;
            this.transmittance = 1.0D;
            double tEnter = 0.0D;

            while (tEnter <= VIEW_DISTANCE && this.transmittance > 0.02D) {
                double tExit = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
                BlockState state = scene.state(x, y, z);
                if (state == null) {
                    break;
                }

                this.hitCount = 0;
                if (!state.isAir()) {
                    this.collectBlock(state, x, y, z, ex, ey, ez, dx, dy, dz, tEnter, tExit);
                }
                while (nextEntityHit < this.entityHitCount && this.entityHits[nextEntityHit].t <= tExit) {
                    Hit hit = this.nextHit();
                    if (hit == null) {
                        break;
                    }
                    hit.copy(this.entityHits[nextEntityHit++]);
                }
                if (this.hitCount > 0) {
                    this.composite(ex, ey, ez, dx, dy, dz);
                }

                if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                    x += stepX;
                    tEnter = tMaxX;
                    tMaxX += tDeltaX;
                } else if (tMaxY < tMaxZ) {
                    y += stepY;
                    tEnter = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    tEnter = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }

            if (this.transmittance > 0.02D) {
                // Past the loaded chunks there is only fog, which shades into open sky above the horizon.
                int background = scene.skyColor(dy);
                this.red += ((background >> 16) & 0xFF) * this.transmittance;
                this.green += ((background >> 8) & 0xFF) * this.transmittance;
                this.blue += (background & 0xFF) * this.transmittance;
            }
            return clampColor(this.red) << 16 | clampColor(this.green) << 8 | clampColor(this.blue);
        }

        private void collectBlock(
            BlockState state,
            int x,
            int y,
            int z,
            double ex,
            double ey,
            double ez,
            double dx,
            double dy,
            double dz,
            double tEnter,
            double tExit
        ) {
            double lx = ex - x;
            double ly = ey - y;
            double lz = ez - z;
            double minT = tEnter - 1.0E-6D;
            double maxT = tExit + 1.0E-6D;

            for (MineBotCameraModels.Quad quad : this.models.get(state)) {
                double facing = dx * quad.nx() + dy * quad.ny() + dz * quad.nz();
                if (facing >= -1.0E-9D) {
                    continue;
                }
                double t = ((quad.ox() - lx) * quad.nx() + (quad.oy() - ly) * quad.ny() + (quad.oz() - lz) * quad.nz()) / facing;
                if (t < minT || t > maxT) {
                    continue;
                }
                double hx = lx + dx * t - quad.ox();
                double hy = ly + dy * t - quad.oy();
                double hz = lz + dz * t - quad.oz();
                double s = (hx * quad.sx() + hy * quad.sy() + hz * quad.sz()) * quad.sInv();
                double v = (hx * quad.tx() + hy * quad.ty() + hz * quad.tz()) * quad.tInv();
                if (s < -EDGE || s > 1.0D + EDGE || v < -EDGE || v > 1.0D + EDGE) {
                    continue;
                }
                if (quad.cullFace() != null) {
                    BlockState neighbor = this.scene.state(x + quad.cullFace().getOffsetX(), y + quad.cullFace().getOffsetY(), z + quad.cullFace().getOffsetZ());
                    if (neighbor != null && (neighbor.isOpaqueFullCube() || state.isSideInvisible(neighbor, quad.cullFace()))) {
                        continue;
                    }
                }
                Hit hit = this.nextHit();
                if (hit == null) {
                    return;
                }
                hit.set(QUAD, t, s, v, quad.nx(), quad.ny(), quad.nz(), quad.order());
                hit.quad = quad;
                hit.state = state;
                hit.x = x;
                hit.y = y;
                hit.z = z;
            }

            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty() && !(this.scene.eyeInWater && fluid.isIn(FluidTags.WATER))) {
                this.collectFluid(fluid, x, y, z, lx, ly, lz, dx, dy, dz, minT, maxT);
            }
        }

        // A fluid is a box as tall as its level, with only the sides that do not face the same fluid or a solid block.
        private void collectFluid(
            FluidState fluid,
            int x,
            int y,
            int z,
            double lx,
            double ly,
            double lz,
            double dx,
            double dy,
            double dz,
            double minT,
            double maxT
        ) {
            BlockState above = this.scene.state(x, y + 1, z);
            boolean fullAbove = above != null && above.getFluidState().getFluid().matchesType(fluid.getFluid());
            double height = fullAbove ? 1.0D : fluid.getHeight();

            if (dy < 0 && !fullAbove) {
                this.fluidFace(fluid, x, y, z, (height - ly) / dy, lx, ly, lz, dx, dy, dz, minT, maxT, 0, 1, 0, height);
            }
            if (dy > 0 && this.fluidSideVisible(fluid, x, y - 1, z)) {
                this.fluidFace(fluid, x, y, z, -ly / dy, lx, ly, lz, dx, dy, dz, minT, maxT, 0, -1, 0, height);
            }
            if (dx > 0 && this.fluidSideVisible(fluid, x - 1, y, z)) {
                this.fluidFace(fluid, x, y, z, -lx / dx, lx, ly, lz, dx, dy, dz, minT, maxT, -1, 0, 0, height);
            }
            if (dx < 0 && this.fluidSideVisible(fluid, x + 1, y, z)) {
                this.fluidFace(fluid, x, y, z, (1.0D - lx) / dx, lx, ly, lz, dx, dy, dz, minT, maxT, 1, 0, 0, height);
            }
            if (dz > 0 && this.fluidSideVisible(fluid, x, y, z - 1)) {
                this.fluidFace(fluid, x, y, z, -lz / dz, lx, ly, lz, dx, dy, dz, minT, maxT, 0, 0, -1, height);
            }
            if (dz < 0 && this.fluidSideVisible(fluid, x, y, z + 1)) {
                this.fluidFace(fluid, x, y, z, (1.0D - lz) / dz, lx, ly, lz, dx, dy, dz, minT, maxT, 0, 0, 1, height);
            }
        }

        private boolean fluidSideVisible(FluidState fluid, int x, int y, int z) {
            BlockState neighbor = this.scene.state(x, y, z);
            return neighbor == null
                || !(neighbor.isOpaqueFullCube() || neighbor.getFluidState().getFluid().matchesType(fluid.getFluid()));
        }

        private void fluidFace(
            FluidState fluid,
            int x,
            int y,
            int z,
            double t,
            double lx,
            double ly,
            double lz,
            double dx,
            double dy,
            double dz,
            double minT,
            double maxT,
            int nx,
            int ny,
            int nz,
            double height
        ) {
            if (t < minT || t > maxT) {
                return;
            }
            double hx = lx + dx * t;
            double hy = ly + dy * t;
            double hz = lz + dz * t;
            if (hx < -EDGE || hx > 1.0D + EDGE || hy < -EDGE || hy > height + EDGE || hz < -EDGE || hz > 1.0D + EDGE) {
                return;
            }
            double s;
            double v;
            if (ny != 0) {
                s = hx;
                v = hz;
            } else {
                s = nx != 0 ? hz : hx;
                v = 1.0D - hy;
            }
            Hit hit = this.nextHit();
            if (hit == null) {
                return;
            }
            hit.set(FLUID, t, s, v, nx, ny, nz, Integer.MAX_VALUE);
            hit.state = fluid.getBlockState();
            hit.lava = fluid.isIn(FluidTags.LAVA);
            hit.x = x;
            hit.y = y;
            hit.z = z;
        }

        // Every visible entity face along the ray, nearest first; the block walk merges them in by distance.
        private void collectEntities(double ex, double ey, double ez, double dx, double dy, double dz, int column, int row) {
            this.entityHitCount = 0;
            for (MineBotCameraEntities.Shape shape : this.shapes) {
                if (column < shape.screenMinX || column > shape.screenMaxX || row < shape.screenMinY || row > shape.screenMaxY) {
                    continue;
                }
                double enter = shape.enter(ex, ey, ez, dx, dy, dz);
                if (enter < 0.0D || enter > VIEW_DISTANCE) {
                    continue;
                }
                for (MineBotCameraEntities.Face face : shape.faces) {
                    double facing = dx * face.nx + dy * face.ny + dz * face.nz;
                    if (Math.abs(facing) < 1.0E-9D || (facing > 0.0D && !face.twoSided)) {
                        continue;
                    }
                    double t = ((face.ox - ex) * face.nx + (face.oy - ey) * face.ny + (face.oz - ez) * face.nz) / facing;
                    if (t <= 1.0E-4D || t > VIEW_DISTANCE) {
                        continue;
                    }
                    double hx = ex + dx * t - face.ox;
                    double hy = ey + dy * t - face.oy;
                    double hz = ez + dz * t - face.oz;
                    double s = (hx * face.sx + hy * face.sy + hz * face.sz) * face.sInv;
                    double v = (hx * face.tx + hy * face.ty + hz * face.tz) * face.tInv;
                    if (s < -EDGE || s > 1.0D + EDGE || v < -EDGE || v > 1.0D + EDGE) {
                        continue;
                    }
                    int argb = face.texture.sample(face.u0 + face.uS * s + face.uT * v, face.v0 + face.vS * s + face.vT * v);
                    if (((argb >>> 24) & 0xFF) < 26 || this.entityHitCount >= this.entityHits.length) {
                        continue;
                    }
                    double sign = facing > 0.0D ? -1.0D : 1.0D;
                    Hit hit = this.entityHits[this.entityHitCount++];
                    hit.set(ENTITY, t, 0.0D, 0.0D, face.nx * sign, face.ny * sign, face.nz * sign, Integer.MIN_VALUE);
                    hit.argb = argb;
                    hit.shade = face.shade;
                    hit.tint = face.tint;
                    hit.light = face.light >= 0 ? face.light : shape.light;
                }
            }
            Hit[] hits = this.entityHits;
            for (int i = 1; i < this.entityHitCount; i++) {
                Hit current = hits[i];
                int j = i - 1;
                while (j >= 0 && hits[j].t > current.t) {
                    hits[j + 1] = hits[j];
                    j--;
                }
                hits[j + 1] = current;
            }
        }

        private Hit nextHit() {
            return this.hitCount < this.hits.length ? this.hits[this.hitCount++] : null;
        }

        // Front to back: a cut-out texel lets the line of sight through, a see-through one tints it, an opaque one ends it.
        private void composite(double ex, double ey, double ez, double dx, double dy, double dz) {
            Hit[] hits = this.hits;
            for (int i = 1; i < this.hitCount; i++) {
                Hit current = hits[i];
                int j = i - 1;
                while (j >= 0 && hits[j].isBehind(current)) {
                    hits[j + 1] = hits[j];
                    j--;
                }
                hits[j + 1] = current;
            }

            for (int i = 0; i < this.hitCount && this.transmittance > 0.02D; i++) {
                Hit hit = hits[i];
                int argb;
                double shade;
                int tint = -1;
                int light;
                switch (hit.kind) {
                    case QUAD -> {
                        argb = hit.quad.sample(hit.s, hit.v);
                        shade = hit.quad.shade();
                        if (hit.quad.tintIndex() >= 0) {
                            tint = blockTint(this.scene, hit.state, hit.quad.tintIndex(), hit.x, hit.y, hit.z);
                        }
                        light = this.lightAt(ex + dx * hit.t + hit.nx * 0.01D, ey + dy * hit.t + hit.ny * 0.01D, ez + dz * hit.t + hit.nz * 0.01D);
                    }
                    case FLUID -> {
                        argb = (hit.lava ? this.lavaTexture : this.waterTexture).sample(hit.s, hit.v);
                        shade = sideShade(hit.nx, hit.ny, hit.nz);
                        if (!hit.lava) {
                            tint = waterColor(this.scene, hit.x, hit.y, hit.z);
                        }
                        light = this.lightAt(ex + dx * hit.t + hit.nx * 0.01D, ey + dy * hit.t + hit.ny * 0.01D, ez + dz * hit.t + hit.nz * 0.01D);
                    }
                    default -> {
                        argb = hit.argb;
                        shade = hit.shade;
                        tint = hit.tint;
                        light = hit.light;
                    }
                }

                double alpha = ((argb >>> 24) & 0xFF) / 255.0D;
                if (alpha < 0.1D) {
                    continue;
                }
                double brightness = shade * this.scene.brightness[light];
                double r = ((argb >> 16) & 0xFF) * brightness;
                double g = ((argb >> 8) & 0xFF) * brightness;
                double b = (argb & 0xFF) * brightness;
                if (tint != -1) {
                    r *= ((tint >> 16) & 0xFF) / 255.0D;
                    g *= ((tint >> 8) & 0xFF) / 255.0D;
                    b *= (tint & 0xFF) / 255.0D;
                }
                double fog = this.scene.fogAmount(hit.t);
                if (fog > 0.0D) {
                    int fogColor = this.scene.fogColor(dy);
                    r += (((fogColor >> 16) & 0xFF) - r) * fog;
                    g += (((fogColor >> 8) & 0xFF) - g) * fog;
                    b += ((fogColor & 0xFF) - b) * fog;
                }

                double weight = alpha >= 0.99D ? this.transmittance : alpha * this.transmittance;
                this.red += r * weight;
                this.green += g * weight;
                this.blue += b * weight;
                this.transmittance = alpha >= 0.99D ? 0.0D : this.transmittance * (1.0D - alpha);
            }
        }

        private int lightAt(double x, double y, double z) {
            int bx = MathHelper.floor(x);
            int by = MathHelper.floor(y);
            int bz = MathHelper.floor(z);
            return this.scene.blockLight(bx, by, bz) << 4 | this.scene.skyLight(bx, by, bz);
        }

    }

    private static int waterColor(Scene scene, int x, int y, int z) {
        Biome biome = scene.biome(x, y, z);
        return biome == null ? DEFAULT_WATER : biome.getWaterColor() & 0xFFFFFF;
    }

    // Mirrors the game's block colour providers for the blocks that ship with it.
    private static int blockTint(Scene scene, BlockState state, int tintIndex, int x, int y, int z) {
        Tint kind = TINTS.computeIfAbsent(state.getBlock(), MineBotCameraRenderer::tintOf);
        Biome biome = scene.biome(x, y, z);
        return switch (kind) {
            case NONE -> -1;
            case GRASS -> biome == null ? DEFAULT_GRASS : biome.getGrassColorAt(x, z) & 0xFFFFFF;
            case GRASS_EXCEPT_FIRST -> tintIndex == 0 ? -1 : biome == null ? DEFAULT_GRASS : biome.getGrassColorAt(x, z) & 0xFFFFFF;
            case FOLIAGE -> biome == null ? DEFAULT_FOLIAGE : biome.getFoliageColor() & 0xFFFFFF;
            case DRY_FOLIAGE -> biome == null ? DEFAULT_FOLIAGE : biome.getDryFoliageColor() & 0xFFFFFF;
            case BIRCH -> FoliageColors.BIRCH & 0xFFFFFF;
            case SPRUCE -> FoliageColors.SPRUCE & 0xFFFFFF;
            case WATER -> waterColor(scene, x, y, z);
            case LILY_PAD -> 0x208030;
            case REDSTONE -> RedstoneWireBlock.getWireColor(state.get(RedstoneWireBlock.POWER)) & 0xFFFFFF;
            case STEM -> {
                int age = state.get(StemBlock.AGE);
                yield (age * 32) << 16 | (255 - age * 8) << 8 | (age * 4);
            }
            case ATTACHED_STEM -> 0xE0C71C;
        };
    }

    private enum Tint {
        NONE, GRASS, GRASS_EXCEPT_FIRST, FOLIAGE, DRY_FOLIAGE, BIRCH, SPRUCE, WATER, LILY_PAD, REDSTONE, STEM, ATTACHED_STEM
    }

    private static Tint tintOf(Block block) {
        if (block instanceof RedstoneWireBlock) {
            return Tint.REDSTONE;
        }
        if (block instanceof StemBlock) {
            return Tint.STEM;
        }
        Identifier id = Registries.BLOCK.getId(block);
        if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return Tint.GRASS;
        }
        return switch (id.getPath()) {
            case "oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine" -> Tint.FOLIAGE;
            case "leaf_litter" -> Tint.DRY_FOLIAGE;
            case "birch_leaves" -> Tint.BIRCH;
            case "spruce_leaves" -> Tint.SPRUCE;
            case "water", "water_cauldron", "bubble_column" -> Tint.WATER;
            case "lily_pad" -> Tint.LILY_PAD;
            case "attached_melon_stem", "attached_pumpkin_stem" -> Tint.ATTACHED_STEM;
            case "pink_petals", "wildflowers" -> Tint.GRASS_EXCEPT_FIRST;
            default -> Tint.GRASS;
        };
    }

    private static double sideShade(double nx, double ny, double nz) {
        return Math.min(1.0D, nx * nx * 0.6D + ny * ny * ((3.0D + ny) / 4.0D) + nz * nz * 0.8D);
    }

    private static int clampColor(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static final class Hit {
        int kind;
        double t;
        double s;
        double v;
        double nx;
        double ny;
        double nz;
        int order;
        MineBotCameraModels.Quad quad;
        BlockState state;
        boolean lava;
        int argb;
        double shade;
        int tint;
        int light;
        int x;
        int y;
        int z;

        void set(int kind, double t, double s, double v, double nx, double ny, double nz, int order) {
            this.kind = kind;
            this.t = t;
            this.s = s;
            this.v = v;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.order = order;
            this.quad = null;
            this.state = null;
            this.lava = false;
        }

        void copy(Hit other) {
            this.set(other.kind, other.t, other.s, other.v, other.nx, other.ny, other.nz, other.order);
            this.argb = other.argb;
            this.shade = other.shade;
            this.tint = other.tint;
            this.light = other.light;
        }

        // Faces at the same depth are drawn in model order, so a later element (grass side overlay) covers an earlier one.
        boolean isBehind(Hit other) {
            if (Math.abs(this.t - other.t) > 1.0E-7D) {
                return this.t > other.t;
            }
            return this.order < other.order;
        }
    }

    // ------------------------------------------------------------------ scene copy

    /** Everything the renderer reads, copied on the server thread so drawing can happen elsewhere. */
    private static final class Scene {
        final double eyeX;
        final double eyeY;
        final double eyeZ;
        final float yaw;
        final float pitch;
        final int minChunkX;
        final int minChunkZ;
        final int chunksX;
        final int chunksZ;
        final int minSectionY;
        final int sectionsY;
        final int worldBottomY;
        final int worldTopY;
        final boolean[] loaded;
        final ChunkSection[] sections;
        final ChunkNibbleArray[] blockLightArrays;
        final ChunkNibbleArray[] skyLightArrays;
        final boolean hasSkyLight;
        final boolean hasSky;
        final boolean eyeInWater;
        final int skyColor;
        final int horizonColor;
        final double fogStart;
        final double fogEnd;
        final double[] brightness = new double[256];
        final List<MineBotCameraEntities.Capture> entities;
        final List<MineBotCameraEntities.SignCapture> signs = new ArrayList<>();

        private Scene(ServerWorld world, MineBotEntity robot) {
            Vec3d eye = robot.getCommandRayStart();
            this.eyeX = eye.x;
            this.eyeY = eye.y;
            this.eyeZ = eye.z;
            this.yaw = robot.getYaw();
            this.pitch = robot.getPitch();
            this.worldBottomY = world.getBottomY();
            this.worldTopY = world.getTopYInclusive();
            this.hasSkyLight = world.getDimension().hasSkyLight();
            this.hasSky = this.hasSkyLight && !world.getDimension().hasCeiling();

            int eyeBlockX = MathHelper.floor(eye.x);
            int eyeBlockZ = MathHelper.floor(eye.z);
            this.minChunkX = (eyeBlockX - VIEW_DISTANCE) >> 4;
            this.minChunkZ = (eyeBlockZ - VIEW_DISTANCE) >> 4;
            this.chunksX = ((eyeBlockX + VIEW_DISTANCE) >> 4) - this.minChunkX + 1;
            this.chunksZ = ((eyeBlockZ + VIEW_DISTANCE) >> 4) - this.minChunkZ + 1;
            this.minSectionY = Math.max(world.getBottomSectionCoord(), (MathHelper.floor(eye.y) - VIEW_DISTANCE) >> 4);
            int maxSectionY = Math.min(world.getTopSectionCoord() - 1, (MathHelper.floor(eye.y) + VIEW_DISTANCE) >> 4);
            this.sectionsY = Math.max(0, maxSectionY - this.minSectionY + 1);

            int columns = this.chunksX * this.chunksZ;
            this.loaded = new boolean[columns];
            this.sections = new ChunkSection[columns * this.sectionsY];
            this.blockLightArrays = new ChunkNibbleArray[this.sections.length];
            this.skyLightArrays = new ChunkNibbleArray[this.sections.length];
            ChunkLightingView blockLight = world.getLightingProvider().get(LightType.BLOCK);
            ChunkLightingView skyLight = world.getLightingProvider().get(LightType.SKY);

            for (int cz = 0; cz < this.chunksZ; cz++) {
                for (int cx = 0; cx < this.chunksX; cx++) {
                    int chunkX = this.minChunkX + cx;
                    int chunkZ = this.minChunkZ + cz;
                    WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                    if (chunk == null) {
                        continue;
                    }
                    int column = cz * this.chunksX + cx;
                    this.loaded[column] = true;
                    for (int sy = 0; sy < this.sectionsY; sy++) {
                        int sectionY = this.minSectionY + sy;
                        int index = sy * columns + column;
                        ChunkSection section = chunk.getSection(chunk.getSectionIndex(sectionY << 4));
                        if (!section.isEmpty()) {
                            this.sections[index] = section.copy();
                        }
                        ChunkNibbleArray light = blockLight.getLightSection(ChunkSectionPos.from(chunkX, sectionY, chunkZ));
                        this.blockLightArrays[index] = light == null ? null : light.copy();
                    }
                    if (this.hasSkyLight) {
                        this.copySkyLight(skyLight, world, chunkX, chunkZ, column);
                    }
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (blockEntity instanceof SignBlockEntity sign && sign.getPos().getSquaredDistance(eye) <= VIEW_DISTANCE * VIEW_DISTANCE) {
                            BlockPos pos = sign.getPos();
                            int light = world.getLightLevel(LightType.BLOCK, pos) << 4 | (this.hasSkyLight ? world.getLightLevel(LightType.SKY, pos) : 0);
                            MineBotCameraEntities.SignCapture capture = MineBotCameraEntities.captureSign(sign, light);
                            if (capture != null) {
                                this.signs.add(capture);
                            }
                        }
                    }
                }
            }

            BlockPos eyePos = BlockPos.ofFloored(eye);
            FluidState eyeFluid = world.getFluidState(eyePos);
            this.eyeInWater = eyeFluid.isIn(FluidTags.WATER) && eye.y < eyePos.getY() + eyeFluid.getHeight(world, eyePos);

            EnvironmentAttributeAccess environment = world.getEnvironmentAttributes();
            int sky = attribute(environment, EnvironmentAttributes.SKY_COLOR_VISUAL, eye, 0x78A7FF);
            int fog = attribute(environment, EnvironmentAttributes.FOG_COLOR_VISUAL, eye, 0xC0D8FF);
            float skyFactor = attribute(environment, EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL, eye, 1.0F);
            if (this.eyeInWater) {
                int waterFog = attribute(environment, EnvironmentAttributes.WATER_FOG_COLOR_VISUAL, eye, 0x050533);
                float waterFogEnd = attribute(environment, EnvironmentAttributes.WATER_FOG_END_DISTANCE_VISUAL, eye, 48.0F);
                this.skyColor = waterFog & 0xFFFFFF;
                this.horizonColor = waterFog & 0xFFFFFF;
                // The game shortens this to a quarter for a player who just dived and lengthens it over 30 s; use halfway.
                this.fogStart = -8.0D;
                this.fogEnd = MathHelper.clamp(waterFogEnd * 0.5D, 8.0D, VIEW_DISTANCE);
            } else {
                this.skyColor = sky & 0xFFFFFF;
                this.horizonColor = fog & 0xFFFFFF;
                this.fogStart = FOG_START;
                this.fogEnd = VIEW_DISTANCE;
            }

            // The game's light curve at the "Bright" brightness setting, per block light (high nibble) and sky light (low).
            double ambient = world.getDimension().ambientLight();
            for (int blockLevel = 0; blockLevel < 16; blockLevel++) {
                for (int skyLevel = 0; skyLevel < 16; skyLevel++) {
                    double level = Math.max(blockLevel, skyLevel * MathHelper.clamp(skyFactor, 0.0F, 1.0F)) / 15.0D;
                    double curve = level / (4.0D - 3.0D * level);
                    curve = ambient + curve * (1.0D - ambient);
                    curve = 0.03D + curve * 0.96D;
                    double inverse = 1.0D - curve;
                    this.brightness[blockLevel << 4 | skyLevel] = 1.0D - inverse * inverse * inverse * inverse;
                }
            }

            this.entities = new ArrayList<>();
            Box area = new Box(eye, eye).expand(VIEW_DISTANCE);
            for (Entity entity : world.getOtherEntities(robot, area, candidate -> !candidate.isInvisible() && !candidate.isSpectator())) {
                BlockPos lightPos = BlockPos.ofFloored(entity.getBoundingBox().getCenter());
                int light = world.getLightLevel(LightType.BLOCK, lightPos) << 4 | (this.hasSkyLight ? world.getLightLevel(LightType.SKY, lightPos) : 0);
                this.entities.add(MineBotCameraEntities.capture(entity, entityColor(entity), light));
            }
        }

        static Scene capture(ServerWorld world, MineBotEntity robot) {
            return new Scene(world, robot);
        }

        // A section with no sky light data takes the bottom layer of the nearest section above that has some, as the game does; none above means open sky.
        private void copySkyLight(ChunkLightingView skyLight, ServerWorld world, int chunkX, int chunkZ, int column) {
            int columns = this.chunksX * this.chunksZ;
            ChunkNibbleArray above = null;
            for (int sectionY = world.getTopSectionCoord(); sectionY >= this.minSectionY; sectionY--) {
                ChunkNibbleArray light = skyLight.getLightSection(ChunkSectionPos.from(chunkX, sectionY, chunkZ));
                int sy = sectionY - this.minSectionY;
                boolean inRange = sy < this.sectionsY;
                if (light != null) {
                    above = light;
                    if (inRange) {
                        this.skyLightArrays[sy * columns + column] = light.copy();
                    }
                } else if (inRange && above != null) {
                    ChunkNibbleArray filled = new ChunkNibbleArray();
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            int value = above.get(x, 0, z);
                            for (int y = 0; y < 16; y++) {
                                filled.set(x, y, z, value);
                            }
                        }
                    }
                    this.skyLightArrays[sy * columns + column] = filled;
                }
            }
        }

        private int index(int x, int y, int z) {
            int cx = (x >> 4) - this.minChunkX;
            int cz = (z >> 4) - this.minChunkZ;
            int sy = (y >> 4) - this.minSectionY;
            if (cx < 0 || cz < 0 || cx >= this.chunksX || cz >= this.chunksZ || sy < 0 || sy >= this.sectionsY) {
                return -1;
            }
            return (sy * this.chunksZ + cz) * this.chunksX + cx;
        }

        /** The block at a position; air above the copied range, null past its edge, below the world or in an unloaded chunk. */
        BlockState state(int x, int y, int z) {
            if (y > this.worldTopY) {
                return Blocks.AIR.getDefaultState();
            }
            if (y < this.worldBottomY) {
                return null;
            }
            int cx = (x >> 4) - this.minChunkX;
            int cz = (z >> 4) - this.minChunkZ;
            if (cx < 0 || cz < 0 || cx >= this.chunksX || cz >= this.chunksZ || !this.loaded[cz * this.chunksX + cx]) {
                return null;
            }
            int sy = (y >> 4) - this.minSectionY;
            if (sy < 0) {
                return null;
            }
            if (sy >= this.sectionsY) {
                return Blocks.AIR.getDefaultState();
            }
            ChunkSection section = this.sections[(sy * this.chunksZ + cz) * this.chunksX + cx];
            return section == null ? Blocks.AIR.getDefaultState() : section.getBlockState(x & 15, y & 15, z & 15);
        }

        int blockLight(int x, int y, int z) {
            int index = this.index(x, y, z);
            ChunkNibbleArray light = index < 0 ? null : this.blockLightArrays[index];
            return light == null ? 0 : light.get(x & 15, y & 15, z & 15);
        }

        int skyLight(int x, int y, int z) {
            if (!this.hasSkyLight) {
                return 0;
            }
            int index = this.index(x, y, z);
            ChunkNibbleArray light = index < 0 ? null : this.skyLightArrays[index];
            return light == null ? 15 : light.get(x & 15, y & 15, z & 15);
        }

        Biome biome(int x, int y, int z) {
            int index = this.index(x, y, z);
            ChunkSection section = index < 0 ? null : this.sections[index];
            return section == null ? null : section.getBiomeContainer().get((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2).value();
        }

        double fogAmount(double distance) {
            return distance <= this.fogStart ? 0.0D : Math.min(1.0D, (distance - this.fogStart) / (this.fogEnd - this.fogStart));
        }

        /** Fog fades to the horizon colour, and into the sky colour when looking up under an open sky. */
        int fogColor(double dy) {
            return this.hasSky && !this.eyeInWater ? this.skyColor(Math.min(dy, 0.0D)) : this.horizonColor;
        }

        int skyColor(double dy) {
            if (!this.hasSky || this.eyeInWater) {
                return this.horizonColor;
            }
            double blend = MathHelper.clamp((dy + 0.02D) / 0.22D, 0.0D, 1.0D);
            blend = blend * blend * (3.0D - 2.0D * blend);
            return lerpColor(this.horizonColor, this.skyColor, blend);
        }

        private static <T> T attribute(EnvironmentAttributeAccess access, EnvironmentAttribute<T> attribute, Vec3d pos, T fallback) {
            try {
                T value = access.getAttributeValue(attribute, pos);
                return value == null ? fallback : value;
            } catch (RuntimeException exception) {
                return fallback;
            }
        }
    }

    private static int entityColor(Entity entity) {
        return switch (MineBotScanner.categoryOf(entity)) {
            case "player" -> PLAYER_COLOR;
            case "minebot" -> MINEBOT_COLOR;
            case "hostile" -> HOSTILE_COLOR;
            case "animal" -> ANIMAL_COLOR;
            case "item" -> ITEM_COLOR;
            case "vehicle" -> VEHICLE_COLOR;
            default -> OTHER_ENTITY_COLOR;
        };
    }

    private static int lerpColor(int from, int to, double amount) {
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
        return r << 16 | g << 8 | b;
    }
}
