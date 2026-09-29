package com.oori.minebot.client;

import com.oori.minebot.MineBotCameraRequestPayload;
import com.oori.minebot.MineBotCameraResponsePayload;
import com.oori.minebot.MineBotEntity;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;

public final class MineBotCameraClient {
    private static final int MAX_CAPTURE_WIDTH = 640;
    private static PendingCapture pendingCapture;

    private MineBotCameraClient() {
    }

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(MineBotCameraRequestPayload.ID, (payload, context) ->
            context.client().execute(() -> handleSnapshotRequest(context.client(), payload))
        );
        WorldRenderEvents.END_MAIN.register(context -> capturePendingFrame(MinecraftClient.getInstance()));
    }

    private static void handleSnapshotRequest(MinecraftClient client, MineBotCameraRequestPayload payload) {
        if (pendingCapture != null) {
            sendResponse(MineBotCameraResponsePayload.error(payload.requestId(), "A MineBot camera capture is already in progress"));
            return;
        }

        if (client.world == null || client.player == null) {
            sendResponse(MineBotCameraResponsePayload.error(payload.requestId(), "The client world is not ready for camera capture"));
            return;
        }

        if (!(client.world.getEntityById(payload.entityId()) instanceof MineBotEntity robot)) {
            sendResponse(MineBotCameraResponsePayload.error(payload.requestId(), "The requested MineBot is not loaded on the client"));
            return;
        }

        Entity previousCamera = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        pendingCapture = new PendingCapture(payload.requestId(), robot, previousCamera, client.options.getPerspective());
        client.options.setPerspective(Perspective.FIRST_PERSON);
        client.setCameraEntity(robot);
    }

    private static void capturePendingFrame(MinecraftClient client) {
        PendingCapture capture = pendingCapture;
        if (capture == null) {
            return;
        }

        if (client.world == null || !capture.robot.isAlive()) {
            pendingCapture = null;
            restoreCamera(client, capture);
            sendResponse(MineBotCameraResponsePayload.error(capture.requestId, "The MineBot camera is no longer available"));
            return;
        }

        pendingCapture = null;
        ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), nativeImage -> {
            try (nativeImage) {
                EncodedSnapshot snapshot = encodeSnapshot(nativeImage);
                sendResponse(new MineBotCameraResponsePayload(capture.requestId, true, "", snapshot.width, snapshot.height, snapshot.pngBytes));
            } catch (Exception exception) {
                sendResponse(MineBotCameraResponsePayload.error(capture.requestId, exception.getMessage()));
            } finally {
                restoreCamera(client, capture);
            }
        });
    }

    private static EncodedSnapshot encodeSnapshot(NativeImage nativeImage) throws IOException {
        BufferedImage image = new BufferedImage(nativeImage.getWidth(), nativeImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, nativeImage.getWidth(), nativeImage.getHeight(), nativeImage.copyPixelsArgb(), 0, nativeImage.getWidth());

        BufferedImage output = image;
        int targetWidth = image.getWidth();
        int targetHeight = image.getHeight();

        if (image.getWidth() > MAX_CAPTURE_WIDTH) {
            targetWidth = MAX_CAPTURE_WIDTH;
            targetHeight = Math.max(1, Math.round(image.getHeight() * (targetWidth / (float) image.getWidth())));
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null);
            graphics.dispose();
            output = scaled;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(output, "png", outputStream);
        return new EncodedSnapshot(targetWidth, targetHeight, outputStream.toByteArray());
    }

    private static void restoreCamera(MinecraftClient client, PendingCapture capture) {
        client.setCameraEntity(capture.previousCamera);
        client.options.setPerspective(capture.previousPerspective);
    }

    private static void sendResponse(MineBotCameraResponsePayload payload) {
        if (ClientPlayNetworking.canSend(MineBotCameraResponsePayload.ID)) {
            ClientPlayNetworking.send(payload);
        }
    }

    private record PendingCapture(String requestId, MineBotEntity robot, Entity previousCamera, Perspective previousPerspective) {
    }

    private record EncodedSnapshot(int width, int height, byte[] pngBytes) {
    }
}
