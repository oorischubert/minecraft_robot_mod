package com.oori.minebot;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.LandPathNodeMaker;
import net.minecraft.entity.ai.pathing.MobNavigation;
import net.minecraft.entity.ai.pathing.PathContext;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.ai.pathing.PathNodeNavigator;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.vehicle.VehicleEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;

/**
 * Land navigation that also swims the way a player does: straight up a column of water (a
 * waterfall, a flooded shaft) and out of the water onto a bank one block above it.
 *
 * <p>Vanilla land paths treat water as flat: they cross it at the surface and never climb it, and
 * they only step out onto land that is level with the water. Paths still never dive: the robot
 * floats, and only a crouch takes it under. They also keep out of water the robot cannot breathe in
 * (water that reaches the ceiling) unless there is no other way.
 *
 * <p>Vanilla also judges a spot by the kind of block in it, not by its shape: cocoa pods, trapdoors,
 * amethyst, pointed dripstone, big dripleaves and scaffolding count as passable, open doors and trapdoors
 * seen side-on too, and the robot walks into them and stays there. Here a spot, and the way into it, also
 * need room for the robot's whole body. Vanilla paths ignore entities too; here they go round other robots, mobs and players where there is
 * room, and never through shulkers, which are as solid as blocks, or past boats and minecarts close enough
 * to be picked up by them.
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

    // Vanilla counts a node as reached within a block of it in height. Floating up a water column the robot
    // rises past the column's nodes faster than that, so the lowest stays its target and steers it back
    // down until the path times out. A water node of the column it has risen above counts as reached.
    @Override
    protected void continueFollowingPath() {
        while (this.currentPath != null && !this.currentPath.isFinished()) {
            PathNode node = this.currentPath.getCurrentNode();
            if (node.type != PathNodeType.WATER
                || node.x != this.entity.getBlockX()
                || node.z != this.entity.getBlockZ()
                || this.entity.getY() <= node.y) {
                break;
            }
            this.currentPath.next();
        }
        if (this.currentPath != null && !this.currentPath.isFinished()) {
            super.continueFollowingPath();
        }
    }

    /** Whether a path can be planned now: vanilla plans none while the robot is in the air, mid-jump or shoved. */
    public boolean canPlanFromHere() {
        return this.isAtValidPosition();
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
        // A spot where another robot, a mob or a player stands costs as much as this many more blocks of
        // path. Walking into them shoves them off their spot, and in a corner it wedges both, so paths go
        // round them and only push through where there is no other way.
        private static final float OCCUPIED_PENALTY = 8.0F;
        // Entities this far away (vertically half as far) are left out; they will have moved by the time
        // the robot gets there.
        private static final double OBSTACLE_RANGE = 24.0D;
        private static final double CLEARANCE_EPSILON = 1.0E-3D;
        // A boat picks up a mob that comes within 0.2 blocks of it, and a pushed minecart one it runs into.
        private static final double VEHICLE_CLEARANCE = 0.3D;

        private final List<Box> solidEntities = new ArrayList<>();
        private final List<Box> occupants = new ArrayList<>();

        @Override
        public void init(ChunkCache cachedWorld, MobEntity entity) {
            super.init(cachedWorld, entity);
            Box around = entity.getBoundingBox().expand(OBSTACLE_RANGE, OBSTACLE_RANGE / 2.0D, OBSTACLE_RANGE);
            for (Entity other : entity.getEntityWorld().getOtherEntities(entity, around, other -> !other.isConnectedThroughVehicle(entity))) {
                if (other instanceof VehicleEntity) {
                    this.solidEntities.add(other.getBoundingBox().expand(VEHICLE_CLEARANCE, 0.0D, VEHICLE_CLEARANCE));
                } else if (other.isCollidable(entity)) {
                    this.solidEntities.add(other.getBoundingBox());
                } else if (other instanceof LivingEntity living && living.isPushable()) {
                    this.occupants.add(other.getBoundingBox());
                }
            }
        }

        @Override
        public void clear() {
            this.solidEntities.clear();
            this.occupants.clear();
            super.clear();
        }

        // A spot the robot could stand on but its body does not fit into counts as blocked. Paths then go
        // round it or, where the robot can step or jump up, over it.
        @Override
        public PathNodeType getNodeType(PathContext context, int x, int y, int z, MobEntity mob) {
            PathNodeType type = super.getNodeType(context, x, y, z, mob);
            if (type == PathNodeType.OPEN || type == PathNodeType.WATER || mob.getPathfindingPenalty(type) < 0.0F) {
                return type;
            }

            Box body = this.standingBody(context, x, y, z);
            return context.getWorld().isSpaceEmpty(mob, body) && !intersectsAny(this.solidEntities, body) ? type : PathNodeType.BLOCKED;
        }

        // The robot's body standing in block (x, y, z): centred, on the floor below, or on top of anything in
        // the block itself low enough to step onto (a carpet, a snow layer, a candle).
        private Box standingBody(PathContext context, int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            double floor = getFeetY(context.getWorld(), pos);
            VoxelShape inside = context.getBlockState(pos).getCollisionShape(context.getWorld(), pos);
            if (!inside.isEmpty() && inside.getMax(Direction.Axis.Y) <= this.entity.getStepHeight()) {
                floor = Math.max(floor, y + inside.getMax(Direction.Axis.Y));
            }

            double halfWidth = this.entity.getWidth() / 2.0D;
            return new Box(
                x + 0.5D - halfWidth, floor + CLEARANCE_EPSILON, z + 0.5D - halfWidth,
                x + 0.5D + halfWidth, floor + this.entity.getHeight() - CLEARANCE_EPSILON, z + 0.5D + halfWidth
            );
        }

        // Both spots may have room while the way between them does not: an open trapdoor or another panel
        // on the face between two blocks stands across the robot's way. Test the body halfway across, at
        // the height of the higher floor, where it is while it steps or jumps up or before it drops down.
        // Not from the start: the robot stands where it is, which need not be the middle of its block.
        private int withoutBlockedCrossings(PathNode[] successors, int count, PathNode node) {
            if (node.previous == null || this.isWater(node.x, node.y, node.z)) {
                return count;
            }

            int kept = 0;
            for (int i = 0; i < count; i++) {
                PathNode successor = successors[i];
                if (this.isWater(successor.x, successor.y, successor.z) || this.canCross(node, successor)) {
                    successors[kept++] = successor;
                }
            }
            return kept;
        }

        private boolean canCross(PathNode from, PathNode to) {
            Box start = this.standingBody(this.context, from.x, from.y, from.z);
            Box end = this.standingBody(this.context, to.x, to.y, to.z);
            Box halfway = start.offset((end.minX - start.minX) / 2.0D, Math.max(0.0D, end.minY - start.minY), (end.minZ - start.minZ) / 2.0D);
            return this.context.getWorld().isSpaceEmpty(this.entity, halfway) && !intersectsAny(this.solidEntities, halfway);
        }

        private static boolean intersectsAny(List<Box> boxes, Box body) {
            for (Box box : boxes) {
                if (box.intersects(body)) {
                    return true;
                }
            }
            return false;
        }

        // Vanilla starts a swimmer at the top of its water column. Where the water reaches the ceiling the
        // robot does not fit there, and no path would start, so start where it really is.
        @Override
        public PathNode getStart() {
            PathNode start = super.getStart();
            if (this.entity.getPathfindingPenalty(start.type) >= 0.0F || !this.entity.isTouchingWater()) {
                return start;
            }

            BlockPos feet = this.entity.getBlockPos();
            return this.getStart(new BlockPos(start.x, Math.min(start.y, feet.getY()), start.z));
        }

        @Override
        public int getSuccessors(PathNode[] successors, PathNode node) {
            int count = this.addSwimmingSuccessors(successors, node, super.getSuccessors(successors, node));
            count = this.withoutBlockedCrossings(successors, count, node);
            for (int i = 0; i < count; i++) {
                PathNode successor = successors[i];
                if (successor.type == PathNodeType.WATER && !this.canBreatheAt(successor.x, successor.y, successor.z)) {
                    successor.penalty = Math.max(successor.penalty, SUBMERGED_PENALTY);
                }
                if (!this.occupants.isEmpty() && this.isOccupied(successor)) {
                    successor.penalty = Math.max(successor.penalty, OCCUPIED_PENALTY);
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

        private boolean isOccupied(PathNode node) {
            double halfWidth = this.entity.getWidth() / 2.0D;
            double feet = this.getFeetY(new BlockPos(node.x, node.y, node.z));
            Box body = new Box(
                node.x + 0.5D - halfWidth, feet, node.z + 0.5D - halfWidth,
                node.x + 0.5D + halfWidth, feet + this.entity.getHeight(), node.z + 0.5D + halfWidth
            );
            return intersectsAny(this.occupants, body);
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
