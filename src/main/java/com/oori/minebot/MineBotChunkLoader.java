package com.oori.minebot;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * Keeps the chunks around robots loaded with MineBot's own chunk ticket, which never touches /forceload.
 *
 * A ticket lapses two seconds after it was last renewed, and is not saved with the world. A robot renews its
 * ticket every tick for as long as it wants its area loaded (see MineBotEntity#shouldKeepChunksLoaded), so
 * nothing stays loaded for long once the robot stops asking, also after a crash or a missed clean-up.
 * Busy robots also get their ticket renewed after every server tick, so one that was moved out of its
 * ticking area, say by /tp, does not freeze where it can no longer renew it itself.
 */
public final class MineBotChunkLoader {
    // The chunks this far from the robot's chunk tick entities: a 3x3 area.
    private static final int ENTITY_TICKING_RADIUS = 1;
    // A ticket of radius r makes the chunks up to r - 2 away entity ticking, and loads those up to r away.
    private static final int TICKET_RADIUS = ENTITY_TICKING_RADIUS + 2;
    private static final long TICKET_LIFETIME_TICKS = 40L;
    private static final ChunkTicketType TICKET = Registry.register(
        Registries.TICKET_TYPE,
        MineBotMod.id("robot"),
        new ChunkTicketType(
            TICKET_LIFETIME_TICKS,
            ChunkTicketType.FOR_LOADING | ChunkTicketType.FOR_SIMULATION | ChunkTicketType.RESETS_IDLE_TIMEOUT
        )
    );
    private static final int DEFAULT_HOLD_SECONDS = 300;
    private static final Map<MinecraftServer, MineBotChunkLoader> LOADERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile int holdTicks = DEFAULT_HOLD_SECONDS * 20;

    // Robots driven by a program, carrying out an order, or evil. Also read by the client thread.
    private final Map<UUID, MineBotEntity> busyRobots = new ConcurrentHashMap<>();

    private MineBotChunkLoader() {
    }

    /** Registers the ticket type (on class load) and the tick hook; called once while the mod initializes. */
    static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> get(server).renewBusyRobots());
    }

    public static MineBotChunkLoader get(MinecraftServer server) {
        return LOADERS.computeIfAbsent(server, ignored -> new MineBotChunkLoader());
    }

    public static void startForServer(MinecraftServer server) {
        int seconds = MineBotConfig.readNonNegativeInt(MineBotConfig.read(), "chunks.hold_seconds", DEFAULT_HOLD_SECONDS);
        holdTicks = Math.min(seconds, Integer.MAX_VALUE / 20) * 20;
    }

    public static void stopForServer(MinecraftServer server) {
        LOADERS.remove(server);
    }

    /** How long a robot keeps its area loaded after it was last busy. */
    static int holdTicks() {
        return holdTicks;
    }

    /** True while any robot is busy; then the singleplayer game does not pause. */
    public boolean hasActiveRobots() {
        return !this.busyRobots.isEmpty();
    }

    /** Called by every robot on every tick. */
    void update(MineBotEntity robot, boolean keepLoaded, boolean busy) {
        if (busy && !robot.isRemoved()) {
            this.busyRobots.put(robot.getUuid(), robot);
        } else {
            this.busyRobots.remove(robot.getUuid(), robot);
        }

        if (keepLoaded && !robot.isRemoved() && robot.getEntityWorld() instanceof ServerWorld world) {
            keepLoaded(world, robot.getChunkPos());
        }
    }

    void release(MineBotEntity robot) {
        this.busyRobots.remove(robot.getUuid(), robot);
    }

    private void renewBusyRobots() {
        for (MineBotEntity robot : this.busyRobots.values()) {
            if (robot.isRemoved()) {
                this.busyRobots.remove(robot.getUuid(), robot);
            } else if (robot.getEntityWorld() instanceof ServerWorld world) {
                keepLoaded(world, robot.getChunkPos());
            }
        }
    }

    /** Loads the robot area around a chunk, or keeps it loaded for two more seconds. */
    static void keepLoaded(ServerWorld world, ChunkPos chunk) {
        world.getChunkManager().addTicket(TICKET, chunk, TICKET_RADIUS);
    }

    /**
     * True once the entities in the 3x3 chunks around a chunk are loaded and ticking. Loaded alone is not
     * enough: entities of a chunk that is about to unload are still loaded but no longer found.
     */
    static boolean isAreaLoaded(ServerWorld world, ChunkPos center) {
        for (int dx = -ENTITY_TICKING_RADIUS; dx <= ENTITY_TICKING_RADIUS; dx++) {
            for (int dz = -ENTITY_TICKING_RADIUS; dz <= ENTITY_TICKING_RADIUS; dz++) {
                ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                if (!world.isChunkLoaded(chunk.toLong()) || !world.shouldTickEntityAt(chunk.getStartPos())) {
                    return false;
                }
            }
        }
        return true;
    }
}
