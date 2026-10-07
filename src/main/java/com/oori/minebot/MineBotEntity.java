package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EntityData;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.Leashable;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.control.MoveControl;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNodeType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.item.TridentItem;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.IngredientPlacement;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringHelper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LocalDifficulty;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.VehicleInventory;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.rule.GameRules;
import net.minecraft.village.Merchant;

public final class MineBotEntity extends PathAwareEntity implements ExtendedScreenHandlerFactory<MineBotScreenOpeningData> {
    /** Longest name a player can give a robot; the robot screen also limits it to what fits on its title row. */
    public static final int MAX_NAME_LENGTH = 16;
    private static final String DEFAULT_NAME = "MineBot";
    private static final TrackedData<Boolean> CONNECTED = DataTracker.registerData(MineBotEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> EVIL = DataTracker.registerData(MineBotEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> CROUCHED = DataTracker.registerData(MineBotEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> SELECTED_SLOT = DataTracker.registerData(MineBotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> COMMAND_PITCH = DataTracker.registerData(MineBotEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> SKIN = DataTracker.registerData(MineBotEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final double ITEM_PICKUP_RADIUS = 1.35D;
    private static final int ITEM_PICKUP_GRACE_TICKS = 10;
    private static final double MAX_COUNTED_MOVEMENT_PER_TICK = 4.0D;
    private static final double EVIL_ATTACK_DAMAGE = 13.0D;
    private static final double MOVE_TO_ARRIVAL_TOLERANCE = 0.75D;
    private static final double MOVE_BY_ARRIVAL_TOLERANCE = 0.12D;
    private static final double MOVE_BY_SNAP_MAX_HEIGHT = 1.0D;
    private static final double MOVE_BY_PROGRESS_EPSILON = 0.01D;
    private static final int MOVE_BY_MAX_STALL_TICKS = 8;
    private static final int MOVE_BY_MAX_LANDING_TICKS = 40;
    private static final double MOVE_TO_FINAL_APPROACH_DISTANCE = 2.0D;
    private static final double MOVE_TO_AFLOAT_VERTICAL_TOLERANCE = 1.25D;
    private static final int MOVE_TO_MAX_FINAL_APPROACH_TICKS = 60;
    // move_to counts the robot as stuck when it reached no new spot of its path in MOVE_TO_STALL_TICKS (longer
    // at lower speeds). Moving alone is no headway: a robot jumping at a step, or shoved back and forth by a
    // mob, moves a lot and gets nowhere. It then plans again from where things stand, which goes round a robot
    // or mob that stepped into its way after the last plan, and gives up after MOVE_TO_MAX_STALLS stalls in a row.
    private static final int MOVE_TO_STALL_TICKS = 30;
    private static final int MOVE_TO_MAX_STALLS = 3;
    // Swimming is slower, and climbing out onto a bank can take a few bobs and hops at the edge.
    private static final int MOVE_TO_WATER_STALL_FACTOR = 2;
    // A stalled robot plans again only on the ground; it waits up to this long to land.
    private static final int MOVE_TO_MAX_AIRBORNE_TICKS = 40;
    // On the last stretch, where it walks straight at the target without a path, getting this much closer is headway.
    private static final double MOVE_TO_APPROACH_PROGRESS = 0.25D;
    // How far above the water surface a bank's top may be for the robot to jump out onto it (a jump rises 1.25).
    private static final double WATER_HOP_CLEARANCE = 1.2D;
    // The deepest drop a driven robot will walk off: three blocks, the most a fall takes without damage.
    private static final double MAX_SAFE_DROP = 3.0D;
    private static final double MAX_USE_HOLD_SECONDS = 60.0D;
    private static final double HAZARD_EPSILON = 1.0E-6D;
    // Stepping out of fire: a robot that stands in or beside fire or lava may path past it (vanilla's cost
    // for a spot next to fire) instead of never; it picks a safe cell this far away at most, and gives up
    // on one it has not got closer to for this many ticks.
    private static final float FIRE_ESCAPE_DANGER_PENALTY = 8.0F;
    private static final int FIRE_ESCAPE_RADIUS = 4;
    private static final int FIRE_ESCAPE_STALL_TICKS = 20;
    // Turning back for air: it allows this many ticks per block back to where it last breathed (it swims
    // about two blocks a second, and the way back may wind) and keeps this many ticks in hand.
    private static final int AIR_TICKS_PER_BLOCK = 12;
    private static final int AIR_RESERVE_TICKS = 40;
    private static final int AIR_REPATH_TICKS = 10;
    // Orders that move the robot are refused while it swims back for air.
    private static final Set<String> MOVEMENT_ACTIONS = Set.of("move", "move_by", "move_to", "crouch", "center", "jump", "pillar_up", "bridge", "stop", "enter_vehicle");
    // pillar_up: a jump lifts the feet about 1.25 blocks. A jump that has neither placed its block nor landed
    // after PILLAR_MAX_WAIT_TICKS ends the pillar.
    private static final double PILLAR_MAX_RISE = 1.2D;
    private static final int PILLAR_MAX_WAIT_TICKS = 40;
    private static final int PILLAR_MAX_COUNT = 64;
    // A crouched robot keeps at least this much of its 0.6-wide box on the block it stands on, so like a
    // sneaking player it can lean up to 0.25 past an edge, far enough to see the side of that block.
    private static final double CROUCH_MIN_FOOTING = 0.05D;
    // bridge: how far past the edge it leans to place a block, how close counts as there, and how many
    // ticks without getting closer end the bridge.
    private static final double BRIDGE_LEAN = 0.2D;
    private static final double BRIDGE_ARRIVAL_TOLERANCE = 0.03D;
    private static final int BRIDGE_MAX_STALL_TICKS = 10;
    private static final int BRIDGE_MAX_COUNT = 64;
    // attack_entity until_dead: a swing comes no faster than the target's 10-tick hurt immunity lets a hit
    // land. A target the robot still sees is fought for the whole of max_seconds however far it keeps; one
    // it has not seen for FIGHT_UNSEEN_TICKS is lost. Following keeps within FIGHT_FOLLOW_DISTANCE, repathing
    // this often, and only while the target is within FIGHT_FOLLOW_LEASH of where the fight started. The
    // shield guard raises a hotbar shield between swings.
    private static final int FIGHT_MIN_SWING_TICKS = 10;
    private static final int FIGHT_UNSEEN_TICKS = 60;
    private static final double FIGHT_FOLLOW_DISTANCE = 2.5D;
    private static final double FIGHT_FOLLOW_LEASH = 16.0D;
    private static final int FIGHT_REPATH_TICKS = 10;
    private static final double FIGHT_DEFAULT_SECONDS = 30.0D;
    private static final double FIGHT_MAX_SECONDS = 120.0D;
    private static final float FIGHT_DEFAULT_MIN_HEALTH = 8.0F;
    // A fight may start on a named target this far away that the robot can see.
    private static final double FIGHT_ENGAGE_RANGE = 16.0D;
    // Commands that leave a fight running because they only read, talk or feed the robot; any other
    // command ends it.
    private static final Set<String> FIGHT_KEEPING_ACTIONS = Set.of(
        "status", "read_chat", "print", "inventory", "scan_blocks", "scan_entities", "environment",
        "look_type", "camera_type", "camera_inspect", "slot_type", "slot_inspect", "eat", "refuel"
    );
    // How many of the robot's latest hurts status lists.
    private static final int RECENT_HURTS = 8;
    // Robots are metal: fire, lava, magma and fireball hits do this share of the damage they do to a mob.
    private static final float FIRE_DAMAGE_FACTOR = 0.25F;
    // Health comes back only from eating ingots, one heart each. Copper goes first: iron is worth more.
    private static final float HEALTH_PER_INGOT = 2.0F;
    private static final List<Item> EDIBLE_INGOTS = List.of(Items.COPPER_INGOT, Items.IRON_INGOT);
    // How often a robot writes where it is to the saved robot list.
    private static final int REGISTRY_UPDATE_TICKS = 20;
    // A robot carried by water faster than this (blocks per tick, squared) is drifting, not floating.
    private static final double DRIFT_SPEED_SQUARED = 1.0E-4D;

    private final SimpleInventory robotInventory = new SimpleInventory(MineBotMod.ROBOT_INVENTORY_SIZE);
    private final SimpleInventory fuelInventory = new SimpleInventory(1);
    private final MineBotChatInbox chatInbox = new MineBotChatInbox();
    private String accessCode = randomCode();
    private UUID ownerUuid;
    private String ownerName = "";
    private int energyMilliblocks;
    private Vec3d lastTrackedMovementPos;
    private float forwardInput;
    private float sidewaysInput;
    private Vec3d moveTarget;
    private double moveTargetSpeed = 1.0D;
    private int moveTargetApproachTicks;
    // Path spots this move_to has reached, and how far along the path being followed it has been counted.
    private final it.unimi.dsi.fastutil.longs.LongSet moveReachedNodes = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private Path moveWatchedPath;
    private int moveWatchedIndex;
    private double moveClosestApproach = Double.MAX_VALUE;
    private int moveStallTicks;
    private int moveStalls;
    private Vec3d moveByTarget;
    private double moveBySpeed = 1.0D;
    private double moveByLastDistance = Double.MAX_VALUE;
    private int moveByStallTicks;
    private float moveByYaw;
    private BlockPos breakingPos;
    private int breakingTicksRemaining;
    private int breakingTicksTotal;
    private boolean hasLastMoveResult;
    private boolean lastMoveSucceeded;
    private String lastMoveMessage = "";
    private boolean hasLastAttackResult;
    private boolean lastAttackSucceeded;
    private String lastAttackMessage = "";
    // use_item on a hold-to-use item (bow, crossbow, trident, shield): the hotbar slot and item being
    // held, and the ticks left until the use is released. heldUseSlot is -1 when nothing is held.
    private int heldUseSlot = -1;
    private ItemStack heldUseItem = ItemStack.EMPTY;
    private String heldUseItemId = "";
    private int heldUseTicksTotal;
    private int heldUseTicksRemaining;
    private JsonObject lastUseResult;
    private boolean itemPickupEnabled = true;
    // Why the hazard guard last held the robot back; the driving command is ended on the next tick.
    private String pendingHazardStop;
    private Vec3d lastBreathPos;
    private boolean seekingAir;
    private int airRepathTicks;
    // Stepping out of fire or lava on its own (fireEscapeTarget is null when not): the safe cell it walks
    // to, how close it has got, how long it has not got closer, and cells it gave up on this escape.
    private Vec3d fireEscapeTarget;
    private double fireEscapeBestDistance;
    private int fireEscapeStallTicks;
    private final Set<BlockPos> fireEscapeTried = new HashSet<>();
    // pillar_up in progress: blocks still to place, blocks placed, and the cell the current jump fills.
    private boolean pillaring;
    private int pillarRemaining;
    private int pillarPlaced;
    private int pillarRequested;
    private BlockPos pillarCell;
    private int pillarWaitTicks;
    // bridge in progress: the way it builds (null when not bridging), the yaw it backs up with, blocks placed
    // and asked for, the block it builds out from, and where it is walking: the lean over that block's edge,
    // or the middle of the block it just placed.
    private Direction bridgeDirection;
    private float bridgeYaw;
    private int bridgePlaced;
    private int bridgeRequested;
    private BlockPos bridgeSupport;
    private Vec3d bridgeWalkTarget;
    private boolean bridgeLeaning;
    private double bridgeLastDistance;
    private int bridgeStallTicks;
    // attack_entity until_dead in progress (fightTarget is null when not fighting): how long it has run and
    // may run, the health at which the robot gives up, whether it steps after the target and from where, the
    // ticks to its next swing, since the target was last in reach and since it was last seen, the tally so
    // far, and why its steps after the target were last held back. The shield guard remembers the weapon's
    // slot and the shield's (-1 without one) and whether the shield is up. lastFightResult is how the last
    // fight ended.
    private Entity fightTarget;
    private boolean fightFollow;
    private Vec3d fightStart;
    private float fightMinHealth;
    private int fightTicks;
    private int fightMaxTicks;
    private int fightCooldownTicks;
    private int fightOutOfReachTicks;
    private int fightUnseenTicks;
    private int fightRepathTicks;
    private int fightSwings;
    private int fightHits;
    private float fightDamage;
    private float fightStartHealth;
    private String fightBlockedBy;
    private int fightWeaponSlot;
    private int fightShieldSlot = -1;
    private boolean fightShieldUp;
    private JsonObject lastFightResult;
    // Every time the robot lost health: a count kept with the robot, and the latest hurts, oldest first.
    private int hurtCount;
    private final ArrayDeque<Hurt> recentHurts = new ArrayDeque<>();
    // Ticks the robot still keeps its area loaded since it was last busy.
    private int chunkHoldTicks;
    private int registryUpdateTicks;

    public MineBotEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        // Health never regenerates on its own, so paths never pass next to lava or fire, or over magma and fire.
        this.setPathfindingPenalty(PathNodeType.DANGER_FIRE, -1.0F);
        this.setPathfindingPenalty(PathNodeType.DAMAGE_FIRE, -1.0F);
        // It swims at about 2 blocks/s against 2.75 on foot, so a block of water costs about two of land,
        // not vanilla's nine, which walked it the long way round every lake.
        this.setPathfindingPenalty(PathNodeType.WATER, 1.0F);
        this.setPersistent();
        if (world instanceof ServerWorld) {
            // A saved robot replaces this roll with its skin in readCustomData.
            this.dataTracker.set(SKIN, MineBotSkin.roll(this.random).ordinal());
        }
    }

    public static DefaultAttributeContainer.Builder createAttributes() {
        return MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 20.0D)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.25D)
            .add(EntityAttributes.FOLLOW_RANGE, 64.0D)
            .add(EntityAttributes.ATTACK_DAMAGE, EVIL_ATTACK_DAMAGE)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 0.15D);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new FloatGoal(this));
        this.goalSelector.add(1, new EvilMeleeAttackGoal(this, 1.15D, true));
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CONNECTED, false);
        builder.add(EVIL, false);
        builder.add(CROUCHED, false);
        builder.add(SELECTED_SLOT, 0);
        builder.add(COMMAND_PITCH, 0.0F);
        builder.add(SKIN, MineBotSkin.CLASSIC.ordinal());
    }

    @Override
    public EntityData initialize(ServerWorldAccess world, LocalDifficulty difficulty, SpawnReason spawnReason, EntityData entityData) {
        this.setHealth(this.getMaxHealth());
        this.setPersistent();
        this.setCrouched(false);
        this.dataTracker.set(COMMAND_PITCH, roundAngle(this.getPitch()));
        return super.initialize(world, difficulty, spawnReason, entityData);
    }

    @Override
    public boolean canSpawn(WorldAccess world, SpawnReason spawnReason) {
        return true;
    }

    @Override
    public boolean canSpawn(WorldView world) {
        return true;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canPickUpLoot() {
        // Vanilla mob looting equips drops into the main hand, where syncEquippedStack() would erase them.
        // tickItemPickup() is the only pickup path, so drops always land in the robot hotbar.
        return false;
    }

    @Override
    protected void writeCustomData(WriteView writeView) {
        super.writeCustomData(writeView);
        writeView.putString("access_code", this.accessCode);
        if (this.ownerUuid != null) {
            writeView.putString("owner_uuid", this.ownerUuid.toString());
        }
        if (!this.ownerName.isBlank()) {
            writeView.putString("owner_name", this.ownerName);
        }
        writeView.putInt("energy_milliblocks", this.energyMilliblocks);
        writeView.putBoolean("evil", this.isEvil());
        writeView.putString("skin", this.getSkin().id());
        writeView.putBoolean("crouched", this.isCrouched());
        writeView.putInt("selected_slot", this.getSelectedSlot());
        writeView.putInt("hurt_count", this.hurtCount);
        this.robotInventory.toDataList(writeView.getListAppender("inventory", ItemStack.OPTIONAL_CODEC));
        this.fuelInventory.toDataList(writeView.getListAppender("fuel", ItemStack.OPTIONAL_CODEC));
    }

    @Override
    protected void readCustomData(ReadView readView) {
        super.readCustomData(readView);
        this.accessCode = readView.getString("access_code", randomCode());
        this.ownerUuid = parseUuid(readView.getString("owner_uuid", ""));
        this.ownerName = readView.getString("owner_name", "");
        this.energyMilliblocks = readView.getInt("energy_milliblocks", 0);
        int legacyEnergyTicks = readView.getInt("energy_ticks", 0);
        if (this.energyMilliblocks <= 0 && legacyEnergyTicks > 0) {
            double powderEquivalent = legacyEnergyTicks / (double) MineBotMod.LEGACY_BLAZE_TICKS_PER_POWDER;
            this.energyMilliblocks = (int) Math.round(powderEquivalent * MineBotMod.MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER);
        }
        this.dataTracker.set(EVIL, readView.getBoolean("evil", false));
        // No saved skin (a robot from before skins, or /summon) keeps the roll from the constructor.
        this.dataTracker.set(SKIN, MineBotSkin.byId(readView.getString("skin", this.getSkin().id())).ordinal());
        this.setCrouched(readView.getBoolean("crouched", false));
        this.setSelectedSlot(readView.getInt("selected_slot", 0));
        this.hurtCount = readView.getInt("hurt_count", 0);
        readView.getOptionalTypedListView("inventory", ItemStack.OPTIONAL_CODEC).ifPresent(this.robotInventory::readDataList);
        readView.getOptionalTypedListView("fuel", ItemStack.OPTIONAL_CODEC).ifPresent(this.fuelInventory::readDataList);
        this.dataTracker.set(COMMAND_PITCH, roundAngle(this.getPitch()));
    }

    @Override
    protected void dropLoot(ServerWorld world, DamageSource damageSource, boolean causedByPlayer) {
        this.itemPickupEnabled = false;
        this.dropStack(world, new ItemStack(Items.IRON_INGOT, 3));
        this.dropStack(world, new ItemStack(Items.COPPER_INGOT, 1));

        for (ItemStack stack : this.robotInventory.clearToList()) {
            if (!stack.isEmpty()) {
                this.dropStack(world, stack);
            }
        }

        for (ItemStack stack : this.fuelInventory.clearToList()) {
            if (!stack.isEmpty()) {
                this.dropStack(world, stack);
            }
        }
    }

    @Override
    protected void dropEquipment(ServerWorld world, DamageSource source, boolean causedByPlayer) {
        // MineBot shows a visual copy of its selected hotbar item in the main hand.
        // Actual item drops are handled from robotInventory/fuelInventory in dropLoot().
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.isEvil()) {
            this.preserveCommandedPitch();
        }

        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            this.tickChunkLoading(serverWorld);
            this.tickRegistry(serverWorld);
            this.tickEvilTarget(serverWorld);
            this.syncEquippedStack();
            this.applyPendingHazardStop();
            this.tickFirePathPenalty();
            this.tickAirReflex();
            this.tickFireReflex();
            this.tickMoveByTarget();
            this.tickMoveTarget();
            this.tickPillar();
            this.tickBridge();
            this.tickActiveBreak(serverWorld);
            this.tickHeldUse(serverWorld);
            this.tickFight(serverWorld);
            this.tickFuelFromMovement();
            this.tickItemPickup(serverWorld);
        }
    }

    @Override
    protected EntityNavigation createNavigation(World world) {
        return new MineBotNavigation(this, world);
    }

    @Override
    public void tickMovement() {
        if (this.getEntityWorld() instanceof ServerWorld && this.isCrouched() && this.isTouchingWater() && this.shouldSwimInFluids()) {
            // Sink like a sneaking player, instead of drifting down on water gravity alone.
            this.knockDownwards();
        }

        super.tickMovement();

        if (!(this.getEntityWorld() instanceof ServerWorld)) {
            return;
        }

        if (Math.abs(this.forwardInput) < 0.001F && Math.abs(this.sidewaysInput) < 0.001F) {
            return;
        }

        Vec3d forward = Vec3d.fromPolar(0.0F, this.getYaw()).normalize();
        Vec3d right = new Vec3d(forward.z, 0.0D, -forward.x);
        Vec3d motion = forward.multiply(this.forwardInput).add(right.multiply(this.sidewaysInput));

        if (motion.lengthSquared() > 1.0D) {
            motion = motion.normalize();
        }

        Vec3d velocity = this.getVelocity();
        Vec3d horizontalStep = this.applyCrouchEdgeGuard(motion.multiply(0.18D));
        Vec3d next = horizontalStep.add(0.0D, velocity.y, 0.0D);
        this.setVelocity(next.x, velocity.y, next.z);
        this.velocityDirty = true;
        this.bodyYaw = this.getYaw();
        this.headYaw = this.getYaw();
    }

    @Override
    protected void travelInWater(Vec3d movementInput, double gravity, boolean falling, double y) {
        // A mob's movement input is its walking speed attribute, a quarter of a player's full input,
        // so it barely swims and currents carry it away. Swim with a player's full stroke instead.
        double stroke = 1.0D / Math.max(0.01D, this.getAttributeBaseValue(EntityAttributes.MOVEMENT_SPEED));
        super.travelInWater(new Vec3d(movementInput.x * stroke, movementInput.y, movementInput.z * stroke), gravity, falling, y);
        this.swimOverObstacle();
    }

    // When a swimming player is blocked they hold jump: that lifts them over a step in the water, and at
    // the surface it makes them jump out onto the block they push against. Do the same.
    private void swimOverObstacle() {
        if (!this.horizontalCollision || this.isCrouched() || !this.isTouchingWater()) {
            return;
        }

        boolean rawInput = Math.abs(this.forwardInput) > 0.001F || Math.abs(this.sidewaysInput) > 0.001F;
        Vec3d heading = rawInput ? this.rawInputHeading() : this.moveControlHeading();
        if (heading == null) {
            return;
        }

        World world = this.getEntityWorld();
        Box ahead = this.getBoundingBox().offset(heading.x * 0.3D, 0.0D, heading.z * 0.3D);
        if (world.isSpaceEmpty(this, ahead)) {
            return;
        }

        this.getJumpControl().setActive();

        // Jump out only toward land above the water, so a path that brushes a bank stays in the water.
        double surfaceY = this.getY() + this.getFluidHeight(FluidTags.WATER);
        if (this.isSubmergedInWater() || !rawInput && this.getMoveControl().getTargetY() <= surfaceY) {
            return;
        }

        // Rise only as far as the top of the bank, so a ceiling above the water stops just the hops
        // that would really hit it.
        double bankTop = collisionTop(world, this, ahead);
        if (bankTop - surfaceY > WATER_HOP_CLEARANCE) {
            return;
        }

        double rise = bankTop - this.getY() + 0.01D;
        if (world.isSpaceEmpty(this, this.getBoundingBox().offset(0.0D, rise, 0.0D))
            && world.isSpaceEmpty(this, ahead.offset(0.0D, rise, 0.0D))) {
            this.jump();
        }
    }

    private static double collisionTop(World world, Entity entity, Box box) {
        double top = box.minY;
        for (VoxelShape shape : world.getBlockCollisions(entity, box)) {
            if (!shape.isEmpty()) {
                top = Math.max(top, shape.getMax(Direction.Axis.Y));
            }
        }
        return top;
    }

    // A swimmer running short of air turns back to where they last breathed. Swimming straight up is no
    // way out where the water reaches the ceiling (a flooded passage or pocket), so the robot does the
    // same: it drops its order, stands up and swims back, and refuses movement orders until it can
    // breathe. It allows enough air for the way back plus a reserve.
    private void tickAirReflex() {
        if (!this.isSubmergedInWater()) {
            // Also in mid-air: after a fall into deep water, the way back is the surface it fell through.
            this.lastBreathPos = this.getEntityPos();
            if (this.seekingAir) {
                this.seekingAir = false;
                this.getNavigation().stop();
            }
            return;
        }

        if (this.lastBreathPos == null || this.isEvil() || this.hasVehicle()) {
            return;
        }

        if (!this.seekingAir) {
            double distance = Math.sqrt(this.squaredDistanceTo(this.lastBreathPos));
            if (this.getAir() > MathHelper.ceil(distance * AIR_TICKS_PER_BLOCK) + AIR_RESERVE_TICKS) {
                return;
            }
            this.startSeekingAir();
        }

        if (!this.hasAvailableEnergy()) {
            return;
        }

        if (!this.getNavigation().isIdle()) {
            return;
        }

        Vec3d target = this.lastBreathPos;
        if (this.airRepathTicks > 0) {
            this.airRepathTicks--;
        } else {
            this.airRepathTicks = AIR_REPATH_TICKS;
            Path path = this.getNavigation().findPathTo(target.x, target.y, target.z, 0);
            if (path != null && this.getNavigation().startMovingAlong(path, 1.0D)) {
                return;
            }
        }
        // No path back yet (it may have come in through a gap the pathfinder will not use): swim straight
        // at it. The move control needs a target every tick.
        this.getMoveControl().moveTo(target.x, target.y, target.z, 1.0D);
    }

    private void startSeekingAir() {
        this.cancelPendingBreak();
        if (this.isFighting()) {
            this.finishFight("interrupted", "Ran short of air and turned back to " + formatPosition(this.lastBreathPos) + ", where it last breathed");
        }
        this.stopActiveMovement();
        this.setCrouched(false);
        this.seekingAir = true;
        this.airRepathTicks = 0;
        this.recordLastMoveResult(false, "Ran short of air and turned back to " + formatPosition(this.lastBreathPos) + ", where it last breathed");
    }

    // Paths never pass next to lava or fire, except when the robot already stands in or beside some: then
    // the cells around it cost vanilla's price for a spot next to fire, so it can path away from it.
    private void tickFirePathPenalty() {
        boolean nearBurning = this.getEntityWorld()
            .getStatesInBoxIfLoaded(this.getBoundingBox().expand(1.0D, 0.0D, 1.0D))
            .anyMatch(MineBotEntity::isBurningBlock);
        float penalty = nearBurning ? FIRE_ESCAPE_DANGER_PENALTY : -1.0F;
        if (this.getPathfindingPenalty(PathNodeType.DANGER_FIRE) != penalty) {
            this.setPathfindingPenalty(PathNodeType.DANGER_FIRE, penalty);
        }
    }

    // A robot standing in fire or lava with no order to move steps out of it on its own, as a player would:
    // to the nearest safe cell within a few blocks that it can stand in. The hazard guard still keeps it off
    // drops, and a running fight goes on from wherever it stands. A movement order already driving the robot
    // takes it out itself (the guard lets it leave burning blocks it already stands in).
    private void tickFireReflex() {
        if (!this.isStandingInBurningBlock() || this.isEvil() || this.hasVehicle()) {
            this.clearFireEscape();
            return;
        }
        if (this.isDrivenByMove() || this.pillaring || this.isBridging() || this.seekingAir || !this.hasAvailableEnergy()) {
            this.clearFireEscape();
            return;
        }

        if (this.fireEscapeTarget != null) {
            double distance = this.horizontalDistanceTo(this.fireEscapeTarget);
            if (distance < this.fireEscapeBestDistance - 0.05D) {
                this.fireEscapeBestDistance = distance;
                this.fireEscapeStallTicks = 0;
            } else if (++this.fireEscapeStallTicks > FIRE_ESCAPE_STALL_TICKS) {
                this.fireEscapeTried.add(BlockPos.ofFloored(this.fireEscapeTarget));
                this.fireEscapeTarget = null;
            }
        }
        if (this.fireEscapeTarget == null) {
            this.fireEscapeTarget = this.findFireEscape();
            this.fireEscapeBestDistance = Double.MAX_VALUE;
            this.fireEscapeStallTicks = 0;
            if (this.fireEscapeTarget == null) {
                return;
            }
            this.cancelPendingBreak();
            if (!this.getNavigation().isIdle()) {
                this.getNavigation().stop();
            }
        }
        this.getMoveControl().moveTo(this.fireEscapeTarget.x, this.fireEscapeTarget.y, this.fireEscapeTarget.z, 1.0D);
    }

    private boolean isEscapingFire() {
        return this.fireEscapeTarget != null;
    }

    private void clearFireEscape() {
        if (this.fireEscapeTarget != null) {
            this.fireEscapeTarget = null;
            if (!this.isDrivenByMove() && !this.isFighting() && !this.getNavigation().isIdle()) {
                this.getNavigation().stop();
            }
        }
        this.fireEscapeTried.clear();
    }

    private boolean isStandingInBurningBlock() {
        return this.getEntityWorld()
            .getStatesInBoxIfLoaded(this.getBoundingBox().contract(HAZARD_EPSILON))
            .anyMatch(MineBotEntity::isBurningBlock);
    }

    // The nearest cell within FIRE_ESCAPE_RADIUS, at most one block up or down, where the robot's body fits,
    // stands on something and touches no fire or lava; null when there is none.
    private Vec3d findFireEscape() {
        World world = this.getEntityWorld();
        BlockPos feet = this.getBlockPos();
        Vec3d best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -FIRE_ESCAPE_RADIUS; dx <= FIRE_ESCAPE_RADIUS; dx++) {
            for (int dz = -FIRE_ESCAPE_RADIUS; dz <= FIRE_ESCAPE_RADIUS; dz++) {
                for (int dy : new int[] {0, 1, -1}) {
                    BlockPos cell = feet.add(dx, dy, dz);
                    if (this.fireEscapeTried.contains(cell) || !this.isSafeStandingCell(world, cell)) {
                        continue;
                    }
                    Vec3d centre = new Vec3d(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
                    // Level ground first: a step up or down counts as a block further away.
                    double distance = this.horizontalDistanceTo(centre) + Math.abs(dy);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = centre;
                    }
                }
            }
        }
        return best;
    }

    private boolean isSafeStandingCell(World world, BlockPos cell) {
        Box body = new Box(
            cell.getX() + 0.5D - 0.3D, cell.getY(), cell.getZ() + 0.5D - 0.3D,
            cell.getX() + 0.5D + 0.3D, cell.getY() + 1.95D, cell.getZ() + 0.5D + 0.3D
        );
        if (!world.isSpaceEmpty(this, body)) {
            return false;
        }
        if (world.getStatesInBoxIfLoaded(body.expand(0.0D, 0.1D, 0.0D)).anyMatch(MineBotEntity::isBurningBlock)) {
            return false;
        }
        // Something to stand on: the slice just under the feet is not empty.
        Box footing = new Box(body.minX, cell.getY() - 0.1D, body.minZ, body.maxX, cell.getY(), body.maxZ);
        return !world.isSpaceEmpty(this, footing);
    }

    private void refuseMovementWhileSeekingAir(String action) {
        if (this.seekingAir && MOVEMENT_ACTIONS.contains(action)) {
            throw fail(
                "seeking_air",
                "The robot ran short of air and is swimming back to " + formatPosition(this.lastBreathPos)
                    + ", where it last breathed. It takes movement orders again once its head is above water"
            );
        }
    }

    private static String formatPosition(Vec3d pos) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", pos.x, pos.y, pos.z);
    }

    private Vec3d rawInputHeading() {
        Vec3d forward = Vec3d.fromPolar(0.0F, this.getYaw()).normalize();
        Vec3d right = new Vec3d(forward.z, 0.0D, -forward.x);
        return forward.multiply(this.forwardInput).add(right.multiply(this.sidewaysInput)).normalize();
    }

    // Toward the point move_to's path is steering at, or null when the robot is not being steered.
    private Vec3d moveControlHeading() {
        if (this.forwardSpeed <= 0.001F) {
            return null;
        }

        MoveControl moveControl = this.getMoveControl();
        Vec3d toTarget = new Vec3d(moveControl.getTargetX() - this.getX(), 0.0D, moveControl.getTargetZ() - this.getZ());
        return toTarget.lengthSquared() < 1.0E-6D ? null : toTarget.normalize();
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (this.isEvil()) {
            return ActionResult.PASS;
        }

        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return ActionResult.SUCCESS;
        }

        serverPlayer.openHandledScreen(this);
        return ActionResult.SUCCESS;
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new MineBotScreenHandler(syncId, playerInventory, this);
    }

    /**
     * "MineBot John" for a robot named John, else "MineBot" and its code. Players see this in death
     * messages. The client does not know the code, so there an unnamed robot is "MineBot".
     */
    @Override
    public Text getDisplayName() {
        MutableText name = Text.translatable("entity.minebot.minebot");
        if (this.hasRobotName()) {
            name.append(" ").append(this.getCustomName());
        } else if (!this.getEntityWorld().isClient()) {
            name.append(" " + this.getAccessCode());
        }
        return name;
    }

    /** Whether a player gave the robot a name of its own; "MineBot" in any case counts as no name. */
    public boolean hasRobotName() {
        return this.hasCustomName() && !DEFAULT_NAME.equalsIgnoreCase(this.getCustomName().getString());
    }

    /** Renames the robot from its screen. The name is cleaned with {@link #sanitizeName}; an empty one removes it. */
    public void rename(String requested) {
        String name = sanitizeName(requested);
        this.setCustomName(name.isEmpty() ? null : Text.literal(name));
    }

    /** Drops whitespace, formatting codes and control characters, and keeps at most {@link #MAX_NAME_LENGTH} characters. */
    /**
     * The player a mob remembers as having hurt it when a robot hurts it. Loot that only drops for a
     * player's kill (blaze rods, for one) and experience need one.
     */
    public static PlayerEntity killCreditPlayer(ServerWorld world) {
        return MineBotFakePlayer.forWorld(world);
    }

    public static String sanitizeName(String requested) {
        StringBuilder name = new StringBuilder();
        StringHelper.stripInvalidChars(requested == null ? "" : requested).codePoints()
            .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint))
            .limit(MAX_NAME_LENGTH)
            .forEach(name::appendCodePoint);
        return name.toString();
    }

    @Override
    public MineBotScreenOpeningData getScreenOpeningData(ServerPlayerEntity player) {
        return new MineBotScreenOpeningData(this.getId(), this.getAccessCode(), this.getWebSocketEndpoint(), this.getSkin().id());
    }

    @Override
    public EntityDimensions getBaseDimensions(EntityPose pose) {
        if (pose == EntityPose.CROUCHING) {
            return EntityDimensions.fixed(0.6F, 1.5F);
        }

        return EntityDimensions.fixed(0.6F, 1.95F);
    }

    public JsonObject executeCommand(JsonObject request) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "response");

        if (request.has("request_id")) {
            response.add("request_id", request.get("request_id"));
        }

        if (!request.has("action")) {
            response.addProperty("ok", false);
            response.addProperty("error_code", "invalid_request");
            response.addProperty("error", "Command is missing an action");
            return response;
        }

        String action = request.get("action").getAsString();

        try {
            this.refuseMovementWhileSeekingAir(action);
            if (this.isFighting() && !FIGHT_KEEPING_ACTIONS.contains(action)) {
                this.finishFight("interrupted", "Interrupted by " + action);
            }
            JsonObject result = switch (action) {
                case "move" -> this.handleMove(request);
                case "move_by" -> this.handleMoveBy(request);
                case "move_to" -> this.handleMoveTo(request);
                case "turn", "turn_by" -> this.handleTurn(request);
                case "turn_to" -> this.handleTurnTo(request);
                case "crouch" -> this.handleCrouch();
                case "uncrouch" -> this.handleUncrouch();
                case "center" -> this.handleCenter();
                case "jump" -> this.handleJump();
                case "pillar_up" -> this.handlePillarUp(request);
                case "bridge" -> this.handleBridge(request);
                case "enter_vehicle" -> this.handleEnterVehicle();
                case "exit_vehicle" -> this.handleExitVehicle();
                case "attack", "break", "break_block" -> this.handleAttack();
                case "place" -> this.handlePlace();
                case "craft" -> this.handleCraft(request);
                case "furnace_inspect" -> this.handleFurnaceInspect();
                case "furnace_place" -> this.handleFurnacePlace(request);
                case "furnace_take" -> this.handleFurnaceTake(request);
                case "chest_inspect" -> this.handleChestInspect();
                case "chest_place" -> this.handleChestPlace(request);
                case "chest_take" -> this.handleChestTake(request);
                case "drop" -> this.handleDrop(request);
                case "select_slot" -> this.handleSelectSlot(request);
                case "slot_type", "slot_inspect" -> this.handleSlotInspect(request);
                case "print" -> this.handlePrint(request);
                case "look_type" -> this.handleLookType();
                case "camera_type", "camera_inspect" -> this.handleLookType();
                case "status" -> this.createStatusPayload();
                case "read_chat" -> this.handleReadChat(request);
                case "inventory" -> this.handleInventory();
                case "scan_blocks" -> this.handleScanBlocks(request);
                case "scan_entities" -> this.handleScanEntities(request);
                case "environment" -> this.handleEnvironment();
                case "stop" -> this.handleStop();
                case "look_at" -> this.handleLookAt(request);
                case "attack_entity" -> this.handleAttackEntity(request);
                case "use_item" -> this.handleUseItem(request);
                case "use_on_entity" -> this.handleUseOnEntity();
                case "move_item" -> this.handleMoveItem(request);
                case "refuel" -> this.handleRefuel(request);
                case "eat" -> this.handleEat(request);
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            };

            response.addProperty("ok", true);
            response.add("result", result);
        } catch (MineBotCommandException exception) {
            response.addProperty("ok", false);
            response.addProperty("error_code", exception.getCode());
            response.addProperty("error", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            response.addProperty("ok", false);
            response.addProperty("error_code", "invalid_request");
            response.addProperty("error", exception.getMessage());
        } catch (Exception exception) {
            MineBotMod.LOGGER.error("Unhandled MineBot command failure for action {}", action, exception);
            response.addProperty("ok", false);
            response.addProperty("error_code", "internal_error");
            response.addProperty("error", "MineBot failed while executing that command");
        }

        return response;
    }

    public JsonObject createStatusPayload() {
        JsonObject status = new JsonObject();
        status.addProperty("entity_id", this.getId());
        status.addProperty("entity_uuid", this.getUuidAsString());
        status.addProperty("display_name", this.getDisplayName().getString());
        status.addProperty("code", this.getAccessCode());
        status.addProperty("access_code", this.getAccessCode());
        status.addProperty("endpoint", this.getWebSocketEndpoint());
        status.addProperty("dimension", this.getEntityWorld().getRegistryKey().getValue().toString());
        status.addProperty("connected", this.isConnected());
        status.addProperty("evil", this.isEvil());
        if (this.ownerUuid != null) {
            status.addProperty("owner_uuid", this.ownerUuid.toString());
        }
        status.addProperty("owner_name", this.getOwnerName());
        status.addProperty("owner_online", this.isOwnerOnline());
        status.addProperty("chunk_loader_active", this.shouldKeepChunksLoaded());
        status.addProperty("chunk_x", this.getChunkPos().x);
        status.addProperty("chunk_z", this.getChunkPos().z);
        status.addProperty("selected_slot", this.getSelectedSlot());
        status.addProperty("selected_item", this.getSelectedItemId());
        status.addProperty("crouched", this.isCrouched());
        status.addProperty("in_vehicle", this.hasVehicle());
        status.addProperty("in_water", this.isTouchingWater());
        status.addProperty("air", Math.max(0, this.getAir()));
        status.addProperty("max_air", this.getMaxAir());
        status.addProperty("seeking_air", this.seekingAir);
        if (this.seekingAir) {
            status.addProperty("air_target_x", roundCoordinate(this.lastBreathPos.x));
            status.addProperty("air_target_y", roundCoordinate(this.lastBreathPos.y));
            status.addProperty("air_target_z", roundCoordinate(this.lastBreathPos.z));
        }
        status.addProperty("health", roundCoordinate(this.getHealth()));
        status.addProperty("max_health", roundCoordinate(this.getMaxHealth()));
        status.addProperty("energy_milliblocks", this.energyMilliblocks);
        status.addProperty("energy_blocks", roundCoordinate(this.energyMilliblocks / 1_000.0D));
        status.addProperty("energy_powder_equivalent", this.energyMilliblocks / (double) MineBotMod.MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER);
        status.addProperty("fuel_count", this.fuelInventory.getStack(0).getCount());
        status.addProperty("stored_energy_milliblocks", this.getStoredEnergyMilliblocks());
        status.addProperty("stored_range_blocks", roundCoordinate(this.getStoredEnergyMilliblocks() / 1_000.0D));
        status.addProperty("movement_blocks_per_blaze_powder", MineBotMod.MOVEMENT_BLOCKS_PER_BLAZE_POWDER);
        status.addProperty("x", roundCoordinate(this.getX()));
        status.addProperty("y", roundCoordinate(this.getY()));
        status.addProperty("z", roundCoordinate(this.getZ()));
        status.addProperty("yaw", roundAngle(this.getYaw()));
        status.addProperty("pitch", roundAngle(this.getPitch()));
        status.addProperty("look_block", this.getLookedBlockId());
        status.addProperty("moving_to_target", this.moveTarget != null);
        status.addProperty("moving_by_target", this.moveByTarget != null);
        status.addProperty("pillaring", this.pillaring);
        status.addProperty("pillar_placed", this.pillarPlaced);
        status.addProperty("pillar_requested", this.pillarRequested);
        status.addProperty("bridging", this.isBridging());
        status.addProperty("bridge_placed", this.bridgePlaced);
        status.addProperty("bridge_requested", this.bridgeRequested);
        status.addProperty("direct_move_active", Math.abs(this.forwardInput) > 0.001F || Math.abs(this.sidewaysInput) > 0.001F);
        status.addProperty("direct_move_x", roundCoordinate(this.forwardInput));
        status.addProperty("direct_move_z", roundCoordinate(this.sidewaysInput));
        if (this.moveTarget != null) {
            status.addProperty("move_target_x", roundCoordinate(this.moveTarget.x));
            status.addProperty("move_target_y", roundCoordinate(this.moveTarget.y));
            status.addProperty("move_target_z", roundCoordinate(this.moveTarget.z));
            status.addProperty("move_target_speed", roundCoordinate(this.moveTargetSpeed));
        }
        if (this.moveByTarget != null) {
            status.addProperty("move_by_target_x", roundCoordinate(this.moveByTarget.x));
            status.addProperty("move_by_target_y", roundCoordinate(this.moveByTarget.y));
            status.addProperty("move_by_target_z", roundCoordinate(this.moveByTarget.z));
            status.addProperty("move_by_target_speed", roundCoordinate(this.moveBySpeed));
        }
        status.addProperty("last_move_known", this.hasLastMoveResult);
        status.addProperty("last_move_success", this.hasLastMoveResult && this.lastMoveSucceeded);
        if (!this.lastMoveMessage.isBlank()) {
            status.addProperty("last_move_message", this.lastMoveMessage);
        }
        status.addProperty("breaking_block", this.breakingPos != null);
        if (this.breakingPos != null) {
            status.addProperty("break_target_x", this.breakingPos.getX());
            status.addProperty("break_target_y", this.breakingPos.getY());
            status.addProperty("break_target_z", this.breakingPos.getZ());
            status.addProperty("break_ticks_remaining", this.breakingTicksRemaining);
            status.addProperty(
                "break_progress",
                this.breakingTicksTotal <= 0 ? 0.0D : 1.0D - (this.breakingTicksRemaining / (double) this.breakingTicksTotal)
            );
        }
        status.addProperty("using_item", this.isHoldingUse());
        if (this.isHoldingUse()) {
            status.addProperty("use_item_id", this.heldUseItemId);
            status.addProperty("use_hold_ticks", this.heldUseTicksTotal);
            status.addProperty("use_ticks_remaining", this.heldUseTicksRemaining);
        }
        if (this.lastUseResult != null) {
            status.add("last_use", this.lastUseResult.deepCopy());
        }
        status.addProperty("last_attack_known", this.hasLastAttackResult);
        status.addProperty("last_attack_success", this.hasLastAttackResult && this.lastAttackSucceeded);
        if (!this.lastAttackMessage.isBlank()) {
            status.addProperty("last_attack_message", this.lastAttackMessage);
        }
        status.addProperty("escaping_fire", this.isEscapingFire());
        status.addProperty("fighting", this.isFighting());
        if (this.isFighting()) {
            status.addProperty("fight_target_id", this.fightTarget.getId());
            status.addProperty("fight_swings", this.fightSwings);
            status.addProperty("fight_hits", this.fightHits);
            status.addProperty("fight_shield_up", this.fightShieldUp);
        }
        if (this.lastFightResult != null) {
            status.add("last_fight", this.lastFightResult.deepCopy());
        }
        status.addProperty("hurt_count", this.hurtCount);
        status.add("recent_hurt", this.recentHurtsPayload());
        return status;
    }

    public JsonObject createLocatorPayload() {
        JsonObject robot = new JsonObject();
        robot.addProperty("entity_id", this.getId());
        robot.addProperty("entity_uuid", this.getUuidAsString());
        robot.addProperty("display_name", this.getDisplayName().getString());
        robot.addProperty("code", this.getAccessCode());
        robot.addProperty("access_code", this.getAccessCode());
        robot.addProperty("endpoint", this.getWebSocketEndpoint());
        robot.addProperty("dimension", this.getEntityWorld().getRegistryKey().getValue().toString());
        robot.addProperty("connected", this.isConnected());
        robot.addProperty("evil", this.isEvil());
        if (this.ownerUuid != null) {
            robot.addProperty("owner_uuid", this.ownerUuid.toString());
        }
        robot.addProperty("owner_name", this.getOwnerName());
        robot.addProperty("owner_online", this.isOwnerOnline());
        robot.addProperty("health", roundCoordinate(this.getHealth()));
        robot.addProperty("max_health", roundCoordinate(this.getMaxHealth()));
        robot.addProperty("x", roundCoordinate(this.getX()));
        robot.addProperty("y", roundCoordinate(this.getY()));
        robot.addProperty("z", roundCoordinate(this.getZ()));
        robot.addProperty("loaded", true);
        robot.addProperty("dead", false);
        return robot;
    }

    public PropertyDelegate createPropertyDelegate() {
        return new PropertyDelegate() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> MineBotEntity.this.isConnected() ? 1 : 0;
                    case 1 -> MineBotScreenHandler.toEnergyProperty(MineBotEntity.this.energyMilliblocks);
                    case 2 -> MineBotEntity.this.getSelectedSlot();
                    case 3 -> Math.round(MineBotEntity.this.getHealth() * 10.0F);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int size() {
                return 4;
            }
        };
    }

    public Inventory getFuelInventory() {
        return this.fuelInventory;
    }

    public Inventory getRobotInventory() {
        return this.robotInventory;
    }

    public MineBotChatInbox getChatInbox() {
        return this.chatInbox;
    }

    public String getAccessCode() {
        return this.accessCode;
    }

    public void setOwner(PlayerEntity player) {
        this.ownerUuid = player.getUuid();
        this.ownerName = player.getName().getString();
    }

    public UUID getOwnerUuid() {
        return this.ownerUuid;
    }

    public String getOwnerName() {
        return this.ownerName == null ? "" : this.ownerName;
    }

    public ServerPlayerEntity getOnlineOwner() {
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld) || this.ownerUuid == null) {
            return null;
        }

        return serverWorld.getServer().getPlayerManager().getPlayer(this.ownerUuid);
    }

    public boolean isOwnerOnline() {
        return this.getOnlineOwner() != null;
    }

    public String getWebSocketEndpoint() {
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return "";
        }

        MineBotWebSocketService service = MineBotWebSocketService.get(serverWorld.getServer());
        if (service == null) {
            return "";
        }

        return service.getEndpoint();
    }

    public boolean isConnected() {
        return this.dataTracker.get(CONNECTED);
    }

    public boolean isEvil() {
        return this.dataTracker.get(EVIL);
    }

    public boolean isCrouched() {
        return this.dataTracker.get(CROUCHED);
    }

    public MineBotSkin getSkin() {
        return MineBotSkin.byIndex(this.dataTracker.get(SKIN));
    }

    public void setCrouched(boolean crouched) {
        this.dataTracker.set(CROUCHED, crouched);
        this.setSneaking(crouched);
        this.setPose(crouched ? EntityPose.CROUCHING : EntityPose.STANDING);
        this.calculateDimensions();
    }

    public void setConnected(boolean connected) {
        this.dataTracker.set(CONNECTED, connected);

        if (connected && this.getEntityWorld() instanceof ServerWorld serverWorld) {
            // Load its area at once: a robot found in a chunk that does not tick entities only ticks once it is.
            this.chunkHoldTicks = MineBotChunkLoader.holdTicks();
            MineBotChunkLoader.get(serverWorld.getServer()).update(this, true, true);
        }

        if (!connected) {
            this.setCrouched(false);
            this.stopActiveMovement();
            this.cancelPendingBreak();
            this.cancelPendingUse();
        }
    }

    public void enterEvilMode() {
        if (this.isEvil()) {
            return;
        }

        this.dataTracker.set(EVIL, true);
        this.setConnected(false);
        this.stopActiveMovement();
        this.setTarget(null);
    }

    public void orientFromPlacement(Direction facing) {
        this.applyLook(facing.getPositiveHorizontalDegrees(), 0.0F);
    }

    @Override
    protected float modifyAppliedDamage(DamageSource source, float amount) {
        float modified = super.modifyAppliedDamage(source, amount);
        return source.isIn(DamageTypeTags.IS_FIRE) ? modified * FIRE_DAMAGE_FACTOR : modified;
    }

    @Override
    protected void applyDamage(ServerWorld world, DamageSource source, float amount) {
        float before = this.getHealth();
        super.applyDamage(world, source, amount);
        float lost = before - this.getHealth();
        if (lost > 0.0F) {
            this.recordHurt(world, source, lost);
        }
    }

    // The robot feels every hit: how much it lost and what kind of damage it was. It knows who dealt it only
    // when it can see them, as a player turning round would; a projectile that hit it it always knows.
    private void recordHurt(ServerWorld world, DamageSource source, float lost) {
        Entity attacker = source.getAttacker() == this ? null : source.getAttacker();
        Entity direct = source.getSource();
        boolean attackerSeen = attacker != null && MineBotScanner.canPerceive(this, attacker);
        this.hurtCount++;
        this.recentHurts.addLast(new Hurt(
            this.hurtCount,
            world.getTime(),
            lost,
            this.getHealth(),
            source.getTypeRegistryEntry().getIdAsString(),
            attacker != null,
            attackerSeen ? attacker.getDisplayName().getString() : null,
            attackerSeen ? Registries.ENTITY_TYPE.getId(attacker.getType()).toString() : null,
            attackerSeen ? attacker.getId() : -1,
            direct != null && direct != attacker && direct != this ? Registries.ENTITY_TYPE.getId(direct.getType()).toString() : null
        ));
        while (this.recentHurts.size() > RECENT_HURTS) {
            this.recentHurts.removeFirst();
        }
    }

    private JsonArray recentHurtsPayload() {
        long now = this.getEntityWorld().getTime();
        JsonArray hurts = new JsonArray();
        for (Hurt hurt : this.recentHurts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", hurt.id());
            entry.addProperty("seconds_ago", roundCoordinate(Math.max(0L, now - hurt.tick()) / 20.0D));
            entry.addProperty("amount", roundCoordinate(hurt.amount()));
            entry.addProperty("health", roundCoordinate(hurt.health()));
            entry.addProperty("cause", hurt.cause());
            if (hurt.hasAttacker()) {
                entry.addProperty("attacker_seen", hurt.attacker() != null);
            }
            if (hurt.attacker() != null) {
                entry.addProperty("attacker", hurt.attacker());
                entry.addProperty("attacker_type", hurt.attackerType());
                entry.addProperty("attacker_id", hurt.attackerId());
            }
            if (hurt.projectile() != null) {
                entry.addProperty("projectile", hurt.projectile());
            }
            hurts.add(entry);
        }
        return hurts;
    }

    /** One time the robot lost health. attacker is null when there was none, or the robot could not see it. */
    private record Hurt(
        int id,
        long tick,
        float amount,
        float health,
        String cause,
        boolean hasAttacker,
        String attacker,
        String attackerType,
        int attackerId,
        String projectile
    ) {
    }

    @Override
    public void onDeath(DamageSource damageSource) {
        this.itemPickupEnabled = false;
        if (!this.isRemoved() && !this.dead && this.getEntityWorld() instanceof ServerWorld serverWorld) {
            // Read the message before super.onDeath, which clears the damage tracker.
            Text deathMessage = this.getDamageTracker().getDeathMessage();
            if (serverWorld.getGameRules().getValue(GameRules.SHOW_DEATH_MESSAGES)) {
                serverWorld.getServer().getPlayerManager().broadcast(deathMessage, false);
            } else {
                MineBotMod.LOGGER.info("MineBot {} died: {}", this.getAccessCode(), deathMessage.getString());
            }

            JsonObject death = this.createDeathPayload(damageSource, deathMessage);
            MineBotRegistry.get(serverWorld.getServer()).recordDeath(this, death);
            MineBotWebSocketService service = MineBotWebSocketService.get(serverWorld.getServer());
            if (service != null) {
                service.onRobotDied(this, death);
            }
        }

        super.onDeath(damageSource);
    }

    private JsonObject createDeathPayload(DamageSource damageSource, Text deathMessage) {
        JsonObject death = new JsonObject();
        death.addProperty("code", this.getAccessCode());
        death.addProperty("display_name", this.getDisplayName().getString());
        death.addProperty("message", deathMessage.getString());
        death.addProperty("cause", damageSource.getTypeRegistryEntry().getIdAsString());
        if (damageSource.getAttacker() != null) {
            death.addProperty("killer", damageSource.getAttacker().getDisplayName().getString());
        }
        String dimension = this.getEntityWorld().getRegistryKey().getValue().toString();
        Vec3d pos = new Vec3d(this.getX(), this.getY(), this.getZ());
        death.addProperty("dimension", dimension);
        death.addProperty("x", roundCoordinate(pos.x));
        death.addProperty("y", roundCoordinate(pos.y));
        death.addProperty("z", roundCoordinate(pos.z));
        death.addProperty("timestamp_ms", System.currentTimeMillis());
        // Orders that reached the robot but were never read by its program.
        death.add("unread_chat", this.chatInbox.read(true, MineBotChatInbox.CAPACITY, dimension, pos).get("messages"));
        return death;
    }

    @Override
    public void remove(RemovalReason reason) {
        this.itemPickupEnabled = false;
        super.remove(reason);
    }

    // Also called when the robot's chunk unloads, which bypasses remove().
    @Override
    public void onRemove(RemovalReason reason) {
        super.onRemove(reason);
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        MineBotChunkLoader.get(serverWorld.getServer()).release(this);
        MineBotRegistry registry = MineBotRegistry.get(serverWorld.getServer());
        switch (reason) {
            case UNLOADED_TO_CHUNK, UNLOADED_WITH_PLAYER -> registry.update(this);
            // Gone without dying (a dying robot was recorded dead already).
            case KILLED, DISCARDED -> registry.forgetLiving(this.getUuid());
            // The robot that arrives in the other dimension records itself.
            case CHANGED_DIMENSION -> {
            }
        }
    }

    public int getSelectedSlot() {
        return this.dataTracker.get(SELECTED_SLOT);
    }

    public void setSelectedSlot(int slot) {
        this.dataTracker.set(SELECTED_SLOT, MathHelper.clamp(slot, 0, MineBotMod.ROBOT_INVENTORY_SIZE - 1));
        this.syncEquippedStack();
    }

    private void tickFuelFromMovement() {
        Vec3d currentPos = new Vec3d(this.getX(), this.getY(), this.getZ());
        if (this.lastTrackedMovementPos == null) {
            this.lastTrackedMovementPos = currentPos;
            return;
        }

        boolean afloat = this.isTouchingWater() || this.isInLava();
        double distance = afloat
            ? Math.hypot(currentPos.x - this.lastTrackedMovementPos.x, currentPos.z - this.lastTrackedMovementPos.z)
            : currentPos.distanceTo(this.lastTrackedMovementPos);
        this.lastTrackedMovementPos = currentPos;

        if (distance <= 0.0001D || distance > MAX_COUNTED_MOVEMENT_PER_TICK) {
            return;
        }

        this.consumeMovementEnergy(distance);
    }

    private void consumeMovementEnergy(double blocksMoved) {
        int remainingMilliblocks = Math.max(1, (int) Math.round(blocksMoved * 1_000.0D));

        while (remainingMilliblocks > 0) {
            if (this.energyMilliblocks <= 0) {
                if (this.fuelInventory.getStack(0).isEmpty()) {
                    this.energyMilliblocks = 0;
                    boolean wasMoving = this.moveTarget != null || this.moveByTarget != null || this.pillaring || this.isBridging();
                    if (this.isFighting()) {
                        this.finishFight("out_of_energy", "The MineBot ran out of blaze powder energy");
                    }
                    this.stopActiveMovement();
                    if (wasMoving) {
                        this.recordLastMoveResult(false, "The MineBot ran out of blaze powder energy before reaching the destination");
                    }
                    return;
                }

                this.fuelInventory.getStack(0).decrement(1);
                this.energyMilliblocks += MineBotMod.MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER;
                this.fuelInventory.markDirty();
            }

            int consumed = Math.min(remainingMilliblocks, this.energyMilliblocks);
            this.energyMilliblocks -= consumed;
            remainingMilliblocks -= consumed;
        }
    }

    private JsonObject handleMove(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.clearPathingTarget();
        this.clearMoveByTarget();
        this.clearBridge();
        float forward = clampUnit(readDouble(request, "x"));
        float sideways = clampUnit(request.has("z") ? readDouble(request, "z") : request.has("y") ? readDouble(request, "y") : 0.0D);
        // Releasing the input keeps the result, so the caller can still read why the guard stopped the robot.
        if (Math.abs(forward) > 0.001F || Math.abs(sideways) > 0.001F) {
            this.clearLastMoveResult();
            this.forwardInput = forward;
            this.sidewaysInput = sideways;
        } else {
            // Stop dead: the guards only check steps taken under input, so a slide after release could
            // carry a robot leaning over an edge off it.
            this.clearDirectMotion();
        }

        JsonObject result = new JsonObject();
        result.addProperty("forward", this.forwardInput);
        result.addProperty("sideways", this.sidewaysInput);
        return result;
    }

    private JsonObject handleMoveBy(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.clearDirectMotion();
        this.clearPathingTarget();
        this.clearMoveByTarget();
        this.clearBridge();
        this.clearLastMoveResult();

        double localX = roundCoordinate(readDouble(request, "x"));
        double localZ = roundCoordinate(request.has("z") ? readDouble(request, "z") : request.has("y") ? readDouble(request, "y") : 0.0D);
        double speed = readSpeed(request);
        float snappedYaw = snapToCardinalYaw(this.getYaw());
        double baseX = MathHelper.floor(this.getX()) + 0.5D;
        double baseZ = MathHelper.floor(this.getZ()) + 0.5D;
        Vec3d offset = localOffsetFromYaw(snappedYaw, localX, localZ);
        Vec3d target = this.resolveMoveTarget(roundCoordinate(baseX + offset.x), roundCoordinate(baseZ + offset.z), false);

        if (this.squaredDistanceTo(target.x, target.y, target.z) <= MOVE_BY_ARRIVAL_TOLERANCE * MOVE_BY_ARRIVAL_TOLERANCE) {
            this.recordLastMoveResult(true, "");

            JsonObject result = new JsonObject();
            result.addProperty("arrived", true);
            result.addProperty("x", roundCoordinate(this.getX()));
            result.addProperty("y", roundCoordinate(this.getY()));
            result.addProperty("z", roundCoordinate(this.getZ()));
            return result;
        }

        this.moveByTarget = target;
        this.moveBySpeed = speed;
        this.moveByLastDistance = horizontalDistanceTo(target);
        this.moveByStallTicks = 0;
        this.moveByYaw = snappedYaw;
        this.applyLook(snappedYaw, this.getPitch());

        JsonObject result = new JsonObject();
        result.addProperty("moving_by_target", true);
        result.addProperty("target_x", roundCoordinate(target.x));
        result.addProperty("target_y", roundCoordinate(target.y));
        result.addProperty("target_z", roundCoordinate(target.z));
        result.addProperty("speed", speed);
        return result;
    }

    private JsonObject handleMoveTo(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.clearDirectMotion();
        this.clearMoveByTarget();
        this.clearBridge();
        this.clearLastMoveResult();

        if (!request.has("x") && !request.has("z")) {
            throw fail("invalid_request", "move_to requires at least one of 'x' or 'z'");
        }

        double targetX = roundCoordinate(readOptionalDouble(request, "x", null, this.getX()));
        double targetZ = roundCoordinate(readOptionalDouble(request, "z", null, this.getZ()));
        double speed = readSpeed(request);
        Vec3d target = request.has("y")
            ? this.resolveMoveTarget(targetX, MathHelper.floor(readDouble(request, "y")), targetZ)
            : this.resolveMoveTarget(targetX, targetZ, true);
        if (this.isWithinArrivalRange(target)) {
            // Already within the arrival tolerance; the exact point may be blocked, which is still an arrival.
            this.snapToExactPosition(this.arrivalPosition(target));
            this.clearPathingTarget();
            this.recordLastMoveResult(true, "");

            JsonObject result = new JsonObject();
            result.addProperty("arrived", true);
            result.addProperty("x", roundCoordinate(this.getX()));
            result.addProperty("y", roundCoordinate(this.getY()));
            result.addProperty("z", roundCoordinate(this.getZ()));
            return result;
        }

        this.clearPathingTarget();
        boolean started = this.startPathTo(target, speed);
        if (!started && !this.canFinishMoveDirectly(target)) {
            throw fail("movement_failed", "MineBot could not find a path to that location");
        }

        this.moveTarget = target;
        this.moveTargetSpeed = speed;

        JsonObject result = new JsonObject();
        result.addProperty("moving_to_target", true);
        result.addProperty("target_x", roundCoordinate(target.x));
        result.addProperty("target_y", roundCoordinate(target.y));
        result.addProperty("target_z", roundCoordinate(target.z));
        result.addProperty("speed", speed);
        return result;
    }

    private JsonObject handleTurn(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        float yawDelta = clampDeltaDegrees((float) readOptionalDouble(request, "yaw", null, 0.0D), 180.0F);
        float pitchDelta = clampDeltaDegrees((float) readOptionalDouble(request, "pitch", null, 0.0D), 90.0F);
        float basePitch = this.dataTracker.get(COMMAND_PITCH);
        this.applyLook(MathHelper.wrapDegrees(this.getYaw() + yawDelta), MathHelper.clamp(basePitch + pitchDelta, -90.0F, 90.0F));

        JsonObject result = new JsonObject();
        result.addProperty("yaw", roundAngle(this.getYaw()));
        result.addProperty("pitch", roundAngle(this.getPitch()));
        return result;
    }

    private JsonObject handleTurnTo(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        if (!request.has("yaw") && !request.has("pitch")) {
            throw fail("invalid_request", "turn_to requires at least one of 'yaw' or 'pitch'");
        }

        float yaw = MathHelper.wrapDegrees((float) readOptionalDouble(request, "yaw", null, this.getYaw()));
        float pitch = MathHelper.clamp((float) readOptionalDouble(request, "pitch", null, this.dataTracker.get(COMMAND_PITCH)), -90.0F, 90.0F);
        this.applyLook(yaw, pitch);

        JsonObject result = new JsonObject();
        result.addProperty("yaw", roundAngle(this.getYaw()));
        result.addProperty("pitch", roundAngle(this.getPitch()));
        return result;
    }

    private JsonObject handleCrouch() {
        this.setCrouched(true);

        JsonObject result = new JsonObject();
        result.addProperty("crouched", true);
        return result;
    }

    private JsonObject handleUncrouch() {
        this.setCrouched(false);

        JsonObject result = new JsonObject();
        result.addProperty("crouched", false);
        return result;
    }

    private JsonObject handleCenter() {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.stopActiveMovement();

        Vec3d centered = this.resolveCenteredPosition();
        float snappedYaw = snapToCardinalYaw(this.getYaw());

        this.refreshPositionAndAngles(centered.x, centered.y, centered.z, snappedYaw, 0.0F);
        this.applyLook(snappedYaw, 0.0F);
        this.setVelocity(0.0D, this.getVelocity().y, 0.0D);
        this.velocityDirty = true;

        JsonObject result = new JsonObject();
        result.addProperty("centered", true);
        result.addProperty("x", roundCoordinate(this.getX()));
        result.addProperty("y", roundCoordinate(this.getY()));
        result.addProperty("z", roundCoordinate(this.getZ()));
        result.addProperty("yaw", roundAngle(this.getYaw()));
        result.addProperty("pitch", roundAngle(this.getPitch()));
        return result;
    }

    private JsonObject handleJump() {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.setCrouched(false);

        // The jump only sets the upward velocity; the robot leaves the ground on its next tick, so the
        // ground state from before the jump is what says whether it jumped.
        boolean onGround = this.isOnGround();
        boolean swimming = !onGround && (this.isTouchingWater() || this.isInLava());
        if (onGround) {
            this.jump();
        } else if (swimming) {
            this.getJumpControl().setActive();
        }

        JsonObject result = new JsonObject();
        result.addProperty("jumped", onGround);
        result.addProperty("swimming", swimming);
        result.addProperty("crouched", this.isCrouched());
        result.addProperty("velocity_y", this.getVelocity().y);
        return result;
    }

    // Pillar up as a player does: look down, jump, and at the top of the jump put the selected block into
    // the cell the feet just left, then land on it. The server runs it tick by tick, so the block goes in on
    // the first tick the cell is clear, whatever the connection's latency. Progress is in status
    // (pillaring, pillar_placed) and the outcome in last_move_*.
    private JsonObject handlePillarUp(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.stopActiveMovement();
        this.clearLastMoveResult();
        this.setCrouched(false);

        int count = request.has("count") ? (int) readDouble(request, "count") : 1;
        if (count < 1 || count > PILLAR_MAX_COUNT) {
            throw new IllegalArgumentException("count must be 1.." + PILLAR_MAX_COUNT);
        }
        if (this.hasVehicle()) {
            throw fail("movement_failed", "The robot cannot pillar up while riding a vehicle");
        }
        if (!this.isOnGround()) {
            throw fail(
                "movement_failed",
                this.isTouchingWater()
                    ? "The robot is floating in water; it can only pillar up standing on the ground"
                    : "The robot is not standing on the ground"
            );
        }

        // Stand in the middle of the block, so the robot lands squarely on each block it places.
        Vec3d middle = new Vec3d(MathHelper.floor(this.getX()) + 0.5D, this.getY(), MathHelper.floor(this.getZ()) + 0.5D);
        if (this.canOccupyPosition(middle)) {
            this.refreshPositionAndAngles(middle.x, middle.y, middle.z, this.getYaw(), this.getPitch());
            this.setVelocity(0.0D, this.getVelocity().y, 0.0D);
            this.velocityDirty = true;
        }
        this.applyLook(this.getYaw(), 90.0F);

        BlockPos cell = this.checkPillarJump();
        this.pillaring = true;
        this.pillarRemaining = count;
        this.pillarPlaced = 0;
        this.pillarRequested = count;
        this.pillarCell = null;
        this.pillarWaitTicks = 0;

        JsonObject result = new JsonObject();
        result.addProperty("pillaring", true);
        result.addProperty("count", count);
        result.addProperty("item", this.getSelectedItemId());
        result.addProperty("pos", cell.toShortString());
        result.addProperty("x", roundCoordinate(this.getX()));
        result.addProperty("y", roundCoordinate(this.getY()));
        result.addProperty("z", roundCoordinate(this.getZ()));
        return result;
    }

    private void tickPillar() {
        if (!this.pillaring) {
            return;
        }

        if (this.pillarCell == null) {
            // Waiting on the ground: the pillar is done, or the next jump starts.
            if (!this.isOnGround()) {
                if (++this.pillarWaitTicks > PILLAR_MAX_WAIT_TICKS) {
                    this.finishPillar(false, "The robot did not come down onto solid ground after placing "
                        + this.pillarPlaced + " of " + this.pillarRequested + " blocks");
                }
                return;
            }
            if (this.pillarRemaining <= 0) {
                this.finishPillar(true, "");
                return;
            }
            if (!this.hasAvailableEnergy()) {
                this.finishPillar(false, "The MineBot ran out of blaze powder energy after placing "
                    + this.pillarPlaced + " of " + this.pillarRequested + " blocks");
                return;
            }
            try {
                this.pillarCell = this.checkPillarJump();
            } catch (MineBotCommandException exception) {
                this.finishPillar(false, exception.getMessage() + " (placed " + this.pillarPlaced + " of "
                    + this.pillarRequested + " blocks)");
                return;
            }
            this.pillarWaitTicks = 0;
            this.jump();
            return;
        }

        // In the air: the block goes in on the first tick the robot's body is clear of the cell.
        this.pillarWaitTicks++;
        ItemStack stack = this.robotInventory.getStack(this.getSelectedSlot());
        if (stack.getItem() instanceof BlockItem blockItem
            && blockItem.place(this.pillarPlacementContext(stack, this.pillarCell)).isAccepted()) {
            this.robotInventory.markDirty();
            this.syncEquippedStack();
            this.swingHand(Hand.MAIN_HAND, true);
            this.pillarPlaced++;
            this.pillarRemaining--;
            this.pillarCell = null;
            this.pillarWaitTicks = 0;
            return;
        }
        if (this.pillarWaitTicks > 2 && this.isOnGround() || this.pillarWaitTicks > PILLAR_MAX_WAIT_TICKS) {
            this.finishPillar(false, "The jump did not get clear of " + this.pillarCell.toShortString()
                + " to place a block there (placed " + this.pillarPlaced + " of " + this.pillarRequested + " blocks)");
        }
    }

    // The cell the next jump fills, after checking that the robot holds a block it can stand on, that a
    // jump clears the cell, and that there is room above its head to stand on the new block.
    private BlockPos checkPillarJump() {
        ItemStack stack = this.robotInventory.getStack(this.getSelectedSlot());
        if (stack.isEmpty()) {
            throw fail("missing_item", "The selected hotbar slot has no blocks; select a block to build with");
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            throw fail("invalid_item", itemIdOf(stack.copy()) + " in the selected slot is not a block");
        }

        World world = this.getEntityWorld();
        BlockPos cell = new BlockPos(MathHelper.floor(this.getX()), MathHelper.ceil(this.getY() - 1.0E-4D), MathHelper.floor(this.getZ()));
        MineBotPlacementContext context = this.pillarPlacementContext(stack, cell);
        if (!context.getBlockPos().equals(cell) || !context.canPlace()) {
            throw fail("movement_failed", "Cannot build in " + cell.toShortString() + " under the robot: there is "
                + idOf(world.getBlockState(cell)) + " there and " + idOf(world.getBlockState(cell.down())) + " below it");
        }
        BlockState state = blockItem.getBlock().getPlacementState(context);
        if (state == null || !state.canPlaceAt(world, cell)) {
            throw fail("movement_failed", itemIdOf(stack.copy()) + " cannot be placed at " + cell.toShortString());
        }
        VoxelShape shape = state.getCollisionShape(world, cell);
        if (shape.isEmpty()) {
            throw fail("invalid_item", itemIdOf(stack.copy()) + " has nothing to stand on; build with a solid block");
        }

        double standY = cell.getY() + shape.getMax(Direction.Axis.Y);
        if (standY - this.getY() > PILLAR_MAX_RISE) {
            throw fail("movement_failed", "A jump from y=" + roundCoordinate(this.getY()) + " does not rise clear of "
                + cell.toShortString() + "; pillar up from the top of a full block");
        }
        if (!world.isSpaceEmpty(this, this.getBoundingBox().offset(0.0D, standY - this.getY(), 0.0D))) {
            String blocker = "the space above the robot's head";
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos above = cell.up(dy);
                if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()) {
                    blocker = idOf(world.getBlockState(above)) + " at " + above.toShortString();
                    break;
                }
            }
            throw fail("movement_failed", "No headroom to stand on a block at " + cell.toShortString() + ": blocked by " + blocker);
        }
        return cell;
    }

    // Placing against the top of the block below the cell, looking straight down, as a pillaring player does.
    private MineBotPlacementContext pillarPlacementContext(ItemStack stack, BlockPos cell) {
        BlockHitResult hit = new BlockHitResult(Vec3d.ofBottomCenter(cell), Direction.UP, cell.down(), false);
        return new MineBotPlacementContext(this.getEntityWorld(), stack, hit, this.getHorizontalFacing(), roundAngle(this.getYaw()), 90.0F);
    }

    private void clearPillar() {
        this.pillaring = false;
        this.pillarRemaining = 0;
        this.pillarCell = null;
        this.pillarWaitTicks = 0;
    }

    private void finishPillar(boolean success, String message) {
        this.clearPillar();
        this.recordLastMoveResult(success, message);
    }

    // Bridge out over open air as a player does: crouch, face back the way it came, back up until it leans
    // over the edge of the block it stands on, look down at that block's outer face and place the selected
    // block against it, then back onto the new block, count times. The crosshair has to really hit that
    // face. The server runs it tick by tick; progress is in status (bridging, bridge_placed) and the outcome
    // in last_move_*. It ends crouched in the middle of the last block, facing the way it built.
    private JsonObject handleBridge(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.stopActiveMovement();
        this.clearLastMoveResult();

        Direction direction = readHorizontalDirection(request, "direction");
        int count = request.has("count") ? (int) readDouble(request, "count") : 1;
        if (count < 1 || count > BRIDGE_MAX_COUNT) {
            throw new IllegalArgumentException("count must be 1.." + BRIDGE_MAX_COUNT);
        }
        if (this.hasVehicle()) {
            throw fail("movement_failed", "The robot cannot bridge while riding a vehicle");
        }
        // The block it stands on, the one nearest its middle, as the game picks it. Worked out afresh, since
        // the game's own choice is only updated as the robot moves and may predate a block just placed.
        Box box = this.getBoundingBox();
        Box underfoot = new Box(box.minX, box.minY - 1.0E-6D, box.minZ, box.maxX, box.minY, box.maxZ);
        BlockPos support = this.isOnGround() ? this.getEntityWorld().findSupportingBlockPos(this, underfoot).orElse(null) : null;
        if (support == null) {
            throw fail(
                "movement_failed",
                this.isTouchingWater()
                    ? "The robot is floating in water; it can only bridge standing on a block"
                    : "The robot is not standing on a block"
            );
        }

        BlockPos cell = this.checkBridgeStep(support, direction);
        this.setCrouched(true);
        this.bridgeDirection = direction;
        this.bridgeYaw = MathHelper.wrapDegrees(direction.getOpposite().getPositiveHorizontalDegrees());
        this.bridgePlaced = 0;
        this.bridgeRequested = count;
        this.applyLook(this.bridgeYaw, 80.0F);
        this.walkBridgeTo(support, true);

        JsonObject result = new JsonObject();
        result.addProperty("bridging", true);
        result.addProperty("direction", direction.asString());
        result.addProperty("count", count);
        result.addProperty("item", this.getSelectedItemId());
        result.addProperty("pos", cell.toShortString());
        result.addProperty("x", roundCoordinate(this.getX()));
        result.addProperty("y", roundCoordinate(this.getY()));
        result.addProperty("z", roundCoordinate(this.getZ()));
        return result;
    }

    private void tickBridge() {
        if (!this.isBridging()) {
            return;
        }

        if (!this.isOnGround()) {
            this.forwardInput = 0.0F;
            this.sidewaysInput = 0.0F;
            if (++this.bridgeStallTicks > BRIDGE_MAX_STALL_TICKS) {
                this.finishBridge(false, "The robot lost its footing at " + formatPosition(this.getEntityPos()) + this.bridgeProgress());
            }
            return;
        }

        this.applyLook(this.bridgeYaw, this.getPitch());
        double distance = this.horizontalDistanceTo(this.bridgeWalkTarget);
        if (distance > BRIDGE_ARRIVAL_TOLERANCE) {
            if (distance < this.bridgeLastDistance - 0.002D) {
                this.bridgeLastDistance = distance;
                this.bridgeStallTicks = 0;
            } else if (++this.bridgeStallTicks >= BRIDGE_MAX_STALL_TICKS) {
                this.finishBridge(false, this.bridgeLeaning
                    ? "Could not lean out over the " + this.bridgeDirection.asString() + " edge of " + this.bridgeSupport.toShortString() + this.bridgeProgress()
                    : "Could not step onto the new block at " + this.bridgeSupport.toShortString() + this.bridgeProgress());
                return;
            }

            // Backing up, it closes about a third of the remaining distance a tick and does not overshoot.
            Vec3d delta = this.bridgeWalkTarget.subtract(this.getX(), this.bridgeWalkTarget.y, this.getZ());
            Vec3d forward = Vec3d.fromPolar(0.0F, this.bridgeYaw).normalize();
            Vec3d right = new Vec3d(forward.z, 0.0D, -forward.x);
            this.forwardInput = clampUnit(delta.dotProduct(forward) * 1.5D);
            this.sidewaysInput = clampUnit(delta.dotProduct(right) * 1.5D);
            return;
        }

        this.clearDirectMotion();
        if (this.bridgeLeaning) {
            this.placeBridgeBlock();
            return;
        }

        // Standing in the middle of the block it just placed.
        if (this.bridgePlaced >= this.bridgeRequested) {
            this.applyLook(this.bridgeDirection.getPositiveHorizontalDegrees(), 0.0F);
            this.finishBridge(true, "");
            return;
        }
        if (!this.hasAvailableEnergy()) {
            this.finishBridge(false, "The MineBot ran out of blaze powder energy" + this.bridgeProgress());
            return;
        }
        try {
            this.checkBridgeStep(this.bridgeSupport, this.bridgeDirection);
        } catch (MineBotCommandException exception) {
            this.finishBridge(false, exception.getMessage() + this.bridgeProgress());
            return;
        }
        this.walkBridgeTo(this.bridgeSupport, true);
    }

    // Leaning over the edge: look at the outer face of the block underfoot and, if the crosshair really hits
    // it, place the selected block against it.
    private void placeBridgeBlock() {
        BlockPos support = this.bridgeSupport;
        Direction direction = this.bridgeDirection;
        BlockPos cell;
        try {
            cell = this.checkBridgeStep(support, direction);
        } catch (MineBotCommandException exception) {
            this.finishBridge(false, exception.getMessage() + this.bridgeProgress());
            return;
        }

        this.lookAtPoint(this.bridgeFaceHit(support, direction).getPos());
        BlockHitResult hit = this.raycastBlock();
        World world = this.getEntityWorld();
        if (hit == null || !hit.getBlockPos().equals(support) || hit.getSide() != direction) {
            String seen = hit == null
                ? "nothing"
                : "the " + hit.getSide().asString() + " face of " + idOf(world.getBlockState(hit.getBlockPos())) + " at " + hit.getBlockPos().toShortString();
            this.finishBridge(false, "Leaning over the " + direction.asString() + " edge of " + support.toShortString()
                + ", the crosshair hit " + seen + " instead of that block's " + direction.asString() + " face" + this.bridgeProgress());
            return;
        }

        ItemStack stack = this.robotInventory.getStack(this.getSelectedSlot());
        MineBotPlacementContext context = new MineBotPlacementContext(
            world, stack, hit, this.getHorizontalFacing(), roundAngle(this.getYaw()), roundAngle(this.getPitch())
        );
        if (!(stack.getItem() instanceof BlockItem blockItem) || !blockItem.place(context).isAccepted()) {
            this.finishBridge(false, itemIdOf(stack.copy()) + " could not be placed at " + cell.toShortString() + this.bridgeProgress());
            return;
        }
        this.robotInventory.markDirty();
        this.syncEquippedStack();
        this.swingHand(Hand.MAIN_HAND, true);
        this.bridgePlaced++;
        this.walkBridgeTo(cell, false);
    }

    // The cell the next block fills, after checking that the robot holds a block it can stand on level with
    // its feet, that the cell is open, and that the robot fits leaning out over it and standing on it.
    private BlockPos checkBridgeStep(BlockPos support, Direction direction) {
        ItemStack stack = this.robotInventory.getStack(this.getSelectedSlot());
        if (stack.isEmpty()) {
            throw fail("missing_item", "The selected hotbar slot has no blocks; select a block to build with");
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            throw fail("invalid_item", itemIdOf(stack.copy()) + " in the selected slot is not a block");
        }

        World world = this.getEntityWorld();
        BlockPos cell = support.offset(direction);
        MineBotPlacementContext context = new MineBotPlacementContext(
            world, stack, this.bridgeFaceHit(support, direction), direction.getOpposite(), direction.getOpposite().getPositiveHorizontalDegrees(), 80.0F
        );
        if (!context.getBlockPos().equals(cell) || !context.canPlace()) {
            throw fail("movement_failed", "Cannot build at " + cell.toShortString() + ", " + direction.asString()
                + " of the block the robot stands on: there is " + idOf(world.getBlockState(cell)) + " there");
        }
        BlockState state = blockItem.getBlock().getPlacementState(context);
        if (state == null || !state.canPlaceAt(world, cell)) {
            throw fail("movement_failed", itemIdOf(stack.copy()) + " cannot be placed at " + cell.toShortString());
        }
        VoxelShape shape = state.getCollisionShape(world, cell);
        if (shape.isEmpty()) {
            throw fail("invalid_item", itemIdOf(stack.copy()) + " has nothing to stand on; build with a solid block");
        }
        if (Math.abs(cell.getY() + shape.getMax(Direction.Axis.Y) - this.getY()) > 0.01D) {
            throw fail("movement_failed", itemIdOf(stack.copy()) + " at " + cell.toShortString()
                + " would not be level with the robot's feet; bridge with full blocks from the top of a full block");
        }

        Vec3d middle = new Vec3d(cell.getX() + 0.5D, this.getY(), cell.getZ() + 0.5D);
        Vec3d lean = middle.subtract(direction.getOffsetX() * (0.5D - BRIDGE_LEAN), 0.0D, direction.getOffsetZ() * (0.5D - BRIDGE_LEAN));
        EntityDimensions crouched = this.getDimensions(EntityPose.CROUCHING);
        if (!world.isSpaceEmpty(this, crouched.getBoxAt(lean)) || !world.isSpaceEmpty(this, crouched.getBoxAt(middle))) {
            String blocker = "something in the way";
            for (int dy = 1; dy <= 2; dy++) {
                BlockPos above = cell.up(dy);
                if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty()) {
                    blocker = idOf(world.getBlockState(above)) + " at " + above.toShortString();
                    break;
                }
            }
            throw fail("movement_failed", "No room for the robot over " + cell.toShortString() + ": blocked by " + blocker);
        }
        return cell;
    }

    // The point the robot aims at to place against the outer face of support: on the face, in the upper part
    // of the block's outline, so a slab or stair placed there takes its upper half, level with the feet.
    private BlockHitResult bridgeFaceHit(BlockPos support, Direction direction) {
        World world = this.getEntityWorld();
        VoxelShape outline = world.getBlockState(support).getOutlineShape(world, support);
        double bottom = outline.isEmpty() ? 0.0D : outline.getMin(Direction.Axis.Y);
        double top = outline.isEmpty() ? 1.0D : outline.getMax(Direction.Axis.Y);
        Vec3d point = new Vec3d(
            support.getX() + 0.5D + direction.getOffsetX() * 0.5D,
            support.getY() + bottom + (top - bottom) * 0.7D,
            support.getZ() + 0.5D + direction.getOffsetZ() * 0.5D
        );
        return new BlockHitResult(point, direction, support, false);
    }

    // Walk next to the middle of block, or, leaning, to BRIDGE_LEAN past its edge on the bridge side.
    private void walkBridgeTo(BlockPos block, boolean leaning) {
        double out = leaning ? 0.5D + BRIDGE_LEAN : 0.0D;
        this.bridgeSupport = block.toImmutable();
        this.bridgeLeaning = leaning;
        this.bridgeWalkTarget = new Vec3d(
            block.getX() + 0.5D + this.bridgeDirection.getOffsetX() * out,
            this.getY(),
            block.getZ() + 0.5D + this.bridgeDirection.getOffsetZ() * out
        );
        this.bridgeLastDistance = Double.MAX_VALUE;
        this.bridgeStallTicks = 0;
    }

    private String bridgeProgress() {
        return " (placed " + this.bridgePlaced + " of " + this.bridgeRequested + " blocks)";
    }

    private boolean isBridging() {
        return this.bridgeDirection != null;
    }

    private void clearBridge() {
        this.bridgeDirection = null;
        this.bridgeSupport = null;
        this.bridgeWalkTarget = null;
        this.bridgeStallTicks = 0;
    }

    private void finishBridge(boolean success, String message) {
        this.clearBridge();
        this.clearDirectMotion();
        this.recordLastMoveResult(success, message);
    }

    private static Direction readHorizontalDirection(JsonObject request, String key) {
        String value = readString(request, key).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw fail("invalid_request", "'" + key + "' must be north, south, east or west, not '" + value + "'");
        };
    }

    private JsonObject handleAttack() {
        this.requireEnergy();
        this.cancelPendingUse();
        if (this.breakingPos != null) {
            throw new IllegalArgumentException("MineBot is already attacking a block");
        }

        this.stopActiveMovement();
        BlockHitResult hit = this.raycastBlock();
        if (hit == null) {
            throw new IllegalArgumentException("No block is in front of the robot");
        }

        BlockPos targetPos = hit.getBlockPos();
        BlockState state = this.getEntityWorld().getBlockState(targetPos);

        if (state.isAir()) {
            throw new IllegalArgumentException("The target block is air");
        }

        if (state.getHardness(this.getEntityWorld(), targetPos) < 0.0F) {
            throw new IllegalArgumentException("The target block cannot be broken");
        }

        String startFailure = this.getAttackFailureReason(state);
        if (startFailure != null) {
            throw new IllegalArgumentException(startFailure);
        }

        this.clearLastAttackResult();
        this.syncEquippedStack();
        this.setCurrentHand(Hand.MAIN_HAND);
        this.swingHand(Hand.MAIN_HAND, true);
        this.breakingPos = targetPos.toImmutable();
        this.breakingTicksTotal = this.getBreakDurationTicks(state, targetPos);
        this.breakingTicksRemaining = this.breakingTicksTotal;

        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            serverWorld.setBlockBreakingInfo(this.getId(), this.breakingPos, 0);
        }

        JsonObject result = new JsonObject();
        result.addProperty("block", idOf(state));
        result.addProperty("pos", targetPos.toShortString());
        result.addProperty("attacking", true);
        result.addProperty("eta_ticks", this.breakingTicksTotal);
        return result;
    }

    private JsonObject handlePlace() {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
        boolean selectedIsBlockItem = selected.getItem() instanceof BlockItem;
        String selectedItemId = itemIdOf(selected.copy());
        BlockHitResult hit = this.resolveUseHit(this.raycastBlock(), selected);
        this.syncEquippedStack();
        this.setCurrentHand(Hand.MAIN_HAND);
        this.swingHand(Hand.MAIN_HAND, true);
        if (hit == null) {
            throw new IllegalArgumentException("No block is in front of the robot");
        }

        JsonObject blockFirstInteraction = this.tryBlockFirstInteraction(selected, selectedItemId, hit);
        if (blockFirstInteraction != null) {
            return blockFirstInteraction;
        }

        if (selected.isEmpty()) {
            JsonObject result = new JsonObject();
            result.addProperty("used", "minecraft:air");
            result.addProperty("pos", hit.getBlockPos().toShortString());
            result.addProperty("no_action", true);
            return result;
        }

        Map<BlockPos, BlockState> beforeStates = this.captureInteractionStates(hit);
        float yaw = roundAngle(this.getYaw());
        float pitch = roundAngle(this.getPitch());
        Direction horizontalFacing = this.getHorizontalFacing();
        ActionResult used;
        if (selectedIsBlockItem) {
            MineBotPlacementContext placementContext = new MineBotPlacementContext(
                this.getEntityWorld(),
                selected,
                hit,
                horizontalFacing,
                yaw,
                pitch
            );
            used = ((BlockItem) selected.getItem()).place(placementContext);
        } else {
            MineBotItemUsageContext useContext = new MineBotItemUsageContext(
                this.getEntityWorld(),
                selected,
                hit,
                horizontalFacing,
                yaw,
                pitch
            );
            used = selected.useOnBlock(useContext);
        }
        this.robotInventory.markDirty();
        this.syncEquippedStack();

        if (!used.isAccepted() && selectedIsBlockItem) {
            used = this.tryUnderfootBlockPlacement((BlockItem) selected.getItem(), selected, hit);
            this.robotInventory.markDirty();
            this.syncEquippedStack();
        }

        if (used.isAccepted()) {
            BlockPos affectedPos = this.findAffectedInteractionPos(beforeStates, hit);
            BlockPos resultPos = affectedPos != null ? affectedPos : hit.getBlockPos();
            JsonObject result = new JsonObject();

            if (selectedIsBlockItem) {
                result.addProperty("placed", idOf(this.getEntityWorld().getBlockState(resultPos)));
            } else {
                result.addProperty("used", selectedItemId);
            }

            result.addProperty("pos", resultPos.toShortString());
            return result;
        }

        JsonObject interaction = this.tryBasicInteraction(hit);
        if (interaction != null) {
            return interaction;
        }

        if (selectedIsBlockItem) {
            throw new IllegalArgumentException("The target placement position is not valid for that block");
        }

        throw new IllegalArgumentException("The selected item could not be used on that block");
    }

    private JsonObject handleEnterVehicle() {
        this.requireEnergy();
        this.cancelPendingBreak();
        JsonObject result = this.tryVehicleInteraction(itemIdOf(this.robotInventory.getStack(this.getSelectedSlot()).copy()));
        if (result != null) {
            return result;
        }
        throw fail("wrong_block", "No rideable vehicle is in front of the robot");
    }

    private JsonObject handleExitVehicle() {
        this.requireEnergy();
        this.cancelPendingBreak();
        if (!this.hasVehicle()) {
            throw fail("wrong_block", "The robot is not currently in a vehicle");
        }
        return this.exitVehicle(itemIdOf(this.robotInventory.getStack(this.getSelectedSlot()).copy()));
    }

    private JsonObject exitVehicle(String selectedItemId) {
        Entity vehicle = this.getVehicle();
        this.stopRiding();

        JsonObject result = new JsonObject();
        result.addProperty("used", selectedItemId);
        result.addProperty("exited_vehicle", true);
        result.addProperty("in_vehicle", false);
        if (vehicle != null) {
            result.addProperty("vehicle", Registries.ENTITY_TYPE.getId(vehicle.getType()).toString());
            result.addProperty("vehicle_uuid", vehicle.getUuidAsString());
        }
        return result;
    }

    private JsonObject tryVehicleInteraction(String selectedItemId) {
        EntityHitResult entityHit = this.raycastRideableEntity();
        if (entityHit == null) {
            return null;
        }

        Entity vehicle = entityHit.getEntity();
        if (!this.startRiding(vehicle)) {
            return null;
        }

        JsonObject result = new JsonObject();
        result.addProperty("used", selectedItemId);
        result.addProperty("entered_vehicle", true);
        result.addProperty("in_vehicle", true);
        result.addProperty("vehicle", Registries.ENTITY_TYPE.getId(vehicle.getType()).toString());
        result.addProperty("vehicle_uuid", vehicle.getUuidAsString());
        return result;
    }

    private JsonObject tryBlockFirstInteraction(ItemStack selected, String selectedItemId, BlockHitResult hit) {
        BlockState state = this.getEntityWorld().getBlockState(hit.getBlockPos());
        if (state.isOf(Blocks.TNT) && (selected.isOf(Items.FLINT_AND_STEEL) || selected.isOf(Items.FIRE_CHARGE))) {
            return this.primeTnt(selected, selectedItemId, hit.getBlockPos());
        }

        if ((state.contains(Properties.OPEN) || state.contains(Properties.POWERED))
            && state.createScreenHandlerFactory(this.getEntityWorld(), hit.getBlockPos()) == null) {
            ActionResult result = state.onUse(this.getEntityWorld(), null, hit);
            if (result.isAccepted()) {
                JsonObject response = new JsonObject();
                response.addProperty("used", idOf(this.getEntityWorld().getBlockState(hit.getBlockPos())));
                response.addProperty("pos", hit.getBlockPos().toShortString());
                return response;
            }
        }

        return null;
    }

    private JsonObject primeTnt(ItemStack selected, String selectedItemId, BlockPos pos) {
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            throw fail("interaction_unavailable", "TNT can only be primed in a server world");
        }

        BlockState state = serverWorld.getBlockState(pos);
        TntEntity primed = new TntEntity(serverWorld, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, this);
        primed.setBlockState(state);
        serverWorld.spawnEntity(primed);
        serverWorld.removeBlock(pos, false);
        serverWorld.playSound(null, pos, SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.BLOCKS, 1.0F, 1.0F);
        serverWorld.emitGameEvent(this, GameEvent.PRIME_FUSE, pos);

        if (selected.isOf(Items.FLINT_AND_STEEL)) {
            selected.damage(1, this, Hand.MAIN_HAND.getEquipmentSlot());
        } else {
            selected.decrement(1);
        }

        this.robotInventory.markDirty();
        this.syncEquippedStack();

        JsonObject result = new JsonObject();
        result.addProperty("used", selectedItemId);
        result.addProperty("activated", "minecraft:tnt");
        result.addProperty("pos", pos.toShortString());
        return result;
    }

    private ActionResult tryUnderfootBlockPlacement(BlockItem blockItem, ItemStack stack, BlockHitResult hit) {
        MineBotPlacementContext placementContext = new MineBotPlacementContext(
            this.getEntityWorld(),
            stack,
            hit,
            this.getHorizontalFacing(),
            roundAngle(this.getYaw()),
            roundAngle(this.getPitch())
        );
        BlockPos targetPos = placementContext.getBlockPos();
        BlockPos feetPos = BlockPos.ofFloored(this.getX(), this.getY(), this.getZ());
        if (!targetPos.equals(feetPos)) {
            return ActionResult.FAIL;
        }

        BlockState targetState = blockItem.getBlock().getPlacementState(placementContext);
        if (targetState == null) {
            return ActionResult.FAIL;
        }

        if (!targetState.getCollisionShape(this.getEntityWorld(), targetPos).isEmpty()) {
            return ActionResult.FAIL;
        }

        if (!targetState.canPlaceAt(this.getEntityWorld(), targetPos)) {
            return ActionResult.FAIL;
        }

        if (!this.getEntityWorld().canPlace(targetState, targetPos, ShapeContext.of(this))) {
            return ActionResult.FAIL;
        }

        if (!this.getEntityWorld().setBlockState(targetPos, targetState, 11)) {
            return ActionResult.FAIL;
        }

        BlockItem.writeNbtToBlockEntity(this.getEntityWorld(), null, targetPos, stack);
        targetState.getBlock().onPlaced(this.getEntityWorld(), targetPos, targetState, this, stack);
        this.getEntityWorld().playSound(
            null,
            targetPos,
            targetState.getSoundGroup().getPlaceSound(),
            SoundCategory.BLOCKS,
            (targetState.getSoundGroup().getVolume() + 1.0F) / 2.0F,
            targetState.getSoundGroup().getPitch() * 0.8F
        );
        this.getEntityWorld().emitGameEvent(GameEvent.BLOCK_PLACE, targetPos, GameEvent.Emitter.of(this, targetState));
        stack.decrement(1);
        return ActionResult.SUCCESS;
    }

    private JsonObject handleSelectSlot(JsonObject request) {
        int requested = (int) readDouble(request, "slot");
        this.setSelectedSlot(requested);

        JsonObject result = new JsonObject();
        result.addProperty("selected_slot", this.getSelectedSlot());
        result.addProperty("selected_item", this.getSelectedItemId());
        return result;
    }

    private JsonObject handleDrop(JsonObject request) {
        int slot = request.has("slot") ? (int) readDouble(request, "slot") : this.getSelectedSlot();
        slot = MathHelper.clamp(slot, 0, MineBotMod.ROBOT_INVENTORY_SIZE - 1);

        ItemStack stack = this.robotInventory.getStack(slot);
        if (stack.isEmpty()) {
            throw fail("missing_item", "The selected hotbar slot is empty");
        }

        int requestedCount = request.has("count") ? Math.max(1, (int) readDouble(request, "count")) : stack.getCount();
        int dropCount = Math.min(requestedCount, stack.getCount());
        ItemStack dropped = stack.split(dropCount);
        this.robotInventory.markDirty();
        this.syncEquippedStack();

        if (!(this.getEntityWorld() instanceof ServerWorld)) {
            throw fail("interaction_unavailable", "The robot cannot drop items outside a server world");
        }

        // Thrown like a player's drop key: along the look direction, with the 2-second pickup delay that
        // tickItemPickup() respects, so it lands out of pickup range instead of bouncing back.
        this.dropItem(dropped.copy(), false, true);

        JsonObject result = new JsonObject();
        result.addProperty("slot", slot);
        result.addProperty("item", itemIdOf(dropped));
        result.addProperty("dropped_count", dropCount);
        result.addProperty("remaining_count", stack.getCount());
        return result;
    }

    private JsonObject handleSlotInspect(JsonObject request) {
        int slot = request.has("slot") ? (int) readDouble(request, "slot") : this.getSelectedSlot();
        slot = MathHelper.clamp(slot, 0, MineBotMod.ROBOT_INVENTORY_SIZE - 1);
        ItemStack stack = this.robotInventory.getStack(slot);

        JsonObject result = new JsonObject();
        result.addProperty("slot", slot);
        result.addProperty("item", itemIdOf(stack));
        result.addProperty("count", stack.getCount());
        return result;
    }

    private JsonObject handlePrint(JsonObject request) {
        String message = request.has("message") ? readString(request, "message") : "";
        if (message.isBlank()) {
            throw fail("invalid_request", "print requires a non-empty message");
        }

        String sender = "MineBot:" + this.getAccessCode();
        Text chatLine = Text.literal("<" + sender + "> " + message);
        ServerPlayerEntity recipient = null;
        if (request.has("to")) {
            String recipientName = readString(request, "to");
            if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
                for (ServerPlayerEntity player : serverWorld.getServer().getPlayerManager().getPlayerList()) {
                    if (player.getName().getString().equalsIgnoreCase(recipientName)) {
                        recipient = player;
                        break;
                    }
                }
            }
            if (recipient == null) {
                throw fail("player_not_found", "No online player is named " + recipientName);
            }
            recipient.sendMessage(chatLine, false);
        } else if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            for (ServerPlayerEntity player : serverWorld.getServer().getPlayerManager().getPlayerList()) {
                player.sendMessage(chatLine, false);
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("sender", sender);
        result.addProperty("message", message);
        if (recipient != null) {
            result.addProperty("to", recipient.getName().getString());
        }
        return result;
    }

    private JsonObject handleLookType() {
        BlockHitResult hit = this.raycastCameraInspect();
        Vec3d start = this.getCommandRayStart();
        JsonObject result = new JsonObject();
        double blockDistance = MineBotMod.CAMERA_TYPE_MAX_VISION_BLOCKS;
        if (hit == null) {
            result.addProperty("block", "minecraft:air");
            result.addProperty("distance", MineBotMod.CAMERA_TYPE_NO_HIT_DISTANCE_BLOCKS);
        } else {
            BlockState state = this.getEntityWorld().getBlockState(hit.getBlockPos());
            FluidState fluidState = this.getEntityWorld().getFluidState(hit.getBlockPos());
            blockDistance = start.distanceTo(hit.getPos());
            result.addProperty("block", fluidState.isEmpty() ? idOf(state) : idOf(fluidState));
            result.addProperty("distance", roundCoordinate(blockDistance));
            result.addProperty("x", hit.getBlockPos().getX());
            result.addProperty("y", hit.getBlockPos().getY());
            result.addProperty("z", hit.getBlockPos().getZ());
            result.addProperty("face", hit.getSide().asString());
            BlockHitResult actionHit = this.raycastBlock();
            result.addProperty(
                "in_reach",
                fluidState.isEmpty()
                    ? actionHit != null && actionHit.getBlockPos().equals(hit.getBlockPos())
                    : blockDistance <= MineBotMod.INTERACTION_REACH_BLOCKS
            );
        }

        EntityHitResult entityHit = this.raycastCrosshairEntity(blockDistance);
        if (entityHit != null) {
            Entity entity = entityHit.getEntity();
            double entityDistance = start.distanceTo(entityHit.getPos());
            result.addProperty("entity", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
            result.addProperty("entity_id", entity.getId());
            result.addProperty("entity_uuid", entity.getUuidAsString());
            result.addProperty("entity_name", entity.getDisplayName().getString());
            result.addProperty("entity_distance", roundCoordinate(entityDistance));
            result.addProperty("entity_in_reach", entityDistance <= MineBotMod.INTERACTION_REACH_BLOCKS);
        }
        return result;
    }

    private JsonObject handleReadChat(JsonObject request) {
        boolean peek = request.has("peek") && request.get("peek").getAsBoolean();
        int limit = readOptionalInt(request, "limit", null, MineBotChatInbox.CAPACITY);
        if (limit <= 0) {
            throw fail("invalid_request", "read_chat limit must be at least 1");
        }

        return this.chatInbox.read(
            peek,
            limit,
            this.getEntityWorld().getRegistryKey().getValue().toString(),
            new Vec3d(this.getX(), this.getY(), this.getZ())
        );
    }

    private JsonObject handleInventory() {
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < this.robotInventory.size(); slot++) {
            ItemStack stack = this.robotInventory.getStack(slot);
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("item", itemIdOf(stack));
            entry.addProperty("count", stack.getCount());
            if (!stack.isEmpty() && stack.isDamageable()) {
                entry.addProperty("damage", stack.getDamage());
                entry.addProperty("max_damage", stack.getMaxDamage());
            }
            slots.add(entry);
        }

        ItemStack fuel = this.fuelInventory.getStack(0);
        JsonObject result = new JsonObject();
        result.addProperty("selected_slot", this.getSelectedSlot());
        result.add("slots", slots);
        result.addProperty("slots_total", this.robotInventory.size());
        result.addProperty("slots_used", countUsedSlots(this.robotInventory));
        result.addProperty("fuel_item", itemIdOf(fuel));
        result.addProperty("fuel_count", fuel.getCount());
        result.addProperty("stored_range_blocks", roundCoordinate(this.getStoredEnergyMilliblocks() / 1_000.0D));
        return result;
    }

    private JsonObject handleScanBlocks(JsonObject request) {
        ServerWorld serverWorld = this.requireServerWorld();
        int limit = MathHelper.clamp(readOptionalInt(request, "limit", null, 64), 1, 256);
        MineBotScanner.BlockFilter filter = MineBotScanner.parseBlockFilter(
            request.has("blocks") && request.get("blocks").isJsonArray() ? request.getAsJsonArray("blocks") : null
        );
        // Only matching blocks are ray traced, so a filtered scan can afford a wider cube.
        int maxRadius = filter == null ? MineBotMod.SCAN_BLOCKS_MAX_RADIUS : MineBotMod.SCAN_BLOCKS_MAX_FILTERED_RADIUS;
        int radius = MathHelper.clamp(readOptionalInt(request, "radius", null, 8), 1, maxRadius);

        BlockPos origin = this.getBlockPos();
        boolean hasCenterX = request.has("center_x");
        boolean hasCenterY = request.has("center_y");
        boolean hasCenterZ = request.has("center_z");
        if (hasCenterX || hasCenterY || hasCenterZ) {
            if (!hasCenterX || !hasCenterY || !hasCenterZ) {
                throw fail("invalid_request", "scan_blocks needs all of center_x, center_y and center_z, or none of them");
            }

            origin = new BlockPos(
                (int) readDouble(request, "center_x"),
                (int) readDouble(request, "center_y"),
                (int) readDouble(request, "center_z")
            );
            if (Vec3d.ofCenter(origin).distanceTo(new Vec3d(this.getX(), this.getY(), this.getZ())) > 32.0D) {
                throw fail("invalid_request", "The scan centre must be within 32 blocks of the robot");
            }
        }

        return MineBotScanner.scanBlocks(serverWorld, this, origin, radius, this.getCommandRayStart(), filter, limit);
    }

    private JsonObject handleScanEntities(JsonObject request) {
        double radius = MathHelper.clamp(readOptionalDouble(request, "radius", null, 16.0D), 1.0D, 64.0D);
        int limit = MathHelper.clamp(readOptionalInt(request, "limit", null, 32), 1, 128);
        boolean playersOnly = request.has("players_only") && request.get("players_only").getAsBoolean();
        Set<EntityType<?>> types = null;
        if (request.has("types") && request.get("types").isJsonArray() && !request.getAsJsonArray("types").isEmpty()) {
            types = new HashSet<>();
            for (JsonElement element : request.getAsJsonArray("types")) {
                String typeId = element.getAsString().trim();
                Identifier identifier = Identifier.tryParse(typeId);
                if (identifier == null || !Registries.ENTITY_TYPE.containsId(identifier)) {
                    throw fail("invalid_request", "Unknown entity type: " + typeId);
                }
                types.add(Registries.ENTITY_TYPE.get(identifier));
            }
        }

        return MineBotScanner.scanEntities(this, radius, types, playersOnly, limit);
    }

    private JsonObject handleEnvironment() {
        World world = this.getEntityWorld();
        BlockPos feetPos = this.getBlockPos();
        BlockPos headPos = BlockPos.ofFloored(this.getCommandRayStart());
        long timeOfDay = world.getTimeOfDay();

        JsonObject result = new JsonObject();
        result.addProperty("dimension", world.getRegistryKey().getValue().toString());
        result.addProperty(
            "biome",
            world.getBiome(feetPos).getKey().map(key -> key.getValue().toString()).orElse("unknown")
        );
        result.addProperty("time_of_day", Math.floorMod(timeOfDay, 24_000L));
        result.addProperty("day", Math.floorDiv(timeOfDay, 24_000L));
        result.addProperty("is_day", world.isDay());
        result.addProperty("raining", world.isRaining());
        result.addProperty("thundering", world.isThundering());
        result.addProperty("light", world.getLightLevel(headPos));
        result.addProperty("block_below", idOf(world.getBlockState(this.getSupportBlockPos())));
        result.addProperty("block_at_feet", idOf(world.getBlockState(feetPos)));
        result.addProperty("block_at_head", idOf(world.getBlockState(headPos)));
        result.addProperty("on_ground", this.isOnGround());
        result.addProperty("in_water", this.isTouchingWater());
        result.addProperty("in_lava", this.isInLava());
        return result;
    }

    private JsonObject handleStop() {
        boolean wasMoving = this.moveTarget != null || this.moveByTarget != null || this.pillaring || this.isBridging();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        this.stopActiveMovement();
        if (wasMoving) {
            this.recordLastMoveResult(false, "The MineBot was stopped before reaching the destination");
        }

        JsonObject result = new JsonObject();
        result.addProperty("stopped", true);
        return result;
    }

    private JsonObject handleLookAt(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();

        Vec3d target;
        if (request.has("entity_id")) {
            int entityId = (int) readDouble(request, "entity_id");
            Entity entity = this.getEntityWorld().getEntityById(entityId);
            if (entity == null || entity == this || entity.isRemoved() || !MineBotScanner.canPerceive(this, entity)) {
                throw fail("entity_not_found", "No visible entity has id " + entityId);
            }
            target = entity instanceof LivingEntity ? entity.getEyePos() : entity.getBoundingBox().getCenter();
        } else {
            target = new Vec3d(readDouble(request, "x"), readDouble(request, "y"), readDouble(request, "z"));
        }

        this.lookAtPoint(target);

        JsonObject result = new JsonObject();
        result.addProperty("yaw", roundAngle(this.getYaw()));
        result.addProperty("pitch", roundAngle(this.getPitch()));
        return result;
    }

    // One hit, or with until_dead a fight: the server swings at the target each time the weapon has
    // recharged, re-aiming every tick, until it dies or something ends the fight. Progress is in status
    // (fighting, fight_hits) and the outcome in last_fight.
    private JsonObject handleAttackEntity(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        ServerWorld serverWorld = this.requireServerWorld();
        boolean untilDead = request.has("until_dead") && request.get("until_dead").getAsBoolean();
        boolean follow = request.has("follow") && request.get("follow").getAsBoolean();
        Entity target;
        if (untilDead && request.has("entity_id") && !request.get("entity_id").isJsonNull()) {
            // A fight may start on a target in view but out of reach: the robot walks up to it (follow) or
            // waits, shield up, for it to come within reach.
            int entityId = (int) readDouble(request, "entity_id");
            Entity named = this.getEntityWorld().getEntityById(entityId);
            if (named == null || named == this || named.isRemoved() || !named.isAlive()
                || !MineBotScanner.canPerceive(this, named) || this.squaredDistanceTo(named) > FIGHT_ENGAGE_RANGE * FIGHT_ENGAGE_RANGE) {
                throw fail("entity_not_found", "No visible entity has id " + entityId + " within " + (int) FIGHT_ENGAGE_RANGE + " blocks");
            }
            target = named;
            this.lookAtPoint(target.getBoundingBox().getCenter());
        } else {
            target = this.requireCrosshairEntity().getEntity();
        }
        if (!target.isAttackable()) {
            throw fail("interaction_unavailable", "That entity cannot be attacked");
        }

        if (target instanceof PlayerEntity && !serverWorld.isPvpEnabled()) {
            throw fail("interaction_unavailable", "PvP is disabled on this server");
        }

        if (!untilDead) {
            Strike strike = this.strike(serverWorld, target);
            JsonObject result = new JsonObject();
            result.addProperty("entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
            result.addProperty("entity_id", target.getId());
            result.addProperty("damage", roundCoordinate(strike.damage()));
            result.addProperty("hit", strike.hit());
            if (target instanceof LivingEntity livingTarget) {
                result.addProperty("killed", livingTarget.isDead() || livingTarget.isRemoved());
                result.addProperty("health", roundCoordinate(Math.max(0.0F, livingTarget.getHealth())));
            } else {
                result.addProperty("killed", target.isRemoved());
            }
            return result;
        }

        if (!(target instanceof LivingEntity livingTarget)) {
            throw fail("interaction_unavailable", Registries.ENTITY_TYPE.getId(target.getType()) + " is not alive; until_dead fights living entities only");
        }
        float minHealth = (float) readOptionalDouble(request, "min_health", null, FIGHT_DEFAULT_MIN_HEALTH);
        if (!(minHealth >= 0.0F && minHealth < this.getMaxHealth())) {
            throw new IllegalArgumentException("min_health must be between 0 and " + roundCoordinate(this.getMaxHealth()));
        }
        double maxSeconds = readOptionalDouble(request, "max_seconds", null, FIGHT_DEFAULT_SECONDS);
        if (!(maxSeconds >= 1.0D && maxSeconds <= FIGHT_MAX_SECONDS)) {
            throw new IllegalArgumentException("max_seconds must be between 1 and " + (int) FIGHT_MAX_SECONDS);
        }
        if (this.getHealth() <= minHealth) {
            throw fail("interaction_unavailable", String.format(
                Locale.ROOT, "The robot's health is %.1f, already at or below min_health %.1f", this.getHealth(), minHealth
            ));
        }
        boolean guard = !request.has("guard") || request.get("guard").isJsonNull() || request.get("guard").getAsBoolean();
        if (follow) {
            this.stopActiveMovement();
        }

        this.fightTarget = target;
        this.fightFollow = follow;
        this.fightStart = this.getEntityPos();
        this.fightMinHealth = minHealth;
        this.fightTicks = 0;
        this.fightMaxTicks = (int) Math.round(maxSeconds * 20.0D);
        this.fightCooldownTicks = 0;
        this.fightOutOfReachTicks = 0;
        this.fightUnseenTicks = 0;
        this.fightRepathTicks = 0;
        this.fightSwings = 0;
        this.fightHits = 0;
        this.fightDamage = 0.0F;
        this.fightStartHealth = this.getHealth();
        this.fightBlockedBy = null;
        this.fightWeaponSlot = this.getSelectedSlot();
        this.fightShieldSlot = guard ? this.findGuardShieldSlot() : -1;
        this.fightShieldUp = false;
        this.lastFightResult = null;

        JsonObject result = new JsonObject();
        result.addProperty("fighting", true);
        result.addProperty("entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
        result.addProperty("entity_id", target.getId());
        result.addProperty("health", roundCoordinate(Math.max(0.0F, livingTarget.getHealth())));
        result.addProperty("swing_ticks", this.attackIntervalTicks());
        result.addProperty("follow", follow);
        result.addProperty("guard", this.fightShieldSlot >= 0);
        result.addProperty("min_health", roundCoordinate(minHealth));
        result.addProperty("max_seconds", roundCoordinate(maxSeconds));
        return result;
    }

    // A melee hit with the selected item, at the full strength of a player's recharged attack. The hit pushes
    // the target back as any hit does (the game's own 0.4); only a knockback enchantment adds to that, as for
    // a standing player, so the robot does not knock its target out of its own reach.
    private Strike strike(ServerWorld serverWorld, Entity target) {
        ItemStack weapon = this.robotInventory.getStack(this.getSelectedSlot());
        this.syncEquippedStack();
        this.swingHand(Hand.MAIN_HAND, true);

        DamageSource source = weapon.getDamageSource(this, () -> this.getDamageSources().mobAttack(this));
        float damage = (float) weapon.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT)
            .applyOperations(EntityAttributes.ATTACK_DAMAGE, 1.0D, EquipmentSlot.MAINHAND);
        damage = EnchantmentHelper.getDamage(serverWorld, weapon, target, source, damage);

        boolean hit = false;
        if (!(target instanceof PlayerEntity player && (player.isCreative() || player.isSpectator()))) {
            Vec3d targetVelocity = target.getVelocity();
            hit = target.damage(serverWorld, source, damage);
            if (hit) {
                float extraKnockback = this.getAttackKnockbackAgainst(target, source);
                if (extraKnockback > 0.0F) {
                    this.knockbackTarget(target, extraKnockback, targetVelocity);
                }
                if (target instanceof LivingEntity livingTarget && !weapon.isEmpty() && weapon.postHit(livingTarget, this)) {
                    weapon.postDamageEntity(livingTarget, this);
                }
                EnchantmentHelper.onTargetDamaged(serverWorld, target, source, weapon);
                this.onAttacking(target);
            }
        }
        this.syncRobotInventory();
        return new Strike(hit, damage);
    }

    private record Strike(boolean hit, float damage) {
    }

    // A player's attack recharges in 20 / attack speed ticks: 13 for a sword, 20 or 25 for an axe, 5 for a
    // bare hand. A target ignores a second hit within its 10 ticks of hurt immunity.
    private int attackIntervalTicks() {
        return this.attackIntervalTicks(this.getSelectedSlot());
    }

    private int attackIntervalTicks(int slot) {
        ItemStack weapon = this.robotInventory.getStack(slot);
        double speed = weapon.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, AttributeModifiersComponent.DEFAULT)
            .applyOperations(EntityAttributes.ATTACK_SPEED, 4.0D, EquipmentSlot.MAINHAND);
        return Math.max(FIGHT_MIN_SWING_TICKS, MathHelper.ceil(20.0D / Math.max(0.1D, speed)));
    }

    private boolean isFighting() {
        return this.fightTarget != null;
    }

    private void tickFight(ServerWorld serverWorld) {
        if (!this.isFighting()) {
            return;
        }

        Entity target = this.fightTarget;
        String name = target.getDisplayName().getString();
        this.fightTicks++;
        if (this.fightCooldownTicks > 0) {
            this.fightCooldownTicks--;
        }
        if (this.isFightTargetDead()) {
            this.finishFight("killed", "Killed " + name);
            return;
        }
        if (target.isRemoved() || target.getEntityWorld() != this.getEntityWorld()) {
            this.finishFight("gone", name + " is gone");
            return;
        }
        if (!this.hasAvailableEnergy()) {
            this.finishFight("out_of_energy", "The MineBot ran out of blaze powder energy");
            return;
        }
        if (this.getHealth() <= this.fightMinHealth) {
            this.finishFight("low_health", String.format(
                Locale.ROOT, "The robot's health fell to %.1f, at or below min_health %.1f", this.getHealth(), this.fightMinHealth
            ));
            return;
        }

        boolean seen = MineBotScanner.canPerceive(this, target);
        boolean inReach = false;
        if (seen) {
            this.fightUnseenTicks = 0;
            this.lookAtPoint(target.getBoundingBox().getCenter());
            EntityHitResult hit = this.crosshairEntityHit();
            // Something else in the crosshair is not hit in its place.
            inReach = hit != null && hit.getEntity() == target;
        } else if (++this.fightUnseenTicks > FIGHT_UNSEEN_TICKS) {
            String message = name + " went out of sight for " + this.fightUnseenTicks / 20 + " s";
            if (this.fightFollow && this.fightBlockedBy != null) {
                message += "; following it stopped: " + this.fightBlockedBy;
            }
            this.finishFight("out_of_reach", message);
            return;
        }

        if (this.fightTicks > this.fightMaxTicks) {
            String message = "Still fighting " + name + " after " + this.fightMaxTicks / 20 + " s";
            if (seen && this.fightOutOfReachTicks > 0) {
                message += String.format(
                    Locale.ROOT, "; it stayed out of reach (%.1f blocks away) for the last %d s", Math.sqrt(this.squaredDistanceTo(target)), this.fightOutOfReachTicks / 20
                );
            }
            if (this.fightFollow && this.fightBlockedBy != null) {
                message += "; following it stopped: " + this.fightBlockedBy;
            }
            this.finishFight("timeout", message);
            return;
        }

        // A sword in reach of a recharged arm swings now; otherwise the shield guard stands ready.
        boolean swingNow = inReach && this.fightCooldownTicks <= 0;
        this.tickFightGuard(swingNow);
        if (inReach) {
            this.fightOutOfReachTicks = 0;
        } else {
            this.fightOutOfReachTicks++;
        }
        if (swingNow) {
            Strike strike = this.strike(serverWorld, target);
            this.fightSwings++;
            if (strike.hit()) {
                this.fightHits++;
                this.fightDamage += strike.damage();
            }
            this.fightCooldownTicks = this.attackIntervalTicks(this.fightWeaponSlot);
            if (this.isFightTargetDead()) {
                this.finishFight("killed", "Killed " + name);
                return;
            }
        }

        if (this.fightFollow) {
            this.tickFightFollow(target, seen);
        }
    }

    // The shield guard: with a shield elsewhere in the hotbar, the robot holds it up whenever it is not
    // swinging, as a player holds one in the off hand, and takes the weapon back for each swing. It faces
    // the target, so the shield meets what the target shoots or swings at it.
    private void tickFightGuard(boolean swingNow) {
        if (this.fightShieldSlot < 0) {
            return;
        }
        if (this.robotInventory.getStack(this.fightShieldSlot).getItem() != Items.SHIELD
            || this.getSelectedSlot() != (this.fightShieldUp ? this.fightShieldSlot : this.fightWeaponSlot)) {
            // The shield is gone or something changed the selected slot meanwhile: fight on without the guard.
            this.fightShieldSlot = -1;
            this.fightShieldUp = false;
            return;
        }
        if (swingNow) {
            if (this.fightShieldUp) {
                this.lowerGuardShield();
            }
            return;
        }
        if (!this.fightShieldUp) {
            this.raiseGuardShield();
        }
    }

    private int findGuardShieldSlot() {
        for (int slot = 0; slot < this.robotInventory.size(); slot++) {
            if (slot != this.getSelectedSlot() && this.robotInventory.getStack(slot).getItem() == Items.SHIELD) {
                return slot;
            }
        }
        return -1;
    }

    private void raiseGuardShield() {
        this.setSelectedSlot(this.fightShieldSlot);
        this.syncEquippedStack();
        this.clearActiveItem();
        this.setCurrentHand(Hand.MAIN_HAND);
        this.fightShieldUp = true;
    }

    private void lowerGuardShield() {
        this.clearActiveItem();
        this.setSelectedSlot(this.fightWeaponSlot);
        this.syncEquippedStack();
        this.fightShieldUp = false;
    }

    // Keeps close to a target it can see, but not after one that gets far from where the fight started. A
    // target on the ground is followed along a path the way move_to does; one in the air, such as a hovering
    // blaze, has no path to it, so the robot walks straight at the ground under it. The hazard guard holds
    // back any step into lava or fire or off a drop of more than three blocks, and the fight goes on from
    // where the robot stands.
    private void tickFightFollow(Entity target, boolean seen) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        boolean leashed = Math.hypot(target.getX() - this.fightStart.x, target.getZ() - this.fightStart.z) > FIGHT_FOLLOW_LEASH;
        if (leashed) {
            this.fightBlockedBy = "the robot keeps within " + (int) FIGHT_FOLLOW_LEASH + " blocks of where the fight started";
        }
        if (!seen || leashed || dx * dx + dz * dz <= FIGHT_FOLLOW_DISTANCE * FIGHT_FOLLOW_DISTANCE
            || !this.hasAvailableEnergy() || this.isEscapingFire()) {
            if (!this.getNavigation().isIdle()) {
                this.getNavigation().stop();
            }
            return;
        }

        boolean airborne = !target.isOnGround() && !target.isTouchingWater() && target.getY() > this.getY() + 0.5D;
        if (!airborne) {
            if (--this.fightRepathTicks > 0 && !this.getNavigation().isIdle()) {
                return;
            }
            this.fightRepathTicks = FIGHT_REPATH_TICKS;
            Path path = this.getNavigation().findPathTo(target, 1);
            if (path != null && this.getNavigation().startMovingAlong(path, 1.0D)) {
                return;
            }
        }

        // Straight at the spot under the target. The move control needs a target every tick.
        if (!this.getNavigation().isIdle()) {
            this.getNavigation().stop();
        }
        this.getMoveControl().moveTo(target.getX(), this.getY(), target.getZ(), 1.0D);
    }

    private boolean isFightTargetDead() {
        return this.fightTarget instanceof LivingEntity living
            && (living.isDead() || living.getRemovalReason() == RemovalReason.KILLED);
    }

    private void finishFight(String ended, String message) {
        Entity target = this.fightTarget;
        JsonObject result = new JsonObject();
        result.addProperty("entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
        result.addProperty("entity_id", target.getId());
        result.addProperty("killed", "killed".equals(ended));
        result.addProperty("ended", ended);
        result.addProperty("message", message);
        result.addProperty("swings", this.fightSwings);
        result.addProperty("hits", this.fightHits);
        result.addProperty("damage", roundCoordinate(this.fightDamage));
        // The robot only knows the health of a target it can still see.
        if (target instanceof LivingEntity living && ("killed".equals(ended) || !target.isRemoved() && MineBotScanner.canPerceive(this, target))) {
            result.addProperty("target_health", roundCoordinate(Math.max(0.0F, living.getHealth())));
        }
        result.addProperty("seconds", roundCoordinate(this.fightTicks / 20.0D));
        result.addProperty("health", roundCoordinate(this.getHealth()));
        result.addProperty("health_lost", roundCoordinate(Math.max(0.0F, this.fightStartHealth - this.getHealth())));
        result.addProperty("guard", this.fightShieldSlot >= 0);
        this.clearFight();
        this.lastFightResult = result;
    }

    private void clearFight() {
        if (this.fightFollow && !this.getNavigation().isIdle()) {
            this.getNavigation().stop();
        }
        if (this.fightShieldUp && this.getSelectedSlot() == this.fightShieldSlot) {
            this.lowerGuardShield();
        }
        this.fightTarget = null;
        this.fightFollow = false;
        this.fightBlockedBy = null;
        this.fightShieldSlot = -1;
        this.fightShieldUp = false;
    }

    private JsonObject handleUseItem(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        ServerWorld serverWorld = this.requireServerWorld();
        Integer requestedHoldTicks = null;
        if (request.has("hold_seconds") && !request.get("hold_seconds").isJsonNull()) {
            double seconds = readDouble(request, "hold_seconds");
            if (!(seconds >= 0.05D && seconds <= MAX_USE_HOLD_SECONDS)) {
                throw new IllegalArgumentException("hold_seconds must be between 0.05 and " + (int) MAX_USE_HOLD_SECONDS);
            }
            requestedHoldTicks = Math.max(1, (int) Math.round(seconds * 20.0D));
        }
        ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
        if (selected.isEmpty()) {
            throw fail("missing_item", "The selected hotbar slot is empty");
        }

        String usedItemId = itemIdOf(selected);
        // Eating or drinking would feed the fake player, not the robot, and use the item up for nothing.
        // Block items such as glow berries still go on to be placed.
        if (selected.contains(DataComponentTypes.CONSUMABLE) && !(selected.getItem() instanceof BlockItem)) {
            throw fail("interaction_unavailable", "Robots cannot eat or drink " + usedItemId + "; they eat iron and copper ingots with eat");
        }
        Map<String, Integer> countsBefore = this.countHotbarItems();
        Set<Integer> projectilesBefore = this.nearbyProjectileIds(serverWorld);
        Integer finalRequestedHoldTicks = requestedHoldTicks;
        int[] holdTicks = {0};
        this.swingHand(Hand.MAIN_HAND, true);
        ActionResult used = this.interactAsFakePlayer(serverWorld, player -> {
            ItemStack stack = player.getMainHandStack();
            boolean loaded = stack.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(stack);
            if (stack.getItem() instanceof RangedWeaponItem && !loaded && player.getProjectileType(stack).isEmpty()) {
                throw fail(
                    "missing_item",
                    usedItemId + " needs ammunition in the hotbar ("
                        + (stack.getItem() instanceof CrossbowItem ? "arrows or firework rockets" : "arrows") + ")"
                );
            }

            ActionResult result = player.interactionManager.interactItem(player, serverWorld, stack, Hand.MAIN_HAND);
            if (player.isUsingItem()) {
                holdTicks[0] = finalRequestedHoldTicks != null
                    ? finalRequestedHoldTicks
                    : naturalHoldTicks(player.getActiveItem(), player);
            }
            return result;
        });

        if (holdTicks[0] > 0) {
            // Hold-to-use item: the robot draws, charges or raises it for real game ticks, then the use
            // finishes in tickHeldUse(). Poll status until using_item is false and read last_use.
            this.startHeldUse(usedItemId, holdTicks[0]);
            JsonObject result = new JsonObject();
            result.addProperty("used", usedItemId);
            result.addProperty("accepted", true);
            result.addProperty("holding", true);
            result.addProperty("hold_ticks", holdTicks[0]);
            result.addProperty("eta_ticks", holdTicks[0]);
            result.addProperty("selected_item", this.getSelectedItemId());
            return result;
        }

        // Items such as flint and steel, bone meal and hoes only act on a block. When the air use did
        // nothing, fall back to using the item on the crosshair block, as a player's right-click would.
        JsonObject onBlock = null;
        if (!used.isAccepted() && this.raycastBlock() != null) {
            try {
                onBlock = this.handlePlace();
            } catch (IllegalArgumentException ignored) {
                // Nothing to do on that block either: report accepted=false like a plain air click.
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("used", usedItemId);
        result.addProperty("accepted", used.isAccepted() || onBlock != null);
        this.addUseOutcome(result, serverWorld, countsBefore, projectilesBefore);
        if (onBlock != null) {
            result.add("on_block", onBlock);
        }
        return result;
    }

    /** How long a player holds the item for its full effect: a full bow draw, a crossbow load, a trident throw. */
    private static int naturalHoldTicks(ItemStack stack, LivingEntity user) {
        if (stack.getItem() instanceof BowItem) {
            return BowItem.TICKS_PER_SECOND;
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return CrossbowItem.getPullTime(stack, user);
        }
        if (stack.getItem() instanceof TridentItem) {
            return TridentItem.MIN_DRAW_DURATION;
        }
        int maxUseTicks = stack.getMaxUseTime(user);
        // Items held for as long as the button is (shields, spyglasses) get a second unless told otherwise.
        return maxUseTicks > 0 && maxUseTicks <= 100 ? maxUseTicks : 20;
    }

    private void startHeldUse(String itemId, int holdTicks) {
        this.heldUseSlot = this.getSelectedSlot();
        this.heldUseItem = this.robotInventory.getStack(this.heldUseSlot).copyWithCount(1);
        this.heldUseItemId = itemId;
        this.heldUseTicksTotal = holdTicks;
        this.heldUseTicksRemaining = holdTicks;
        this.lastUseResult = null;
        // Shows the robot drawing or raising the item; tickItemStackUsage() keeps that pose free of effects.
        this.syncEquippedStack();
        this.clearActiveItem();
        this.setCurrentHand(Hand.MAIN_HAND);
    }

    private boolean isHoldingUse() {
        return this.heldUseSlot >= 0;
    }

    private void tickHeldUse(ServerWorld serverWorld) {
        if (!this.isHoldingUse()) {
            return;
        }
        if (this.getSelectedSlot() != this.heldUseSlot
            || !ItemStack.areItemsEqual(this.robotInventory.getStack(this.heldUseSlot), this.heldUseItem)) {
            this.finishHeldUse(false, "The selected item changed before the use finished", null);
            return;
        }
        if (--this.heldUseTicksRemaining > 0) {
            return;
        }

        int holdTicks = this.heldUseTicksTotal;
        Map<String, Integer> countsBefore = this.countHotbarItems();
        Set<Integer> projectilesBefore = this.nearbyProjectileIds(serverWorld);
        this.clearHeldUse();
        // A player's use ticks once per tick while held and fires on release; this replays that on the
        // fake player, aimed where the robot aims now.
        this.interactAsFakePlayer(serverWorld, player -> {
            player.setCurrentHand(Hand.MAIN_HAND);
            for (int tick = 0; tick < holdTicks && player.tickItemUse(); tick++) {
                // Each call is one game tick of holding.
            }
            if (player.isUsingItem()) {
                player.stopUsingItem();
            }
            return null;
        });

        JsonObject result = new JsonObject();
        result.addProperty("held_ticks", holdTicks);
        this.addUseOutcome(result, serverWorld, countsBefore, projectilesBefore);
        ItemStack after = this.robotInventory.getStack(this.getSelectedSlot());
        if (after.getItem() instanceof CrossbowItem) {
            result.addProperty("charged", CrossbowItem.isCharged(after));
        }
        this.finishHeldUse(true, "", result);
    }

    private void cancelPendingUse() {
        if (this.isHoldingUse()) {
            this.finishHeldUse(false, "The use was interrupted before it finished", null);
        }
    }

    private void finishHeldUse(boolean completed, String message, JsonObject outcome) {
        JsonObject result = new JsonObject();
        result.addProperty("used", this.heldUseItemId);
        result.addProperty("completed", completed);
        if (!message.isBlank()) {
            result.addProperty("message", message);
        }
        if (outcome != null) {
            for (Map.Entry<String, JsonElement> entry : outcome.entrySet()) {
                result.add(entry.getKey(), entry.getValue());
            }
        }
        this.clearHeldUse();
        this.lastUseResult = result;
    }

    private void clearHeldUse() {
        if (this.heldUseSlot >= 0) {
            this.clearActiveItem();
        }
        this.heldUseSlot = -1;
        this.heldUseItem = ItemStack.EMPTY;
        this.heldUseTicksTotal = 0;
        this.heldUseTicksRemaining = 0;
    }

    // The robot only shows the use pose; the fake player does the real use when the hold ends. Counting
    // down still lets a raised shield start blocking, as it would for a player.
    @Override
    protected void tickItemStackUsage(ItemStack stack) {
        if (this.itemUseTimeLeft > 1) {
            this.itemUseTimeLeft--;
        }
    }

    /** Adds what the use changed: projectiles it launched, hotbar items spent and gained, the selected item. */
    private void addUseOutcome(JsonObject result, ServerWorld serverWorld, Map<String, Integer> countsBefore, Set<Integer> projectilesBefore) {
        JsonArray projectiles = new JsonArray();
        for (ProjectileEntity projectile : this.nearbyProjectiles(serverWorld)) {
            if (!projectilesBefore.contains(projectile.getId())) {
                projectiles.add(Registries.ENTITY_TYPE.getId(projectile.getType()).toString());
            }
        }
        result.add("projectiles", projectiles);

        Map<String, Integer> countsAfter = this.countHotbarItems();
        JsonObject spent = new JsonObject();
        JsonObject gained = new JsonObject();
        Set<String> ids = new HashSet<>(countsBefore.keySet());
        ids.addAll(countsAfter.keySet());
        for (String id : ids.stream().sorted().toList()) {
            int change = countsAfter.getOrDefault(id, 0) - countsBefore.getOrDefault(id, 0);
            if (change < 0) {
                spent.addProperty(id, -change);
            } else if (change > 0) {
                gained.addProperty(id, change);
            }
        }
        result.add("spent", spent);
        result.add("gained", gained);
        result.addProperty("selected_item", this.getSelectedItemId());
    }

    private Map<String, Integer> countHotbarItems() {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < this.robotInventory.size(); slot++) {
            ItemStack stack = this.robotInventory.getStack(slot);
            if (!stack.isEmpty()) {
                counts.merge(itemIdOf(stack), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private List<ProjectileEntity> nearbyProjectiles(ServerWorld serverWorld) {
        Box around = Box.of(this.getCommandRayStart(), 6.0D, 6.0D, 6.0D);
        return serverWorld.getEntitiesByClass(ProjectileEntity.class, around, Entity::isAlive);
    }

    private Set<Integer> nearbyProjectileIds(ServerWorld serverWorld) {
        Set<Integer> ids = new HashSet<>();
        for (ProjectileEntity projectile : this.nearbyProjectiles(serverWorld)) {
            ids.add(projectile.getId());
        }
        return ids;
    }

    private JsonObject handleUseOnEntity() {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        ServerWorld serverWorld = this.requireServerWorld();
        EntityHitResult entityHit = this.requireCrosshairEntity();
        Entity target = entityHit.getEntity();
        String usedItemId = itemIdOf(this.robotInventory.getStack(this.getSelectedSlot()));
        Vec3d hitOffset = entityHit.getPos().subtract(target.getX(), target.getY(), target.getZ());

        this.swingHand(Hand.MAIN_HAND, true);
        ActionResult used = this.interactAsFakePlayer(serverWorld, player -> {
            ActionResult result = target.interactAt(player, hitOffset, Hand.MAIN_HAND);
            if (!result.isAccepted()) {
                result = player.interact(target, Hand.MAIN_HAND);
            }

            if (target instanceof Leashable leashable && leashable.getLeashHolder() == player) {
                leashable.attachLeash(this, true);
            }
            if (target instanceof Merchant merchant && merchant.getCustomer() == player) {
                merchant.setCustomer(null);
            }
            return result;
        });

        JsonObject result = new JsonObject();
        result.addProperty("entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
        result.addProperty("entity_id", target.getId());
        result.addProperty("used", usedItemId);
        result.addProperty("accepted", used.isAccepted());
        result.addProperty("selected_item", this.getSelectedItemId());
        return result;
    }

    private JsonObject handleMoveItem(JsonObject request) {
        int from = (int) readDouble(request, "from");
        int to = (int) readDouble(request, "to");
        int size = this.robotInventory.size();
        if (from < 0 || from >= size || to < 0 || to >= size) {
            throw fail("invalid_request", "Slots must be between 0 and " + (size - 1));
        }
        if (from == to) {
            throw fail("invalid_request", "move_item needs two different slots");
        }

        ItemStack source = this.robotInventory.getStack(from);
        if (source.isEmpty()) {
            throw fail("missing_item", "Slot " + from + " is empty");
        }

        Integer requested = null;
        if (request.has("count")) {
            requested = (int) readDouble(request, "count");
            if (requested <= 0) {
                throw fail("invalid_request", "Item counts must be at least 1");
            }
        }

        int count = requested == null ? source.getCount() : Math.min(requested, source.getCount());
        ItemStack destination = this.robotInventory.getStack(to);
        int moved;
        boolean swapped = false;
        if (destination.isEmpty()) {
            moved = Math.min(count, Math.min(source.getMaxCount(), this.robotInventory.getMaxCount(source)));
            this.robotInventory.setStack(to, source.split(moved));
        } else if (ItemStack.areItemsAndComponentsEqual(source, destination)) {
            int space = Math.min(destination.getMaxCount(), this.robotInventory.getMaxCount(destination)) - destination.getCount();
            if (space <= 0) {
                throw fail("target_full", "Slot " + to + " is already full");
            }
            moved = Math.min(count, space);
            source.decrement(moved);
            destination.increment(moved);
        } else if (requested == null || requested == source.getCount()) {
            moved = source.getCount();
            swapped = true;
            this.robotInventory.setStack(from, destination);
            this.robotInventory.setStack(to, source);
        } else {
            throw fail("target_full", "Slot " + to + " already holds " + itemIdOf(destination));
        }

        if (this.robotInventory.getStack(from).isEmpty()) {
            this.robotInventory.setStack(from, ItemStack.EMPTY);
        }
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("from", from);
        result.addProperty("to", to);
        result.addProperty("moved", moved);
        result.addProperty("swapped", swapped);
        return result;
    }

    private JsonObject handleRefuel(JsonObject request) {
        int available = countMatchingItems(this.robotInventory, Items.BLAZE_POWDER);
        if (available <= 0) {
            throw fail("missing_item", "The robot hotbar does not contain any minecraft:blaze_powder");
        }

        ItemStack fuel = this.fuelInventory.getStack(0);
        int space = MineBotMod.MAX_FUEL_STACK - fuel.getCount();
        if (space <= 0 || (!fuel.isEmpty() && !fuel.isOf(Items.BLAZE_POWDER))) {
            throw fail("target_full", "The fuel slot is full");
        }

        int requested = available;
        if (request.has("count")) {
            requested = (int) readDouble(request, "count");
            if (requested <= 0) {
                throw fail("invalid_request", "Item counts must be at least 1");
            }
        }

        int moved = Math.min(requested, Math.min(available, space));
        ItemStack removed = this.removeFromRobotInventory(Items.BLAZE_POWDER, moved);
        if (fuel.isEmpty()) {
            this.fuelInventory.setStack(0, removed);
        } else {
            fuel.increment(removed.getCount());
        }
        this.fuelInventory.markDirty();
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("moved", moved);
        result.addProperty("fuel_count", this.fuelInventory.getStack(0).getCount());
        result.addProperty("stored_range_blocks", roundCoordinate(this.getStoredEnergyMilliblocks() / 1_000.0D));
        return result;
    }

    private JsonObject handleEat(JsonObject request) {
        List<Item> edible = EDIBLE_INGOTS;
        String itemId = readOptionalString(request, "item", null);
        if (itemId != null) {
            Item item = requireRegisteredItem(itemId);
            if (!EDIBLE_INGOTS.contains(item)) {
                throw fail("invalid_item", "Robots eat only minecraft:iron_ingot and minecraft:copper_ingot, not " + itemId);
            }
            edible = List.of(item);
        }

        int requested = Integer.MAX_VALUE;
        if (request.has("count")) {
            requested = (int) readDouble(request, "count");
            if (requested <= 0) {
                throw fail("invalid_request", "Item counts must be at least 1");
            }
        }

        float before = this.getHealth();
        if (before >= this.getMaxHealth()) {
            throw fail("target_full", "The robot is already at full health");
        }

        // Never more ingots than it takes to fill up.
        int wanted = Math.min(requested, MathHelper.ceil((this.getMaxHealth() - before) / HEALTH_PER_INGOT));
        JsonObject spent = new JsonObject();
        int eaten = 0;
        Item lastEaten = null;
        for (Item item : edible) {
            int taken = Math.min(wanted - eaten, countMatchingItems(this.robotInventory, item));
            if (taken > 0) {
                this.removeFromRobotInventory(item, taken);
                spent.addProperty(itemIdOf(new ItemStack(item)), taken);
                eaten += taken;
                lastEaten = item;
            }
        }
        if (lastEaten == null) {
            String wantedIds = edible.size() == 1 ? itemIdOf(new ItemStack(edible.get(0))) : "minecraft:iron_ingot or minecraft:copper_ingot";
            throw fail("missing_item", "The robot hotbar does not contain any " + wantedIds);
        }

        this.heal(eaten * HEALTH_PER_INGOT);
        this.syncRobotInventory();
        this.playSound(SoundEvents.ENTITY_GENERIC_EAT.value(), 1.0F, 1.0F);
        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            Vec3d mouth = this.getCommandRayStart().add(this.getRotationVector().multiply(0.4D)).add(0.0D, -0.2D, 0.0D);
            serverWorld.spawnParticles(
                new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(lastEaten)),
                mouth.x, mouth.y, mouth.z, 8, 0.1D, 0.1D, 0.1D, 0.05D
            );
        }

        JsonObject result = new JsonObject();
        result.addProperty("eaten", eaten);
        result.add("spent", spent);
        result.addProperty("healed", roundCoordinate(this.getHealth() - before));
        result.addProperty("health", roundCoordinate(this.getHealth()));
        result.addProperty("max_health", roundCoordinate(this.getMaxHealth()));
        return result;
    }

    // The fake player is lent the whole hotbar, the selected slot in its hand, so ammunition and free slots
    // work as for a player. Each slot goes back where it came from.
    private <T> T interactAsFakePlayer(ServerWorld serverWorld, Function<MineBotFakePlayer, T> interaction) {
        int selected = this.getSelectedSlot();
        int size = this.robotInventory.size();
        int[] order = new int[size];
        order[0] = selected;
        for (int slot = 0, next = 1; slot < size; slot++) {
            if (slot != selected) {
                order[next++] = slot;
            }
        }

        MineBotFakePlayer player = MineBotFakePlayer.acquire(serverWorld, this.getCommandRayStart(), this.getYaw(), this.getPitch());
        List<ItemStack> lent = new ArrayList<>(size);
        for (int slot : order) {
            lent.add(this.robotInventory.removeStack(slot));
        }
        try {
            MineBotFakePlayer.hold(player, lent);
            return interaction.apply(player);
        } finally {
            MineBotFakePlayer.Released released = MineBotFakePlayer.release(player, size);
            for (int index = 0; index < size; index++) {
                this.robotInventory.setStack(order[index], released.slots().get(index));
            }
            for (ItemStack extra : released.others()) {
                if (!insertIntoInventory(this.robotInventory, extra)) {
                    this.dropStack(serverWorld, extra);
                }
            }
            this.syncRobotInventory();
        }
    }

    private ServerWorld requireServerWorld() {
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            throw fail("interaction_unavailable", "The robot can only do that in a server world");
        }
        return serverWorld;
    }

    private EntityHitResult requireCrosshairEntity() {
        EntityHitResult hit = this.crosshairEntityHit();
        if (hit == null) {
            throw fail("not_looking_at_entity", "No entity is in front of the robot within reach");
        }
        return hit;
    }

    // The entity in the crosshair within reach and in front of any block, or null.
    private EntityHitResult crosshairEntityHit() {
        BlockHitResult blockHit = this.raycastBlock();
        double reach = blockHit == null
            ? MineBotMod.INTERACTION_REACH_BLOCKS
            : this.getCommandRayStart().distanceTo(blockHit.getPos());
        return this.raycastCrosshairEntity(reach);
    }

    // A mob that has died stays in the world for its second of death animation; the crosshair passes through it.
    private EntityHitResult raycastCrosshairEntity(double maxDistance) {
        Vec3d start = this.getCommandRayStart();
        Vec3d direction = this.getRotationVec(1.0F).multiply(maxDistance);
        Box searchBox = this.getBoundingBox().stretch(direction).expand(1.0D);
        return ProjectileUtil.raycast(
            this,
            start,
            start.add(direction),
            searchBox,
            entity -> entity != this && entity.isAlive() && !entity.isSpectator() && entity.canHit() && entity != this.getVehicle(),
            maxDistance * maxDistance
        );
    }

    private JsonObject handleCraft(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        this.stopActiveMovement();
        int gridSize = this.isLookingAtCraftingTable() ? 3 : 2;

        Item targetItem = requireRegisteredItem(readString(request, "item"));
        CraftPlan plan = this.findCraftPlan(targetItem, gridSize);
        if (plan == null) {
            if (gridSize < 3 && !this.hasCraftingRecipeFitting(targetItem, gridSize) && this.hasCraftingRecipeFitting(targetItem, 3)) {
                throw fail("wrong_block", "Not looking at crafting table");
            }
            throw fail("missing_ingredients", "Missing ingredients to craft " + itemIdOf(new ItemStack(targetItem)) + "!");
        }

        int operations = 1;
        if (request.has("count")) {
            int requestedCount = (int) request.get("count").getAsDouble();
            if (requestedCount <= 0) {
                throw fail("invalid_request", "Craft count must be at least 1");
            }

            int resultCount = plan.result().getCount();
            if (requestedCount % resultCount != 0) {
                throw fail(
                    "invalid_request",
                    itemIdOf(new ItemStack(targetItem)) + " crafts in batches of " + resultCount + ", so count must be a multiple of " + resultCount
                );
            }

            operations = requestedCount / resultCount;
        }

        int craftedCount = 0;
        Identifier recipeId = plan.recipeId();
        for (int operation = 0; operation < operations; operation++) {
            CraftPlan nextPlan = operation == 0 ? plan : this.findCraftPlan(targetItem, gridSize);
            if (nextPlan == null) {
                throw fail(
                    "missing_ingredients",
                    "Missing ingredients to finish crafting " + itemIdOf(new ItemStack(targetItem)) + "!"
                );
            }

            recipeId = nextPlan.recipeId();
            this.applyCraftPlan(nextPlan);
            craftedCount += nextPlan.result().getCount();
        }

        JsonObject result = new JsonObject();
        result.addProperty("crafted", itemIdOf(plan.result()));
        result.addProperty("count", craftedCount);
        result.addProperty("operations", operations);
        result.addProperty("recipe_id", recipeId.toString());
        result.addProperty("grid", gridSize + "x" + gridSize);
        return result;
    }

    private JsonObject handleFurnaceInspect() {
        FurnaceAccess furnace = this.requireLookedFurnace();
        JsonObject result = new JsonObject();
        result.addProperty("kind", idOf(furnace.state()));
        result.add("input", describeStack(furnace.inventory().getStack(0)));
        result.add("fuel", describeStack(furnace.inventory().getStack(1)));
        result.add("output", describeStack(furnace.inventory().getStack(2)));
        return result;
    }

    private JsonObject handleFurnacePlace(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        this.stopActiveMovement();

        FurnaceAccess furnace = this.requireLookedFurnace();
        TransferRequest foodRequest = this.readTransferRequest(request, "food", null, "food_count", null);
        TransferRequest fuelRequest = this.readTransferRequest(request, "fuel", null, "fuel_count", null);

        if (foodRequest == null && fuelRequest == null) {
            throw fail("invalid_request", "furnace.place requires at least one of food=... or fuel=...");
        }

        this.ensureRobotHasRequestedItems(foodRequest, fuelRequest);

        if (foodRequest != null) {
            this.ensureSlotCanAccept(furnace.inventory(), 0, foodRequest.item(), foodRequest.count(), "furnace input");
        }

        if (fuelRequest != null) {
            if (!this.getEntityWorld().getFuelRegistry().isFuel(new ItemStack(fuelRequest.item()))) {
                throw fail("invalid_item", itemIdOf(new ItemStack(fuelRequest.item())) + " is not valid furnace fuel");
            }
            this.ensureSlotCanAccept(furnace.inventory(), 1, fuelRequest.item(), fuelRequest.count(), "furnace fuel");
        }

        if (foodRequest != null) {
            this.moveFromRobotInventoryToSlot(furnace.inventory(), 0, foodRequest.item(), foodRequest.count());
        }

        if (fuelRequest != null) {
            this.moveFromRobotInventoryToSlot(furnace.inventory(), 1, fuelRequest.item(), fuelRequest.count());
        }

        furnace.inventory().markDirty();
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("kind", idOf(furnace.state()));
        if (foodRequest != null) {
            result.addProperty("placed_food", foodRequest.itemId());
            result.addProperty("placed_food_count", foodRequest.count());
        }
        if (fuelRequest != null) {
            result.addProperty("placed_fuel", fuelRequest.itemId());
            result.addProperty("placed_fuel_count", fuelRequest.count());
        }
        return result;
    }

    private JsonObject handleFurnaceTake(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        this.stopActiveMovement();

        FurnaceAccess furnace = this.requireLookedFurnace();
        TransferRequest foodRequest = this.readTransferRequest(request, "food", null, "food_count", null);
        TransferRequest fuelRequest = this.readTransferRequest(request, "fuel", null, "fuel_count", null);

        if (foodRequest == null && fuelRequest == null) {
            foodRequest = new TransferRequest(null, "", 1);
        }

        ItemStack foodExtracted = ItemStack.EMPTY;
        ItemStack fuelExtracted = ItemStack.EMPTY;

        if (foodRequest != null) {
            foodExtracted = this.takeFromFurnaceFood(furnace.inventory(), foodRequest);
        }

        if (fuelRequest != null) {
            fuelExtracted = this.takeFromFurnaceSlot(furnace.inventory(), 1, fuelRequest, "furnace fuel");
        }

        furnace.inventory().markDirty();
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("kind", idOf(furnace.state()));
        if (foodRequest != null) {
            result.addProperty("took_food", itemIdOf(foodExtracted));
            result.addProperty("took_food_count", foodRequest.count());
        }
        if (fuelRequest != null) {
            result.addProperty("took_fuel", itemIdOf(fuelExtracted));
            result.addProperty("took_fuel_count", fuelRequest.count());
        }
        return result;
    }

    private JsonObject handleChestInspect() {
        ContainerAccess container = this.requireLookedChestLike();
        JsonObject result = new JsonObject();
        result.addProperty("kind", container.kind());
        result.addProperty("slots_total", container.inventory().size());
        result.addProperty("slots_used", this.countUsedSlots(container.inventory()));
        result.add("items", summarizeInventory(container.inventory()));
        return result;
    }

    private JsonObject handleChestPlace(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        this.stopActiveMovement();

        ContainerAccess container = this.requireLookedChestLike();
        TransferRequest transfer = this.requireTransferRequest(request, "item", "count");
        this.ensureRobotHasRequestedItems(transfer);

        ItemStack toInsert = new ItemStack(transfer.item(), transfer.count());
        if (container.inventory() instanceof SidedInventory sidedInventory && !sidedInventory.canInsert(0, toInsert, Direction.UP)) {
            throw fail("invalid_item", transfer.itemId() + " cannot be placed in the " + container.label());
        }
        if (!canInsertIntoInventory(copyInventory(container.inventory()), toInsert.copy())) {
            throw fail("target_full", "The " + container.label() + " does not have enough room for that item");
        }

        ItemStack removed = this.removeFromRobotInventory(transfer.item(), transfer.count());
        if (!insertIntoInventory(container.inventory(), removed.copy())) {
            throw fail("target_full", "The " + container.label() + " does not have enough room for that item");
        }

        container.inventory().markDirty();
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("kind", container.kind());
        result.addProperty("placed", transfer.itemId());
        result.addProperty("count", transfer.count());
        return result;
    }

    private JsonObject handleChestTake(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
        this.cancelPendingUse();
        this.stopActiveMovement();

        ContainerAccess container = this.requireLookedChestLike();
        TransferRequest transfer = this.requireTransferRequest(request, "item", "count");
        int available = countMatchingItems(container.inventory(), transfer.item());
        if (available < transfer.count()) {
            throw fail("target_empty", "The " + container.label() + " does not contain enough " + transfer.itemId());
        }

        ItemStack toInsert = new ItemStack(transfer.item(), transfer.count());
        if (!canInsertIntoInventory(copyInventory(this.robotInventory), toInsert.copy())) {
            throw fail("no_inventory_space", "The robot hotbar does not have enough room for that item");
        }

        ItemStack removed = removeFromInventory(container.inventory(), transfer.item(), transfer.count());
        if (!insertIntoInventory(this.robotInventory, removed.copy())) {
            throw fail("no_inventory_space", "The robot hotbar does not have enough room for that item");
        }

        container.inventory().markDirty();
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("kind", container.kind());
        result.addProperty("took", transfer.itemId());
        result.addProperty("count", transfer.count());
        return result;
    }

    private boolean isLookingAtCraftingTable() {
        BlockHitResult hit = this.raycastBlock();
        return hit != null && this.getEntityWorld().getBlockState(hit.getBlockPos()).isOf(Blocks.CRAFTING_TABLE);
    }

    private FurnaceAccess requireLookedFurnace() {
        BlockHitResult hit = this.requireLookedBlock();
        BlockState state = this.getEntityWorld().getBlockState(hit.getBlockPos());
        BlockEntity blockEntity = this.getEntityWorld().getBlockEntity(hit.getBlockPos());
        if (blockEntity instanceof AbstractFurnaceBlockEntity furnace) {
            return new FurnaceAccess(state, furnace, hit.getBlockPos().toImmutable());
        }

        throw fail("wrong_block", "Not looking at furnace, blast furnace, or smoker");
    }

    private ContainerAccess requireLookedChestLike() {
        BlockHitResult blockHit = this.raycastBlock();
        double reach = blockHit == null
            ? MineBotMod.INTERACTION_REACH_BLOCKS
            : this.getCommandRayStart().distanceTo(blockHit.getPos());
        EntityHitResult entityHit = this.raycastCrosshairEntity(reach);
        if (entityHit != null && entityHit.getEntity() instanceof VehicleInventory vehicleInventory) {
            Entity entity = entityHit.getEntity();
            String label = entity instanceof AbstractMinecartEntity ? "minecart" : "boat";
            return new ContainerAccess(label, Registries.ENTITY_TYPE.getId(entity.getType()).toString(), vehicleInventory);
        }

        BlockHitResult hit = this.requireLookedBlock();
        BlockPos pos = hit.getBlockPos();
        BlockState state = this.getEntityWorld().getBlockState(pos);

        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Inventory inventory = ChestBlock.getInventory(chestBlock, state, this.getEntityWorld(), pos, false);
            if (inventory == null) {
                throw fail("interaction_unavailable", "The chest cannot be accessed right now");
            }
            return new ContainerAccess("chest", idOf(state), inventory);
        }

        String label = null;
        if (state.isOf(Blocks.BARREL)) {
            label = "barrel";
        } else if (state.getBlock() instanceof ShulkerBoxBlock) {
            label = "shulker box";
        } else if (state.isOf(Blocks.HOPPER)) {
            label = "hopper";
        } else if (state.isOf(Blocks.DROPPER)) {
            label = "dropper";
        } else if (state.isOf(Blocks.DISPENSER)) {
            label = "dispenser";
        }

        if (label != null) {
            BlockEntity blockEntity = this.getEntityWorld().getBlockEntity(pos);
            if (blockEntity instanceof Inventory inventory) {
                return new ContainerAccess(label, idOf(state), inventory);
            }
        }

        throw fail("wrong_block", "Not looking at a chest, barrel, shulker box, hopper, dropper, dispenser, storage minecart, or chest boat");
    }

    private BlockHitResult requireLookedBlock() {
        BlockHitResult hit = this.raycastBlock();
        if (hit == null) {
            throw fail("not_looking_at_block", "The robot is not looking at a block");
        }
        return hit;
    }

    private CraftPlan findCraftPlan(Item targetItem, int gridSize) {
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return null;
        }

        for (RecipeEntry<CraftingRecipe> craftingEntry : findCraftingRecipes(serverWorld, targetItem)) {
            CraftPlan plan;
            try {
                plan = this.tryBuildCraftPlan(craftingEntry, craftingEntry.value(), serverWorld, gridSize);
            } catch (MineBotCommandException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                MineBotMod.LOGGER.debug("Skipping recipe {} after planning failure", craftingEntry.id(), exception);
                continue;
            }
            if (plan != null) {
                return plan;
            }
        }

        return null;
    }

    private boolean hasCraftingRecipeFitting(Item targetItem, int gridSize) {
        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }

        for (RecipeEntry<CraftingRecipe> craftingEntry : findCraftingRecipes(serverWorld, targetItem)) {
            IngredientPlacement placement = craftingEntry.value().getIngredientPlacement();
            if (!placement.hasNoPlacement() && resolveCraftGridSlots(craftingEntry.value(), placement, gridSize) != null) {
                return true;
            }
        }

        return false;
    }

    private static List<RecipeEntry<CraftingRecipe>> findCraftingRecipes(ServerWorld serverWorld, Item targetItem) {
        List<RecipeEntry<CraftingRecipe>> recipes = new ArrayList<>();
        for (RecipeEntry<?> entry : serverWorld.getRecipeManager().values()) {
            if (!(entry.value() instanceof CraftingRecipe recipe)) {
                continue;
            }

            ItemStack preview;
            try {
                preview = recipe.craft(CraftingRecipeInput.create(3, 3, emptyCraftingGrid()), serverWorld.getRegistryManager());
            } catch (MineBotCommandException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                MineBotMod.LOGGER.debug("Skipping recipe {} while planning craft", entry.id(), exception);
                continue;
            }
            if (!preview.isOf(targetItem)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            RecipeEntry<CraftingRecipe> craftingEntry = (RecipeEntry<CraftingRecipe>) entry;
            recipes.add(craftingEntry);
        }
        return recipes;
    }

    private static int[] resolveCraftGridSlots(CraftingRecipe recipe, IngredientPlacement placement, int gridSize) {
        List<Ingredient> ingredients = placement.getIngredients();
        IntList placementSlots = placement.getPlacementSlots();
        if (ingredients.isEmpty()) {
            return null;
        }

        int[] gridSlots = new int[ingredients.size()];
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            int width = shapedRecipe.getWidth();
            int height = shapedRecipe.getHeight();
            if (width > gridSize || height > gridSize || placementSlots.size() != width * height) {
                return null;
            }

            Arrays.fill(gridSlots, -1);
            for (int patternSlot = 0; patternSlot < placementSlots.size(); patternSlot++) {
                int ingredientIndex = placementSlots.getInt(patternSlot);
                if (ingredientIndex >= 0 && ingredientIndex < gridSlots.length) {
                    gridSlots[ingredientIndex] = patternSlot % width + (patternSlot / width) * gridSize;
                }
            }

            for (int gridSlot : gridSlots) {
                if (gridSlot < 0) {
                    return null;
                }
            }
            return gridSlots;
        }

        if (ingredients.size() > gridSize * gridSize || ingredients.size() != placementSlots.size()) {
            return null;
        }

        for (int ingredientIndex = 0; ingredientIndex < gridSlots.length; ingredientIndex++) {
            gridSlots[ingredientIndex] = placementSlots.getInt(ingredientIndex);
            if (gridSlots[ingredientIndex] < 0 || gridSlots[ingredientIndex] >= gridSize * gridSize) {
                return null;
            }
        }
        return gridSlots;
    }

    private CraftPlan tryBuildCraftPlan(RecipeEntry<CraftingRecipe> entry, CraftingRecipe recipe, ServerWorld serverWorld, int gridSize) {
        IngredientPlacement placement = recipe.getIngredientPlacement();
        if (placement.hasNoPlacement()) {
            return null;
        }

        List<Ingredient> ingredients = placement.getIngredients();
        int[] gridSlots = resolveCraftGridSlots(recipe, placement, gridSize);
        if (gridSlots == null) {
            return null;
        }

        ItemStack[] grid = new ItemStack[gridSize * gridSize];
        Arrays.fill(grid, ItemStack.EMPTY);
        int[] usedCounts = new int[this.robotInventory.size()];

        if (!this.assignCraftIngredients(recipe, ingredients, gridSlots, gridSize, 0, usedCounts, grid, serverWorld)) {
            return null;
        }

        CraftingRecipeInput input = CraftingRecipeInput.create(gridSize, gridSize, toStackList(grid));
        if (!recipe.matches(input, serverWorld)) {
            return null;
        }

        ItemStack result = recipe.craft(input, serverWorld.getRegistryManager());
        if (result.isEmpty()) {
            return null;
        }

        List<ItemStack> remainders = copyStacks(recipe.getRecipeRemainders(input));
        if (!this.canStoreCraftOutputs(usedCounts, result, remainders)) {
            throw fail("no_inventory_space", "The robot hotbar does not have room for the crafted output");
        }

        return new CraftPlan(entry.id().getValue(), usedCounts, result.copy(), remainders);
    }

    private boolean assignCraftIngredients(
        CraftingRecipe recipe,
        List<Ingredient> ingredients,
        int[] gridSlots,
        int gridSize,
        int ingredientIndex,
        int[] usedCounts,
        ItemStack[] grid,
        ServerWorld serverWorld
    ) {
        if (ingredientIndex >= ingredients.size()) {
            return recipe.matches(CraftingRecipeInput.create(gridSize, gridSize, toStackList(grid)), serverWorld);
        }

        Ingredient ingredient = ingredients.get(ingredientIndex);
        int gridSlot = gridSlots[ingredientIndex];

        for (int inventorySlot = 0; inventorySlot < this.robotInventory.size(); inventorySlot++) {
            ItemStack source = this.robotInventory.getStack(inventorySlot);
            if (source.isEmpty() || usedCounts[inventorySlot] >= source.getCount()) {
                continue;
            }

            ItemStack single = source.copyWithCount(1);
            if (!ingredient.test(single)) {
                continue;
            }

            usedCounts[inventorySlot]++;
            grid[gridSlot] = single;

            if (this.assignCraftIngredients(recipe, ingredients, gridSlots, gridSize, ingredientIndex + 1, usedCounts, grid, serverWorld)) {
                return true;
            }

            usedCounts[inventorySlot]--;
            grid[gridSlot] = ItemStack.EMPTY;
        }

        return false;
    }

    private boolean canStoreCraftOutputs(int[] usedCounts, ItemStack result, List<ItemStack> remainders) {
        List<ItemStack> snapshot = copyInventory(this.robotInventory);
        consumeInventoryUses(snapshot, usedCounts);
        if (!canInsertIntoInventory(snapshot, result.copy())) {
            return false;
        }

        for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty() && !canInsertIntoInventory(snapshot, remainder.copy())) {
                return false;
            }
        }

        return true;
    }

    private void applyCraftPlan(CraftPlan plan) {
        consumeInventoryUses(this.robotInventory, plan.usedCounts());
        if (!insertIntoInventory(this.robotInventory, plan.result().copy())) {
            throw fail("no_inventory_space", "The robot hotbar does not have room for the crafted output");
        }

        for (ItemStack remainder : plan.remainders()) {
            if (!remainder.isEmpty() && !insertIntoInventory(this.robotInventory, remainder.copy())) {
                throw fail("no_inventory_space", "The robot hotbar does not have room for the crafting remainders");
            }
        }

        this.syncRobotInventory();
    }

    private TransferRequest requireTransferRequest(JsonObject request, String itemKey, String countKey) {
        TransferRequest transfer = this.readTransferRequest(request, itemKey, null, countKey, null);
        if (transfer == null) {
            throw fail("invalid_request", "Missing required field '" + itemKey + "'");
        }
        return transfer;
    }

    private TransferRequest readTransferRequest(
        JsonObject request,
        String primaryItemKey,
        String aliasItemKey,
        String primaryCountKey,
        String aliasCountKey
    ) {
        String itemId = readOptionalString(request, primaryItemKey, aliasItemKey);
        if (itemId == null) {
            return null;
        }

        int count = readOptionalInt(request, primaryCountKey, aliasCountKey, 1);
        if (count <= 0) {
            throw fail("invalid_request", "Item counts must be at least 1");
        }

        Item item = requireRegisteredItem(itemId);
        return new TransferRequest(item, itemIdOf(new ItemStack(item)), count);
    }

    private void ensureRobotHasRequestedItems(TransferRequest... requests) {
        Map<Item, Integer> requiredCounts = new LinkedHashMap<>();
        for (TransferRequest request : requests) {
            if (request == null) {
                continue;
            }
            requiredCounts.merge(request.item(), request.count(), Integer::sum);
        }

        for (Map.Entry<Item, Integer> entry : requiredCounts.entrySet()) {
            int available = countMatchingItems(this.robotInventory, entry.getKey());
            if (available < entry.getValue()) {
                throw fail(
                    "missing_item",
                    "The robot hotbar does not contain enough " + itemIdOf(new ItemStack(entry.getKey()))
                );
            }
        }
    }

    private void ensureSlotCanAccept(Inventory inventory, int slot, Item item, int count, String label) {
        ItemStack current = inventory.getStack(slot);
        if (current.isEmpty()) {
            int limit = Math.min(inventory.getMaxCount(new ItemStack(item)), new ItemStack(item).getMaxCount());
            if (count > limit) {
                throw fail("target_full", "The " + label + " slot cannot hold that many items");
            }
            return;
        }

        if (!current.isOf(item)) {
            throw fail("target_full", "The " + label + " slot already contains " + itemIdOf(current));
        }

        int limit = Math.min(inventory.getMaxCount(current), current.getMaxCount());
        if (current.getCount() + count > limit) {
            throw fail("target_full", "The " + label + " slot does not have enough room");
        }
    }

    private void moveFromRobotInventoryToSlot(Inventory targetInventory, int slot, Item item, int count) {
        ItemStack moved = this.removeFromRobotInventory(item, count);
        ItemStack current = targetInventory.getStack(slot);
        if (current.isEmpty()) {
            targetInventory.setStack(slot, moved.copy());
        } else {
            current.increment(moved.getCount());
        }
    }

    private ItemStack removeFromRobotInventory(Item item, int count) {
        return removeFromInventory(this.robotInventory, item, count);
    }

    private ItemStack takeFromFurnaceSlot(Inventory furnaceInventory, int slot, TransferRequest request, String label) {
        ItemStack current = furnaceInventory.getStack(slot);
        if (current.isEmpty()) {
            throw fail("target_empty", "The " + label + " slot is empty");
        }

        if (request.item() != null && !current.isOf(request.item())) {
            throw fail("target_empty", "The " + label + " slot does not contain " + request.itemId());
        }

        if (current.getCount() < request.count()) {
            throw fail("target_empty", "The " + label + " slot does not contain enough items");
        }

        ItemStack extracted = current.copyWithCount(request.count());
        if (!canInsertIntoInventory(copyInventory(this.robotInventory), extracted.copy())) {
            throw fail("no_inventory_space", "The robot hotbar does not have enough room for that item");
        }

        furnaceInventory.removeStack(slot, request.count());
        if (!insertIntoInventory(this.robotInventory, extracted.copy())) {
            throw fail("no_inventory_space", "The robot hotbar does not have enough room for that item");
        }
        return extracted;
    }

    private ItemStack takeFromFurnaceFood(Inventory furnaceInventory, TransferRequest request) {
        if (this.canTakeFromFurnaceFoodSlot(furnaceInventory.getStack(2), request)) {
            return this.takeFromFurnaceSlot(furnaceInventory, 2, request, "furnace output");
        }

        if (this.canTakeFromFurnaceFoodSlot(furnaceInventory.getStack(0), request)) {
            return this.takeFromFurnaceSlot(furnaceInventory, 0, request, "furnace input");
        }

        if (request.item() == null) {
            throw fail("target_empty", "The furnace does not contain any smelted or smeltable item");
        }

        throw fail("target_empty", "The furnace does not contain enough " + request.itemId());
    }

    private boolean canTakeFromFurnaceFoodSlot(ItemStack stack, TransferRequest request) {
        if (stack.isEmpty()) {
            return false;
        }

        if (request.item() != null && !stack.isOf(request.item())) {
            return false;
        }

        return stack.getCount() >= request.count();
    }

    private void syncRobotInventory() {
        this.robotInventory.markDirty();
        this.syncEquippedStack();
    }

    private static JsonObject describeStack(ItemStack stack) {
        JsonObject description = new JsonObject();
        description.addProperty("item", itemIdOf(stack));
        description.addProperty("count", stack.getCount());
        description.addProperty("empty", stack.isEmpty());
        return description;
    }

    private static JsonObject summarizeInventory(Inventory inventory) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(itemIdOf(stack), stack.getCount(), Integer::sum);
        }

        JsonObject items = new JsonObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            items.addProperty(entry.getKey(), entry.getValue());
        }
        return items;
    }

    private static int countUsedSlots(Inventory inventory) {
        int used = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (!inventory.getStack(slot).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    private static int countMatchingItems(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ItemStack removeFromInventory(Inventory inventory, Item item, int count) {
        int remaining = count;
        ItemStack removed = new ItemStack(item, 0);

        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isOf(item)) {
                continue;
            }

            int moved = Math.min(remaining, stack.getCount());
            if (moved <= 0) {
                continue;
            }

            stack.decrement(moved);
            removed.increment(moved);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            remaining -= moved;
        }

        if (remaining > 0) {
            throw fail("missing_item", "Inventory did not contain enough " + itemIdOf(new ItemStack(item)));
        }

        inventory.markDirty();
        return removed;
    }

    private static boolean insertIntoInventory(Inventory inventory, ItemStack stack) {
        return canInsertIntoInventory(new InventoryView(inventory), stack, true);
    }

    private static boolean canInsertIntoInventory(List<ItemStack> stacks, ItemStack stack) {
        return canInsertIntoInventory(new InventoryView(stacks), stack, false);
    }

    private static boolean canInsertIntoInventory(InventoryView inventory, ItemStack stack, boolean mutate) {
        if (stack.isEmpty()) {
            return true;
        }

        for (int slot = 0; slot < inventory.size() && !stack.isEmpty(); slot++) {
            ItemStack current = inventory.getStack(slot);
            if (current.isEmpty() || !ItemStack.areItemsAndComponentsEqual(current, stack)) {
                continue;
            }

            int limit = Math.min(current.getMaxCount(), inventory.getMaxCount(current));
            int move = Math.min(limit - current.getCount(), stack.getCount());
            if (move <= 0) {
                continue;
            }

            if (mutate) {
                current.increment(move);
            } else {
                inventory.setStack(slot, current.copyWithCount(current.getCount() + move));
            }
            stack.decrement(move);
        }

        for (int slot = 0; slot < inventory.size() && !stack.isEmpty(); slot++) {
            ItemStack current = inventory.getStack(slot);
            if (!current.isEmpty()) {
                continue;
            }

            int limit = Math.min(stack.getMaxCount(), inventory.getMaxCount(stack));
            int move = Math.min(limit, stack.getCount());
            if (move <= 0) {
                continue;
            }

            if (mutate) {
                inventory.setStack(slot, stack.copyWithCount(move));
            } else {
                inventory.setStack(slot, stack.copyWithCount(move));
            }
            stack.decrement(move);
        }

        if (mutate) {
            inventory.markDirty();
        }
        return stack.isEmpty();
    }

    private static List<ItemStack> copyInventory(Inventory inventory) {
        List<ItemStack> snapshot = new ArrayList<>(inventory.size());
        for (int slot = 0; slot < inventory.size(); slot++) {
            snapshot.add(inventory.getStack(slot).copy());
        }
        return snapshot;
    }

    private static List<ItemStack> copyStacks(Iterable<ItemStack> stacks) {
        List<ItemStack> copies = new ArrayList<>();
        for (ItemStack stack : stacks) {
            copies.add(stack.copy());
        }
        return copies;
    }

    private static void consumeInventoryUses(Inventory inventory, int[] usedCounts) {
        for (int slot = 0; slot < usedCounts.length; slot++) {
            int count = usedCounts[slot];
            if (count <= 0) {
                continue;
            }

            ItemStack stack = inventory.getStack(slot);
            stack.decrement(count);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
        }
        inventory.markDirty();
    }

    private static void consumeInventoryUses(List<ItemStack> inventory, int[] usedCounts) {
        for (int slot = 0; slot < usedCounts.length; slot++) {
            int count = usedCounts[slot];
            if (count <= 0) {
                continue;
            }

            ItemStack stack = inventory.get(slot);
            if (stack.isEmpty()) {
                continue;
            }

            int nextCount = stack.getCount() - count;
            inventory.set(slot, nextCount > 0 ? stack.copyWithCount(nextCount) : ItemStack.EMPTY);
        }
    }

    private static List<ItemStack> toStackList(ItemStack[] grid) {
        List<ItemStack> stacks = new ArrayList<>(grid.length);
        for (ItemStack stack : grid) {
            stacks.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        return stacks;
    }

    private static List<ItemStack> emptyCraftingGrid() {
        ItemStack[] grid = new ItemStack[9];
        Arrays.fill(grid, ItemStack.EMPTY);
        return toStackList(grid);
    }

    private static Item requireRegisteredItem(String itemId) {
        Identifier identifier = Identifier.tryParse(itemId);
        if (identifier == null || !Registries.ITEM.containsId(identifier)) {
            throw fail("invalid_item", "Unknown item id: " + itemId);
        }
        return Registries.ITEM.get(identifier);
    }

    private static String readString(JsonObject request, String key) {
        if (!request.has(key)) {
            throw fail("invalid_request", "Command is missing required field '" + key + "'");
        }

        String value = request.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw fail("invalid_request", "Field '" + key + "' cannot be empty");
        }
        return value;
    }

    private static String readOptionalString(JsonObject request, String primaryKey, String aliasKey) {
        if (primaryKey != null && request.has(primaryKey)) {
            return readString(request, primaryKey);
        }
        if (aliasKey != null && request.has(aliasKey)) {
            return readString(request, aliasKey);
        }
        return null;
    }

    private static int readOptionalInt(JsonObject request, String primaryKey, String aliasKey, int defaultValue) {
        if (primaryKey != null && request.has(primaryKey)) {
            return (int) request.get(primaryKey).getAsDouble();
        }
        if (aliasKey != null && request.has(aliasKey)) {
            return (int) request.get(aliasKey).getAsDouble();
        }
        return defaultValue;
    }

    private static double readOptionalDouble(JsonObject request, String primaryKey, String aliasKey, double defaultValue) {
        if (primaryKey != null && request.has(primaryKey)) {
            return request.get(primaryKey).getAsDouble();
        }
        if (aliasKey != null && request.has(aliasKey)) {
            return request.get(aliasKey).getAsDouble();
        }
        return defaultValue;
    }

    private static MineBotCommandException fail(String code, String message) {
        return new MineBotCommandException(code, message);
    }

    private void requireEnergy() {
        if (!this.hasAvailableEnergy()) {
            throw fail("out_of_energy", "The MineBot is out of blaze powder energy");
        }
    }

    private boolean hasAvailableEnergy() {
        return this.energyMilliblocks > 0 || !this.fuelInventory.getStack(0).isEmpty();
    }

    private int getStoredEnergyMilliblocks() {
        return this.energyMilliblocks + this.fuelInventory.getStack(0).getCount() * MineBotMod.MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER;
    }

    private void tickChunkLoading(ServerWorld serverWorld) {
        boolean busy = this.isBusy();
        if (busy) {
            this.chunkHoldTicks = MineBotChunkLoader.holdTicks();
        } else if (this.chunkHoldTicks > 0) {
            this.chunkHoldTicks--;
        }
        MineBotChunkLoader.get(serverWorld.getServer()).update(this, busy || this.chunkHoldTicks > 0 || this.isInDanger(), busy);
    }

    private void tickRegistry(ServerWorld serverWorld) {
        if (--this.registryUpdateTicks <= 0) {
            this.registryUpdateTicks = REGISTRY_UPDATE_TICKS;
            MineBotRegistry.get(serverWorld.getServer()).update(this);
        }
    }

    /**
     * The robot keeps its area loaded while it is busy, for a while after (chunks.hold_seconds), and while it
     * is in danger, so the robot and its drops are still there for the next order and it never freezes halfway
     * through a fall or a fire.
     */
    private boolean shouldKeepChunksLoaded() {
        return this.isBusy() || this.chunkHoldTicks > 0 || this.isInDanger();
    }

    /** Driven by a program, carrying out an order, or turned evil. */
    private boolean isBusy() {
        return this.isEvil()
            || this.isConnected()
            || this.moveByTarget != null
            || this.moveTarget != null
            || this.breakingPos != null
            || this.isHoldingUse()
            || this.pillaring
            || this.isBridging()
            || this.isFighting()
            || this.seekingAir
            || Math.abs(this.forwardInput) > 0.001F
            || Math.abs(this.sidewaysInput) > 0.001F;
    }

    /** Falling, burning, freezing, in lava, under water or still short of air, or carried off by a current. */
    private boolean isInDanger() {
        return this.isOnFire()
            || this.isInLava()
            || this.getFrozenTicks() > 0
            || this.getAir() < this.getMaxAir()
            || this.isTouchingWater() && this.getVelocity().horizontalLengthSquared() > DRIFT_SPEED_SQUARED
            || !this.isOnGround() && !this.isTouchingWater() && !this.hasVehicle() && !this.isClimbing();
    }

    private void tickEvilTarget(ServerWorld serverWorld) {
        if (!this.isEvil()) {
            if (this.getTarget() instanceof PlayerEntity) {
                this.setTarget(null);
            }
            return;
        }

        PlayerEntity currentTarget = this.getTarget() instanceof PlayerEntity player && this.isValidEvilTarget(player) ? player : null;
        if (currentTarget != null) {
            return;
        }

        PlayerEntity nearest = serverWorld.getPlayers()
            .stream()
            .filter(this::isValidEvilTarget)
            .min(java.util.Comparator.comparingDouble(this::squaredDistanceTo))
            .orElse(null);

        this.setTarget(nearest);
    }

    private boolean isValidEvilTarget(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator();
    }

    private void tickMoveTarget() {
        if (this.moveTarget == null) {
            return;
        }

        if (!this.hasAvailableEnergy()) {
            this.clearPathingTarget();
            this.recordLastMoveResult(false, "The MineBot ran out of blaze powder energy before reaching the destination");
            return;
        }

        if (this.isWithinArrivalRange(this.moveTarget)) {
            // Within the arrival tolerance counts as arrived even when the exact point is blocked.
            this.snapToExactPosition(this.arrivalPosition(this.moveTarget));
            this.clearPathingTarget();
            this.recordLastMoveResult(true, "");
            return;
        }

        if (this.tickMoveStall()) {
            return;
        }

        if (this.getNavigation().isIdle()) {
            if (this.canFinishMoveDirectly(this.moveTarget)) {
                this.moveTargetApproachTicks++;
                if (this.moveTargetApproachTicks > MOVE_TO_MAX_FINAL_APPROACH_TICKS) {
                    this.clearPathingTarget();
                    this.recordLastMoveResult(false, "MineBot could not continue moving to that location");
                    return;
                }

                this.getMoveControl().moveTo(this.moveTarget.x, this.moveTarget.y, this.moveTarget.z, this.moveTargetSpeed);
                return;
            }

            if (!((MineBotNavigation) this.getNavigation()).canPlanFromHere()) {
                return;
            }
            boolean restarted = this.startPathTo(this.moveTarget, this.moveTargetSpeed);
            if (!restarted) {
                this.clearPathingTarget();
                this.recordLastMoveResult(false, "MineBot could not continue moving to that location");
            }
        }
    }

    // True when it handled this tick: planned again after a stall, or gave up.
    private boolean tickMoveStall() {
        if (this.madeMoveProgress()) {
            this.moveStallTicks = 0;
            this.moveStalls = 0;
            return false;
        }
        int limit = (int) Math.ceil(MOVE_TO_STALL_TICKS / Math.max(0.25D, Math.min(1.0D, this.moveTargetSpeed)));
        if (this.isTouchingWater()) {
            limit *= MOVE_TO_WATER_STALL_FACTOR;
        }
        if (++this.moveStallTicks < limit) {
            return false;
        }
        if (!((MineBotNavigation) this.getNavigation()).canPlanFromHere() && this.moveStallTicks < limit + MOVE_TO_MAX_AIRBORNE_TICKS) {
            // Mid-jump or shoved into the air: plan again once it lands.
            return false;
        }

        this.moveStallTicks = 0;
        if (++this.moveStalls >= MOVE_TO_MAX_STALLS) {
            String blocker = this.describeBlocker();
            this.clearPathingTarget();
            this.recordLastMoveResult(false, "Stuck at " + formatPosition(this.getEntityPos()) + blocker + ", and found no way round");
            return true;
        }

        this.getNavigation().stop();
        if (!this.startPathTo(this.moveTarget, this.moveTargetSpeed) && !this.canFinishMoveDirectly(this.moveTarget)) {
            this.clearPathingTarget();
            this.recordLastMoveResult(false, "MineBot could not continue moving to that location");
        }
        return true;
    }

    // Headway is reaching a spot of the path that this move has not reached before, or, on the last stretch
    // without a path, getting closer to the target. A new path starts where the robot already is.
    private boolean madeMoveProgress() {
        Path path = this.getNavigation().getCurrentPath();
        if (path != null && !path.isFinished()) {
            if (path != this.moveWatchedPath) {
                this.moveWatchedPath = path;
                this.moveWatchedIndex = path.getCurrentNodeIndex();
                this.moveReachedNodes.add(path.getNodePos(Math.min(this.moveWatchedIndex, path.getLength() - 1)).asLong());
            }
            boolean progress = false;
            for (; this.moveWatchedIndex < path.getCurrentNodeIndex(); this.moveWatchedIndex++) {
                progress |= this.moveReachedNodes.add(path.getNodePos(this.moveWatchedIndex).asLong());
            }
            return progress;
        }

        double distance = this.horizontalDistanceTo(this.moveTarget);
        if (distance < this.moveClosestApproach - MOVE_TO_APPROACH_PROGRESS) {
            this.moveClosestApproach = distance;
            return true;
        }
        return false;
    }

    // What the robot is pressed against: a robot, mob, player or solid entity within a block of it, else the
    // nearest block touching its body above its feet. Empty when nothing is there.
    private String describeBlocker() {
        World world = this.getEntityWorld();
        Box touching = this.getBoundingBox().expand(0.25D, 0.0D, 0.25D);
        // A mob it pushes against shoves it back, so at any moment the two may be up to a block apart. Of
        // those, name the one nearest to where it was heading.
        Path path = this.getNavigation().getCurrentPath();
        Vec3d heading = path != null && !path.isFinished() ? path.getNodePosition(this) : this.moveTarget != null ? this.moveTarget : this.getEntityPos();
        Entity entity = world.getOtherEntities(
                this, this.getBoundingBox().expand(1.0D, 0.5D, 1.0D), other -> !other.isConnectedThroughVehicle(this)
                    && (other.isCollidable(this) || other instanceof LivingEntity living && living.isPushable())
            )
            .stream()
            .min(java.util.Comparator.comparingDouble(other -> other.squaredDistanceTo(heading)))
            .orElse(null);
        if (entity != null) {
            return ", blocked by " + entity.getDisplayName().getString() + " at " + formatPosition(entity.getEntityPos());
        }

        Box body = touching.withMinY(touching.minY + 0.05D);
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(BlockPos.ofFloored(body.minX, body.minY, body.minZ), BlockPos.ofFloored(body.maxX, body.maxY, body.maxZ))) {
            VoxelShape shape = world.getBlockState(pos).getCollisionShape(world, pos);
            if (shape.isEmpty() || shape.getBoundingBoxes().stream().noneMatch(box -> box.offset(pos).intersects(body))) {
                continue;
            }
            double distance = pos.toCenterPos().squaredDistanceTo(this.getX(), this.getBodyY(0.5D), this.getZ());
            if (distance < nearestDistance) {
                nearest = pos.toImmutable();
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return "";
        }
        return ", against " + Registries.BLOCK.getId(world.getBlockState(nearest).getBlock())
            + " at (" + nearest.getX() + ", " + nearest.getY() + ", " + nearest.getZ() + ")";
    }

    private boolean startPathTo(Vec3d target, double speed) {
        // startMovingTo() accepts paths that end next to the target, which is outside MOVE_TO_ARRIVAL_TOLERANCE.
        Path path = this.getNavigation().findPathTo(target.x, target.y, target.z, 0);
        return path != null && this.getNavigation().startMovingAlong(path, speed);
    }

    private boolean canFinishMoveDirectly(Vec3d target) {
        double verticalTolerance = this.isTouchingWater() ? MOVE_TO_AFLOAT_VERTICAL_TOLERANCE : 0.6D;
        return this.horizontalDistanceTo(target) <= MOVE_TO_FINAL_APPROACH_DISTANCE
            && Math.abs(target.y - this.getY()) <= verticalTolerance;
    }

    // A floating robot bobs up to a block above the water block it was sent to, so height is judged loosely there.
    private boolean isWithinArrivalRange(Vec3d target) {
        if (this.isTouchingWater()) {
            return this.horizontalDistanceTo(target) <= MOVE_TO_ARRIVAL_TOLERANCE
                && Math.abs(target.y - this.getY()) <= MOVE_TO_AFLOAT_VERTICAL_TOLERANCE;
        }

        return this.squaredDistanceTo(target.x, target.y, target.z) <= MOVE_TO_ARRIVAL_TOLERANCE * MOVE_TO_ARRIVAL_TOLERANCE;
    }

    private Vec3d arrivalPosition(Vec3d target) {
        return this.isTouchingWater() ? new Vec3d(target.x, this.getY(), target.z) : target;
    }

    private void tickMoveByTarget() {
        if (this.moveByTarget == null) {
            return;
        }

        if (!this.hasAvailableEnergy()) {
            this.finishMoveBy(false, "The MineBot ran out of blaze powder energy before completing the relative move");
            return;
        }

        double horizontalDistance = this.horizontalDistanceTo(this.moveByTarget);
        if (horizontalDistance <= MOVE_BY_ARRIVAL_TOLERANCE) {
            double heightOff = this.moveByTarget.y - this.getY();
            if (heightOff < -MOVE_BY_SNAP_MAX_HEIGHT && this.getVelocity().y < 0.0D && !this.isOnGround()
                && !this.isTouchingWater() && !this.isInLava() && ++this.moveByStallTicks < MOVE_BY_MAX_LANDING_TICKS) {
                // Still dropping onto the target: let it land rather than end the move in the air.
                this.forwardInput = 0.0F;
                this.sidewaysInput = 0.0F;
                return;
            }
            // Snap only across a small height step, where the body's old and new boxes overlap; never through blocks.
            if (Math.abs(heightOff) > MOVE_BY_SNAP_MAX_HEIGHT) {
                this.finishMoveBy(false, String.format(Locale.ROOT,
                    "MineBot reached that X/Z at y=%.1f, but the walkable height there is y=%.1f", this.getY(), this.moveByTarget.y));
                return;
            }
            if (this.canOccupyPosition(this.moveByTarget)) {
                this.refreshPositionAndAngles(this.moveByTarget.x, this.moveByTarget.y, this.moveByTarget.z, this.moveByYaw, this.getPitch());
                this.applyLook(this.moveByYaw, this.getPitch());
            }
            this.finishMoveBy(true, "");
            return;
        }

        if (horizontalDistance < this.moveByLastDistance - MOVE_BY_PROGRESS_EPSILON) {
            this.moveByLastDistance = horizontalDistance;
            this.moveByStallTicks = 0;
        } else {
            this.moveByStallTicks++;
            if (this.moveByStallTicks >= MOVE_BY_MAX_STALL_TICKS) {
                this.finishMoveBy(false, "MineBot could not continue moving to that relative location");
                return;
            }
        }

        this.applyLook(this.moveByYaw, this.getPitch());

        Vec3d delta = this.moveByTarget.subtract(this.getX(), this.moveByTarget.y, this.getZ());
        Vec3d forward = Vec3d.fromPolar(0.0F, this.moveByYaw).normalize();
        Vec3d right = new Vec3d(forward.z, 0.0D, -forward.x);
        this.forwardInput = clampUnit(delta.dotProduct(forward) * this.moveBySpeed);
        this.sidewaysInput = clampUnit(delta.dotProduct(right) * this.moveBySpeed);
    }

    private void tickItemPickup(ServerWorld serverWorld) {
        if (!this.itemPickupEnabled || !this.isAlive() || this.deathTime > 0 || this.isRemoved()) {
            return;
        }

        Box pickupBox = this.getBoundingBox().expand(ITEM_PICKUP_RADIUS, 0.5D, ITEM_PICKUP_RADIUS);
        for (ItemEntity itemEntity : serverWorld.getEntitiesByClass(ItemEntity.class, pickupBox, ItemEntity::isAlive)) {
            if (itemEntity.getItemAge() < ITEM_PICKUP_GRACE_TICKS) {
                continue;
            }

            if (itemEntity.cannotPickup()) {
                continue;
            }

            ItemStack entityStack = itemEntity.getStack();
            if (entityStack.isEmpty()) {
                continue;
            }

            ItemStack remaining = entityStack.copy();
            int originalCount = remaining.getCount();
            insertIntoInventory(this.robotInventory, remaining);

            int pickedUp = originalCount - remaining.getCount();
            if (pickedUp <= 0) {
                continue;
            }

            this.syncRobotInventory();
            this.sendPickup(itemEntity, pickedUp);

            if (remaining.isEmpty()) {
                itemEntity.discard();
            } else {
                itemEntity.setStack(remaining);
            }
        }
    }

    private void tickActiveBreak(ServerWorld serverWorld) {
        if (this.breakingPos == null) {
            return;
        }

        if (!this.hasAvailableEnergy()) {
            this.finishBreakingTask(serverWorld, false, "The MineBot ran out of blaze powder energy");
            return;
        }

        BlockState currentState = serverWorld.getBlockState(this.breakingPos);
        if (currentState.isAir() || currentState.getHardness(serverWorld, this.breakingPos) < 0.0F) {
            this.finishBreakingTask(serverWorld, false, "The target block could not be finished");
            return;
        }

        String failureReason = this.getAttackFailureReason(currentState);
        if (failureReason != null) {
            this.finishBreakingTask(serverWorld, false, failureReason);
            return;
        }

        this.breakingTicksRemaining = Math.max(0, this.breakingTicksRemaining - 1);

        if (this.breakingTicksRemaining > 0 && this.breakingTicksRemaining % 6 == 0) {
            this.swingHand(Hand.MAIN_HAND, true);
        }

        int elapsed = this.breakingTicksTotal - this.breakingTicksRemaining;
        int stage = Math.min(9, MathHelper.floor(elapsed * 10.0F / Math.max(1, this.breakingTicksTotal)));
        serverWorld.setBlockBreakingInfo(this.getId(), this.breakingPos, stage);

        if (this.breakingTicksRemaining > 0) {
            return;
        }

        boolean broken = serverWorld.breakBlock(this.breakingPos, true, this);
        if (!broken) {
            this.finishBreakingTask(serverWorld, false, "The MineBot could not finish breaking that block");
            return;
        }

        this.damageSelectedTool();
        this.finishBreakingTask(serverWorld, true, "");
    }

    private int getBreakDurationTicks(BlockState state, BlockPos pos) {
        float hardness = Math.max(0.0F, state.getHardness(this.getEntityWorld(), pos));
        ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
        float toolSpeed = selected.isEmpty() ? 1.0F : Math.max(1.0F, selected.getMiningSpeedMultiplier(state));
        float duration = Math.max(1.0F, hardness) * 10.0F / Math.max(1.0F, toolSpeed);
        return MathHelper.clamp(MathHelper.ceil(duration), 6, 120);
    }

    private void stopActiveMovement() {
        if (this.isFighting()) {
            this.finishFight("interrupted", "The robot stopped fighting");
        }
        this.clearPillar();
        this.clearBridge();
        this.clearMoveByTarget();
        this.clearPathingTarget();
        this.clearDirectMotion();
    }

    private void clearPathingTarget() {
        this.moveTarget = null;
        this.moveTargetSpeed = 1.0D;
        this.moveTargetApproachTicks = 0;
        this.moveReachedNodes.clear();
        this.moveWatchedPath = null;
        this.moveWatchedIndex = 0;
        this.moveClosestApproach = Double.MAX_VALUE;
        this.moveStallTicks = 0;
        this.moveStalls = 0;
        this.getNavigation().stop();
    }

    private void clearMoveByTarget() {
        this.moveByTarget = null;
        this.moveBySpeed = 1.0D;
        this.moveByLastDistance = Double.MAX_VALUE;
        this.moveByStallTicks = 0;
    }

    private void finishMoveBy(boolean success, String message) {
        this.clearMoveByTarget();
        this.clearDirectMotion();
        this.recordLastMoveResult(success, message);
    }

    private void clearDirectMotion() {
        this.forwardInput = 0.0F;
        this.sidewaysInput = 0.0F;
        Vec3d velocity = this.getVelocity();
        this.setVelocity(0.0D, velocity.y, 0.0D);
        this.velocityDirty = true;
    }

    private void cancelPendingBreak() {
        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            if (this.breakingPos != null) {
                this.finishBreakingTask(serverWorld, false, "The MineBot stopped attacking before the block was broken");
            }
        } else {
            this.clearBreakingTask();
        }
    }

    private void finishBreakingTask(ServerWorld serverWorld, boolean success, String message) {
        if (this.breakingPos != null) {
            serverWorld.setBlockBreakingInfo(this.getId(), this.breakingPos, -1);
        }

        this.clearBreakingTask();
        this.recordLastAttackResult(success, message);
    }

    private void clearBreakingTask() {
        this.breakingPos = null;
        this.breakingTicksRemaining = 0;
        this.breakingTicksTotal = 0;
    }

    private void clearLastAttackResult() {
        this.hasLastAttackResult = false;
        this.lastAttackSucceeded = false;
        this.lastAttackMessage = "";
    }

    private void clearLastMoveResult() {
        this.hasLastMoveResult = false;
        this.lastMoveSucceeded = false;
        this.lastMoveMessage = "";
    }

    private void recordLastMoveResult(boolean success, String message) {
        this.hasLastMoveResult = true;
        this.lastMoveSucceeded = success;
        this.lastMoveMessage = message == null ? "" : message;
    }

    private void recordLastAttackResult(boolean success, String message) {
        this.hasLastAttackResult = true;
        this.lastAttackSucceeded = success;
        this.lastAttackMessage = message == null ? "" : message;
    }

    private String getAttackFailureReason(BlockState state) {
        ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
        if (state.isToolRequired() && (selected.isEmpty() || !selected.isSuitableFor(state))) {
            return "The selected tool cannot break that block";
        }

        if (!selected.isEmpty() && selected.isDamageable() && selected.getDamage() >= selected.getMaxDamage() - 1) {
            return "The selected tool is too damaged to finish breaking that block";
        }

        return null;
    }

    private void damageSelectedTool() {
        ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
        if (selected.isEmpty() || !selected.isDamageable()) {
            return;
        }

        selected.damage(1, this, EquipmentSlot.MAINHAND);
        this.syncEquippedStack();
    }

    private void syncEquippedStack() {
        ItemStack mainHand = this.getMainHandStack();
        ItemStack displayStack;

        if (this.isEvil()) {
            displayStack = new ItemStack(Items.IRON_AXE);
        } else {
            ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
            displayStack = selected.isEmpty() ? ItemStack.EMPTY : selected.copyWithCount(1);
        }

        if (!ItemStack.areItemsAndComponentsEqual(mainHand, displayStack) || mainHand.getCount() != displayStack.getCount()) {
            this.setStackInHand(Hand.MAIN_HAND, displayStack);
        }
    }

    private String getSelectedItemId() {
        return itemIdOf(this.robotInventory.getStack(this.getSelectedSlot()));
    }

    private String getLookedBlockId() {
        BlockHitResult hit = this.raycastBlock(MineBotMod.INTERACTION_REACH_BLOCKS);
        if (hit == null) {
            return "minecraft:air";
        }

        return idOf(this.getEntityWorld().getBlockState(hit.getBlockPos()));
    }

    private BlockHitResult raycastBlock() {
        return this.raycastBlock(MineBotMod.INTERACTION_REACH_BLOCKS);
    }

    private BlockHitResult raycastCameraInspect() {
        Vec3d start = this.getCommandRayStart();
        Vec3d end = start.add(this.getRotationVec(1.0F).multiply(MineBotMod.CAMERA_TYPE_MAX_VISION_BLOCKS));
        HitResult hitResult = this.getEntityWorld().raycast(
            new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, this)
        );
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockHitResult blockHitResult = (BlockHitResult) hitResult;
        BlockPos hitPos = blockHitResult.getBlockPos();
        BlockState blockState = this.getEntityWorld().getBlockState(hitPos);
        FluidState fluidState = this.getEntityWorld().getFluidState(hitPos);
        return blockState.isAir() && fluidState.isEmpty() ? null : blockHitResult;
    }

    private EntityHitResult raycastRideableEntity() {
        Vec3d start = this.getCommandRayStart();
        Vec3d end = start.add(this.getRotationVec(1.0F).multiply(MineBotMod.INTERACTION_REACH_BLOCKS));
        Box searchBox = this.getBoundingBox().stretch(end.subtract(start)).expand(1.0D);
        return ProjectileUtil.raycast(
            this,
            start,
            end,
            searchBox,
            entity -> entity != this && entity.isAlive() && entity != this.getVehicle(),
            MineBotMod.INTERACTION_REACH_BLOCKS * MineBotMod.INTERACTION_REACH_BLOCKS
        );
    }

    private BlockHitResult raycastBlock(double reach) {
        Vec3d start = this.getCommandRayStart();
        Vec3d end = start.add(this.getRotationVec(1.0F).multiply(reach));
        HitResult hitResult = this.getEntityWorld().raycast(
            new RaycastContext(start, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, this)
        );
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockHitResult blockHitResult = (BlockHitResult) hitResult;
        return this.getEntityWorld().getBlockState(blockHitResult.getBlockPos()).isAir() ? null : blockHitResult;
    }

    Vec3d getCommandRayStart() {
        return this.isCrouched()
            ? new Vec3d(this.getX(), this.getY() + this.getStandingEyeHeight(), this.getZ())
            : this.getEyePos();
    }

    // Turn so the crosshair ray passes through target.
    private void lookAtPoint(Vec3d target) {
        Vec3d start = this.getCommandRayStart();
        double deltaX = target.x - start.x;
        double deltaY = target.y - start.y;
        double deltaZ = target.z - start.z;
        double horizontal = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = horizontal < 1.0E-6D ? this.getYaw() : (float) (MathHelper.atan2(deltaZ, deltaX) * MathHelper.DEGREES_PER_RADIAN) - 90.0F;
        float pitch = horizontal < 1.0E-6D && Math.abs(deltaY) < 1.0E-6D
            ? this.getPitch()
            : (float) -(MathHelper.atan2(deltaY, horizontal) * MathHelper.DEGREES_PER_RADIAN);
        this.applyLook(yaw, pitch);
    }

    private BlockHitResult resolveUseHit(BlockHitResult hit, ItemStack selected) {
        if (!(selected.getItem() instanceof BlockItem)) {
            return hit;
        }

        return this.normalizeReplaceablePlacementHit(hit, selected);
    }

    private BlockHitResult normalizeReplaceablePlacementHit(BlockHitResult hit, ItemStack selected) {
        if (hit == null) {
            return null;
        }

        World world = this.getEntityWorld();
        float yaw = roundAngle(this.getYaw());
        Direction horizontalFacing = this.getHorizontalFacing();
        BlockState hitState = world.getBlockState(hit.getBlockPos());
        MineBotPlacementContext directContext = new MineBotPlacementContext(world, selected, hit, horizontalFacing, yaw, roundAngle(this.getPitch()));
        if (!hitState.isAir() && hitState.canReplace(directContext)) {
            return hit;
        }

        BlockPos adjacentPos = hit.getBlockPos().offset(hit.getSide());
        BlockState adjacentState = world.getBlockState(adjacentPos);
        if (adjacentState.isAir()) {
            return hit;
        }

        BlockHitResult adjacentHit = this.createFaceHit(adjacentPos, hit.getSide());
        MineBotPlacementContext adjacentContext = new MineBotPlacementContext(world, selected, adjacentHit, horizontalFacing, yaw, roundAngle(this.getPitch()));
        return adjacentState.canReplace(adjacentContext) ? adjacentHit : hit;
    }

    private BlockHitResult createFaceHit(BlockPos pos, Direction side) {
        Vec3d hitPos = Vec3d.ofCenter(pos).add(
            side.getOffsetX() * 0.5D,
            side.getOffsetY() * 0.5D,
            side.getOffsetZ() * 0.5D
        );
        return new BlockHitResult(hitPos, side, pos, false);
    }

    private Map<BlockPos, BlockState> captureInteractionStates(BlockHitResult hit) {
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        this.addInteractionState(states, hit.getBlockPos());
        this.addInteractionState(states, hit.getBlockPos().offset(hit.getSide()));
        this.addInteractionState(states, hit.getBlockPos().up());
        return states;
    }

    private void addInteractionState(Map<BlockPos, BlockState> states, BlockPos pos) {
        if (!states.containsKey(pos) && this.getEntityWorld().isInBuildLimit(pos)) {
            states.put(pos.toImmutable(), this.getEntityWorld().getBlockState(pos));
        }
    }

    private BlockPos findAffectedInteractionPos(Map<BlockPos, BlockState> beforeStates, BlockHitResult hit) {
        for (Map.Entry<BlockPos, BlockState> entry : beforeStates.entrySet()) {
            BlockState currentState = this.getEntityWorld().getBlockState(entry.getKey());
            if (!currentState.equals(entry.getValue())) {
                return entry.getKey();
            }
        }

        return hit.getBlockPos();
    }

    private JsonObject tryBasicInteraction(BlockHitResult hit) {
        BlockState state = this.getEntityWorld().getBlockState(hit.getBlockPos());
        BlockState updated = null;

        if (state.contains(Properties.OPEN)) {
            updated = state.with(Properties.OPEN, !state.get(Properties.OPEN));
        } else if (state.contains(Properties.POWERED)) {
            updated = state.with(Properties.POWERED, !state.get(Properties.POWERED));
        }

        if (updated == null) {
            return null;
        }

        this.getEntityWorld().setBlockState(hit.getBlockPos(), updated);
        JsonObject result = new JsonObject();
        result.addProperty("used", idOf(updated));
        result.addProperty("pos", hit.getBlockPos().toShortString());
        return result;
    }

    private Vec3d resolveMoveTarget(double targetX, double targetZ, boolean surfaceFallback) {
        int blockX = MathHelper.floor(targetX);
        int blockZ = MathHelper.floor(targetZ);
        int currentY = MathHelper.floor(this.getY());
        BlockPos standingPos = this.findWalkableY(blockX, currentY, blockZ, 12);

        // The heightmap top is the open surface, except under a ceiling: in the Nether it is the top of the bedrock roof.
        if (standingPos == null && surfaceFallback && !this.getEntityWorld().getDimension().hasCeiling()) {
            int topY = this.getEntityWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            standingPos = this.findWalkableY(blockX, topY, blockZ, 8);
        }

        if (standingPos == null) {
            throw new IllegalArgumentException("MineBot could not find a walkable Y height within 12 blocks of its own at that X/Z location");
        }

        return new Vec3d(targetX, this.standingFeetY(standingPos), targetZ);
    }

    private Vec3d resolveMoveTarget(double targetX, int targetY, double targetZ) {
        BlockPos standingPos = this.findWalkableY(MathHelper.floor(targetX), targetY, MathHelper.floor(targetZ), 12);
        if (standingPos == null) {
            throw new IllegalArgumentException("MineBot could not find a walkable Y height near Y " + targetY + " at that X/Z location");
        }

        return new Vec3d(targetX, this.standingFeetY(standingPos), targetZ);
    }

    private boolean snapToExactPosition(Vec3d target) {
        Vec3d exact = new Vec3d(target.x, target.y, target.z);
        if (!this.canOccupyPosition(exact) || this.isOccupiedByOther(exact)) {
            return false;
        }

        this.refreshPositionAndAngles(exact.x, exact.y, exact.z, this.getYaw(), this.getPitch());
        this.applyLook(this.getYaw(), this.getPitch());
        this.setVelocity(0.0D, this.getVelocity().y, 0.0D);
        this.velocityDirty = true;
        return true;
    }

    private Vec3d resolveCenteredPosition() {
        double currentY = this.getY();
        int blockX = MathHelper.floor(this.getX());
        int blockZ = MathHelper.floor(this.getZ());
        double centerX = blockX + 0.5D;
        double centerZ = blockZ + 0.5D;
        Vec3d exact = new Vec3d(centerX, currentY, centerZ);
        if (this.canOccupyPosition(exact)) {
            return exact;
        }

        throw fail("movement_failed", "MineBot could not center exactly on this block");
    }

    private BlockPos findWalkableY(int x, int startY, int z, int searchDistance) {
        for (int offset = 0; offset <= searchDistance; offset++) {
            int upY = startY + offset;
            if (this.isStandableAt(x, upY, z)) {
                return new BlockPos(x, upY, z);
            }

            if (offset == 0) {
                continue;
            }

            int downY = startY - offset;
            if (this.isStandableAt(x, downY, z)) {
                return new BlockPos(x, downY, z);
            }
        }

        return null;
    }

    private BlockPos getSupportBlockPos() {
        return BlockPos.ofFloored(this.getX(), this.getBoundingBox().minY - 0.2D, this.getZ());
    }

    private boolean isStandableAt(int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.up();
        BlockPos below = feet.down();
        World world = this.getEntityWorld();

        if (!world.isInBuildLimit(feet) || !world.isInBuildLimit(head) || !world.isInBuildLimit(below)) {
            return false;
        }

        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        BlockState belowState = world.getBlockState(below);

        boolean floating = feetState.getFluidState().isIn(FluidTags.WATER) && headState.getFluidState().isEmpty();
        if (this.lowObstacleTop(feet) > 0.0D) {
            // On a carpet, a snow layer or a candle: it stands on top, so its whole body must fit above it.
            double halfWidth = this.getWidth() / 2.0D;
            double feetY = this.standingFeetY(feet);
            Box body = new Box(x + 0.5D - halfWidth, feetY + 1.0E-3D, z + 0.5D - halfWidth, x + 0.5D + halfWidth, feetY + this.getHeight() - 1.0E-3D, z + 0.5D + halfWidth);
            return world.isSpaceEmpty(this, body);
        }
        // Above a carpet the robot would stand on the carpet, a block lower.
        return feetState.getCollisionShape(world, feet).isEmpty()
            && headState.getCollisionShape(world, head).isEmpty()
            && (floating || !belowState.getCollisionShape(world, below).isEmpty() && this.lowObstacleTop(below) == 0.0D);
    }

    // The top of a block in the robot's feet block that it steps onto and paths walk through (a carpet, a
    // snow layer, a candle), or 0. Slabs and stairs are not: paths stand the robot in the block above them.
    private double lowObstacleTop(BlockPos feet) {
        World world = this.getEntityWorld();
        BlockState state = world.getBlockState(feet);
        VoxelShape shape = state.getCollisionShape(world, feet);
        if (shape.isEmpty() || !state.canPathfindThrough(NavigationType.LAND)) {
            return 0.0D;
        }
        double top = shape.getMax(Direction.Axis.Y);
        return top <= this.getStepHeight() ? top : 0.0D;
    }

    // Where the robot's feet are when it stands in block pos: on top of a low block in it, else at its bottom.
    private double standingFeetY(BlockPos pos) {
        return pos.getY() + this.lowObstacleTop(pos);
    }

    private boolean canOccupyPosition(Vec3d position) {
        Vec3d delta = position.subtract(this.getX(), this.getY(), this.getZ());
        return this.getEntityWorld().isSpaceEmpty(this, this.getBoundingBox().offset(delta));
    }

    // Another robot, mob or player stands there: snapping onto the exact point would put the two inside each other.
    private boolean isOccupiedByOther(Vec3d position) {
        Vec3d delta = position.subtract(this.getX(), this.getY(), this.getZ());
        return !this.getEntityWorld().getOtherEntities(
            this, this.getBoundingBox().offset(delta), other -> other instanceof LivingEntity living && living.isPushable() && !other.isConnectedThroughVehicle(this)
        ).isEmpty();
    }

    // Every tick of self-driven motion passes through here. While a command drives the robot, a step into
    // lava or fire, or off a drop of more than MAX_SAFE_DROP, is held back and the command ends with the
    // reason. move and move_by also stop before stepping from dry land into deep water; move_to swims on purpose.
    @Override
    protected Vec3d adjustMovementForSneaking(Vec3d movement, MovementType type) {
        movement = super.adjustMovementForSneaking(movement, type);
        if (type != MovementType.SELF
            || this.isEvil()
            || !(this.getEntityWorld() instanceof ServerWorld)
            || !this.isDrivenByCommand()
            || movement.horizontalLengthSquared() < 1.0E-7D) {
            return movement;
        }

        String hazard = this.hazardAhead(movement);
        if (hazard == null) {
            return movement;
        }

        this.pendingHazardStop = hazard;
        return new Vec3d(0.0D, movement.y, 0.0D);
    }

    // A fight drives the robot too: it steps after its target, and a hit can knock it about. So does its own
    // step out of fire.
    private boolean isDrivenByCommand() {
        return this.isDrivenByMove() || this.isFighting() || this.isEscapingFire();
    }

    private boolean isDrivenByMove() {
        return this.moveTarget != null
            || this.moveByTarget != null
            || Math.abs(this.forwardInput) > 0.001F
            || Math.abs(this.sidewaysInput) > 0.001F;
    }

    private void applyPendingHazardStop() {
        if (this.pendingHazardStop == null) {
            return;
        }

        String hazard = this.pendingHazardStop;
        this.pendingHazardStop = null;
        if (!this.isDrivenByCommand()) {
            return;
        }
        if (!this.isDrivenByMove()) {
            // A fight goes on from where the robot stands; only its steps after the target are held back.
            // A step out of fire that is held back tries another cell.
            if (this.isFighting()) {
                this.fightBlockedBy = hazard;
                if (this.fightFollow) {
                    this.getNavigation().stop();
                }
            }
            if (this.isEscapingFire()) {
                this.fireEscapeTried.add(BlockPos.ofFloored(this.fireEscapeTarget));
                this.fireEscapeTarget = null;
            }
            return;
        }

        String placed = this.isBridging() ? this.bridgeProgress() : "";
        this.stopActiveMovement();
        this.recordLastMoveResult(false, "Stopped: " + hazard + placed);
    }

    // What the robot would step into by moving its box horizontally by movement, or null when the step is safe.
    private String hazardAhead(Vec3d movement) {
        World world = this.getEntityWorld();
        Box moved = this.getBoundingBox().offset(movement.x, 0.0D, movement.z);
        if (this.entersNewBurningBlock(world, moved)) {
            return "lava or fire ahead";
        }

        // Only a robot standing on something can walk off an edge; a floating one is already in the water.
        if (!this.isOnGround()) {
            return null;
        }

        String deepWater = this.moveTarget == null && !this.isTouchingWater()
            ? "deep water ahead (move_to can swim through water)"
            : null;
        double lowest = moved.minY - MAX_SAFE_DROP - 0.25D;
        double top = moved.minY;
        int waterLayers = 0;
        // Down the column under the step one block layer at a time, to the first floor. A single layer of
        // water over it is a puddle to wade through; more is water the robot would float in.
        while (top > lowest) {
            double bottom = Math.max(lowest, Math.floor(top - HAZARD_EPSILON));
            Box layer = new Box(
                moved.minX + HAZARD_EPSILON, bottom, moved.minZ + HAZARD_EPSILON,
                moved.maxX - HAZARD_EPSILON, top, moved.maxZ - HAZARD_EPSILON
            );
            if (!world.isSpaceEmpty(this, layer)) {
                return null;
            }

            if (world.getStatesInBoxIfLoaded(layer).anyMatch(MineBotEntity::isBurningBlock)) {
                return "lava below the edge ahead";
            }

            if (world.getStatesInBoxIfLoaded(layer).anyMatch(state -> state.getFluidState().isIn(FluidTags.WATER))
                && ++waterLayers > 1) {
                return deepWater;
            }

            top = bottom;
        }

        return waterLayers > 0 ? deepWater : "a drop of more than " + (int) MAX_SAFE_DROP + " blocks ahead";
    }

    private static boolean isBurningBlock(BlockState state) {
        return state.isIn(BlockTags.FIRE) || state.getFluidState().isIn(FluidTags.LAVA);
    }

    // True when the moved box touches a burning block that the robot does not already touch. A robot
    // standing in fire or lava may step within it and out of it; it is never held in it.
    private boolean entersNewBurningBlock(World world, Box moved) {
        Box current = this.getBoundingBox().contract(HAZARD_EPSILON);
        Box ahead = moved.contract(HAZARD_EPSILON);
        return BlockPos.stream(ahead).anyMatch(pos -> {
            if (!isBurningBlock(world.getBlockState(pos))) {
                return false;
            }
            Box block = new Box(pos);
            return !block.intersects(current);
        });
    }

    private Vec3d applyCrouchEdgeGuard(Vec3d horizontalStep) {
        if (!this.isCrouched() || !this.isOnGround() || horizontalStep.lengthSquared() <= 1.0E-6D) {
            return horizontalStep;
        }

        if (this.isSafeCrouchStep(horizontalStep.x, horizontalStep.z)) {
            return horizontalStep;
        }

        if (Math.abs(horizontalStep.x) > 1.0E-6D && this.isSafeCrouchStep(horizontalStep.x, 0.0D)) {
            return new Vec3d(horizontalStep.x, 0.0D, 0.0D);
        }

        if (Math.abs(horizontalStep.z) > 1.0E-6D && this.isSafeCrouchStep(0.0D, horizontalStep.z)) {
            return new Vec3d(0.0D, 0.0D, horizontalStep.z);
        }

        // Otherwise go as far as is safe, so the robot ends up leaning out as far as it can.
        double safe = 0.0D;
        double unsafe = 1.0D;
        for (int i = 0; i < 6; i++) {
            double fraction = (safe + unsafe) * 0.5D;
            if (this.isSafeCrouchStep(horizontalStep.x * fraction, horizontalStep.z * fraction)) {
                safe = fraction;
            } else {
                unsafe = fraction;
            }
        }
        return horizontalStep.multiply(safe);
    }

    // Like a sneaking player, a crouched robot may lean out over an edge while its box, less
    // CROUCH_MIN_FOOTING on every side, still rests on something. It only steps under input, and releasing
    // the input stops it dead, so it never slides further out than this.
    private boolean isSafeCrouchStep(double x, double z) {
        Box moved = this.getBoundingBox().offset(x, 0.0D, z);
        World world = this.getEntityWorld();
        if (!world.isSpaceEmpty(this, moved)) {
            return false;
        }

        Box footing = new Box(
            moved.minX + CROUCH_MIN_FOOTING, moved.minY - 0.1D, moved.minZ + CROUCH_MIN_FOOTING,
            moved.maxX - CROUCH_MIN_FOOTING, moved.minY, moved.maxZ - CROUCH_MIN_FOOTING
        );
        return !world.isSpaceEmpty(this, footing);
    }

    private double horizontalDistanceTo(Vec3d target) {
        return Math.hypot(target.x - this.getX(), target.z - this.getZ());
    }

    private void applyLook(float yaw, float pitch) {
        float wrappedYaw = roundAngle(MathHelper.wrapDegrees(yaw));
        float clampedPitch = roundAngle(MathHelper.clamp(pitch, -90.0F, 90.0F));
        this.dataTracker.set(COMMAND_PITCH, clampedPitch);

        this.setRotation(wrappedYaw, clampedPitch);
        this.setYaw(wrappedYaw);
        this.setPitch(clampedPitch);
        this.setHeadYaw(wrappedYaw);
        this.setBodyYaw(wrappedYaw);
        this.headYaw = wrappedYaw;
        this.bodyYaw = wrappedYaw;
    }

    private void preserveCommandedPitch() {
        float roundedPitch = roundAngle(this.dataTracker.get(COMMAND_PITCH));
        if (Math.abs(this.getPitch() - roundedPitch) <= 0.001F) {
            return;
        }

        this.setRotation(this.getYaw(), roundedPitch);
        this.setPitch(roundedPitch);
    }

    private static float snapToCardinalYaw(float yaw) {
        return roundAngle(MathHelper.wrapDegrees(Math.round(MathHelper.wrapDegrees(yaw) / 90.0F) * 90.0F));
    }

    private static Vec3d localOffsetFromYaw(float yaw, double x, double z) {
        double yawRadians = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRadians);
        double forwardZ = Math.cos(yawRadians);
        double rightX = Math.cos(yawRadians);
        double rightZ = Math.sin(yawRadians);
        return new Vec3d(
            roundCoordinate(forwardX * x + rightX * z),
            0.0D,
            roundCoordinate(forwardZ * x + rightZ * z)
        );
    }

    private static Direction rotateRight(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> direction;
        };
    }

    private static Direction rotateLeft(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.WEST;
            case WEST -> Direction.SOUTH;
            case SOUTH -> Direction.EAST;
            case EAST -> Direction.NORTH;
            default -> direction;
        };
    }


    static String itemIdOf(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air" : Registries.ITEM.getId(stack.getItem()).toString();
    }

    static String idOf(BlockState state) {
        return Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private static String idOf(FluidState state) {
        return Registries.FLUID.getId(state.getFluid()).toString();
    }

    private static double readDouble(JsonObject request, String key) {
        if (!request.has(key)) {
            throw new IllegalArgumentException("Command is missing required field '" + key + "'");
        }

        return request.get(key).getAsDouble();
    }

    private static float clampUnit(double value) {
        return (float) MathHelper.clamp(value, -1.0D, 1.0D);
    }

    private static float readSpeed(JsonObject request) {
        if (!request.has("speed")) {
            return 1.0F;
        }

        double speed = request.get("speed").getAsDouble();
        if (speed <= 0.0D) {
            throw fail("invalid_request", "Movement speed must be greater than 0");
        }

        return (float) MathHelper.clamp(speed, 0.0D, 1.0D);
    }

    private static float clampDeltaDegrees(double value, float maxMagnitude) {
        return (float) MathHelper.clamp(value, -maxMagnitude, maxMagnitude);
    }

    static double roundCoordinate(double value) {
        return Math.round(value * 1_000.0D) / 1_000.0D;
    }

    private static final class MineBotPlacementContext extends ItemPlacementContext {
        private final Direction horizontalFacing;
        private final float playerYaw;
        private final float playerPitch;

        private MineBotPlacementContext(World world, ItemStack stack, BlockHitResult hit, Direction horizontalFacing, float playerYaw, float playerPitch) {
            super(world, null, Hand.MAIN_HAND, stack, hit);
            this.horizontalFacing = horizontalFacing;
            this.playerYaw = playerYaw;
            this.playerPitch = playerPitch;
        }

        @Override
        public Direction getHorizontalPlayerFacing() {
            return this.horizontalFacing;
        }

        @Override
        public float getPlayerYaw() {
            return this.playerYaw;
        }

        @Override
        public Direction getPlayerLookDirection() {
            if (this.playerPitch <= -45.0F) {
                return Direction.UP;
            }

            if (this.playerPitch >= 45.0F) {
                return Direction.DOWN;
            }

            return this.horizontalFacing;
        }

        @Override
        public Direction getVerticalPlayerLookDirection() {
            return this.playerPitch < 0.0F ? Direction.UP : Direction.DOWN;
        }

        @Override
        public Direction[] getPlacementDirections() {
            Direction preferred = this.canReplaceExisting() ? null : this.getSide().getOpposite();
            Direction look = this.getPlayerLookDirection();
            Direction vertical = this.getVerticalPlayerLookDirection();
            Direction right = rotateRight(this.horizontalFacing);
            Direction left = rotateLeft(this.horizontalFacing);
            Direction[] candidates = new Direction[] {
                preferred,
                look,
                vertical,
                this.horizontalFacing,
                right,
                left,
                this.horizontalFacing.getOpposite()
            };
            Direction[] result = new Direction[6];
            int index = 0;
            for (Direction direction : candidates) {
                if (direction == null) {
                    continue;
                }

                boolean alreadyIncluded = false;
                for (int i = 0; i < index; i++) {
                    if (result[i] == direction) {
                        alreadyIncluded = true;
                        break;
                    }
                }

                if (!alreadyIncluded) {
                    result[index++] = direction;
                }
            }

            return Arrays.copyOf(result, index);
        }

        @Override
        public boolean shouldCancelInteraction() {
            return false;
        }
    }

    private static final class MineBotItemUsageContext extends net.minecraft.item.ItemUsageContext {
        private final Direction horizontalFacing;
        private final float playerYaw;

        private MineBotItemUsageContext(World world, ItemStack stack, BlockHitResult hit, Direction horizontalFacing, float playerYaw, float playerPitch) {
            super(world, null, Hand.MAIN_HAND, stack, hit);
            this.horizontalFacing = horizontalFacing;
            this.playerYaw = playerYaw;
        }

        @Override
        public Direction getHorizontalPlayerFacing() {
            return this.horizontalFacing;
        }

        @Override
        public float getPlayerYaw() {
            return this.playerYaw;
        }

        @Override
        public boolean shouldCancelInteraction() {
            return false;
        }
    }

    private static float roundAngle(float value) {
        return (float) (Math.round(value * 10.0D) / 10.0D);
    }

    private static String randomCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record CraftPlan(Identifier recipeId, int[] usedCounts, ItemStack result, List<ItemStack> remainders) {
    }

    private record TransferRequest(Item item, String itemId, int count) {
    }

    private record FurnaceAccess(BlockState state, Inventory inventory, BlockPos pos) {
    }

    private record ContainerAccess(String label, String kind, Inventory inventory) {
    }

    private static final class InventoryView {
        private final Inventory inventory;
        private final List<ItemStack> stacks;

        private InventoryView(Inventory inventory) {
            this.inventory = inventory;
            this.stacks = null;
        }

        private InventoryView(List<ItemStack> stacks) {
            this.inventory = null;
            this.stacks = stacks;
        }

        private int size() {
            return this.inventory != null ? this.inventory.size() : this.stacks.size();
        }

        private ItemStack getStack(int slot) {
            return this.inventory != null ? this.inventory.getStack(slot) : this.stacks.get(slot);
        }

        private void setStack(int slot, ItemStack stack) {
            if (this.inventory != null) {
                this.inventory.setStack(slot, stack);
            } else {
                this.stacks.set(slot, stack);
            }
        }

        private int getMaxCount(ItemStack stack) {
            return this.inventory != null ? this.inventory.getMaxCount(stack) : stack.getMaxCount();
        }

        private void markDirty() {
            if (this.inventory != null) {
                this.inventory.markDirty();
            }
        }
    }

    private static final class FloatGoal extends SwimGoal {
        private final MineBotEntity mineBot;

        private FloatGoal(MineBotEntity mineBot) {
            super(mineBot);
            this.mineBot = mineBot;
        }

        @Override
        public boolean canStart() {
            return !this.mineBot.isCrouched() && super.canStart();
        }

        @Override
        public void tick() {
            if (this.mineBot.isSubmergedInWater()) {
                // Head under water: swim straight up like a player holding jump, not on 80% of ticks.
                this.mineBot.getJumpControl().setActive();
                return;
            }

            super.tick();
        }
    }

    private static final class EvilMeleeAttackGoal extends MeleeAttackGoal {
        private final MineBotEntity mineBot;

        private EvilMeleeAttackGoal(MineBotEntity mineBot, double speed, boolean pauseWhenMobIdle) {
            super(mineBot, speed, pauseWhenMobIdle);
            this.mineBot = mineBot;
        }

        @Override
        public boolean canStart() {
            return this.mineBot.isEvil() && super.canStart();
        }

        @Override
        public boolean shouldContinue() {
            return this.mineBot.isEvil() && super.shouldContinue();
        }
    }
}
