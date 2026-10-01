package com.oori.minebot.client;

import com.oori.minebot.MineBotEntity;
import com.oori.minebot.MineBotMod;
import com.oori.minebot.MineBotRenamePayload;
import com.oori.minebot.MineBotScreenHandler;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

/**
 * The robot's inventory screen, drawn as the robot itself: a monitor in the robot's skin colours on top,
 * showing its status, and the fuel slot and hotbars on the case below. The code and socket copy on click;
 * the name on the title row turns into a text field on click to rename the robot.
 */
public final class MineBotScreen extends HandledScreen<MineBotScreenHandler> {
    private static final double MAX_RANGE_BLOCKS = MineBotMod.MAX_FUEL_STACK * (double) MineBotMod.MOVEMENT_BLOCKS_PER_BLAZE_POWDER;
    private static final float MAX_HEALTH = 20.0F;
    private static final long COPIED_MILLIS = 1500L;
    private static final long RENAME_PENDING_MILLIS = 1000L;
    private static final String DEFAULT_NAME = "MineBot";
    private static final String WIDEST_STATUS = "CONNECTED";

    // Layout, relative to the screen's top-left corner. Slot positions match MineBotScreenHandler.
    private static final int WIDTH = 240;
    private static final int HEIGHT = 170;
    private static final int MONITOR_X1 = 6;
    private static final int MONITOR_Y1 = 6;
    private static final int MONITOR_X2 = 234;
    private static final int MONITOR_Y2 = 90;
    private static final int SCREEN_X1 = 12;
    private static final int SCREEN_Y1 = 12;
    private static final int SCREEN_X2 = 228;
    private static final int SCREEN_Y2 = 80;
    private static final int TEXT_LEFT = 18;
    private static final int TEXT_RIGHT = 222;
    private static final int VALUE_LEFT = 48;
    private static final int TITLE_Y = 16;
    private static final int DIVIDER_Y = 27;
    private static final int CODE_Y = 31;
    private static final int LINK_Y = 43;
    private static final int HEALTH_Y = 55;
    private static final int FUEL_Y = 67;
    private static final int BAR_WIDTH = 88;
    private static final int HEALTH_SEGMENTS = 10;
    private static final int SLOT_LABEL_Y = 96;
    private static final int GROOVE_Y = 130;
    private static final int PLAYER_LABEL_Y = 135;

    private final MineBotScreenTheme theme;
    private CopyTarget copied;
    private long copiedAt;
    private TextFieldWidget nameField;
    private int nameMaxWidth;
    private String pendingName;
    private long pendingNameAt;

    public MineBotScreen(MineBotScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.theme = MineBotScreenTheme.of(handler.getSkin());
        this.backgroundWidth = WIDTH;
        this.backgroundHeight = HEIGHT;
    }

    @Override
    protected void init() {
        super.init();
        // The name must fit left of the widest status, its dot and the blinking cursor.
        this.nameMaxWidth = TEXT_RIGHT - this.textRenderer.getWidth(WIDEST_STATUS) - 7 - 12 - TEXT_LEFT;
        this.nameField = new TextFieldWidget(this.textRenderer, this.x + TEXT_LEFT, this.y + TITLE_Y, this.nameMaxWidth + 8, 9, Text.literal("Robot name"));
        this.nameField.setDrawsBackground(false);
        this.nameField.setTextShadow(false);
        this.nameField.setEditableColor(this.theme.text());
        this.nameField.setMaxLength(MineBotEntity.MAX_NAME_LENGTH);
        this.nameField.setTextPredicate(this::isAllowedName);
        this.nameField.setPlaceholder(Text.literal(DEFAULT_NAME).withColor(this.theme.textDim()));
        this.nameField.setVisible(false);
        this.addDrawableChild(this.nameField);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        if (!this.handler.getCursorStack().isEmpty()) {
            return;
        }
        CopyTarget target = this.copyTargetAt(mouseX, mouseY);
        if (target != null) {
            context.drawTooltip(this.textRenderer, this.tooltipFor(target), mouseX, mouseY);
        } else if (this.isOverName(mouseX, mouseY)) {
            context.drawTooltip(this.textRenderer, List.of(Text.literal("Click to rename")), mouseX, mouseY);
        }
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (!this.isRenaming()) {
            return super.keyPressed(input);
        }
        if (input.isEscape()) {
            this.stopRenaming();
        } else if (input.isEnter()) {
            this.finishRenaming();
        } else {
            // Typed keys belong to the name, not to the inventory, drop or hotbar keys.
            this.nameField.keyPressed(input);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (this.isRenaming()) {
            if (this.nameField.isMouseOver(click.x(), click.y())) {
                return super.mouseClicked(click, doubled);
            }
            this.finishRenaming();
        } else if (click.button() == 0 && this.handler.getCursorStack().isEmpty() && this.isOverName(click.x(), click.y())) {
            this.startRenaming();
            this.client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            return true;
        }

        CopyTarget target = click.button() == 0 ? this.copyTargetAt(click.x(), click.y()) : null;
        if (target != null && this.handler.getCursorStack().isEmpty()) {
            this.client.keyboard.setClipboard(target.value(this));
            this.client.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            this.copied = target;
            this.copiedAt = Util.getMeasuringTimeMs();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int left = this.x;
        int top = this.y;
        MineBotScreenTheme t = this.theme;

        // case
        this.drawBevelBox(context, left, top, left + WIDTH, top + HEIGHT, t.outline(), t.caseFill(), t.caseHi(), t.caseLo());

        // monitor bezel, with the screen recessed into it
        this.drawBevelBox(context, left + MONITOR_X1, top + MONITOR_Y1, left + MONITOR_X2, top + MONITOR_Y2, t.outline(), t.bezel(), t.bezelHi(), t.bezelLo());
        context.fill(left + SCREEN_X1 - 1, top + SCREEN_Y1 - 1, left + SCREEN_X2 + 1, top + SCREEN_Y1, t.bezelLo());
        context.fill(left + SCREEN_X1 - 1, top + SCREEN_Y1, left + SCREEN_X1, top + SCREEN_Y2 + 1, t.bezelLo());
        context.fill(left + SCREEN_X1, top + SCREEN_Y2, left + SCREEN_X2 + 1, top + SCREEN_Y2 + 1, t.bezelHi());
        context.fill(left + SCREEN_X2, top + SCREEN_Y1, left + SCREEN_X2 + 1, top + SCREEN_Y2, t.bezelHi());
        context.fill(left + SCREEN_X1, top + SCREEN_Y1, left + SCREEN_X2, top + SCREEN_Y2, t.screen());
        if (t.scanline() != t.screen()) {
            for (int y = SCREEN_Y1 + 1; y < SCREEN_Y2; y += 2) {
                context.fill(left + SCREEN_X1, top + y, left + SCREEN_X2, top + y + 1, t.scanline());
            }
        }

        // chin: vents on the left, power light on the right
        for (int vent = 0; vent < 2; vent++) {
            int ventY = top + SCREEN_Y2 + 3 + vent * 3;
            context.fill(left + 18, ventY, left + 34, ventY + 1, t.bezelLo());
        }
        int ledX = left + SCREEN_X2 - 10;
        int ledY = top + SCREEN_Y2 + 4;
        context.fill(ledX - 1, ledY - 1, ledX + 7, ledY + 3, t.bezelLo());
        context.fill(ledX, ledY, ledX + 6, ledY + 2, this.handler.isConnected() ? MineBotScreenTheme.LED_CONNECTED : MineBotScreenTheme.LED_IDLE);

        // status readout
        this.drawDottedLine(context, left + TEXT_LEFT, top + DIVIDER_Y, left + TEXT_RIGHT, t.textDim());
        this.drawHealthBar(context, left + VALUE_LEFT, top + HEALTH_Y);
        this.drawFuelBar(context, left + VALUE_LEFT, top + FUEL_Y);
        CopyTarget hovered = this.copyTargetAt(mouseX, mouseY);
        int[] underline = hovered != null ? hovered.bounds(this) : this.isOverName(mouseX, mouseY) ? this.nameBounds() : null;
        if (underline != null) {
            context.fill(underline[0], underline[3] - 1, underline[2], underline[3], t.text());
        }

        // slots
        this.drawSlot(context, left + MineBotScreenHandler.FUEL_SLOT_X - 1, top + MineBotScreenHandler.ROBOT_SLOT_Y - 1, false);
        int grooveX = left + (MineBotScreenHandler.FUEL_SLOT_X + 17 + MineBotScreenHandler.ROBOT_SLOT_X - 1) / 2;
        context.fill(grooveX, top + SLOT_LABEL_Y, grooveX + 1, top + MineBotScreenHandler.ROBOT_SLOT_Y + 17, t.caseLo());
        context.fill(grooveX + 1, top + SLOT_LABEL_Y, grooveX + 2, top + MineBotScreenHandler.ROBOT_SLOT_Y + 17, t.caseHi());
        for (int slot = 0; slot < MineBotMod.ROBOT_INVENTORY_SIZE; slot++) {
            this.drawSlot(
                context,
                left + MineBotScreenHandler.ROBOT_SLOT_X - 1 + slot * 18,
                top + MineBotScreenHandler.ROBOT_SLOT_Y - 1,
                slot == this.handler.getSelectedSlot()
            );
        }
        context.fill(left + 8, top + GROOVE_Y, left + WIDTH - 8, top + GROOVE_Y + 1, t.caseLo());
        context.fill(left + 8, top + GROOVE_Y + 1, left + WIDTH - 8, top + GROOVE_Y + 2, t.caseHi());
        for (int slot = 0; slot < 9; slot++) {
            this.drawSlot(context, left + MineBotScreenHandler.PLAYER_HOTBAR_X - 1 + slot * 18, top + MineBotScreenHandler.PLAYER_HOTBAR_Y - 1, false);
        }

        // case vent beside the player hotbar
        int ventLeft = left + MineBotScreenHandler.PLAYER_HOTBAR_X - 1 + 9 * 18 + 8;
        for (int vent = 0; vent < 4; vent++) {
            int ventY = top + MineBotScreenHandler.PLAYER_HOTBAR_Y + 1 + vent * 4;
            context.fill(ventLeft, ventY, left + WIDTH - 10, ventY + 1, t.slotDark());
            context.fill(ventLeft, ventY + 1, left + WIDTH - 10, ventY + 2, t.caseHi());
        }
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        MineBotScreenTheme t = this.theme;
        boolean connected = this.handler.isConnected();

        // title row: robot name with a blinking cursor, status on the right
        String status = connected ? "CONNECTED" : "IDLE";
        int statusWidth = this.textRenderer.getWidth(status);
        int statusX = TEXT_RIGHT - statusWidth;
        int dotX = statusX - 7;
        context.fill(dotX, TITLE_Y + 2, dotX + 4, TITLE_Y + 6, connected ? MineBotScreenTheme.LED_CONNECTED : MineBotScreenTheme.LED_IDLE);
        context.drawText(this.textRenderer, status, statusX, TITLE_Y, connected ? t.text() : t.textDim(), false);
        if (!this.isRenaming()) {
            String name = this.ellipsize(this.getNameDisplay(), dotX - 12 - TEXT_LEFT);
            context.drawText(this.textRenderer, name, TEXT_LEFT, TITLE_Y, t.text(), false);
            if ((Util.getMeasuringTimeMs() / 500L) % 2L == 0L) {
                int cursorX = TEXT_LEFT + this.textRenderer.getWidth(name) + 2;
                context.fill(cursorX, TITLE_Y + 7, cursorX + 5, TITLE_Y + 8, t.text());
            }
        }

        // code
        context.drawText(this.textRenderer, "CODE", TEXT_LEFT, CODE_Y, t.textDim(), false);
        context.drawText(this.textRenderer, this.getCodeDisplay(), VALUE_LEFT, CODE_Y, t.text(), false);
        String hint = this.isShowingCopied(CopyTarget.CODE) ? "copied" : "click to copy";
        context.drawText(this.textRenderer, hint, TEXT_RIGHT - this.textRenderer.getWidth(hint), CODE_Y, t.textDim(), false);

        // socket
        context.drawText(this.textRenderer, "LINK", TEXT_LEFT, LINK_Y, t.textDim(), false);
        context.drawText(this.textRenderer, this.ellipsize(this.getEndpointDisplay(), TEXT_RIGHT - VALUE_LEFT), VALUE_LEFT, LINK_Y, t.text(), false);

        // health and fuel values, right of their bars
        context.drawText(this.textRenderer, "HP", TEXT_LEFT, HEALTH_Y, t.textDim(), false);
        String health = this.formatHealth(this.handler.getHealth()) + " / 20";
        context.drawText(this.textRenderer, health, TEXT_RIGHT - this.textRenderer.getWidth(health), HEALTH_Y, t.text(), false);
        context.drawText(this.textRenderer, "FUEL", TEXT_LEFT, FUEL_Y, t.textDim(), false);
        String range = String.format("%,d blocks", (long) Math.floor(this.getStoredRangeBlocks()));
        context.drawText(this.textRenderer, range, TEXT_RIGHT - this.textRenderer.getWidth(range), FUEL_Y, t.text(), false);

        // case labels
        context.drawText(this.textRenderer, "Fuel", MineBotScreenHandler.FUEL_SLOT_X - 1, SLOT_LABEL_Y, t.label(), false);
        context.drawText(this.textRenderer, "Robot hotbar", MineBotScreenHandler.ROBOT_SLOT_X - 1, SLOT_LABEL_Y, t.label(), false);
        context.drawText(this.textRenderer, "Your hotbar", MineBotScreenHandler.PLAYER_HOTBAR_X - 1, PLAYER_LABEL_Y, t.label(), false);
    }

    private void drawHealthBar(DrawContext context, int x, int y) {
        int segmentWidth = (BAR_WIDTH + 2) / HEALTH_SEGMENTS - 2;
        float perSegment = MAX_HEALTH / HEALTH_SEGMENTS;
        float health = this.handler.getHealth();
        for (int segment = 0; segment < HEALTH_SEGMENTS; segment++) {
            int sx = x + segment * (segmentWidth + 2);
            float fill = Math.max(0.0F, Math.min(1.0F, (health - segment * perSegment) / perSegment));
            context.fill(sx, y + 1, sx + segmentWidth, y + 6, this.theme.textDim());
            int litWidth = Math.round(segmentWidth * fill);
            if (litWidth > 0) {
                context.fill(sx, y + 1, sx + litWidth, y + 6, this.theme.text());
            }
        }
    }

    private void drawFuelBar(DrawContext context, int x, int y) {
        context.fill(x, y + 1, x + BAR_WIDTH, y + 6, this.theme.textDim());
        int litWidth = (int) Math.round(BAR_WIDTH * Math.min(1.0D, this.getStoredRangeBlocks() / MAX_RANGE_BLOCKS));
        if (litWidth > 0) {
            context.fill(x, y + 1, x + litWidth, y + 6, this.theme.text());
        }
    }

    private void drawSlot(DrawContext context, int x, int y, boolean selected) {
        MineBotScreenTheme t = this.theme;
        if (selected) {
            context.fill(x, y, x + 18, y + 18, t.accent());
        } else {
            context.fill(x, y, x + 18, y + 17, t.slotDark());
            context.fill(x + 1, y + 1, x + 18, y + 18, t.slotLight());
        }
        context.fill(x + 1, y + 1, x + 17, y + 17, t.slotFill());
    }

    /** A 1px outline with its corners cut, a highlight on the top and left edges and a shadow on the others. */
    private void drawBevelBox(DrawContext context, int x1, int y1, int x2, int y2, int outline, int fill, int hi, int lo) {
        context.fill(x1 + 1, y1, x2 - 1, y2, outline);
        context.fill(x1, y1 + 1, x2, y2 - 1, outline);
        context.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, lo);
        context.fill(x1 + 1, y1 + 1, x2 - 2, y2 - 2, hi);
        context.fill(x1 + 2, y1 + 2, x2 - 2, y2 - 2, fill);
    }

    private void drawDottedLine(DrawContext context, int x1, int y, int x2, int color) {
        for (int x = x1; x < x2; x += 2) {
            context.fill(x, y, x + 1, y + 1, color);
        }
    }

    private boolean isRenaming() {
        return this.nameField != null && this.nameField.isVisible();
    }

    private void startRenaming() {
        this.nameField.setText(this.getCustomName());
        this.nameField.setVisible(true);
        this.setFocused(this.nameField);
        this.nameField.setCursorToEnd(false);
    }

    private void finishRenaming() {
        String name = this.nameField.getText();
        if (!name.equals(this.getCustomName())) {
            ClientPlayNetworking.send(new MineBotRenamePayload(name));
            this.pendingName = name;
            this.pendingNameAt = Util.getMeasuringTimeMs();
        }
        this.stopRenaming();
    }

    private void stopRenaming() {
        this.nameField.setVisible(false);
        this.setFocused(null);
    }

    /** No spaces or formatting codes (the server applies the same rule), and short enough for the title row. */
    private boolean isAllowedName(String name) {
        return name.equals(MineBotEntity.sanitizeName(name)) && this.textRenderer.getWidth(name) <= this.nameMaxWidth;
    }

    /** The robot's custom name as the client sees it, or "" when it has none. */
    private String getCustomName() {
        Entity entity = this.client.world == null ? null : this.client.world.getEntityById(this.handler.getEntityId());
        return entity instanceof MineBotEntity robot && robot.hasCustomName() ? robot.getCustomName().getString() : "";
    }

    private String getNameDisplay() {
        String name = this.getCustomName();
        // Show a rename right away, until the server's update arrives.
        if (this.pendingName != null) {
            if (!name.equals(this.pendingName) && Util.getMeasuringTimeMs() - this.pendingNameAt < RENAME_PENDING_MILLIS) {
                name = this.pendingName;
            } else {
                this.pendingName = null;
            }
        }
        return name.isEmpty() ? DEFAULT_NAME : name;
    }

    /** Absolute {x1, y1, x2, y2} of the name on the title row. */
    private int[] nameBounds() {
        int x = this.x + TEXT_LEFT;
        int y = this.y + TITLE_Y;
        int width = Math.min(this.textRenderer.getWidth(this.getNameDisplay()), this.nameMaxWidth);
        return new int[] {x - 1, y - 2, x + width + 1, y + 10};
    }

    private boolean isOverName(double mouseX, double mouseY) {
        if (this.isRenaming()) {
            return false;
        }
        int[] r = this.nameBounds();
        return mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3];
    }

    private CopyTarget copyTargetAt(double mouseX, double mouseY) {
        for (CopyTarget target : CopyTarget.values()) {
            int[] r = target.bounds(this);
            if (r != null && mouseX >= r[0] && mouseX < r[2] && mouseY >= r[1] && mouseY < r[3]) {
                return target;
            }
        }
        return null;
    }

    private List<Text> tooltipFor(CopyTarget target) {
        Text action = Text.literal(this.isShowingCopied(target) ? "Copied" : "Click to copy");
        String full = target.value(this);
        if (target == CopyTarget.LINK && this.textRenderer.getWidth(full) > TEXT_RIGHT - VALUE_LEFT) {
            return List.of(Text.literal(full), action);
        }
        return List.of(action);
    }

    private boolean isShowingCopied(CopyTarget target) {
        return this.copied == target && Util.getMeasuringTimeMs() - this.copiedAt < COPIED_MILLIS;
    }

    private String getEndpointDisplay() {
        String endpoint = this.handler.getEndpoint();
        return endpoint == null || endpoint.isBlank() ? "starting websocket bridge..." : endpoint;
    }

    private String getCodeDisplay() {
        String code = this.handler.getAccessCode();
        return code == null || code.isBlank() ? "..." : code;
    }

    private String formatHealth(float health) {
        return health == Math.floor(health) ? String.valueOf((int) health) : String.format("%.1f", health);
    }

    private double getStoredRangeBlocks() {
        int powderCount = this.handler.getSlot(0).getStack().getCount();
        double totalMilliblocks = this.handler.getEnergyMilliblocks()
            + powderCount * (double) MineBotMod.MOVEMENT_MILLIBLOCKS_PER_BLAZE_POWDER;
        return totalMilliblocks / 1_000.0D;
    }

    private String ellipsize(String value, int maxWidth) {
        if (this.textRenderer.getWidth(value) <= maxWidth) {
            return value;
        }

        String ellipsis = "...";
        return this.textRenderer.trimToWidth(value, maxWidth - this.textRenderer.getWidth(ellipsis)) + ellipsis;
    }

    /** Values on the status screen that copy to the clipboard when clicked. */
    private enum CopyTarget {
        CODE(CODE_Y),
        LINK(LINK_Y);

        private final int rowY;

        CopyTarget(int rowY) {
            this.rowY = rowY;
        }

        private String value(MineBotScreen screen) {
            return this == CODE ? screen.handler.getAccessCode() : screen.handler.getEndpoint();
        }

        /** Absolute {x1, y1, x2, y2} of the value's text, or null when there is nothing to copy yet. */
        private int[] bounds(MineBotScreen screen) {
            String value = this.value(screen);
            if (value == null || value.isBlank()) {
                return null;
            }
            String shown = this == CODE ? value : screen.ellipsize(value, TEXT_RIGHT - VALUE_LEFT);
            int x = screen.x + VALUE_LEFT;
            int y = screen.y + this.rowY;
            return new int[] {x - 1, y - 2, x + screen.textRenderer.getWidth(shown) + 1, y + 10};
        }
    }
}
