package com.oori.minebot;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** config/minebot.properties. Written with every setting at its default when it does not exist yet. */
final class MineBotConfig {
    private static final String FILE = "minebot.properties";
    private static final String TEMPLATE = """
        # MineBot settings. Changes take effect on the next server start.
        #
        # The robot camera draws its pictures on the server with Minecraft's own block textures and models.
        # A dedicated server does not ship them, so the mod reads them from the official Minecraft client jar.
        #
        # camera.client_jar: path to a local copy of the client jar for this Minecraft version
        #   (absolute, or relative to the server folder). Leave empty to use the download below.
        # camera.download_client_jar: when camera.client_jar is empty, download the jar once from Mojang
        #   (piston-data.mojang.com) into minebot/ in the server folder. Set to false to never download.
        camera.client_jar=
        camera.download_client_jar=true

        # A robot keeps the chunks around it loaded while a program drives it or it is moving or mining.
        # chunks.hold_seconds: how long it keeps them loaded after that, so the robot and the items it dropped
        #   are still there for the next task. It also keeps them loaded for as long as it is falling, burning,
        #   in lava, under water or short of air. 0 lets them unload as soon as the robot is idle and safe.
        chunks.hold_seconds=300
        """;

    private MineBotConfig() {
    }

    static Properties read() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE);
        Properties properties = new Properties();
        try {
            if (Files.isRegularFile(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            } else {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    writer.write(TEMPLATE);
                }
            }
        } catch (IOException exception) {
            MineBotMod.LOGGER.warn("MineBot could not read or create config/{}: {}", FILE, exception.getMessage());
        }
        return properties;
    }

    /** A whole number of at least 0; the default when the setting is missing or not a number. */
    static int readNonNegativeInt(Properties properties, String key, int defaultValue) {
        String raw = properties.getProperty(key, "").trim();
        if (raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException exception) {
            MineBotMod.LOGGER.warn("Ignoring {}={} in config/{}: not a whole number", key, raw, FILE);
            return defaultValue;
        }
    }
}
