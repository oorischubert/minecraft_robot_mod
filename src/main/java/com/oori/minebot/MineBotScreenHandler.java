package com.oori.minebot;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.InventoryChangedListener;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

public final class MineBotScreenHandler extends ScreenHandler implements InventoryChangedListener {
    private static final int PROPERTY_COUNT = 4;
    private static final int FUEL_SLOT = 0;
    private static final int ROBOT_FIRST_SLOT = 1;
    private static final int ROBOT_LAST_SLOT = ROBOT_FIRST_SLOT + MineBotMod.ROBOT_INVENTORY_SIZE;
    private static final int PLAYER_FIRST_SLOT = ROBOT_LAST_SLOT;
    private static final int PLAYER_LAST_SLOT = PLAYER_FIRST_SLOT + 9;
    // Item positions; MineBotScreen draws the slot backdrops around them.
    public static final int FUEL_SLOT_X = 14;
    public static final int ROBOT_SLOT_X = 44;
    public static final int ROBOT_SLOT_Y = 107;
    public static final int PLAYER_HOTBAR_X = 44;
    public static final int PLAYER_HOTBAR_Y = 146;
    // Screen handler properties are sent as shorts, so energy travels in tenths of a block.
    private static final int MILLIBLOCKS_PER_ENERGY_UNIT = 100;

    private final MineBotEntity entity;
    private final Inventory fuelInventory;
    private final Inventory robotInventory;
    private final PropertyDelegate propertyDelegate;
    private final String accessCode;
    private final String endpoint;
    private final MineBotSkin skin;

    public MineBotScreenHandler(int syncId, PlayerInventory playerInventory, MineBotScreenOpeningData data) {
        this(
            syncId,
            playerInventory,
            null,
            new SimpleInventory(1),
            new SimpleInventory(MineBotMod.ROBOT_INVENTORY_SIZE),
            new ArrayPropertyDelegate(PROPERTY_COUNT),
            data.accessCode(),
            data.endpoint(),
            MineBotSkin.byId(data.skin())
        );
    }

    public MineBotScreenHandler(int syncId, PlayerInventory playerInventory, MineBotEntity entity) {
        this(
            syncId,
            playerInventory,
            entity,
            entity.getFuelInventory(),
            entity.getRobotInventory(),
            entity.createPropertyDelegate(),
            entity.getAccessCode(),
            entity.getWebSocketEndpoint(),
            entity.getSkin()
        );
    }

    private MineBotScreenHandler(
        int syncId,
        PlayerInventory playerInventory,
        MineBotEntity entity,
        Inventory fuelInventory,
        Inventory robotInventory,
        PropertyDelegate propertyDelegate,
        String accessCode,
        String endpoint,
        MineBotSkin skin
    ) {
        super(MineBotMod.MINEBOT_SCREEN_HANDLER, syncId);
        this.entity = entity;
        this.fuelInventory = fuelInventory;
        this.robotInventory = robotInventory;
        this.propertyDelegate = propertyDelegate;
        this.accessCode = accessCode;
        this.endpoint = endpoint;
        this.skin = skin;

        checkSize(this.robotInventory, MineBotMod.ROBOT_INVENTORY_SIZE);
        checkSize(this.fuelInventory, 1);
        checkDataCount(this.propertyDelegate, PROPERTY_COUNT);
        this.registerInventoryListeners();

        this.addSlot(new Slot(this.fuelInventory, 0, FUEL_SLOT_X, ROBOT_SLOT_Y) {
            @Override
            public boolean canInsert(ItemStack stack) {
                return stack.isOf(Items.BLAZE_POWDER);
            }

            @Override
            public int getMaxItemCount(ItemStack stack) {
                return MineBotMod.MAX_FUEL_STACK;
            }
        });

        for (int slot = 0; slot < MineBotMod.ROBOT_INVENTORY_SIZE; slot++) {
            this.addSlot(new Slot(this.robotInventory, slot, ROBOT_SLOT_X + slot * 18, ROBOT_SLOT_Y));
        }

        this.addPlayerHotbarSlots(playerInventory, PLAYER_HOTBAR_X, PLAYER_HOTBAR_Y);
        this.addProperties(this.propertyDelegate);
    }

    public MineBotEntity getEntity() {
        return this.entity;
    }

    public String getAccessCode() {
        return this.accessCode;
    }

    public String getEndpoint() {
        return this.endpoint;
    }

    public MineBotSkin getSkin() {
        return this.skin;
    }

    public boolean isConnected() {
        return this.propertyDelegate.get(0) != 0;
    }

    public int getEnergyMilliblocks() {
        return this.propertyDelegate.get(1) * MILLIBLOCKS_PER_ENERGY_UNIT;
    }

    static int toEnergyProperty(int energyMilliblocks) {
        return Math.min(Short.MAX_VALUE, Math.max(0, energyMilliblocks / MILLIBLOCKS_PER_ENERGY_UNIT));
    }

    public int getSelectedSlot() {
        return this.propertyDelegate.get(2);
    }

    public float getHealth() {
        return this.propertyDelegate.get(3) / 10.0F;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        ItemStack original = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);

        if (!slot.hasStack()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getStack();
        original = stack.copy();

        if (index == FUEL_SLOT || (index >= ROBOT_FIRST_SLOT && index < ROBOT_LAST_SLOT)) {
            if (!this.insertItem(stack, PLAYER_FIRST_SLOT, PLAYER_LAST_SLOT, true)) {
                return ItemStack.EMPTY;
            }
        } else if (stack.isOf(Items.BLAZE_POWDER)) {
            if (!this.insertItem(stack, FUEL_SLOT, FUEL_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!this.insertItem(stack, ROBOT_FIRST_SLOT, ROBOT_LAST_SLOT, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }

        return original;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return this.entity == null || this.entity.isAlive() && this.entity.squaredDistanceTo(player) <= 64.0D;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        this.unregisterInventoryListeners();
        super.onClosed(player);
    }

    @Override
    public void onInventoryChanged(Inventory sender) {
        this.sendContentUpdates();
    }

    private void registerInventoryListeners() {
        if (this.robotInventory instanceof SimpleInventory simpleRobotInventory) {
            simpleRobotInventory.addListener(this);
        }
        if (this.fuelInventory instanceof SimpleInventory simpleFuelInventory) {
            simpleFuelInventory.addListener(this);
        }
    }

    private void unregisterInventoryListeners() {
        if (this.robotInventory instanceof SimpleInventory simpleRobotInventory) {
            simpleRobotInventory.removeListener(this);
        }
        if (this.fuelInventory instanceof SimpleInventory simpleFuelInventory) {
            simpleFuelInventory.removeListener(this);
        }
    }
}
