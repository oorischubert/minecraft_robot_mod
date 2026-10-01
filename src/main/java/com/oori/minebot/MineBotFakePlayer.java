package com.oori.minebot;

import com.google.common.collect.MapMaker;
import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * The stand-in player that does the robot's item uses. It is lent the robot's hotbar for one interaction:
 * the selected stack in its main hand (slot 0) and the other robot slots in its inventory, in order, so
 * bows and crossbows find their ammunition and new items land in free slots as they would for a player.
 */
final class MineBotFakePlayer extends FakePlayer {
    private static final GameProfile PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("minebot:fake_player".getBytes(StandardCharsets.UTF_8)),
        "[MineBot]"
    );
    // Like Fabric's own fake player cache: one per world, dropped when nothing holds it.
    private static final Map<ServerWorld, MineBotFakePlayer> PLAYERS = new MapMaker().weakValues().makeMap();

    private MineBotFakePlayer(ServerWorld world) {
        super(world, PROFILE);
    }

    static MineBotFakePlayer acquire(ServerWorld world, Vec3d eyePos, float yaw, float pitch) {
        MineBotFakePlayer player = PLAYERS.computeIfAbsent(world, MineBotFakePlayer::new);
        player.getAdvancementTracker().clearCriteria();
        if (player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
            player.interactionManager.changeGameMode(GameMode.SURVIVAL);
        }

        Released stale = release(player);
        if (stale.slots().stream().anyMatch(stack -> !stack.isEmpty()) || !stale.others().isEmpty()) {
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

    /** Lends the stacks: {@code lent.get(0)} goes in the main hand, the rest in the next inventory slots. */
    static void hold(MineBotFakePlayer player, List<ItemStack> lent) {
        ItemCooldownManager cooldowns = player.getItemCooldownManager();
        PlayerInventory inventory = player.getInventory();
        inventory.setSelectedSlot(0);
        for (int slot = 0; slot < lent.size(); slot++) {
            ItemStack stack = lent.get(slot);
            if (!stack.isEmpty()) {
                cooldowns.remove(cooldowns.getGroup(stack));
            }
            inventory.setStack(slot, stack);
        }
    }

    /**
     * Takes everything back. {@code slots} holds inventory slots 0..{@code lentCount - 1}, in the order they
     * were lent; {@code others} is whatever the player got anywhere else.
     */
    static Released release(MineBotFakePlayer player) {
        return release(player, 0);
    }

    static Released release(MineBotFakePlayer player, int lentCount) {
        player.clearActiveItem();

        PlayerInventory inventory = player.getInventory();
        List<ItemStack> slots = new ArrayList<>();
        for (int slot = 0; slot < lentCount; slot++) {
            slots.add(inventory.removeStack(slot));
        }
        List<ItemStack> others = new ArrayList<>();
        for (int slot = lentCount; slot < inventory.size(); slot++) {
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
        return new Released(slots, others);
    }

    /**
     * Holds the item in use for one more tick, as a player's tick does: bows draw, crossbows load and
     * consumables finish. Returns false once the item is no longer in use.
     */
    boolean tickItemUse() {
        if (!this.isUsingItem()) {
            return false;
        }
        if (!ItemStack.areItemsEqual(this.getStackInHand(this.getActiveHand()), this.getActiveItem())) {
            this.clearActiveItem();
            return false;
        }
        this.tickItemStackUsage(this.getActiveItem());
        return this.isUsingItem();
    }

    record Released(List<ItemStack> slots, List<ItemStack> others) {
    }
}
