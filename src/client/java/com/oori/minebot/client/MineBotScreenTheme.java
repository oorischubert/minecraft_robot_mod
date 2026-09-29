package com.oori.minebot.client;

import com.oori.minebot.MineBotSkin;

/** Robot screen colours for each skin, taken from the skin textures (tools/generate_skins.py). */
record MineBotScreenTheme(
    int caseFill,
    int caseHi,
    int caseLo,
    int outline,
    int bezel,
    int bezelHi,
    int bezelLo,
    int screen,
    int scanline,
    int text,
    int textDim,
    int label,
    int slotFill,
    int slotDark,
    int slotLight,
    int accent
) {
    static final int LED_CONNECTED = 0xFF46E068;
    static final int LED_IDLE = 0xFFECA228;

    private static final MineBotScreenTheme CLASSIC = new MineBotScreenTheme(
        0xFF7E8791, 0xFFA0A8B2, 0xFF5E6670, 0xFF14191E,
        0xFFC8CFD7, 0xFFE4E9EE, 0xFF969EA8,
        0xFF0C181E, 0xFF112027, 0xFF7AEEFF, 0xFF43838F,
        0xFF1E2329,
        0xFF5E6670, 0xFF464D56, 0xFFA0A8B2,
        0xFF7AEEFF
    );
    private static final MineBotScreenTheme BEIGE = new MineBotScreenTheme(
        0xFFCCC2A8, 0xFFE0D8C2, 0xFFA89E84, 0xFF1E1C1A,
        0xFFE0D7C0, 0xFFF0EAD8, 0xFFBAB098,
        0xFF0A160C, 0xFF0E1E11, 0xFF70FF84, 0xFF3D8B48,
        0xFF4A4232,
        0xFFA89E84, 0xFF6C6454, 0xFFE0D8C2,
        0xFF28963C
    );
    private static final MineBotScreenTheme POCKET = new MineBotScreenTheme(
        0xFFBEBEB6, 0xFFD6D6CE, 0xFF9A9A92, 0xFF282832,
        0xFFC6C6BE, 0xFFDEDED6, 0xFFA0A098,
        0xFF9EB63A, 0xFF9EB63A, 0xFF263E1E, 0xFF607A30,
        0xFF46464E,
        0xFF9A9A92, 0xFF666662, 0xFFD6D6CE,
        0xFFAC2860
    );
    private static final MineBotScreenTheme TERMINAL = new MineBotScreenTheme(
        0xFF504C48, 0xFF6A6560, 0xFF3A3734, 0xFF100E0C,
        0xFF42403E, 0xFF5E5A56, 0xFF2C2A29,
        0xFF180F06, 0xFF211509, 0xFFFFB232, 0xFF8C611C,
        0xFFC8C0B0,
        0xFF3A3734, 0xFF262422, 0xFF6A6560,
        0xFFFFB232
    );

    static MineBotScreenTheme of(MineBotSkin skin) {
        return switch (skin) {
            case CLASSIC -> CLASSIC;
            case BEIGE -> BEIGE;
            case POCKET -> POCKET;
            case TERMINAL -> TERMINAL;
        };
    }
}
