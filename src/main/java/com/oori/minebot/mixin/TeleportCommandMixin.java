package com.oori.minebot.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.oori.minebot.MineBotEntity;
import com.oori.minebot.MineBotTeleport;
import java.util.Collection;
import net.minecraft.command.EntitySelector;
import net.minecraft.command.argument.PosArgument;
import net.minecraft.entity.Entity;
import net.minecraft.server.command.LookTarget;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.TeleportCommand;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// /tp and /teleport: a player can name a robot a program is connected to as the destination, by code or name, and
// the destination suggestions list those robots. No robot is ever teleported (see MineBotTeleport).
@Mixin(TeleportCommand.class)
public abstract class TeleportCommandMixin {
    @WrapOperation(
        method = "register",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/command/CommandManager;argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;"
        )
    )
    private static RequiredArgumentBuilder<ServerCommandSource, ?> minebot$suggestRobots(
        String name,
        ArgumentType<?> type,
        Operation<RequiredArgumentBuilder<ServerCommandSource, ?>> original
    ) {
        RequiredArgumentBuilder<ServerCommandSource, ?> argument = original.call(name, type);
        return "destination".equals(name) ? argument.suggests(MineBotTeleport::suggestDestinations) : argument;
    }

    // The two commands that teleport to a destination entity: /tp <destination> and /tp <targets> <destination>.
    @WrapOperation(
        method = {"method_13770", "method_13769"},
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/command/argument/EntityArgumentType;getEntity(Lcom/mojang/brigadier/context/CommandContext;Ljava/lang/String;)Lnet/minecraft/entity/Entity;"
        )
    )
    private static Entity minebot$findRobotDestination(
        CommandContext<ServerCommandSource> context,
        String name,
        Operation<Entity> original
    ) throws CommandSyntaxException {
        String playerName = ((EntitySelectorAccessor) context.getArgument(name, EntitySelector.class)).minebot$getPlayerName();
        if (playerName != null && context.getSource().getServer().getPlayerManager().getPlayer(playerName) == null) {
            MineBotEntity robot = MineBotTeleport.findDestination(context.getSource(), playerName);
            if (robot != null) {
                return robot;
            }
        }
        return original.call(context, name);
    }

    @Inject(
        method = "execute(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;Lnet/minecraft/entity/Entity;)I",
        at = @At("HEAD")
    )
    private static void minebot$checkEntityTeleport(
        ServerCommandSource source,
        Collection<? extends Entity> targets,
        Entity destination,
        CallbackInfoReturnable<Integer> cir
    ) throws CommandSyntaxException {
        MineBotTeleport.checkTeleport(targets, destination);
    }

    @Inject(
        method = "execute(Lnet/minecraft/server/command/ServerCommandSource;Ljava/util/Collection;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/command/argument/PosArgument;Lnet/minecraft/command/argument/PosArgument;Lnet/minecraft/server/command/LookTarget;)I",
        at = @At("HEAD")
    )
    private static void minebot$checkLocationTeleport(
        ServerCommandSource source,
        Collection<? extends Entity> targets,
        ServerWorld world,
        PosArgument location,
        PosArgument rotation,
        LookTarget facingLocation,
        CallbackInfoReturnable<Integer> cir
    ) throws CommandSyntaxException {
        MineBotTeleport.checkTeleport(targets, null);
    }
}
