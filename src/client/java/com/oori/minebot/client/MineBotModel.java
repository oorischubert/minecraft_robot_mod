package com.oori.minebot.client;

import com.oori.minebot.MineBotMod;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayer;

public final class MineBotModel extends BipedEntityModel<MineBotRenderState> {
    public static final EntityModelLayer LAYER = new EntityModelLayer(MineBotMod.id("minebot"), "main");

    public MineBotModel(ModelPart root) {
        super(root);
    }

    public static TexturedModelData getTexturedModelData() {
        return TexturedModelData.of(BipedEntityModel.getModelData(Dilation.NONE, 0.0F), 64, 64);
    }
}
