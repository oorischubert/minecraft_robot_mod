package com.oori.minebot.client;

import com.oori.minebot.MineBotMod;
import com.oori.minebot.MineBotScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

public final class MineBotScreen extends HandledScreen<MineBotScreenHandler> {
    private static final double MAX_RANGE_BLOCKS = MineBotMod.MAX_FUEL_STACK * (double) MineBotMod.MOVEMENT_BLOCKS_PER_BLAZE_POWDER;
    private static final int OUTER_PANEL = 0xFF101820;
    private static final int INNER_PANEL = 0xFF17232C;
    private static final int CARD_FILL = 0xFF22313C;
    private static final int CARD_BORDER = 0xFF425563;
    private static final int SLOT_OUTER = 0xFF0D1419;
    private static final int SLOT_INNER = 0xFF273641;
    private static final int ACCENT = 0xFF7CD2A3;
    private static final int TEXT_PRIMARY = 0xFFF2F7FA;
    private static final int TEXT_MUTED = 0xFF9CB0BF;

    public MineBotScreen(MineBotScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 288;
        this.backgroundHeight = 186;
        this.playerInventoryTitleX = 0;
        this.playerInventoryTitleY = 0;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int left = this.x;
        int top = this.y;
        int indicatorColor = this.handler.isConnected() ? 0xFF41D46F : 0xFFD65B5B;
        int energyBarColor = this.handler.isConnected() ? 0xFF4DBD88 : 0xFFF2A65A;
        int energyBarWidth = (int) Math.round(248.0D * this.getFuelBarRatio());

        this.drawPanel(context, left, top, left + this.backgroundWidth, top + this.backgroundHeight, OUTER_PANEL, CARD_BORDER);
        this.drawPanel(context, left + 9, top + 8, left + this.backgroundWidth - 9, top + 32, CARD_FILL, CARD_BORDER);
        this.drawPanel(context, left + 14, top + 42, left + 206, top + 78, CARD_FILL, CARD_BORDER);
        this.drawPanel(context, left + 212, top + 42, left + 274, top + 78, CARD_FILL, CARD_BORDER);
        this.drawPanel(context, left + 14, top + 86, left + 136, top + 112, CARD_FILL, CARD_BORDER);
        this.drawPanel(context, left + 142, top + 86, left + 274, top + 112, CARD_FILL, CARD_BORDER);
        this.drawPanel(context, left + 14, top + 118, left + 274, top + 148, INNER_PANEL, CARD_BORDER);
        this.drawPanel(context, left + 14, top + 152, left + 274, top + 182, INNER_PANEL, CARD_BORDER);

        this.drawSlotBackdrop(context, left + 18, top + 125);

        for (int slot = 0; slot < MineBotMod.ROBOT_INVENTORY_SIZE; slot++) {
            int slotX = left + 50 + slot * 18;
            int slotY = top + 125;
            this.drawSlotBackdrop(context, slotX, slotY);
        }

        for (int slot = 0; slot < 9; slot++) {
            int slotX = left + 50 + slot * 18;
            int slotY = top + 159;
            this.drawSlotBackdrop(context, slotX, slotY);
        }

        int selectedSlotX = left + 50 + this.handler.getSelectedSlot() * 18;
        int selectedSlotY = top + 125;
        context.fill(selectedSlotX - 1, selectedSlotY - 1, selectedSlotX + 19, selectedSlotY, ACCENT);
        context.fill(selectedSlotX - 1, selectedSlotY + 18, selectedSlotX + 19, selectedSlotY + 19, ACCENT);
        context.fill(selectedSlotX - 1, selectedSlotY - 1, selectedSlotX, selectedSlotY + 19, ACCENT);
        context.fill(selectedSlotX + 18, selectedSlotY - 1, selectedSlotX + 19, selectedSlotY + 19, ACCENT);

        int meterX = left + 146;
        int meterY = top + 101;
        context.fill(meterX, meterY, meterX + 120, meterY + 8, SLOT_OUTER);
        context.fill(meterX + 1, meterY + 1, meterX + 119, meterY + 7, SLOT_INNER);
        if (energyBarWidth > 0) {
            context.fill(meterX + 1, meterY + 1, meterX + 1 + Math.min(118, energyBarWidth / 2), meterY + 7, energyBarColor);
        }

        context.fill(left + 231, top + 49, left + 255, top + 73, SLOT_OUTER);
        context.fill(left + 234, top + 52, left + 252, top + 70, indicatorColor);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, Text.literal("MineBot Control"), 17, 15, TEXT_PRIMARY, false);
        context.drawText(this.textRenderer, this.title, 17, 24, TEXT_MUTED, false);

        context.drawText(this.textRenderer, Text.literal("Socket"), 20, 49, TEXT_MUTED, false);
        context.drawText(
            this.textRenderer,
            Text.literal(this.ellipsize(this.getEndpointDisplay(), 176)),
            20,
            61,
            TEXT_PRIMARY,
            false
        );

        context.drawText(this.textRenderer, Text.literal("Robot Code"), 20, 92, TEXT_MUTED, false);
        context.drawText(this.textRenderer, Text.literal(this.getCodeDisplay()), 20, 102, TEXT_PRIMARY, false);

        context.drawText(this.textRenderer, Text.literal("Fuel Reserve"), 148, 92, TEXT_MUTED, false);
        context.drawText(
            this.textRenderer,
            Text.literal(String.format("%.1f blocks", this.getStoredRangeBlocks())),
            148,
            102,
            TEXT_PRIMARY,
            false
        );
        context.drawText(this.textRenderer, Text.literal("Status"), 220, 49, TEXT_MUTED, false);
        context.drawText(
            this.textRenderer,
            Text.literal(this.handler.isConnected() ? "Linked" : "Idle"),
            220,
            61,
            this.handler.isConnected() ? 0xFF9CF5B7 : 0xFFFF9999,
            false
        );
        context.drawText(
            this.textRenderer,
            Text.literal("HP: " + this.formatHealth(this.handler.getHealth())),
            220,
            71,
            TEXT_PRIMARY,
            false
        );

        context.drawText(this.textRenderer, Text.literal("Fuel"), 18, 118, TEXT_MUTED, false);
        context.drawText(this.textRenderer, Text.literal("Robot Hotbar"), 50, 118, TEXT_MUTED, false);
        context.drawText(this.textRenderer, Text.literal("Player Hotbar"), 50, 152, TEXT_MUTED, false);
    }

    private void drawPanel(DrawContext context, int x1, int y1, int x2, int y2, int fillColor, int borderColor) {
        context.fill(x1, y1, x2, y2, borderColor);
        context.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, fillColor);
    }

    private void drawSlotBackdrop(DrawContext context, int x, int y) {
        context.fill(x, y, x + 18, y + 18, SLOT_OUTER);
        context.fill(x + 1, y + 1, x + 17, y + 17, SLOT_INNER);
    }

    private String getEndpointDisplay() {
        String endpoint = this.handler.getEndpoint();
        return endpoint == null || endpoint.isBlank() ? "Starting websocket bridge..." : endpoint;
    }

    private String getCodeDisplay() {
        String code = this.handler.getAccessCode();
        return code == null || code.isBlank() ? "Generating..." : code;
    }

    private String formatHealth(float health) {
        return String.format("%.1f", health);
    }

    private double getStoredRangeBlocks() {
        int powderCount = this.handler.getSlot(0).getStack().getCount();
        double totalMilliblocks = this.handler.getEnergyMilliblocks()
            + powderCount * (double) MineBotMod.MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER;
        return totalMilliblocks / 1_000.0D;
    }

    private double getFuelBarRatio() {
        return Math.min(1.0D, this.getStoredRangeBlocks() / MAX_RANGE_BLOCKS);
    }

    private String ellipsize(String value, int maxWidth) {
        if (this.textRenderer.getWidth(value) <= maxWidth) {
            return value;
        }

        String ellipsis = "...";
        String trimmed = this.textRenderer.trimToWidth(value, maxWidth - this.textRenderer.getWidth(ellipsis));
        return trimmed + ellipsis;
    }
}
