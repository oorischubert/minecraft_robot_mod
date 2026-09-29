package com.oori.minebot.client.mixin;

import com.oori.minebot.MineBotChunkLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "isPaused", at = @At("RETURN"), cancellable = true)
    private void minebot$keepSingleplayerRunningForActiveRobots(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        MinecraftClient client = (MinecraftClient) (Object) this;
        if (!client.isIntegratedServerRunning()) {
            return;
        }

        IntegratedServer server = client.getServer();
        if (server == null) {
            return;
        }

        if (MineBotChunkLoader.get(server).hasActiveRobots()) {
            cir.setReturnValue(false);
        }
    }
}
