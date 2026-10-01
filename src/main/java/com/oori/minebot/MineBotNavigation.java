package com.oori.minebot;

import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Land navigation that also swims the way a player does: straight up a column of water (a
 * waterfall, a flooded shaft) and out of the water onto a bank one block above it.
 *
 * <p>Vanilla land paths treat water as flat: they cross it at the surface and never climb it, and
 * they only step out onto land that is level with the water. Paths still never dive: the robot
 * floats, and only a crouch takes it under. They also keep out of water the robot cannot breathe in
 * (water that reaches the ceiling) unless there is no other way.
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
        // Each block of water with no air above it costs as much as this many more blocks of path, so
        // paths go round through air and only swim a flooded passage when there is no other way.
        private static final float SUBMERGED_PENALTY = 16.0F;

        @Override
        public int getSuccessors(PathNode[] successors, PathNode node) {
            int count = this.addSwimmingSuccessors(successors, node, super.getSuccessors(successors, node));
            for (int i = 0; i < count; i++) {
                PathNode successor = successors[i];
                if (successor.type == PathNodeType.WATER && !this.canBreatheAt(successor.x, successor.y, successor.z)) {
                    successor.penalty = Math.max(successor.penalty, SUBMERGED_PENALTY);
                }
            }
            return count;
        }

        private int addSwimmingSuccessors(PathNode[] successors, PathNode node, int count) {
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
            double bankFeetY = this.getFeetY(new BlockPos(x, y, z));
            if (bankFeetY - waterTop > MAX_BANK_STEP || !this.hasRoomToHop(water, bankFeetY)) {
                return null;
            }
            return this.nodeOfType(x, y, z, type);
        }

        // Hopping out lifts the robot's feet to the bank's floor while it is still over the water, so its
        // whole body needs clear space above the water up to that height. For a bank a full block above
        // the water that is three clear blocks: the robot cannot climb out under a lower ceiling.
        private boolean hasRoomToHop(PathNode water, double bankFeetY) {
            double halfWidth = this.entity.getWidth() / 2.0D;
            double centerX = water.x + 0.5D;
            double centerZ = water.z + 0.5D;
            Box body = new Box(
                centerX - halfWidth, water.y + 1.0D, centerZ - halfWidth,
                centerX + halfWidth, bankFeetY + this.entity.getHeight(), centerZ + halfWidth
            );
            return this.context.getWorld().isSpaceEmpty(this.entity, body);
        }

        // The robot floats up a water column until its head is out or it meets a ceiling. Where the water
        // reaches the ceiling its head stays under, and it drowns once its air runs out.
        private boolean canBreatheAt(int x, int y, int z) {
            BlockPos.Mutable pos = new BlockPos.Mutable(x, y + 1, z);
            int top = this.context.getWorld().getTopYInclusive();
            while (pos.getY() < top && this.isWater(pos.getX(), pos.getY(), pos.getZ())) {
                pos.move(Direction.UP);
            }
            return this.context.getBlockState(pos).getCollisionShape(this.context.getWorld(), pos).isEmpty();
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
