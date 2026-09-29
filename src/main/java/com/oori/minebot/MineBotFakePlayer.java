package com.oori.minebot;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

final class MineBotFakePlayer {
    private static final GameProfile PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("minebot:fake_player".getBytes(StandardCharsets.UTF_8)),
        "[MineBot]"
    );

    private MineBotFakePlayer() {
    }

    static FakePlayer acquire(ServerWorld world, Vec3d eyePos, float yaw, float pitch) {
        FakePlayer player = FakePlayer.get(world, PROFILE);
        player.getAdvancementTracker().clearCriteria();
        if (player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
            player.interactionManager.changeGameMode(GameMode.SURVIVAL);
        }

        Released stale = release(player);
        if (!stale.hand().isEmpty() || !stale.others().isEmpty()) {
            MineBotMod.LOGGER.warn("MineBot fake player still held items from an earlier interaction; discarding them");
        }

        player.setSneaking(false);
        player.setPose(EntityPose.STANDING);
        player.refreshPositionAndAngles(eyePos.x, eyePos.y - player.getStandingEyeHeight(), eyePos.z, yaw, pitch);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setVelocity(Vec3d.ZERO);
        return player;
    }

    static void hold(FakePlayer player, ItemStack held) {
        ItemCooldownManager cooldowns = player.getItemCooldownManager();
        if (!held.isEmpty()) {
            cooldowns.remove(cooldowns.getGroup(held));
        }

        player.setStackInHand(Hand.MAIN_HAND, held);
    }

    static Released release(FakePlayer player) {
        player.clearActiveItem();

        PlayerInventory inventory = player.getInventory();
        ItemStack hand = inventory.removeStack(inventory.getSelectedSlot());
        List<ItemStack> others = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.removeStack(slot);
            if (!stack.isEmpty()) {
                others.add(stack);
            }
        }

        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getEquippedStack(slot);
            if (!stack.isEmpty()) {
                player.equipStack(slot, ItemStack.EMPTY);
                others.add(stack);
            }
        }

        ItemStack cursor = player.currentScreenHandler.getCursorStack();
        if (!cursor.isEmpty()) {
            player.currentScreenHandler.setCursorStack(ItemStack.EMPTY);
            others.add(cursor);
        }

        inventory.markDirty();
        return new Released(hand.isEmpty() ? ItemStack.EMPTY : hand, others);
    }

    record Released(ItemStack hand, List<ItemStack> others) {
    }
}
