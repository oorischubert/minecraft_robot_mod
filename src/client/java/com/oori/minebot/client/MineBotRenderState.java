package com.oori.minebot.client;

import com.oori.minebot.MineBotSkin;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;

public final class MineBotRenderState extends BipedEntityRenderState {
    public boolean connected;
    public boolean evil;
    public MineBotSkin skin = MineBotSkin.CLASSIC;
}
