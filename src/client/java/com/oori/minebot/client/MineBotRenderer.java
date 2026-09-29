package com.oori.minebot.client;

import com.oori.minebot.MineBotEntity;
import com.oori.minebot.MineBotMod;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.util.Identifier;

public final class MineBotRenderer extends BipedEntityRenderer<MineBotEntity, MineBotRenderState, MineBotModel> {
    private static final Identifier DISCONNECTED_TEXTURE = MineBotMod.id("textures/entity/minebot.png");
    private static final Identifier CONNECTED_TEXTURE = MineBotMod.id("textures/entity/minebot_connected.png");
    private static final Identifier EVIL_TEXTURE = MineBotMod.id("textures/entity/minebot_evil.png");

    public MineBotRenderer(EntityRendererFactory.Context context) {
        super(context, new MineBotModel(context.getPart(MineBotModel.LAYER)), 0.45F);
    }

    @Override
    public MineBotRenderState createRenderState() {
        return new MineBotRenderState();
    }

    @Override
    public void updateRenderState(MineBotEntity entity, MineBotRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.connected = entity.isConnected();
        state.evil = entity.isEvil();
    }

    @Override
    public Identifier getTexture(MineBotRenderState state) {
        if (state.evil) {
            return EVIL_TEXTURE;
        }

        return state.connected ? CONNECTED_TEXTURE : DISCONNECTED_TEXTURE;
    }
}
