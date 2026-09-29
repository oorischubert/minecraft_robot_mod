package com.oori.minebot;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record MineBotCameraRequestPayload(String requestId, int entityId) implements CustomPayload {
    public static final CustomPayload.Id<MineBotCameraRequestPayload> ID = new CustomPayload.Id<>(MineBotMod.id("camera_request"));
    public static final PacketCodec<RegistryByteBuf, MineBotCameraRequestPayload> CODEC = PacketCodec.tuple(
        PacketCodecs.string(96),
        MineBotCameraRequestPayload::requestId,
        PacketCodecs.INTEGER,
        MineBotCameraRequestPayload::entityId,
        MineBotCameraRequestPayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
