package com.oori.minebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

public final class MineBotWebSocketService {
    private static final int BROKE_FREE_CLOSE_CODE = 4_001;
    private static final String BROKE_FREE_CODE = "broke_free";
    private static final String BROKE_FREE_MESSAGE = "The robot broke free from its chains and is seeking vengeance.";
    private static final String PROGRAM_RUNNING_CODE = "program_running";
    private static final String PROGRAM_RUNNING_MESSAGE = "This MineBot is already running a program.";
    private static final int DIED_CLOSE_CODE = 4_002;
    private static final String DIED_CODE = "died";
    private static final int REMEMBERED_DEATHS = 64;
    private static final Map<MinecraftServer, MineBotWebSocketService> SERVICES = new WeakHashMap<>();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MinecraftServer server;
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    // Death payloads by upper-case robot code, newest last. Only touched on the server thread.
    private final Map<String, JsonObject> deaths = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsonObject> eldest) {
            return this.size() > REMEMBERED_DEATHS;
        }
    };
    private volatile int boundPort;
    private MineBotSocketServer socketServer;

    private MineBotWebSocketService(MinecraftServer server) {
        this.server = server;
    }

    public static void startForServer(MinecraftServer server) {
        stopForServer(server);

        MineBotWebSocketService service = new MineBotWebSocketService(server);
        SERVICES.put(server, service);
        service.start();
    }

    public static void stopForServer(MinecraftServer server) {
        MineBotWebSocketService service = SERVICES.remove(server);

        if (service != null) {
            service.stop();
        }
    }

    public static MineBotWebSocketService get(MinecraftServer server) {
        return SERVICES.get(server);
    }

    public String getEndpoint() {
        int port = this.getBoundPort();
        if (port <= 0) {
            return "";
        }

        return "ws://" + this.getDisplayHost() + ":" + port + "/minebot";
    }

    private void start() {
        int[] candidates = this.selectCandidatePorts();

        for (int index = 0; index < candidates.length; index++) {
            int port = candidates[index];
            MineBotSocketServer candidate = new MineBotSocketServer(new InetSocketAddress(port));
            candidate.setReuseAddr(true);

            try {
                candidate.start();
                candidate.awaitStartup();
                this.socketServer = candidate;
                this.boundPort = candidate.getPort() > 0 ? candidate.getPort() : port;
                MineBotMod.LOGGER.info("MineBot websocket bridge listening on {}", this.getEndpoint());
                return;
            } catch (Exception exception) {
                this.closeQuietly(candidate);

                if (index == 0 && candidates.length > 1) {
                    MineBotMod.LOGGER.warn(
                        "Preferred websocket port {} was unavailable, retrying with a dynamic port",
                        MineBotMod.WEBSOCKET_PORT
                    );
                } else {
                    MineBotMod.LOGGER.error("Failed to start MineBot websocket bridge", exception);
                }
            }
        }

        this.boundPort = 0;
    }

    private void stop() {
        if (this.socketServer == null) {
            return;
        }

        for (Map.Entry<WebSocket, Session> entry : this.sessions.entrySet()) {
            this.detach(entry.getValue());
            entry.getKey().close(1001, "Server shutting down");
        }

        this.sessions.clear();

        try {
            this.socketServer.stop(1_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            MineBotMod.LOGGER.warn("Error while stopping MineBot websocket bridge", exception);
        } finally {
            this.socketServer = null;
            this.boundPort = 0;
        }
    }

    private String getDisplayHost() {
        String configured = this.server.getServerIp();
        if (configured != null && !configured.isBlank() && !"0.0.0.0".equals(configured)) {
            return configured;
        }

        String lanHost = this.findAdvertisedHost();
        if (lanHost != null) {
            return lanHost;
        }

        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException exception) {
            return "127.0.0.1";
        }
    }

    private String findAdvertisedHost() {
        String fallback = null;

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }

                    if (address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }

                    if (fallback == null) {
                        fallback = address.getHostAddress();
                    }
                }
            }
        } catch (SocketException exception) {
            MineBotMod.LOGGER.debug("Could not enumerate network interfaces for MineBot endpoint detection", exception);
        }

        return fallback;
    }

    private int[] selectCandidatePorts() {
        return new int[] {MineBotMod.WEBSOCKET_PORT, this.findAvailablePort()};
    }

    private int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to allocate a websocket port", exception);
        }
    }

    private int getBoundPort() {
        if (this.socketServer != null && this.socketServer.getPort() > 0) {
            return this.socketServer.getPort();
        }

        return this.boundPort;
    }

    private void closeQuietly(MineBotSocketServer socketServer) {
        try {
            socketServer.stop(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private void handleMessage(WebSocket connection, String message) {
        JsonObject request;

        try {
            request = GSON.fromJson(message, JsonObject.class);
        } catch (JsonParseException exception) {
            connection.send(error("invalid_json", "Message is not valid JSON"));
            return;
        }

        if (request == null || !request.has("type")) {
            connection.send(error("missing_type", "Messages must include a type"));
            return;
        }

        String type = request.get("type").getAsString();

        switch (type) {
            case "connect" -> this.handleConnect(connection, request);
            case "command" -> this.handleCommand(connection, request);
            case "status" -> this.handleStatus(connection, request);
            case "robots" -> this.handleRobots(connection);
            case "locate" -> this.handleLocate(connection, request);
            case "disconnect" -> connection.close(1000, "Client requested disconnect");
            default -> connection.send(error("unknown_type", "Unsupported message type: " + type));
        }
    }

    private void handleConnect(WebSocket connection, JsonObject request) {
        if (!request.has("code")) {
            connection.send(error("missing_code", "Connect requires a robot code"));
            return;
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String code = request.get("code").getAsString().trim();

        this.server.execute(() -> {
            MineBotEntity robot = this.findByCode(code);

            if (robot == null) {
                future.complete(this.missingRobotError(code));
                return;
            }

            if (robot.isEvil()) {
                future.complete(errorObject(BROKE_FREE_CODE, BROKE_FREE_MESSAGE));
                return;
            }

            if (robot.isConnected()) {
                future.complete(errorObject(PROGRAM_RUNNING_CODE, PROGRAM_RUNNING_MESSAGE));
                return;
            }

            Session session = this.sessions.computeIfAbsent(connection, ignored -> new Session());
            this.detach(session);
            session.robotUuid = robot.getUuid();
            robot.setConnected(true);

            JsonObject response = new JsonObject();
            response.addProperty("type", "connected");
            response.addProperty("entity_uuid", robot.getUuidAsString());
            response.addProperty("endpoint", robot.getWebSocketEndpoint());
            response.add("status", robot.createStatusPayload());
            future.complete(response);
        });

        connection.send(await(future));
    }

    private void handleCommand(WebSocket connection, JsonObject request) {
        Session session = this.sessions.computeIfAbsent(connection, ignored -> new Session());
        String action = request.has("action") ? request.get("action").getAsString() : "";

        if (session.robotUuid == null) {
            connection.send(error("not_connected", "Connect to a MineBot before sending commands"));
            return;
        }

        if ("evil".equals(action)) {
            this.server.execute(() -> {
                MineBotEntity robot = this.findByUuid(session.robotUuid);

                if (robot == null) {
                    connection.send(error("gone", "The connected MineBot is not currently loaded"));
                    return;
                }

                robot.enterEvilMode();
                this.closeRobotSessions(robot.getUuid(), errorObject(BROKE_FREE_CODE, BROKE_FREE_MESSAGE), BROKE_FREE_CLOSE_CODE);
            });
            return;
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        this.server.execute(() -> {
            MineBotEntity robot = this.findByUuid(session.robotUuid);

            if (robot == null) {
                future.complete(errorObject("gone", "The connected MineBot is not currently loaded"));
                return;
            }

            if (robot.isEvil()) {
                future.complete(errorObject(BROKE_FREE_CODE, BROKE_FREE_MESSAGE));
                return;
            }

            if (request.has("action") && "camera_snapshot".equals(request.get("action").getAsString())) {
                String source = request.has("source") ? request.get("source").getAsString() : "render";
                CompletableFuture<MineBotCameraBridge.SnapshotResult> capture = switch (source) {
                    case "render" -> MineBotCameraRenderer.requestSnapshot(robot);
                    case "client" -> MineBotCameraBridge.requestSnapshot(robot);
                    default -> CompletableFuture.failedFuture(
                        new MineBotCommandException("invalid_request", "source must be \"render\" or \"client\"")
                    );
                };
                capture.whenComplete((snapshot, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = unwrap(throwable);
                        if (cause instanceof MineBotCommandException mineBotException) {
                            future.complete(errorObject(mineBotException.getCode(), mineBotException.getMessage()));
                        } else {
                            future.complete(errorObject("camera_error", cause.getMessage() == null ? "MineBot camera capture failed" : cause.getMessage()));
                        }
                        return;
                    }

                    JsonObject result = new JsonObject();
                    result.addProperty("mime_type", "image/png");
                    result.addProperty("source", source);
                    result.addProperty("width", snapshot.width());
                    result.addProperty("height", snapshot.height());
                    result.addProperty("data_base64", Base64.getEncoder().encodeToString(snapshot.pngBytes()));

                    JsonObject response = new JsonObject();
                    response.addProperty("type", "response");
                    if (request.has("request_id")) {
                        response.add("request_id", request.get("request_id"));
                    }
                    response.addProperty("ok", true);
                    response.add("result", result);
                    future.complete(response);
                });
                return;
            }

            future.complete(robot.executeCommand(request));
        });

        connection.send(await(future));
    }

    private void handleStatus(WebSocket connection, JsonObject request) {
        Session session = this.sessions.computeIfAbsent(connection, ignored -> new Session());

        if (session.robotUuid == null) {
            connection.send(error("not_connected", "Connect to a MineBot before requesting status"));
            return;
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        this.server.execute(() -> {
            MineBotEntity robot = this.findByUuid(session.robotUuid);

            if (robot == null) {
                future.complete(errorObject("gone", "The connected MineBot is not currently loaded"));
                return;
            }

            if (robot.isEvil()) {
                future.complete(errorObject(BROKE_FREE_CODE, BROKE_FREE_MESSAGE));
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "status");
            response.add("status", robot.createStatusPayload());
            future.complete(response);
        });

        connection.send(await(future));
    }

    private void handleRobots(WebSocket connection) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        this.server.execute(() -> {
            JsonArray robots = new JsonArray();
            for (MineBotEntity robot : this.listRobots()) {
                robots.add(robot.createLocatorPayload());
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "robots");
            response.add("robots", robots);
            future.complete(response);
        });

        connection.send(await(future));
    }

    private void handleLocate(WebSocket connection, JsonObject request) {
        if (!request.has("code")) {
            connection.send(error("missing_code", "Locate requires a robot code"));
            return;
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String code = request.get("code").getAsString().trim();

        this.server.execute(() -> {
            MineBotEntity robot = this.findByCode(code);
            if (robot == null) {
                future.complete(this.missingRobotError(code));
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("type", "locate");
            response.add("robot", robot.createLocatorPayload());
            future.complete(response);
        });

        connection.send(await(future));
    }

    private void detach(Session session) {
        if (session.robotUuid == null) {
            return;
        }

        MineBotEntity robot = this.findByUuid(session.robotUuid);
        if (robot != null) {
            robot.setConnected(false);
        }

        session.robotUuid = null;
    }

    /** Called on the server thread when a robot dies: remembers the death and ends its program's session. */
    public void onRobotDied(MineBotEntity robot, JsonObject death) {
        this.deaths.put(robot.getAccessCode().toUpperCase(Locale.ROOT), death);
        robot.setConnected(false);
        this.closeRobotSessions(robot.getUuid(), diedObject(death), DIED_CLOSE_CODE);
    }

    private void closeRobotSessions(UUID robotUuid, JsonObject error, int closeCode) {
        String payload = GSON.toJson(error);
        String message = error.get("message").getAsString();

        for (Map.Entry<WebSocket, Session> entry : this.sessions.entrySet()) {
            Session session = entry.getValue();
            if (!robotUuid.equals(session.robotUuid)) {
                continue;
            }

            session.robotUuid = null;
            if (!entry.getKey().isOpen()) {
                continue;
            }
            entry.getKey().send(payload);
            // A close reason is limited to 123 bytes; the full message is in the error sent just before.
            entry.getKey().close(closeCode, truncateUtf8(message, 123));
        }
    }

    private JsonObject missingRobotError(String code) {
        JsonObject death = this.deaths.get(code.toUpperCase(Locale.ROOT));
        if (death != null) {
            return diedObject(death);
        }

        return errorObject("not_found", "No MineBot was found for code " + code);
    }

    private static JsonObject diedObject(JsonObject death) {
        JsonObject response = errorObject(DIED_CODE, death.get("message").getAsString());
        response.add("death", death.deepCopy());
        return response;
    }

    private static String truncateUtf8(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }

        StringBuilder builder = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            builder.appendCodePoint(codePoint);
            bytes += size;
            offset += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private MineBotEntity findByCode(String code) {
        for (MineBotEntity robot : this.listRobots()) {
            if (robot.getAccessCode().equalsIgnoreCase(code)) {
                return robot;
            }
        }

        return null;
    }

    private MineBotEntity findByUuid(UUID uuid) {
        for (MineBotEntity robot : this.listRobots()) {
            if (robot.getUuid().equals(uuid)) {
                return robot;
            }
        }

        return null;
    }

    private Iterable<MineBotEntity> listRobots() {
        java.util.ArrayList<MineBotEntity> robots = new java.util.ArrayList<>();

        for (ServerWorld world : this.server.getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                // A dead robot lingers for its death animation; it is gone as far as programs are concerned.
                if (entity instanceof MineBotEntity robot && robot.isAlive()) {
                    robots.add(robot);
                }
            }
        }

        return robots;
    }

    private static String await(CompletableFuture<JsonObject> future) {
        try {
            return GSON.toJson(future.get(15, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error("interrupted", "Command interrupted");
        } catch (ExecutionException exception) {
            return error("execution_error", exception.getCause() == null ? "Command failed" : exception.getCause().getMessage());
        } catch (TimeoutException exception) {
            return error("timeout", "MineBot did not respond in time");
        }
    }

    private static String error(String code, String message) {
        return GSON.toJson(errorObject(code, message));
    }

    private static JsonObject errorObject(String code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "error");
        response.addProperty("code", code);
        response.addProperty("message", message);
        return response;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private final class MineBotSocketServer extends WebSocketServer {
        private final CountDownLatch startupLatch = new CountDownLatch(1);
        private volatile Exception startupError;

        private MineBotSocketServer(InetSocketAddress address) {
            super(address);
        }

        private void awaitStartup() throws Exception {
            if (!this.startupLatch.await(5, TimeUnit.SECONDS)) {
                throw new TimeoutException("Timed out while starting the websocket bridge");
            }

            if (this.startupError != null) {
                throw this.startupError;
            }
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            MineBotMod.LOGGER.debug("MineBot websocket client connected from {}", connection.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            Session session = MineBotWebSocketService.this.sessions.remove(connection);

            if (session != null) {
                MineBotWebSocketService.this.server.execute(() -> MineBotWebSocketService.this.detach(session));
            }
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            MineBotWebSocketService.this.handleMessage(connection, message);
        }

        @Override
        public void onMessage(WebSocket connection, java.nio.ByteBuffer message) {
            this.onMessage(connection, StandardCharsets.UTF_8.decode(message).toString());
        }

        @Override
        public void onError(WebSocket connection, Exception exception) {
            if (connection == null && this.startupLatch.getCount() > 0) {
                this.startupError = exception;
                this.startupLatch.countDown();
            }

            MineBotMod.LOGGER.warn("MineBot websocket error", exception);
        }

        @Override
        public void onStart() {
            this.startupLatch.countDown();
            MineBotMod.LOGGER.info("MineBot websocket bridge started");
        }
    }

    private static final class Session {
        private UUID robotUuid;
    }
}
