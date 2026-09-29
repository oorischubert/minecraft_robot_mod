package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.chunk.WorldChunk;

final class MineBotScanner {
    private static final int MAX_BLOCK_COUNT_ENTRIES = 32;
    private static final double[][] FACE_SAMPLES = {
        {0.5D, 0.5D}, {0.15D, 0.15D}, {0.85D, 0.15D}, {0.15D, 0.85D}, {0.85D, 0.85D}
    };
    private static final double FACE_SAMPLE_INSET = 0.02D;

    private MineBotScanner() {
    }

    static BlockFilter parseBlockFilter(JsonArray blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }

        Set<Block> ids = new HashSet<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (JsonElement element : blocks) {
            String value = element.getAsString().trim();
            if (value.startsWith("#")) {
                Identifier identifier = Identifier.tryParse(value.substring(1));
                TagKey<Block> tag = identifier == null ? null : TagKey.of(RegistryKeys.BLOCK, identifier);
                if (tag == null || Registries.BLOCK.getOptional(tag).isEmpty()) {
                    throw new MineBotCommandException("invalid_request", "Unknown block tag: " + value);
                }
                tags.add(tag);
            } else {
                Identifier identifier = Identifier.tryParse(value);
                if (identifier == null || !Registries.BLOCK.containsId(identifier)) {
                    throw new MineBotCommandException("invalid_request", "Unknown block id: " + value);
                }
                ids.add(Registries.BLOCK.get(identifier));
            }
        }
        return new BlockFilter(ids, tags);
    }

    static JsonObject scanBlocks(
        ServerWorld world,
        MineBotEntity robot,
        BlockPos origin,
        int radius,
        Vec3d eyePos,
        BlockFilter filter,
        int limit
    ) {
        int minX = origin.getX() - radius;
        int maxX = origin.getX() + radius;
        int minY = Math.max(world.getBottomY(), origin.getY() - radius);
        int maxY = Math.min(world.getTopYInclusive(), origin.getY() + radius);
        int minZ = origin.getZ() - radius;
        int maxZ = origin.getZ() + radius;
        ChunkCache chunks = new ChunkCache(world, (minX >> 4) - 1, (minZ >> 4) - 1, (maxX >> 4) + 1, (maxZ >> 4) + 1);

        PriorityQueue<Match> nearest = new PriorityQueue<>(Comparator.comparingDouble(Match::distanceSquared).reversed());
        Object2IntOpenHashMap<Block> counts = new Object2IntOpenHashMap<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable neighbor = new BlockPos.Mutable();
        int total = 0;

        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                WorldChunk chunk = chunks.get(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }

                int startX = Math.max(minX, chunkX << 4);
                int endX = Math.min(maxX, (chunkX << 4) + 15);
                int startZ = Math.max(minZ, chunkZ << 4);
                int endZ = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int x = startX; x <= endX; x++) {
                    for (int z = startZ; z <= endZ; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            BlockState state = chunk.getBlockState(pos.set(x, y, z));
                            if (state.isAir() || (filter != null && !filter.test(state))) {
                                continue;
                            }

                            if (!canSeeBlock(world, robot, chunks, eyePos, x, y, z, neighbor)) {
                                continue;
                            }

                            total++;
                            counts.addTo(state.getBlock(), 1);
                            double dx = x + 0.5D - eyePos.x;
                            double dy = y + 0.5D - eyePos.y;
                            double dz = z + 0.5D - eyePos.z;
                            double distanceSquared = dx * dx + dy * dy + dz * dz;
                            if (nearest.size() < limit) {
                                nearest.add(new Match(pos.asLong(), state, distanceSquared));
                            } else if (distanceSquared < nearest.peek().distanceSquared()) {
                                nearest.poll();
                                nearest.add(new Match(pos.asLong(), state, distanceSquared));
                            }
                        }
                    }
                }
            }
        }

        List<Match> sorted = new ArrayList<>(nearest);
        sorted.sort(Comparator.comparingDouble(Match::distanceSquared));
        JsonArray matches = new JsonArray();
        for (Match match : sorted) {
            pos.set(match.pos());
            JsonObject entry = new JsonObject();
            entry.addProperty("block", MineBotEntity.idOf(match.state()));
            entry.addProperty("x", pos.getX());
            entry.addProperty("y", pos.getY());
            entry.addProperty("z", pos.getZ());
            entry.addProperty("distance", MineBotEntity.roundCoordinate(Math.sqrt(match.distanceSquared())));
            matches.add(entry);
        }

        List<Object2IntMap.Entry<Block>> countEntries = new ArrayList<>(counts.object2IntEntrySet());
        countEntries.sort((left, right) -> Integer.compare(right.getIntValue(), left.getIntValue()));
        JsonObject countsJson = new JsonObject();
        for (int index = 0; index < Math.min(MAX_BLOCK_COUNT_ENTRIES, countEntries.size()); index++) {
            Object2IntMap.Entry<Block> entry = countEntries.get(index);
            countsJson.addProperty(Registries.BLOCK.getId(entry.getKey()).toString(), entry.getIntValue());
        }

        JsonObject result = new JsonObject();
        result.addProperty("origin_x", origin.getX());
        result.addProperty("origin_y", origin.getY());
        result.addProperty("origin_z", origin.getZ());
        result.addProperty("radius", radius);
        result.add("matches", matches);
        result.addProperty("total_matches", total);
        result.addProperty("truncated", total > limit);
        result.add("counts", countsJson);
        return result;
    }

    // A block is seen when a ray from the robot's eyes reaches one of its faces without hitting another block first.
    private static boolean canSeeBlock(
        ServerWorld world,
        MineBotEntity robot,
        ChunkCache chunks,
        Vec3d eye,
        int x,
        int y,
        int z,
        BlockPos.Mutable neighbor
    ) {
        if (MathHelper.floor(eye.x) == x && MathHelper.floor(eye.y) == y && MathHelper.floor(eye.z) == z) {
            return true;
        }

        for (Direction direction : Direction.values()) {
            int offsetX = direction.getOffsetX();
            int offsetY = direction.getOffsetY();
            int offsetZ = direction.getOffsetZ();
            double towardEye = (eye.x - (x + 0.5D + offsetX * 0.5D)) * offsetX
                + (eye.y - (y + 0.5D + offsetY * 0.5D)) * offsetY
                + (eye.z - (z + 0.5D + offsetZ * 0.5D)) * offsetZ;
            if (towardEye <= 0.0D || isCovered(world, chunks, x + offsetX, y + offsetY, z + offsetZ, neighbor)) {
                continue;
            }

            for (double[] sample : FACE_SAMPLES) {
                if (rayReachesBlock(world, robot, eye, facePoint(x, y, z, direction, sample[0], sample[1]), x, y, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCovered(ServerWorld world, ChunkCache chunks, int x, int y, int z, BlockPos.Mutable neighbor) {
        if (y < world.getBottomY() || y > world.getTopYInclusive()) {
            return false;
        }

        WorldChunk chunk = chunks.get(x >> 4, z >> 4);
        return chunk == null || chunk.getBlockState(neighbor.set(x, y, z)).isOpaqueFullCube();
    }

    private static Vec3d facePoint(int x, int y, int z, Direction direction, double u, double v) {
        double depth = (direction.getDirection() == Direction.AxisDirection.POSITIVE ? 1.0D - FACE_SAMPLE_INSET : FACE_SAMPLE_INSET);
        return switch (direction.getAxis()) {
            case X -> new Vec3d(x + depth, y + u, z + v);
            case Y -> new Vec3d(x + u, y + depth, z + v);
            case Z -> new Vec3d(x + u, y + v, z + depth);
        };
    }

    private static boolean rayReachesBlock(ServerWorld world, MineBotEntity robot, Vec3d eye, Vec3d target, int x, int y, int z) {
        BlockHitResult hit = raycastSight(world, robot, eye, target);
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }

        BlockPos hitPos = hit.getBlockPos();
        return hitPos.getX() == x && hitPos.getY() == y && hitPos.getZ() == z;
    }

    private static BlockHitResult raycastSight(ServerWorld world, MineBotEntity robot, Vec3d eye, Vec3d target) {
        return world.raycast(
            new RaycastContext(eye, target, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, robot)
        );
    }

    static boolean hasLineOfSight(MineBotEntity robot, Entity entity) {
        if (!(robot.getEntityWorld() instanceof ServerWorld world) || entity.getEntityWorld() != world) {
            return false;
        }

        Vec3d eye = robot.getCommandRayStart();
        Box box = entity.getBoundingBox();
        Vec3d center = box.getCenter();
        Vec3d[] targets = {entity.getEyePos(), center, new Vec3d(center.x, box.minY + 0.05D, center.z)};
        for (Vec3d target : targets) {
            if (raycastSight(world, robot, eye, target).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    // Players are also noticed through walls, the way their name tag shows through blocks unless they sneak.
    static boolean canPerceive(MineBotEntity robot, Entity entity) {
        if (entity instanceof PlayerEntity player && !player.isSneaking() && !player.isInvisible()) {
            return true;
        }
        return hasLineOfSight(robot, entity);
    }

    static JsonObject scanEntities(MineBotEntity robot, double radius, Set<EntityType<?>> types, boolean playersOnly, int limit) {
        Vec3d center = new Vec3d(robot.getX(), robot.getY(), robot.getZ());
        double radiusSquared = radius * radius;
        Box box = new Box(center, center).expand(radius);
        List<Entity> found = robot.getEntityWorld().getOtherEntities(
            robot,
            box,
            entity -> !entity.isSpectator()
                && entity.isAlive()
                && (!playersOnly || entity instanceof PlayerEntity)
                && (types == null || types.contains(entity.getType()))
                && entity.squaredDistanceTo(center) <= radiusSquared
                && canPerceive(robot, entity)
        );
        found.sort(Comparator.comparingDouble(entity -> entity.squaredDistanceTo(center)));

        JsonArray entities = new JsonArray();
        for (int index = 0; index < Math.min(limit, found.size()); index++) {
            Entity entity = found.get(index);
            JsonObject entry = new JsonObject();
            entry.addProperty("entity_id", entity.getId());
            entry.addProperty("uuid", entity.getUuidAsString());
            entry.addProperty("type", EntityType.getId(entity.getType()).toString());
            entry.addProperty("name", entity.getDisplayName().getString());
            entry.addProperty("category", categoryOf(entity));
            entry.addProperty("x", MineBotEntity.roundCoordinate(entity.getX()));
            entry.addProperty("y", MineBotEntity.roundCoordinate(entity.getY()));
            entry.addProperty("z", MineBotEntity.roundCoordinate(entity.getZ()));
            entry.addProperty("distance", MineBotEntity.roundCoordinate(Math.sqrt(entity.squaredDistanceTo(center))));
            entry.addProperty("line_of_sight", hasLineOfSight(robot, entity));
            if (entity instanceof LivingEntity living) {
                entry.addProperty("health", MineBotEntity.roundCoordinate(living.getHealth()));
                entry.addProperty("max_health", MineBotEntity.roundCoordinate(living.getMaxHealth()));
            }
            if (entity instanceof ItemEntity itemEntity) {
                entry.addProperty("item", MineBotEntity.itemIdOf(itemEntity.getStack()));
                entry.addProperty("count", itemEntity.getStack().getCount());
            }
            entities.add(entry);
        }

        JsonObject result = new JsonObject();
        result.addProperty("radius", MineBotEntity.roundCoordinate(radius));
        result.add("entities", entities);
        result.addProperty("total", found.size());
        result.addProperty("truncated", found.size() > limit);
        return result;
    }

    static String categoryOf(Entity entity) {
        if (entity instanceof PlayerEntity) {
            return "player";
        }
        if (entity instanceof MineBotEntity) {
            return "minebot";
        }
        if (entity instanceof ItemEntity) {
            return "item";
        }
        if (entity instanceof VehicleEntity) {
            return "vehicle";
        }

        SpawnGroup group = entity.getType().getSpawnGroup();
        if (entity instanceof Monster || group == SpawnGroup.MONSTER) {
            return "hostile";
        }
        if (entity instanceof AnimalEntity || (group != SpawnGroup.MISC && entity instanceof LivingEntity)) {
            return "animal";
        }
        return "other";
    }

    record BlockFilter(Set<Block> blocks, List<TagKey<Block>> tags) {
        boolean test(BlockState state) {
            if (this.blocks.contains(state.getBlock())) {
                return true;
            }

            for (TagKey<Block> tag : this.tags) {
                if (state.isIn(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Match(long pos, BlockState state, double distanceSquared) {
    }

    private static final class ChunkCache {
        private final int minChunkX;
        private final int minChunkZ;
        private final int width;
        private final WorldChunk[] chunks;

        private ChunkCache(ServerWorld world, int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            this.minChunkX = minChunkX;
            this.minChunkZ = minChunkZ;
            this.width = maxChunkX - minChunkX + 1;
            int depth = maxChunkZ - minChunkZ + 1;
            this.chunks = new WorldChunk[this.width * depth];
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    this.chunks[(chunkX - minChunkX) + (chunkZ - minChunkZ) * this.width] = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                }
            }
        }

        private WorldChunk get(int chunkX, int chunkZ) {
            return this.chunks[(chunkX - this.minChunkX) + (chunkZ - this.minChunkZ) * this.width];
        }
    }
}
