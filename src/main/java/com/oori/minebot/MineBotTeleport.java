package com.oori.minebot;

import com.mojang.brigadier.Message;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

/**
 * Robots in /tp and /teleport (see TeleportCommandMixin). A player can teleport to a robot a program is connected
 * to by giving its code or its name as the destination, and the destination suggestions list those robots by code.
 * Robots themselves are never teleported: they only get anywhere by walking.
 */
public final class MineBotTeleport {
    /** The tooltip of a named robot's suggestion; its one argument is the name, which the suggestion list shows after the code. */
    public static final String NAMED_ROBOT_SUGGESTION_KEY = "commands.minebot.teleport.robot";

    private static final DynamicCommandExceptionType ROBOT_TELEPORTED = new DynamicCommandExceptionType(
        robot -> Text.empty().append((Text) robot).append(" cannot be teleported: robots only move by walking")
    );
    private static final SimpleCommandExceptionType NOT_A_PLAYER = new SimpleCommandExceptionType(
        Text.literal("Only players can teleport to a robot")
    );
    private static final DynamicCommandExceptionType NOT_CONNECTED = new DynamicCommandExceptionType(
        robot -> Text.empty().append((Text) robot).append(" has no program connected; only connected robots can be teleported to")
    );
    private static final DynamicCommandExceptionType SAME_NAME = new DynamicCommandExceptionType(
        codes -> Text.literal("Several robots have that name; use a code: " + codes)
    );

    private MineBotTeleport() {
    }

    /**
     * The robot a /tp destination names when no online player has that name: the robot with that code, else the
     * robot with that name, either way only while a program is connected to it. Null when no robot matches or the
     * command does not come from a player.
     */
    public static MineBotEntity findDestination(ServerCommandSource source, String name) throws CommandSyntaxException {
        if (commandingPlayer(source) == null) {
            return null;
        }

        List<MineBotEntity> robots = loadedRobots(source.getServer());
        for (MineBotEntity robot : robots) {
            if (robot.getAccessCode().equalsIgnoreCase(name)) {
                return requireConnected(robot);
            }
        }

        List<MineBotEntity> named = new ArrayList<>();
        for (MineBotEntity robot : robots) {
            if (robot.hasRobotName() && robot.getCustomName().getString().equalsIgnoreCase(name)) {
                named.add(robot);
            }
        }

        List<MineBotEntity> connected = named.stream().filter(MineBotEntity::isConnected).toList();
        if (connected.size() > 1) {
            throw SAME_NAME.create(String.join(", ", connected.stream().map(MineBotEntity::getAccessCode).toList()));
        }
        if (connected.size() == 1) {
            return connected.getFirst();
        }
        return named.isEmpty() ? null : requireConnected(named.getFirst());
    }

    /**
     * The destinations /tp suggests: what it suggests anyway, and for a player, every robot a program is connected
     * to whose code or name starts with what was typed. A robot is suggested by its code; a named robot's tooltip
     * carries the name.
     */
    public static CompletableFuture<Suggestions> suggestDestinations(CommandContext<ServerCommandSource> context, SuggestionsBuilder builder) {
        CompletableFuture<Suggestions> usual = EntityArgumentType.entity().listSuggestions(context, builder);
        String typed = builder.getRemainingLowerCase();
        if (commandingPlayer(context.getSource()) == null || typed.startsWith("@")) {
            return usual;
        }

        SuggestionsBuilder robots = builder.createOffset(builder.getStart());
        for (MineBotEntity robot : loadedRobots(context.getSource().getServer())) {
            String name = robot.hasRobotName() ? robot.getCustomName().getString() : null;
            if (robot.isConnected()
                && (robot.getAccessCode().toLowerCase(Locale.ROOT).startsWith(typed)
                    || name != null && name.toLowerCase(Locale.ROOT).startsWith(typed))) {
                robots.suggest(
                    robot.getAccessCode(),
                    name == null
                        ? Text.translatable("entity.minebot.minebot")
                        : Text.translatableWithFallback(NAMED_ROBOT_SUGGESTION_KEY, "MineBot %s", name)
                );
            }
        }

        Suggestions robotSuggestions = robots.build();
        return usual.thenApply(found -> Suggestions.merge(builder.getInput(), List.of(found, robotSuggestions)));
    }

    /** Robots are never teleported, and only players are teleported to a robot. */
    public static void checkTeleport(Collection<? extends Entity> targets, Entity destination) throws CommandSyntaxException {
        for (Entity target : targets) {
            if (target instanceof MineBotEntity robot) {
                throw ROBOT_TELEPORTED.create(robot.getDisplayName());
            }
        }

        if (destination instanceof MineBotEntity) {
            for (Entity target : targets) {
                if (!(target instanceof ServerPlayerEntity) || target instanceof FakePlayer) {
                    throw NOT_A_PLAYER.create();
                }
            }
        }
    }

    /** How a suggestion list shows a suggestion: a named robot's code is followed by its name in brackets. */
    public static String suggestionLabel(String text, Message tooltip) {
        if (tooltip instanceof Text tooltipText
            && tooltipText.getContent() instanceof TranslatableTextContent content
            && NAMED_ROBOT_SUGGESTION_KEY.equals(content.getKey())
            && content.getArgs().length == 1) {
            Object name = content.getArgs()[0];
            return text + " (" + (name instanceof Text nameText ? nameText.getString() : String.valueOf(name)) + ")";
        }
        return text;
    }

    /** The player who runs a command, or null for the console, command blocks, robots and other entities. */
    private static ServerPlayerEntity commandingPlayer(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        return player instanceof FakePlayer ? null : player;
    }

    private static MineBotEntity requireConnected(MineBotEntity robot) throws CommandSyntaxException {
        if (!robot.isConnected()) {
            throw NOT_CONNECTED.create(robot.getDisplayName());
        }
        return robot;
    }

    private static List<MineBotEntity> loadedRobots(MinecraftServer server) {
        List<MineBotEntity> robots = new ArrayList<>();
        for (ServerWorld world : server.getWorlds()) {
            for (Entity entity : world.iterateEntities()) {
                // A dead robot lingers for its death animation.
                if (entity instanceof MineBotEntity robot && robot.isAlive()) {
                    robots.add(robot);
                }
            }
        }
        return robots;
    }
}
