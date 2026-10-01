package com.oori.minebot.client;

import com.oori.minebot.MineBotEntity;
import com.oori.minebot.MineBotMod;
import com.oori.minebot.MineBotSkin;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class MineBotRenderer extends BipedEntityRenderer<MineBotEntity, MineBotRenderState, MineBotModel> {
    private static final Map<MineBotSkin, SkinTextures> TEXTURES = new EnumMap<>(MineBotSkin.class);

    static {
        for (MineBotSkin skin : MineBotSkin.values()) {
            TEXTURES.put(skin, new SkinTextures(
                texture(skin, "idle"),
                texture(skin, "connected"),
                texture(skin, "evil")
            ));
        }
    }

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
        state.skin = entity.getSkin();
    }

    /** The name tag shows only the robot's own name, and nothing for a robot named "MineBot". */
    @Override
    protected boolean hasLabel(MineBotEntity entity, double squaredDistanceToCamera) {
        return entity.hasRobotName() && super.hasLabel(entity, squaredDistanceToCamera);
    }

    @Override
    protected Text getDisplayName(MineBotEntity entity) {
        return entity.getCustomName();
    }

    @Override
    public Identifier getTexture(MineBotRenderState state) {
        SkinTextures textures = TEXTURES.get(state.skin);
        if (state.evil) {
            return textures.evil();
        }

        return state.connected ? textures.connected() : textures.idle();
    }

    private static Identifier texture(MineBotSkin skin, String state) {
        return MineBotMod.id("textures/entity/minebot/" + skin.id() + "_" + state + ".png");
    }

    private record SkinTextures(Identifier idle, Identifier connected, Identifier evil) {
    }
}
