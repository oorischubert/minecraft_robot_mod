package com.oori.minebot.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.suggestion.Suggestion;
import com.oori.minebot.MineBotTeleport;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// A named robot suggested as a /tp destination is listed as "CODE (name)"; choosing it still types just the code.
@Mixin(ChatInputSuggestor.SuggestionWindow.class)
public abstract class SuggestionWindowMixin {
    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/suggestion/Suggestion;getText()Ljava/lang/String;", remap = false)
    )
    private String minebot$showRobotLabel(Suggestion suggestion, Operation<String> original) {
        return MineBotTeleport.suggestionLabel(original.call(suggestion), suggestion.getTooltip());
    }
}
