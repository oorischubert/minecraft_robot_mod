package com.oori.minebot;

import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

final class MineBotSummoning {
    private MineBotSummoning() {
    }

    static ActionResult onUseBlock(PlayerEntity player, World world, Hand hand, BlockHitResult hitResult) {
        ItemStack stack = player.getStackInHand(hand);

        if (!stack.isOf(MineBotMod.CAMERA_BLOCK_ITEM)) {
            return ActionResult.PASS;
        }

        BlockPos headPos = hitResult.getBlockPos();

        if (!world.getBlockState(headPos).canReplace(new ItemPlacementContext(player, hand, stack, hitResult))) {
            headPos = headPos.offset(hitResult.getSide());
        }

        if (!(world instanceof ServerWorld serverWorld)) {
            return ActionResult.SUCCESS;
        }

        if (trySpawn(serverWorld, headPos, player)) {
            if (!player.isCreative()) {
                stack.decrement(1);
            }

            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    private static boolean trySpawn(ServerWorld world, BlockPos headPos, PlayerEntity player) {
        if (!world.getBlockState(headPos).isAir()) {
            return false;
        }

        BlockPos corePos = headPos.down();
        BlockPos basePos = corePos.down();

        if (!world.getBlockState(corePos).isOf(MineBotMod.COMPUTER_BLOCK) || !world.getBlockState(basePos).isOf(net.minecraft.block.Blocks.IRON_BLOCK)) {
            return false;
        }

        if (matchesArms(world, corePos, Direction.EAST)) {
            return spawn(world, player, headPos, corePos, basePos, corePos.east(), corePos.west());
        }

        if (matchesArms(world, corePos, Direction.SOUTH)) {
            return spawn(world, player, headPos, corePos, basePos, corePos.south(), corePos.north());
        }

        return false;
    }

    private static boolean matchesArms(ServerWorld world, BlockPos corePos, Direction direction) {
        return world.getBlockState(corePos.offset(direction)).isOf(net.minecraft.block.Blocks.IRON_BLOCK)
            && world.getBlockState(corePos.offset(direction.getOpposite())).isOf(net.minecraft.block.Blocks.IRON_BLOCK);
    }

    private static boolean spawn(
        ServerWorld world,
        PlayerEntity player,
        BlockPos headPos,
        BlockPos corePos,
        BlockPos basePos,
        BlockPos armA,
        BlockPos armB
    ) {
        MineBotEntity robot = MineBotMod.MINEBOT_ENTITY.create(world, SpawnReason.EVENT);
        if (robot == null) {
            MineBotMod.LOGGER.error("Failed to create MineBot entity");
            return false;
        }

        robot.setOwner(player);
        robot.refreshPositionAndAngles(corePos.getX() + 0.5D, basePos.getY() + 0.05D, corePos.getZ() + 0.5D, player.getYaw(), 0.0F);

        world.removeBlock(headPos, false);
        world.removeBlock(corePos, false);
        world.removeBlock(basePos, false);
        world.removeBlock(armA, false);
        world.removeBlock(armB, false);

        if (world.spawnEntity(robot)) {
            return true;
        }

        MineBotMod.LOGGER.error("Failed to spawn MineBot entity after consuming summoning structure");
        world.setBlockState(headPos, MineBotMod.CAMERA_BLOCK.getDefaultState());
        world.setBlockState(corePos, MineBotMod.COMPUTER_BLOCK.getDefaultState());
        world.setBlockState(basePos, net.minecraft.block.Blocks.IRON_BLOCK.getDefaultState());
        world.setBlockState(armA, net.minecraft.block.Blocks.IRON_BLOCK.getDefaultState());
        world.setBlockState(armB, net.minecraft.block.Blocks.IRON_BLOCK.getDefaultState());
        return false;
    }
}
