package com.oori.minebot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.world.World;

/**
 * Every robot of this world, saved with it in data/minebot_robots.dat: where each living robot was last seen,
 * and how the dead ones died. Programs can then list and connect to robots whose chunks are not loaded, and
 * still learn how a robot died after the server restarted. Only used on the server thread.
 */
public final class MineBotRegistry extends PersistentState {
    // Dead robots beyond this many are forgotten, oldest death first.
    private static final int REMEMBERED_DEATHS = 64;
    private static final Codec<MineBotRegistry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        RobotRecord.CODEC.listOf().optionalFieldOf("robots", List.of()).forGetter(registry -> List.copyOf(registry.records.values()))
    ).apply(instance, MineBotRegistry::new));
    // No data fixer: Fabric API loads saved data that has none without fixing it.
    private static final PersistentStateType<MineBotRegistry> TYPE = new PersistentStateType<>(
        "minebot_robots",
        MineBotRegistry::new,
        CODEC,
        null
    );

    // By robot UUID, oldest first; a robot moves to the end when it dies.
    private final Map<UUID, RobotRecord> records = new LinkedHashMap<>();

    private MineBotRegistry() {
    }

    private MineBotRegistry(List<RobotRecord> saved) {
        for (RobotRecord record : saved) {
            this.records.put(record.uuid(), record);
        }
    }

    public static MineBotRegistry get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    /** Records how a living robot is now. A dying robot is left as recordDeath wrote it. */
    void update(MineBotEntity robot) {
        if (!robot.isAlive()) {
            return;
        }

        RobotRecord next = RobotRecord.of(robot, Optional.empty());
        if (!next.equals(this.records.get(robot.getUuid()))) {
            this.records.put(robot.getUuid(), next);
            this.markDirty();
        }
    }

    void recordDeath(MineBotEntity robot, JsonObject death) {
        this.records.remove(robot.getUuid());
        this.records.put(robot.getUuid(), RobotRecord.of(robot, Optional.of(death.toString())));

        int deaths = (int) this.records.values().stream().filter(RobotRecord::dead).count();
        for (Iterator<RobotRecord> iterator = this.records.values().iterator(); deaths > REMEMBERED_DEATHS && iterator.hasNext(); ) {
            if (iterator.next().dead()) {
                iterator.remove();
                deaths--;
            }
        }
        this.markDirty();
    }

    /** Forgets a living robot that left the world without dying. Dead robots stay until pruned. */
    void forgetLiving(UUID uuid) {
        RobotRecord record = this.records.get(uuid);
        if (record != null && !record.dead()) {
            this.records.remove(uuid);
            this.markDirty();
        }
    }

    /** The robot with this code: the living one if there is one, else the one that died last. */
    RobotRecord find(String code) {
        RobotRecord found = null;
        for (RobotRecord record : this.records.values()) {
            if (record.code().equalsIgnoreCase(code) && (found == null || found.dead())) {
                found = record;
            }
        }
        return found;
    }

    /** Living robots first, oldest first, then dead robots, the last to die first. */
    List<RobotRecord> listed() {
        List<RobotRecord> living = new ArrayList<>();
        List<RobotRecord> dead = new ArrayList<>();
        for (RobotRecord record : this.records.values()) {
            (record.dead() ? dead : living).add(record);
        }
        living.addAll(dead.reversed());
        return living;
    }

    record RobotRecord(
        UUID uuid,
        String code,
        String displayName,
        Optional<UUID> ownerUuid,
        String ownerName,
        Identifier dimension,
        double x,
        double y,
        double z,
        float health,
        float maxHealth,
        boolean evil,
        Optional<String> death
    ) {
        static final Codec<RobotRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Uuids.STRING_CODEC.fieldOf("uuid").forGetter(RobotRecord::uuid),
            Codec.STRING.fieldOf("code").forGetter(RobotRecord::code),
            Codec.STRING.optionalFieldOf("display_name", "MineBot").forGetter(RobotRecord::displayName),
            Uuids.STRING_CODEC.optionalFieldOf("owner_uuid").forGetter(RobotRecord::ownerUuid),
            Codec.STRING.optionalFieldOf("owner_name", "").forGetter(RobotRecord::ownerName),
            Identifier.CODEC.fieldOf("dimension").forGetter(RobotRecord::dimension),
            Codec.DOUBLE.fieldOf("x").forGetter(RobotRecord::x),
            Codec.DOUBLE.fieldOf("y").forGetter(RobotRecord::y),
            Codec.DOUBLE.fieldOf("z").forGetter(RobotRecord::z),
            Codec.FLOAT.optionalFieldOf("health", 0.0F).forGetter(RobotRecord::health),
            Codec.FLOAT.optionalFieldOf("max_health", 20.0F).forGetter(RobotRecord::maxHealth),
            Codec.BOOL.optionalFieldOf("evil", false).forGetter(RobotRecord::evil),
            // The death payload the bridge sends, as JSON text.
            Codec.STRING.optionalFieldOf("death").forGetter(RobotRecord::death)
        ).apply(instance, RobotRecord::new));

        static RobotRecord of(MineBotEntity robot, Optional<String> death) {
            return new RobotRecord(
                robot.getUuid(),
                robot.getAccessCode(),
                robot.getDisplayName().getString(),
                Optional.ofNullable(robot.getOwnerUuid()),
                robot.getOwnerName(),
                robot.getEntityWorld().getRegistryKey().getValue(),
                robot.getX(),
                robot.getY(),
                robot.getZ(),
                Math.max(0.0F, robot.getHealth()),
                robot.getMaxHealth(),
                robot.isEvil(),
                death
            );
        }

        boolean dead() {
            return this.death.isPresent();
        }

        RegistryKey<World> worldKey() {
            return RegistryKey.of(RegistryKeys.WORLD, this.dimension);
        }

        ChunkPos chunkPos() {
            return new ChunkPos(BlockPos.ofFloored(this.x, this.y, this.z));
        }

        String describePosition() {
            return String.format(
                Locale.ROOT,
                "x=%.1f y=%.1f z=%.1f in %s",
                this.x,
                this.y,
                this.z,
                this.dimension
            );
        }

        JsonObject deathPayload() {
            try {
                return JsonParser.parseString(this.death.orElseThrow()).getAsJsonObject();
            } catch (JsonParseException | IllegalStateException exception) {
                JsonObject death = new JsonObject();
                death.addProperty("code", this.code);
                death.addProperty("display_name", this.displayName);
                death.addProperty("message", this.displayName + " died");
                return death;
            }
        }

        /** A robots listing entry made from what was saved, for a robot that is not loaded. */
        JsonObject createLocatorPayload(MinecraftServer server, String endpoint) {
            JsonObject robot = new JsonObject();
            robot.addProperty("entity_uuid", this.uuid.toString());
            robot.addProperty("display_name", this.displayName);
            robot.addProperty("code", this.code);
            robot.addProperty("access_code", this.code);
            robot.addProperty("endpoint", endpoint);
            robot.addProperty("dimension", this.dimension.toString());
            robot.addProperty("connected", false);
            robot.addProperty("evil", this.evil);
            this.ownerUuid.ifPresent(owner -> robot.addProperty("owner_uuid", owner.toString()));
            robot.addProperty("owner_name", this.ownerName);
            robot.addProperty("owner_online", this.ownerUuid.map(owner -> server.getPlayerManager().getPlayer(owner) != null).orElse(false));
            robot.addProperty("health", MineBotEntity.roundCoordinate(this.health));
            robot.addProperty("max_health", MineBotEntity.roundCoordinate(this.maxHealth));
            robot.addProperty("x", MineBotEntity.roundCoordinate(this.x));
            robot.addProperty("y", MineBotEntity.roundCoordinate(this.y));
            robot.addProperty("z", MineBotEntity.roundCoordinate(this.z));
            robot.addProperty("loaded", false);
            robot.addProperty("dead", this.dead());
            if (this.dead()) {
                robot.add("death", this.deathPayload());
            }
            return robot;
        }
    }
}
