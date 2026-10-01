package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.entity.FakePlayer;
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
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.IngredientPlacement;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
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
import net.minecraft.entity.projectile.ProjectileUtil;
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
    private static final double MOVE_BY_PROGRESS_EPSILON = 0.01D;
    private static final int MOVE_BY_MAX_STALL_TICKS = 8;
    private static final double MOVE_TO_FINAL_APPROACH_DISTANCE = 2.0D;
    private static final double MOVE_TO_AFLOAT_VERTICAL_TOLERANCE = 1.25D;
    private static final int MOVE_TO_MAX_FINAL_APPROACH_TICKS = 60;
    // How far above the water surface a bank's top may be for the robot to jump out onto it (a jump rises 1.25).
    private static final double WATER_HOP_CLEARANCE = 1.2D;
    // The deepest drop a driven robot will walk off: three blocks, the most a fall takes without damage.
    private static final double MAX_SAFE_DROP = 3.0D;
    private static final double HAZARD_EPSILON = 1.0E-6D;

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
    private boolean itemPickupEnabled = true;
    // Why the hazard guard last held the robot back; the driving command is ended on the next tick.
    private String pendingHazardStop;

    public MineBotEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        // Health never regenerates, so paths never pass next to lava or fire, or over magma and fire.
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
            MineBotChunkLoader.get(serverWorld.getServer()).update(this, this.shouldKeepChunksLoaded());
            this.tickEvilTarget(serverWorld);
            this.syncEquippedStack();
            this.applyPendingHazardStop();
            this.tickMoveByTarget();
            this.tickMoveTarget();
            this.tickActiveBreak(serverWorld);
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

        double rise = this.getFluidHeight(FluidTags.WATER) + WATER_HOP_CLEARANCE;
        if (world.isSpaceEmpty(this, this.getBoundingBox().offset(0.0D, rise, 0.0D))
            && world.isSpaceEmpty(this, ahead.offset(0.0D, rise, 0.0D))) {
            this.jump();
        }
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
                case "attack_entity" -> this.handleAttackEntity();
                case "use_item" -> this.handleUseItem();
                case "use_on_entity" -> this.handleUseOnEntity();
                case "move_item" -> this.handleMoveItem(request);
                case "refuel" -> this.handleRefuel(request);
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
        status.addProperty("last_attack_known", this.hasLastAttackResult);
        status.addProperty("last_attack_success", this.hasLastAttackResult && this.lastAttackSucceeded);
        if (!this.lastAttackMessage.isBlank()) {
            status.addProperty("last_attack_message", this.lastAttackMessage);
        }
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
            MineBotChunkLoader.get(serverWorld.getServer()).update(this, true);
        }

        if (!connected) {
            this.setCrouched(false);
            this.stopActiveMovement();
            this.cancelPendingBreak();
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

            MineBotWebSocketService service = MineBotWebSocketService.get(serverWorld.getServer());
            if (service != null) {
                service.onRobotDied(this, this.createDeathPayload(damageSource, deathMessage));
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
        if (this.getEntityWorld() instanceof ServerWorld serverWorld) {
            MineBotChunkLoader.get(serverWorld.getServer()).release(this);
        }

        super.remove(reason);
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
                    boolean wasMoving = this.moveTarget != null || this.moveByTarget != null;
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
        float forward = clampUnit(readDouble(request, "x"));
        float sideways = clampUnit(request.has("z") ? readDouble(request, "z") : request.has("y") ? readDouble(request, "y") : 0.0D);
        // Releasing the input keeps the result, so the caller can still read why the guard stopped the robot.
        if (Math.abs(forward) > 0.001F || Math.abs(sideways) > 0.001F) {
            this.clearLastMoveResult();
        }
        this.forwardInput = forward;
        this.sidewaysInput = sideways;

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
        this.clearLastMoveResult();

        double localX = roundCoordinate(readDouble(request, "x"));
        double localZ = roundCoordinate(request.has("z") ? readDouble(request, "z") : request.has("y") ? readDouble(request, "y") : 0.0D);
        double speed = readSpeed(request);
        float snappedYaw = snapToCardinalYaw(this.getYaw());
        double baseX = MathHelper.floor(this.getX()) + 0.5D;
        double baseZ = MathHelper.floor(this.getZ()) + 0.5D;
        Vec3d offset = localOffsetFromYaw(snappedYaw, localX, localZ);
        Vec3d target = this.resolveMoveTarget(roundCoordinate(baseX + offset.x), roundCoordinate(baseZ + offset.z));

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
        this.clearLastMoveResult();

        if (!request.has("x") && !request.has("z")) {
            throw fail("invalid_request", "move_to requires at least one of 'x' or 'z'");
        }

        double targetX = roundCoordinate(readOptionalDouble(request, "x", null, this.getX()));
        double targetZ = roundCoordinate(readOptionalDouble(request, "z", null, this.getZ()));
        double speed = readSpeed(request);
        Vec3d target = request.has("y")
            ? this.resolveMoveTarget(targetX, MathHelper.floor(readDouble(request, "y")), targetZ)
            : this.resolveMoveTarget(targetX, targetZ);
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

        boolean swimming = !this.isOnGround() && (this.isTouchingWater() || this.isInLava());
        if (this.isOnGround()) {
            this.jump();
        } else if (swimming) {
            this.getJumpControl().setActive();
        }

        JsonObject result = new JsonObject();
        result.addProperty("jumped", this.isOnGround() ? false : true);
        result.addProperty("swimming", swimming);
        result.addProperty("crouched", this.isCrouched());
        result.addProperty("velocity_y", this.getVelocity().y);
        return result;
    }

    private JsonObject handleAttack() {
        this.requireEnergy();
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

        if (!(this.getEntityWorld() instanceof ServerWorld serverWorld)) {
            throw fail("interaction_unavailable", "The robot cannot drop items outside a server world");
        }

        ItemEntity itemEntity = this.dropStack(serverWorld, dropped.copy());
        if (itemEntity != null) {
            itemEntity.setPickupDelay(20);
        }

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
        int radius = MathHelper.clamp(readOptionalInt(request, "radius", null, 8), 1, 16);
        int limit = MathHelper.clamp(readOptionalInt(request, "limit", null, 64), 1, 256);
        MineBotScanner.BlockFilter filter = MineBotScanner.parseBlockFilter(
            request.has("blocks") && request.get("blocks").isJsonArray() ? request.getAsJsonArray("blocks") : null
        );

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
        boolean wasMoving = this.moveTarget != null || this.moveByTarget != null;
        this.cancelPendingBreak();
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

        JsonObject result = new JsonObject();
        result.addProperty("yaw", roundAngle(this.getYaw()));
        result.addProperty("pitch", roundAngle(this.getPitch()));
        return result;
    }

    private JsonObject handleAttackEntity() {
        this.requireEnergy();
        this.cancelPendingBreak();
        ServerWorld serverWorld = this.requireServerWorld();
        Entity target = this.requireCrosshairEntity().getEntity();
        if (!target.isAttackable()) {
            throw fail("interaction_unavailable", "That entity cannot be attacked");
        }

        if (target instanceof PlayerEntity && !serverWorld.isPvpEnabled()) {
            throw fail("interaction_unavailable", "PvP is disabled on this server");
        }

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
                this.knockbackTarget(target, 0.4F + this.getAttackKnockbackAgainst(target, source), targetVelocity);
                if (target instanceof LivingEntity livingTarget && !weapon.isEmpty() && weapon.postHit(livingTarget, this)) {
                    weapon.postDamageEntity(livingTarget, this);
                }
                EnchantmentHelper.onTargetDamaged(serverWorld, target, source, weapon);
                this.onAttacking(target);
            }
        }
        this.syncRobotInventory();

        JsonObject result = new JsonObject();
        result.addProperty("entity", Registries.ENTITY_TYPE.getId(target.getType()).toString());
        result.addProperty("entity_id", target.getId());
        result.addProperty("damage", roundCoordinate(damage));
        result.addProperty("hit", hit);
        if (target instanceof LivingEntity livingTarget) {
            result.addProperty("killed", livingTarget.isDead() || livingTarget.isRemoved());
            result.addProperty("health", roundCoordinate(Math.max(0.0F, livingTarget.getHealth())));
        } else {
            result.addProperty("killed", target.isRemoved());
        }
        return result;
    }

    private JsonObject handleUseItem() {
        this.requireEnergy();
        this.cancelPendingBreak();
        ServerWorld serverWorld = this.requireServerWorld();
        ItemStack selected = this.robotInventory.getStack(this.getSelectedSlot());
        if (selected.isEmpty()) {
            throw fail("missing_item", "The selected hotbar slot is empty");
        }

        String usedItemId = itemIdOf(selected);
        this.swingHand(Hand.MAIN_HAND, true);
        ActionResult used = this.interactAsFakePlayer(
            serverWorld,
            player -> player.interactionManager.interactItem(player, serverWorld, player.getMainHandStack(), Hand.MAIN_HAND)
        );

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
        result.addProperty("selected_item", this.getSelectedItemId());
        if (onBlock != null) {
            result.add("on_block", onBlock);
        }
        return result;
    }

    private JsonObject handleUseOnEntity() {
        this.requireEnergy();
        this.cancelPendingBreak();
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

    private ActionResult interactAsFakePlayer(ServerWorld serverWorld, Function<FakePlayer, ActionResult> interaction) {
        int slot = this.getSelectedSlot();
        FakePlayer player = MineBotFakePlayer.acquire(serverWorld, this.getCommandRayStart(), this.getYaw(), this.getPitch());
        ItemStack held = this.robotInventory.removeStack(slot);
        try {
            MineBotFakePlayer.hold(player, held);
            return interaction.apply(player);
        } finally {
            MineBotFakePlayer.Released released = MineBotFakePlayer.release(player);
            this.robotInventory.setStack(slot, released.hand());
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
        BlockHitResult blockHit = this.raycastBlock();
        double reach = blockHit == null
            ? MineBotMod.INTERACTION_REACH_BLOCKS
            : this.getCommandRayStart().distanceTo(blockHit.getPos());
        EntityHitResult hit = this.raycastCrosshairEntity(reach);
        if (hit == null) {
            throw fail("not_looking_at_entity", "No entity is in front of the robot within reach");
        }
        return hit;
    }

    private EntityHitResult raycastCrosshairEntity(double maxDistance) {
        Vec3d start = this.getCommandRayStart();
        Vec3d direction = this.getRotationVec(1.0F).multiply(maxDistance);
        Box searchBox = this.getBoundingBox().stretch(direction).expand(1.0D);
        return ProjectileUtil.raycast(
            this,
            start,
            start.add(direction),
            searchBox,
            entity -> entity != this && !entity.isSpectator() && entity.canHit() && entity != this.getVehicle(),
            maxDistance * maxDistance
        );
    }

    private JsonObject handleCraft(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
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
        result.addProperty("kind", idOf(container.state()));
        result.addProperty("slots_total", container.inventory().size());
        result.addProperty("slots_used", this.countUsedSlots(container.inventory()));
        result.add("items", summarizeInventory(container.inventory()));
        return result;
    }

    private JsonObject handleChestPlace(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
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
        result.addProperty("kind", idOf(container.state()));
        result.addProperty("placed", transfer.itemId());
        result.addProperty("count", transfer.count());
        return result;
    }

    private JsonObject handleChestTake(JsonObject request) {
        this.requireEnergy();
        this.cancelPendingBreak();
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
        result.addProperty("kind", idOf(container.state()));
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
        BlockHitResult hit = this.requireLookedBlock();
        BlockPos pos = hit.getBlockPos();
        BlockState state = this.getEntityWorld().getBlockState(pos);

        if (state.getBlock() instanceof ChestBlock chestBlock) {
            Inventory inventory = ChestBlock.getInventory(chestBlock, state, this.getEntityWorld(), pos, false);
            if (inventory == null) {
                throw fail("interaction_unavailable", "The chest cannot be accessed right now");
            }
            return new ContainerAccess("chest", state, inventory);
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
                return new ContainerAccess(label, state, inventory);
            }
        }

        throw fail("wrong_block", "Not looking at a chest, barrel, shulker box, hopper, dropper, or dispenser");
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

    private boolean shouldKeepChunksLoaded() {
        return this.isEvil()
            || this.isConnected()
            || this.moveByTarget != null
            || this.moveTarget != null
            || this.breakingPos != null
            || Math.abs(this.forwardInput) > 0.001F
            || Math.abs(this.sidewaysInput) > 0.001F;
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

            boolean restarted = this.startPathTo(this.moveTarget, this.moveTargetSpeed);
            if (!restarted) {
                this.clearPathingTarget();
                this.recordLastMoveResult(false, "MineBot could not continue moving to that location");
            }
        }
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
                itemEntity.resetPickupDelay();
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
        this.clearMoveByTarget();
        this.clearPathingTarget();
        this.clearDirectMotion();
    }

    private void clearPathingTarget() {
        this.moveTarget = null;
        this.moveTargetSpeed = 1.0D;
        this.moveTargetApproachTicks = 0;
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

    private BlockHitResult resolvePlacementHit(BlockHitResult hit) {
        if (this.shouldUseCrouchBridgePlacement(hit)) {
            BlockPos supportPos = this.getSupportBlockPos();
            Direction placeSide = this.getHorizontalFacing().getOpposite();
            return this.createFaceHit(supportPos, placeSide);
        }

        return hit;
    }

    private BlockHitResult resolveUseHit(BlockHitResult hit, ItemStack selected) {
        if (!(selected.getItem() instanceof BlockItem)) {
            return hit;
        }

        return this.normalizeReplaceablePlacementHit(this.resolvePlacementHit(hit), selected);
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

    private Vec3d resolveMoveTarget(double targetX, double targetZ) {
        int blockX = MathHelper.floor(targetX);
        int blockZ = MathHelper.floor(targetZ);
        int currentY = MathHelper.floor(this.getY());
        BlockPos standingPos = this.findWalkableY(blockX, currentY, blockZ, 12);

        if (standingPos == null) {
            int topY = this.getEntityWorld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
            standingPos = this.findWalkableY(blockX, topY, blockZ, 8);
        }

        if (standingPos == null) {
            throw new IllegalArgumentException("MineBot could not find a walkable Y height at that X/Z location");
        }

        return new Vec3d(targetX, standingPos.getY(), targetZ);
    }

    private Vec3d resolveMoveTarget(double targetX, int targetY, double targetZ) {
        BlockPos standingPos = this.findWalkableY(MathHelper.floor(targetX), targetY, MathHelper.floor(targetZ), 12);
        if (standingPos == null) {
            throw new IllegalArgumentException("MineBot could not find a walkable Y height near Y " + targetY + " at that X/Z location");
        }

        return new Vec3d(targetX, standingPos.getY(), targetZ);
    }

    private boolean snapToExactPosition(Vec3d target) {
        Vec3d exact = new Vec3d(target.x, target.y, target.z);
        if (!this.canOccupyPosition(exact)) {
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

    private boolean shouldUseCrouchBridgePlacement(BlockHitResult hit) {
        if (!this.isCrouched() || !this.isOnGround() || this.getPitch() < 70.0F) {
            return false;
        }

        BlockPos supportPos = this.getSupportBlockPos();
        if (this.getEntityWorld().getBlockState(supportPos).isAir()) {
            return false;
        }

        BlockPos bridgePos = supportPos.offset(this.getHorizontalFacing().getOpposite());
        if (!this.getEntityWorld().getBlockState(bridgePos).isAir()) {
            return false;
        }

        if (hit == null) {
            return true;
        }

        return hit.getSide() == Direction.DOWN || hit.getBlockPos().getY() <= supportPos.getY();
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
        return feetState.getCollisionShape(world, feet).isEmpty()
            && headState.getCollisionShape(world, head).isEmpty()
            && (floating || !belowState.getCollisionShape(world, below).isEmpty());
    }

    private boolean canOccupyPosition(Vec3d position) {
        Vec3d delta = position.subtract(this.getX(), this.getY(), this.getZ());
        return this.getEntityWorld().isSpaceEmpty(this, this.getBoundingBox().offset(delta));
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

    private boolean isDrivenByCommand() {
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

        this.stopActiveMovement();
        this.recordLastMoveResult(false, "Stopped: " + hazard);
    }

    // What the robot would step into by moving its box horizontally by movement, or null when the step is safe.
    private String hazardAhead(Vec3d movement) {
        World world = this.getEntityWorld();
        Box moved = this.getBoundingBox().offset(movement.x, 0.0D, movement.z);
        if (world.getStatesInBoxIfLoaded(moved.contract(HAZARD_EPSILON)).anyMatch(MineBotEntity::isBurningBlock)) {
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

        return Vec3d.ZERO;
    }

    private boolean isSafeCrouchStep(double x, double z) {
        Box moved = this.getBoundingBox().offset(x, 0.0D, z);
        World world = this.getEntityWorld();
        if (!world.isSpaceEmpty(this, moved)) {
            return false;
        }

        if (world.isSpaceEmpty(this, moved.offset(0.0D, -0.1D, 0.0D))) {
            return false;
        }

        // The old check only required some overlap with support below the future box.
        // Over many crouched bridge-building steps that allowed gradual overhang drift
        // until the robot's center finally slipped off the edge. Require the future
        // center point itself to still be over a solid support block.
        double futureCenterX = (moved.minX + moved.maxX) * 0.5D;
        double futureCenterZ = (moved.minZ + moved.maxZ) * 0.5D;
        BlockPos supportPos = BlockPos.ofFloored(futureCenterX, moved.minY - 0.2D, futureCenterZ);
        return !world.getBlockState(supportPos).getCollisionShape(world, supportPos).isEmpty();
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

    private record ContainerAccess(String label, BlockState state, Inventory inventory) {
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
