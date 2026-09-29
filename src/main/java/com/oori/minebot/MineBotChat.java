package com.oori.minebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.entity.Entity;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public final class MineBotChat {
    private static final int MIN_CODE_PREFIX_LENGTH = 3;

    private MineBotChat() {
    }

    public static void initialize() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> onPlayerMessage(message, sender));
        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) -> onCommandMessage(message, source));
    }

    private static void onPlayerMessage(SignedMessage message, ServerPlayerEntity sender) {
        ServerWorld world = sender.getEntityWorld();
        route(
            world.getServer(),
            contentOf(message),
            sender.getName().getString(),
            sender.getUuid(),
            "player",
            world,
            new Vec3d(sender.getX(), sender.getY(), sender.getZ()),
            true,
            sender
        );
    }

    private static void onCommandMessage(SignedMessage message, ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (player != null) {
            onPlayerMessage(message, player);
            return;
        }

        Entity entity = source.getEntity();
        boolean console = entity == null && ("Server".equals(source.getName()) || "Rcon".equals(source.getName()));
        route(
            source.getServer(),
            contentOf(message),
            source.getName(),
            null,
            console ? "console" : "command",
            source.getWorld(),
            source.getPosition(),
            !console,
            null
        );
    }

    private static String contentOf(SignedMessage message) {
        String content = message.getSignedContent();
        return content == null || content.isBlank() ? message.getContent().getString() : content;
    }

    private static void route(
        MinecraftServer server,
        String raw,
        String senderName,
        UUID senderUuid,
        String senderType,
        ServerWorld senderWorld,
        Vec3d senderPos,
        boolean reportPosition,
        ServerPlayerEntity feedbackPlayer
    ) {
        try {
            String trimmed = raw == null ? "" : raw.trim();
            if (!trimmed.startsWith("@")) {
                return;
            }

            int tokenEnd = 1;
            while (tokenEnd < trimmed.length() && !Character.isWhitespace(trimmed.charAt(tokenEnd))) {
                tokenEnd++;
            }

            String token = trimmed.substring(1, tokenEnd);
            String text = trimmed.substring(tokenEnd).trim();
            if (token.isEmpty() || text.isEmpty()) {
                return;
            }

            List<MineBotEntity> robots = listRobots(server);
            if (robots.isEmpty()) {
                return;
            }

            String address;
            List<MineBotEntity> targets = new ArrayList<>();
            if (token.equalsIgnoreCase("all")) {
                address = "all";
                targets.addAll(robots);
            } else {
                address = null;
                if (token.equalsIgnoreCase("bot")) {
                    MineBotEntity nearest = findNearest(robots, senderWorld, senderPos);
                    if (nearest != null) {
                        address = "bot";
                        targets.add(nearest);
                    }
                }

                if (address == null) {
                    for (MineBotEntity robot : robots) {
                        if (robot.getAccessCode().equalsIgnoreCase(token)) {
                            address = "code";
                            targets.add(robot);
                            break;
                        }
                    }
                }

                if (address == null) {
                    for (MineBotEntity robot : robots) {
                        if (robot.hasCustomName() && stripWhitespace(robot.getCustomName().getString()).equalsIgnoreCase(token)) {
                            targets.add(robot);
                        }
                    }
                    if (!targets.isEmpty()) {
                        address = "name";
                    }
                }

                if (address == null && token.length() >= MIN_CODE_PREFIX_LENGTH) {
                    String prefix = token.toUpperCase(Locale.ROOT);
                    MineBotEntity match = null;
                    int matches = 0;
                    for (MineBotEntity robot : robots) {
                        if (robot.getAccessCode().toUpperCase(Locale.ROOT).startsWith(prefix)) {
                            match = robot;
                            matches++;
                        }
                    }
                    if (matches == 1) {
                        address = "code";
                        targets.add(match);
                    }
                }
            }

            if (address == null || targets.isEmpty()) {
                return;
            }

            MineBotChatInbox.Message message = new MineBotChatInbox.Message(
                senderName,
                senderUuid,
                senderType,
                text,
                trimmed,
                System.currentTimeMillis(),
                reportPosition && senderWorld != null ? senderWorld.getRegistryKey().getValue().toString() : null,
                reportPosition && senderWorld != null ? senderPos : null
            );

            for (MineBotEntity robot : targets) {
                robot.getChatInbox().add(message, address);
                if (feedbackPlayer != null && !"all".equals(address) && !robot.isConnected()) {
                    feedbackPlayer.sendMessage(
                        Text.literal("[MineBot:" + robot.getAccessCode() + "] No program is connected. Your message was queued."),
                        false
                    );
                }
            }
        } catch (RuntimeException exception) {
            MineBotMod.LOGGER.warn("Failed to route MineBot chat message", exception);
        }
    }

    private static MineBotEntity findNearest(List<MineBotEntity> robots, ServerWorld senderWorld, Vec3d senderPos) {
        if (senderWorld == null || senderPos == null) {
            return robots.get(0);
        }

        MineBotEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (MineBotEntity robot : robots) {
            if (robot.getEntityWorld().getRegistryKey() != senderWorld.getRegistryKey()) {
                continue;
            }

            double distance = robot.squaredDistanceTo(senderPos);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = robot;
            }
        }
        return nearest;
    }

    private static List<MineBotEntity> listRobots(MinecraftServer server) {
        List<MineBotEntity> robots = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                if (entity instanceof MineBotEntity robot && robot.isAlive() && !robot.isRemoved() && !robot.isEvil()) {
                    robots.add(robot);
                }
            }
        }
        return robots;
    }

    private static String stripWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character)) {
                builder.append(character);
            }
        }
        return builder.toString();
    }
}
