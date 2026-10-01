package com.oori.minebot;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** Sent by a player's client to rename the robot whose screen they have open. An empty name clears it. */
public record MineBotRenamePayload(String name) implements CustomPayload {
    public static final CustomPayload.Id<MineBotRenamePayload> ID = new CustomPayload.Id<>(MineBotMod.id("rename"));
    public static final PacketCodec<RegistryByteBuf, MineBotRenamePayload> CODEC = PacketCodec.tuple(
        PacketCodecs.string(MineBotEntity.MAX_NAME_LENGTH * 4),
        MineBotRenamePayload::name,
        MineBotRenamePayload::new
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
