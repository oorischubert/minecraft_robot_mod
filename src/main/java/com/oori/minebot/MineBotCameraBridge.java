package com.oori.minebot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public final class MineBotCameraBridge {
    private static final int REQUEST_TIMEOUT_SECONDS = 10;
    private static final Map<String, CompletableFuture<SnapshotResult>> PENDING = new ConcurrentHashMap<>();

    private MineBotCameraBridge() {
    }

    public static void initialize() {
        ServerPlayNetworking.registerGlobalReceiver(MineBotCameraResponsePayload.ID, (payload, context) -> context.server().execute(() -> {
            CompletableFuture<SnapshotResult> future = PENDING.remove(payload.requestId());
            if (future == null) {
                return;
            }

            if (!payload.ok()) {
                future.completeExceptionally(new IllegalStateException(payload.message()));
                return;
            }

            future.complete(new SnapshotResult(payload.width(), payload.height(), payload.pngBytes()));
        }));
    }

    public static CompletableFuture<SnapshotResult> requestSnapshot(MineBotEntity robot) {
        if (!(robot.getEntityWorld() instanceof ServerWorld)) {
            return CompletableFuture.failedFuture(new IllegalStateException("MineBot camera is only available on a server world"));
        }

        ServerPlayerEntity player = robot.getOnlineOwner();
        if (player == null) {
            String ownerName = robot.getOwnerName();
            if (ownerName.isBlank()) {
                return CompletableFuture.failedFuture(
                    new MineBotCommandException("camera_owner_required", "This MineBot has no recorded owner for camera capture")
                );
            }

            return CompletableFuture.failedFuture(
                new MineBotCommandException(
                    "camera_owner_offline",
                    "Camera capture requires the MineBot owner " + ownerName + " to be online"
                )
            );
        }

        if (!ServerPlayNetworking.canSend(player, MineBotCameraRequestPayload.ID)) {
            return CompletableFuture.failedFuture(
                new MineBotCommandException(
                    "camera_owner_unavailable",
                    "The MineBot owner " + player.getName().getString() + " is online, but their client cannot capture MineBot camera frames"
                )
            );
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<SnapshotResult> future = new CompletableFuture<>();
        PENDING.put(requestId, future);

        CompletableFuture.delayedExecutor(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
            CompletableFuture<SnapshotResult> pending = PENDING.remove(requestId);
            if (pending != null) {
                pending.completeExceptionally(new IllegalStateException("Timed out while waiting for a MineBot camera frame"));
            }
        });

        ServerPlayNetworking.send(player, new MineBotCameraRequestPayload(requestId, robot.getId()));
        return future;
    }

    public record SnapshotResult(int width, int height, byte[] pngBytes) {
    }
}
