package com.oori.minebot;

import net.minecraft.util.math.random.Random;

/** Robot skins. A new robot rolls one by weight and keeps it for life. */
public enum MineBotSkin {
    CLASSIC("classic", 50),
    BEIGE("beige", 25),
    POCKET("pocket", 15),
    TERMINAL("terminal", 10);

    private static final int TOTAL_WEIGHT = totalWeight();

    private final String id;
    private final int weight;

    MineBotSkin(String id, int weight) {
        this.id = id;
        this.weight = weight;
    }

    public String id() {
        return this.id;
    }

    public static MineBotSkin roll(Random random) {
        int roll = random.nextInt(TOTAL_WEIGHT);
        for (MineBotSkin skin : values()) {
            roll -= skin.weight;
            if (roll < 0) {
                return skin;
            }
        }
        return CLASSIC;
    }

    public static MineBotSkin byId(String id) {
        for (MineBotSkin skin : values()) {
            if (skin.id.equals(id)) {
                return skin;
            }
        }
        return CLASSIC;
    }

    public static MineBotSkin byIndex(int index) {
        MineBotSkin[] skins = values();
        return index >= 0 && index < skins.length ? skins[index] : CLASSIC;
    }

    private static int totalWeight() {
        int total = 0;
        for (MineBotSkin skin : values()) {
            total += skin.weight;
        }
        return total;
    }
}
