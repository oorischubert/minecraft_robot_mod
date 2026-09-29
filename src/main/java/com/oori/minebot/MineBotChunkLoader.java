package com.oori.minebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

public final class MineBotChunkLoader {
    private static final int LOAD_RADIUS = 1;
    private static final Map<MinecraftServer, MineBotChunkLoader> LOADERS = new WeakHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, Lease> robotLeases = new HashMap<>();
    private final Map<ChunkKey, Integer> refCounts = new HashMap<>();

    private MineBotChunkLoader(MinecraftServer server) {
        this.server = server;
    }

    public static MineBotChunkLoader get(MinecraftServer server) {
        return LOADERS.computeIfAbsent(server, MineBotChunkLoader::new);
    }

    public static void stopForServer(MinecraftServer server) {
        MineBotChunkLoader loader = LOADERS.remove(server);
        if (loader != null) {
            loader.releaseAll();
        }
    }

    public boolean hasActiveRobots() {
        return !this.robotLeases.isEmpty();
    }

    public void update(MineBotEntity robot, boolean active) {
        if (!(robot.getEntityWorld() instanceof ServerWorld world)) {
            this.release(robot.getUuid());
            return;
        }

        if (!active || robot.isRemoved()) {
            this.release(robot.getUuid());
            return;
        }

        Lease nextLease = new Lease(world.getRegistryKey(), robot.getChunkPos());
        Lease currentLease = this.robotLeases.get(robot.getUuid());

        if (nextLease.equals(currentLease)) {
            return;
        }

        this.release(robot.getUuid());
        this.acquire(robot.getUuid(), world, nextLease.chunkPos());
    }

    public void release(MineBotEntity robot) {
        this.release(robot.getUuid());
    }

    private void acquire(UUID robotUuid, ServerWorld world, ChunkPos chunkPos) {
        for (ChunkPos coveredChunk : coveredChunks(chunkPos)) {
            ChunkKey key = new ChunkKey(world.getRegistryKey(), coveredChunk.toLong());
            int nextCount = this.refCounts.getOrDefault(key, 0) + 1;
            this.refCounts.put(key, nextCount);

            if (nextCount == 1) {
                world.setChunkForced(coveredChunk.x, coveredChunk.z, true);
            }
        }

        this.robotLeases.put(robotUuid, new Lease(world.getRegistryKey(), chunkPos));
    }

    private void release(UUID robotUuid) {
        Lease lease = this.robotLeases.remove(robotUuid);
        if (lease == null) {
            return;
        }

        ServerWorld world = this.server.getWorld(lease.worldKey());
        if (world == null) {
            return;
        }

        for (ChunkPos coveredChunk : coveredChunks(lease.chunkPos())) {
            ChunkKey key = new ChunkKey(lease.worldKey(), coveredChunk.toLong());
            Integer currentCount = this.refCounts.get(key);
            if (currentCount == null) {
                continue;
            }

            if (currentCount <= 1) {
                this.refCounts.remove(key);
                world.setChunkForced(coveredChunk.x, coveredChunk.z, false);
                continue;
            }

            this.refCounts.put(key, currentCount - 1);
        }
    }

    private void releaseAll() {
        for (UUID robotUuid : new ArrayList<>(this.robotLeases.keySet())) {
            this.release(robotUuid);
        }
    }

    private static Iterable<ChunkPos> coveredChunks(ChunkPos center) {
        ArrayList<ChunkPos> covered = new ArrayList<>((LOAD_RADIUS * 2 + 1) * (LOAD_RADIUS * 2 + 1));
        for (int dx = -LOAD_RADIUS; dx <= LOAD_RADIUS; dx++) {
            for (int dz = -LOAD_RADIUS; dz <= LOAD_RADIUS; dz++) {
                covered.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        return covered;
    }

    private record ChunkKey(RegistryKey<World> worldKey, long chunkPosLong) {
    }

    private record Lease(RegistryKey<World> worldKey, ChunkPos chunkPos) {
    }
}
