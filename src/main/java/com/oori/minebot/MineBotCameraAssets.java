package com.oori.minebot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.DryFoliageColors;
import net.minecraft.world.biome.FoliageColors;
import net.minecraft.world.biome.GrassColors;

/**
 * Minecraft's block models and textures for the server-side robot camera.
 * They ship only with the game client. In singleplayer they are on the class path already; a dedicated
 * server reads them from the official client jar, either a local copy named in config/minebot.properties
 * or one downloaded from Mojang once and kept in minebot/ in the server folder. Nothing is bundled.
 */
final class MineBotCameraAssets {
    static final String UNAVAILABLE = "camera_assets_unavailable";

    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String PROBE_RESOURCE = "assets/minecraft/textures/block/stone.png";
    private static final String CONFIG_FILE = "minebot.properties";
    private static final String CONFIG_TEMPLATE = """
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
        """;
    private static final Object LOCK = new Object();
    private static CompletableFuture<MineBotCameraAssets> loading;

    private final ZipFile clientJar;
    private final ClassLoader classLoader = MineBotCameraAssets.class.getClassLoader();
    private final Map<String, Optional<JsonObject>> jsonCache = new ConcurrentHashMap<>();
    private final Map<Identifier, Texture> textureCache = new ConcurrentHashMap<>();
    private final Map<String, Optional<Texture>> imageCache = new ConcurrentHashMap<>();

    private MineBotCameraAssets(ZipFile clientJar) {
        this.clientJar = clientJar;
    }

    /** Starts loading in the background so the first snapshot does not wait for a download. */
    static void preload() {
        get();
    }

    /** The loaded assets. A failed load is retried on the next call. */
    static CompletableFuture<MineBotCameraAssets> get() {
        synchronized (LOCK) {
            if (loading == null || loading.isCompletedExceptionally()) {
                loading = CompletableFuture.supplyAsync(MineBotCameraAssets::load, runnable -> {
                    Thread thread = new Thread(runnable, "MineBot camera assets");
                    thread.setDaemon(true);
                    thread.start();
                });
            }
            return loading;
        }
    }

    private static MineBotCameraAssets load() {
        Settings settings = Settings.read();
        String version = SharedConstants.getGameVersion().id();

        MineBotCameraAssets assets;
        if (settings.clientJar() != null) {
            assets = open(settings.clientJar(), version);
        } else if (MineBotCameraAssets.class.getClassLoader().getResource(PROBE_RESOURCE) != null) {
            assets = new MineBotCameraAssets(null);
        } else {
            Path cached = FabricLoader.getInstance().getGameDir().resolve("minebot").resolve("minecraft-client-" + version + ".jar");
            if (!Files.isRegularFile(cached)) {
                if (!settings.download()) {
                    throw new MineBotCommandException(
                        UNAVAILABLE,
                        "The robot camera needs Minecraft's block textures. Set camera.client_jar in config/" + CONFIG_FILE
                            + " to a Minecraft " + version + " client jar, or set camera.download_client_jar=true"
                    );
                }
                download(version, cached);
            }
            assets = open(cached, version);
        }

        assets.installColorMaps();
        MineBotMod.LOGGER.info("MineBot camera textures ready ({})", assets.clientJar == null ? "game class path" : assets.clientJar.getName());
        return assets;
    }

    private static MineBotCameraAssets open(Path jar, String version) {
        try {
            ZipFile zip = new ZipFile(jar.toFile());
            if (zip.getEntry(PROBE_RESOURCE) == null) {
                zip.close();
                throw new MineBotCommandException(UNAVAILABLE, jar + " is not a Minecraft client jar (it has no block textures)");
            }
            return new MineBotCameraAssets(zip);
        } catch (IOException exception) {
            throw new MineBotCommandException(
                UNAVAILABLE,
                "Could not open " + jar + " for camera textures (" + exception.getMessage() + "). Point camera.client_jar in config/"
                    + CONFIG_FILE + " at a Minecraft " + version + " client jar"
            );
        }
    }

    private static void download(String version, Path target) {
        HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        try {
            String versionUrl = null;
            for (JsonElement entry : fetchJson(http, VERSION_MANIFEST_URL).getAsJsonArray("versions")) {
                JsonObject candidate = entry.getAsJsonObject();
                if (version.equals(candidate.get("id").getAsString())) {
                    versionUrl = candidate.get("url").getAsString();
                    break;
                }
            }
            if (versionUrl == null) {
                throw new IOException("Mojang's version list has no Minecraft " + version);
            }

            JsonObject client = fetchJson(http, versionUrl).getAsJsonObject("downloads").getAsJsonObject("client");
            String url = client.get("url").getAsString();
            String sha1 = client.get("sha1").getAsString();

            MineBotMod.LOGGER.info("MineBot camera: downloading the Minecraft {} client jar from Mojang for block textures ({})", version, url);
            Files.createDirectories(target.getParent());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            HttpResponse<Path> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofFile(partial)
            );
            if (response.statusCode() != 200) {
                Files.deleteIfExists(partial);
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }
            if (!sha1.equalsIgnoreCase(sha1Of(partial))) {
                Files.deleteIfExists(partial);
                throw new IOException("the downloaded client jar failed its SHA-1 check");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new MineBotCommandException(
                UNAVAILABLE,
                "Could not download the Minecraft " + version + " client jar for camera textures (" + exception.getMessage()
                    + "). Set camera.client_jar in config/" + CONFIG_FILE + " to a local copy"
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MineBotCommandException(UNAVAILABLE, "The camera texture download was interrupted");
        }
    }

    private static JsonObject fetchJson(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + url);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String sha1Of(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
    }

    // The game client installs biome colour maps itself; a dedicated server has none, so biomes would
    // report black grass and leaves without these.
    private void installColorMaps() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }
        int[] grass = this.colorMap("grass");
        int[] foliage = this.colorMap("foliage");
        int[] dryFoliage = this.colorMap("dry_foliage");
        if (grass != null) {
            GrassColors.setColorMap(grass);
        }
        if (foliage != null) {
            FoliageColors.setColorMap(foliage);
        }
        if (dryFoliage != null) {
            DryFoliageColors.setColorMap(dryFoliage);
        }
    }

    private int[] colorMap(String name) {
        BufferedImage image = decode(this.read("assets/minecraft/textures/colormap/" + name + ".png"));
        if (image == null || image.getWidth() != 256 || image.getHeight() != 256) {
            return null;
        }
        return image.getRGB(0, 0, 256, 256, null, 0, 256);
    }

    /** Raw bytes of a resource such as "assets/minecraft/models/block/stone.json", or null. */
    byte[] read(String path) {
        try {
            try (InputStream in = this.classLoader.getResourceAsStream(path)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            }
            if (this.clientJar != null) {
                ZipEntry entry = this.clientJar.getEntry(path);
                if (entry != null) {
                    try (InputStream in = this.clientJar.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
        } catch (IOException exception) {
            MineBotMod.LOGGER.debug("MineBot camera could not read {}", path, exception);
        }
        return null;
    }

    /** A JSON file under assets/<namespace>/, such as "blockstates/stone.json", or null. */
    JsonObject json(String namespace, String path) {
        return this.jsonCache.computeIfAbsent(namespace + ":" + path, ignored -> {
            byte[] bytes = this.read("assets/" + namespace + "/" + path);
            if (bytes == null) {
                return Optional.empty();
            }
            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                return Optional.of(JsonParser.parseReader(reader).getAsJsonObject());
            } catch (IOException | RuntimeException exception) {
                MineBotMod.LOGGER.debug("MineBot camera could not parse {}:{}", namespace, path, exception);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** A texture such as minecraft:block/stone. Animated textures give their first frame; missing ones give the magenta check. */
    Texture texture(Identifier id) {
        return this.textureCache.computeIfAbsent(id, ignored -> {
            BufferedImage image = decode(this.read("assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png"));
            if (image == null) {
                return Texture.MISSING;
            }
            int width = image.getWidth();
            int height = image.getHeight() > width && image.getHeight() % width == 0 ? width : image.getHeight();
            return new Texture(width, height, image.getRGB(0, 0, width, height, null, 0, width));
        });
    }

    /** A whole image under assets/, such as "minecraft", "textures/entity/zombie/zombie.png", or null when missing. */
    Texture image(String namespace, String path) {
        return this.imageCache.computeIfAbsent(namespace + ":" + path, ignored -> {
            BufferedImage image = decode(this.read("assets/" + namespace + "/" + path));
            return image == null ? Optional.empty() : Optional.of(Texture.of(image));
        }).orElse(null);
    }

    static BufferedImage decode(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException exception) {
            return null;
        }
    }

    record Texture(int width, int height, int[] argb) {
        static final Texture MISSING = new Texture(2, 2, new int[] {0xFFF800F8, 0xFF000000, 0xFF000000, 0xFFF800F8});

        static Texture of(BufferedImage image) {
            return new Texture(image.getWidth(), image.getHeight(), image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()));
        }

        /** ARGB at texture coordinates u, v in 0..1. */
        int sample(double u, double v) {
            int x = Math.min(this.width - 1, Math.max(0, (int) (u * this.width)));
            int y = Math.min(this.height - 1, Math.max(0, (int) (v * this.height)));
            return this.argb[y * this.width + x];
        }
    }

    private record Settings(Path clientJar, boolean download) {
        static Settings read() {
            Path file = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);
            Properties properties = new Properties();
            try {
                if (Files.isRegularFile(file)) {
                    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        properties.load(reader);
                    }
                } else {
                    Files.createDirectories(file.getParent());
                    try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                        writer.write(CONFIG_TEMPLATE);
                    }
                }
            } catch (IOException exception) {
                MineBotMod.LOGGER.warn("MineBot could not read or create config/{}: {}", CONFIG_FILE, exception.getMessage());
            }

            String jar = properties.getProperty("camera.client_jar", "").trim();
            boolean download = !"false".equalsIgnoreCase(properties.getProperty("camera.download_client_jar", "true").trim());
            return new Settings(jar.isEmpty() ? null : FabricLoader.getInstance().getGameDir().resolve(jar), download);
        }
    }
}
