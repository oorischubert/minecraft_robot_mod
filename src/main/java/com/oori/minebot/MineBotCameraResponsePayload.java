package com.oori.minebot;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record MineBotCameraResponsePayload(
    String requestId,
    boolean ok,
    String message,
    int width,
    int height,
    byte[] pngBytes
) implements CustomPayload {
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final CustomPayload.Id<MineBotCameraResponsePayload> ID = new CustomPayload.Id<>(MineBotMod.id("camera_response"));
    public static final PacketCodec<RegistryByteBuf, MineBotCameraResponsePayload> CODEC = PacketCodec.tuple(
        PacketCodecs.string(96),
        MineBotCameraResponsePayload::requestId,
        PacketCodecs.BOOLEAN,
        MineBotCameraResponsePayload::ok,
        PacketCodecs.string(512),
        MineBotCameraResponsePayload::message,
        PacketCodecs.INTEGER,
        MineBotCameraResponsePayload::width,
        PacketCodecs.INTEGER,
        MineBotCameraResponsePayload::height,
        PacketCodecs.byteArray(MAX_IMAGE_BYTES),
        MineBotCameraResponsePayload::pngBytes,
        MineBotCameraResponsePayload::new
    );

    public static MineBotCameraResponsePayload error(String requestId, String message) {
        return new MineBotCameraResponsePayload(requestId, false, message, 0, 0, new byte[0]);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
