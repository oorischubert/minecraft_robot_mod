package com.oori.minebot.client;

import com.oori.minebot.MineBotMod;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.entity.EntityRendererFactories;

public final class MineBotClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererFactories.register(MineBotMod.MINEBOT_ENTITY, MineBotRenderer::new);
        EntityModelLayerRegistry.registerModelLayer(MineBotModel.LAYER, MineBotModel::getTexturedModelData);
        HandledScreens.register(MineBotMod.MINEBOT_SCREEN_HANDLER, MineBotScreen::new);
        MineBotCameraClient.initialize();
    }
}
