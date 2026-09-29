package com.oori.minebot;

import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Land navigation that also swims the way a player does: straight up a column of water (a
 * waterfall, a flooded shaft) and out of the water onto a bank one block above it.
 *
 * <p>Vanilla land paths treat water as flat: they cross it at the surface and never climb it, and
 * they only step out onto land that is level with the water. Paths still never dive: the robot
 * floats, and only a crouch takes it under.
 */
public final class MineBotNavigation extends MobNavigation {
    public MineBotNavigation(MobEntity entity, World world) {
        super(entity, world);
    }

    @Override
    protected PathNodeNavigator createPathNodeNavigator(int range) {
        this.nodeMaker = new SwimmingNodeMaker();
        return new PathNodeNavigator(this.nodeMaker, range);
    }

    // Reach water nodes one by one. Skipping ahead from inside a waterfall steers the robot out of the
    // falling water toward the next ledge before it has climbed high enough, and it falls back down.
    @Override
    public boolean canJumpToNext(PathNodeType nodeType) {
        return nodeType != PathNodeType.WATER && super.canJumpToNext(nodeType);
    }

    // Floating in water shallower than the robot's float depth, it bobs clear of both the water and
    // the floor for a few ticks at a time. Vanilla refuses to plan or follow a path during those ticks.
    @Override
    protected boolean isAtValidPosition() {
        if (super.isAtValidPosition()) {
            return true;
        }

        BlockPos feet = this.entity.getBlockPos();
        return this.world.getFluidState(feet).isIn(FluidTags.WATER) || this.world.getFluidState(feet.down()).isIn(FluidTags.WATER);
    }

    private static final class SwimmingNodeMaker extends LandPathNodeMaker {
        // Feet can rise this far above the top of the water block when hopping out, as for a land step.
        private static final double MAX_BANK_STEP = 1.125D;

        @Override
        public int getSuccessors(PathNode[] successors, PathNode node) {
            int count = super.getSuccessors(successors, node);
            if (!this.isWater(node.x, node.y, node.z)) {
                return count;
            }

            count = withoutDiagonalClimbs(successors, count, node);
            if (this.isWater(node.x, node.y + 1, node.z)) {
                PathNode above = this.swimUpNode(node.x, node.y + 1, node.z);
                if (this.isValidAdjacentSuccessor(above, node)) {
                    successors[count++] = above;
                }
                return count;
            }

            if (!this.hasRoomToHop(node)) {
                return count;
            }

            for (Direction direction : Direction.Type.HORIZONTAL) {
                PathNode bank = this.bankNode(node, direction);
                if (this.isValidAdjacentSuccessor(bank, node)) {
                    successors[count++] = bank;
                }
            }
            return count;
        }

        // A swimmer climbs out straight ahead. A diagonal step up from the top of a one-block waterfall
        // slides the robot along the ledge and out of the falling water before it is high enough.
        private static int withoutDiagonalClimbs(PathNode[] successors, int count, PathNode node) {
            int kept = 0;
            for (int i = 0; i < count; i++) {
                PathNode successor = successors[i];
                boolean diagonal = successor.x != node.x && successor.z != node.z;
                if (!diagonal || successor.y <= node.y) {
                    successors[kept++] = successor;
                }
            }
            return kept;
        }

        private PathNode swimUpNode(int x, int y, int z) {
            PathNodeType type = this.getNodeType(x, y, z);
            if (type != PathNodeType.WATER) {
                return null;
            }
            return this.nodeOfType(x, y, z, type);
        }

        // A standing spot next to a surface water node whose floor is up to a block above the water.
        // Lower banks are ordinary land steps and come from super.getSuccessors().
        private PathNode bankNode(PathNode water, Direction direction) {
            int x = water.x + direction.getOffsetX();
            int y = water.y + 2;
            int z = water.z + direction.getOffsetZ();
            PathNodeType type = this.getNodeType(x, y, z);
            if (type == PathNodeType.OPEN || type == PathNodeType.WATER || this.entity.getPathfindingPenalty(type) < 0.0F) {
                return null;
            }

            double waterTop = water.y + 1.0D;
            if (this.getFeetY(new BlockPos(x, y, z)) - waterTop > MAX_BANK_STEP) {
                return null;
            }
            return this.nodeOfType(x, y, z, type);
        }

        // Hopping out lifts the robot's feet about a block above the water, so its whole body needs
        // clear space above the surface.
        private boolean hasRoomToHop(PathNode water) {
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int dy = 1; dy <= 3; dy++) {
                pos.set(water.x, water.y + dy, water.z);
                if (!this.context.getBlockState(pos).getCollisionShape(this.context.getWorld(), pos).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private PathNode nodeOfType(int x, int y, int z, PathNodeType type) {
            float penalty = this.entity.getPathfindingPenalty(type);
            if (penalty < 0.0F) {
                return null;
            }

            PathNode node = this.getNode(x, y, z);
            node.type = type;
            node.penalty = Math.max(node.penalty, penalty);
            return node;
        }

        private boolean isWater(int x, int y, int z) {
            return this.context.getBlockState(new BlockPos(x, y, z)).getFluidState().isIn(FluidTags.WATER);
        }
    }
}
