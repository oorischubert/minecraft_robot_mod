package com.oori.minebot;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

public final class MineBotSpawnEggItem extends SpawnEggItem {
    private static final double SPAWN_SEARCH_RADIUS = 3.0D;

    public MineBotSpawnEggItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        BlockPos anchor = context.getBlockPos().offset(context.getSide());
        Set<UUID> existingRobots = captureNearbyRobotIds(context.getWorld(), anchor);
        ActionResult result = super.useOnBlock(context);
        claimSpawnedRobot(context.getWorld(), context.getPlayer(), anchor, existingRobots, result);
        return result;
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        BlockHitResult hit = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
        BlockPos anchor = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : user.getBlockPos();
        Set<UUID> existingRobots = captureNearbyRobotIds(world, anchor);
        ActionResult result = super.use(world, user, hand);
        claimSpawnedRobot(world, user, anchor, existingRobots, result);
        return result;
    }

    private static Set<UUID> captureNearbyRobotIds(World world, BlockPos anchor) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return Set.of();
        }

        Set<UUID> existingRobots = new HashSet<>();
        for (MineBotEntity robot : serverWorld.getEntitiesByClass(MineBotEntity.class, spawnSearchBox(anchor), candidate -> true)) {
            existingRobots.add(robot.getUuid());
        }
        return existingRobots;
    }

    private static void claimSpawnedRobot(
        World world,
        PlayerEntity player,
        BlockPos anchor,
        Set<UUID> existingRobots,
        ActionResult result
    ) {
        if (!result.isAccepted() || player == null || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        Direction facing = player.getHorizontalFacing().getOpposite();
        serverWorld.getEntitiesByClass(MineBotEntity.class, spawnSearchBox(anchor), candidate -> !existingRobots.contains(candidate.getUuid()))
            .stream()
            .min(Comparator.comparingDouble(candidate -> candidate.squaredDistanceTo(anchor.getX() + 0.5D, anchor.getY() + 0.5D, anchor.getZ() + 0.5D)))
            .ifPresent(robot -> {
                robot.orientFromPlacement(facing);
                if (robot.getOwnerUuid() == null) {
                    robot.setOwner(player);
                }
            });
    }

    private static Box spawnSearchBox(BlockPos anchor) {
        return new Box(anchor).expand(SPAWN_SEARCH_RADIUS);
    }
}
