package com.oori.minebot;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

public record MineBotScreenOpeningData(int entityId, String accessCode, String endpoint, String skin) {
    public static final PacketCodec<RegistryByteBuf, MineBotScreenOpeningData> PACKET_CODEC = PacketCodec.tuple(
        PacketCodecs.INTEGER,
        MineBotScreenOpeningData::entityId,
        PacketCodecs.STRING,
        MineBotScreenOpeningData::accessCode,
        PacketCodecs.STRING,
        MineBotScreenOpeningData::endpoint,
        PacketCodecs.STRING,
        MineBotScreenOpeningData::skin,
        MineBotScreenOpeningData::new
    );
}
