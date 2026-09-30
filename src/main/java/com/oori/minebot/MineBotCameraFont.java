package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.Identifier;

/**
 * The game's default font for the robot camera: its bitmap glyph sheets (font/default.json in the client jar),
 * measured and placed the way the game's text renderer does it. Characters only the game's Unicode fallback font
 * has (most non-European scripts) are drawn as the game's missing-glyph box.
 */
final class MineBotCameraFont {
    private static final double MISSING_ADVANCE = 6.0D;
    private static volatile MineBotCameraFont instance;

    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Map<Integer, Double> spaces = new HashMap<>();

    private MineBotCameraFont() {
    }

    static MineBotCameraFont get(MineBotCameraAssets assets) {
        MineBotCameraFont font = instance;
        if (font == null) {
            synchronized (MineBotCameraFont.class) {
                font = instance;
                if (font == null) {
                    font = new MineBotCameraFont();
                    font.loadProviders(assets, Identifier.ofVanilla("default"), 0);
                    instance = font;
                }
            }
        }
        return font;
    }

    // The first provider that has a character wins, as in the game.
    private void loadProviders(MineBotCameraAssets assets, Identifier fontId, int depth) {
        JsonObject definition = assets.json(fontId.getNamespace(), "font/" + fontId.getPath() + ".json");
        if (definition == null || depth > 4) {
            return;
        }
        for (JsonElement element : definition.getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            String type = provider.get("type").getAsString().replace("minecraft:", "");
            try {
                switch (type) {
                    case "reference" -> this.loadProviders(assets, Identifier.of(provider.get("id").getAsString()), depth + 1);
                    case "space" -> {
                        for (Map.Entry<String, JsonElement> advance : provider.getAsJsonObject("advances").entrySet()) {
                            this.spaces.putIfAbsent(advance.getKey().codePointAt(0), advance.getValue().getAsDouble());
                        }
                    }
                    case "bitmap" -> this.loadBitmap(assets, provider);
                    default -> {
                    }
                }
            } catch (RuntimeException exception) {
                MineBotMod.LOGGER.debug("MineBot camera skipped a font provider in {}", fontId, exception);
            }
        }
    }

    private void loadBitmap(MineBotCameraAssets assets, JsonObject provider) {
        Identifier file = Identifier.of(provider.get("file").getAsString());
        MineBotCameraAssets.Texture sheet = assets.image(file.getNamespace(), "textures/" + file.getPath());
        JsonArray rows = provider.getAsJsonArray("chars");
        if (sheet == null || rows == null || rows.isEmpty()) {
            return;
        }
        int columns = (int) rows.get(0).getAsString().codePoints().count();
        int cellWidth = sheet.width() / columns;
        int cellHeight = sheet.height() / rows.size();
        int height = provider.has("height") ? provider.get("height").getAsInt() : 8;
        int ascent = provider.get("ascent").getAsInt();
        double scale = (double) height / cellHeight;

        for (int row = 0; row < rows.size(); row++) {
            int[] codepoints = rows.get(row).getAsString().codePoints().toArray();
            for (int column = 0; column < codepoints.length; column++) {
                int codepoint = codepoints[column];
                if (codepoint == 0 || this.glyphs.containsKey(codepoint) || this.spaces.containsKey(codepoint)) {
                    continue;
                }
                int[] cell = new int[cellWidth * cellHeight];
                int inkWidth = 0;
                for (int y = 0; y < cellHeight; y++) {
                    for (int x = 0; x < cellWidth; x++) {
                        int argb = sheet.argb()[(row * cellHeight + y) * sheet.width() + column * cellWidth + x];
                        cell[y * cellWidth + x] = argb;
                        if ((argb >>> 24) != 0) {
                            inkWidth = Math.max(inkWidth, x + 1);
                        }
                    }
                }
                this.glyphs.put(codepoint, new Glyph(cell, cellWidth, cellHeight, scale, ascent, (int) (0.5D + inkWidth * scale) + 1));
            }
        }
    }

    double advance(int codepoint) {
        Double space = this.spaces.get(codepoint);
        if (space != null) {
            return space;
        }
        Glyph glyph = this.glyphs.get(codepoint);
        return glyph == null ? MISSING_ADVANCE : glyph.advance();
    }

    double width(String text) {
        return text.codePoints().mapToDouble(this::advance).sum();
    }

    /** The longest start of the text that fits the width, as a sign shows only its first wrapped line. */
    String fit(String text, double maxWidth) {
        double width = 0.0D;
        int end = 0;
        for (int index = 0; index < text.length(); ) {
            int codepoint = text.codePointAt(index);
            width += this.advance(codepoint);
            if (width > maxWidth) {
                break;
            }
            index += Character.charCount(codepoint);
            end = index;
        }
        return text.substring(0, end);
    }

    /** Draws text whose top-left is at (x, y) in text units onto the canvas. Only ink pixels are written, in the given ARGB colour. */
    void draw(Canvas canvas, String text, double x, double y, int color) {
        double cursor = x;
        for (int index = 0; index < text.length(); ) {
            int codepoint = text.codePointAt(index);
            index += Character.charCount(codepoint);
            if (this.spaces.containsKey(codepoint)) {
                cursor += this.spaces.get(codepoint);
                continue;
            }
            Glyph glyph = this.glyphs.get(codepoint);
            if (glyph == null) {
                // The game's missing glyph: a 5x8 outline.
                for (int gy = 0; gy < 8; gy++) {
                    for (int gx = 0; gx < 5; gx++) {
                        if (gx == 0 || gx == 4 || gy == 0 || gy == 7) {
                            canvas.fill(cursor + gx, y + gy, 1.0D, color);
                        }
                    }
                }
                cursor += MISSING_ADVANCE;
                continue;
            }
            double top = y + 7 - glyph.ascent();
            for (int gy = 0; gy < glyph.cellHeight(); gy++) {
                for (int gx = 0; gx < glyph.cellWidth(); gx++) {
                    if ((glyph.cell()[gy * glyph.cellWidth() + gx] >>> 24) != 0) {
                        canvas.fill(cursor + gx * glyph.scale(), top + gy * glyph.scale(), glyph.scale(), color);
                    }
                }
            }
            cursor += glyph.advance();
        }
    }

    private record Glyph(int[] cell, int cellWidth, int cellHeight, double scale, int ascent, double advance) {
    }

    /** A transparent ARGB image covering a rectangle of text space. */
    static final class Canvas {
        final int width;
        final int height;
        final int[] argb;
        private final double originX;
        private final double originY;
        private final double pixelsPerUnit;

        Canvas(double originX, double originY, double unitsWide, double unitsHigh, double pixelsPerUnit) {
            this.originX = originX;
            this.originY = originY;
            this.pixelsPerUnit = pixelsPerUnit;
            this.width = (int) Math.ceil(unitsWide * pixelsPerUnit);
            this.height = (int) Math.ceil(unitsHigh * pixelsPerUnit);
            this.argb = new int[this.width * this.height];
        }

        /** Fills a square of the given size (text units) whose top-left is at x, y. */
        void fill(double x, double y, double size, int color) {
            int x0 = (int) Math.floor((x - this.originX) * this.pixelsPerUnit + 1.0E-6D);
            int y0 = (int) Math.floor((y - this.originY) * this.pixelsPerUnit + 1.0E-6D);
            int x1 = Math.max(x0 + 1, (int) Math.floor((x + size - this.originX) * this.pixelsPerUnit + 1.0E-6D));
            int y1 = Math.max(y0 + 1, (int) Math.floor((y + size - this.originY) * this.pixelsPerUnit + 1.0E-6D));
            for (int py = Math.max(0, y0); py < Math.min(this.height, y1); py++) {
                for (int px = Math.max(0, x0); px < Math.min(this.width, x1); px++) {
                    this.argb[py * this.width + px] = color;
                }
            }
        }

        boolean isEmpty() {
            for (int value : this.argb) {
                if (value != 0) {
                    return false;
                }
            }
            return true;
        }

        MineBotCameraAssets.Texture texture() {
            return new MineBotCameraAssets.Texture(this.width, this.height, this.argb);
        }
    }
}
