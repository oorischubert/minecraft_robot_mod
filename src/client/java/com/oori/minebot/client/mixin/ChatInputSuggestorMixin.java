package com.oori.minebot.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.suggestion.Suggestion;
import com.oori.minebot.MineBotTeleport;
import net.minecraft.client.gui.screen.ChatInputSuggestor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The suggestion list is as wide as its longest label, a named robot's "CODE (name)" included.
@Mixin(ChatInputSuggestor.class)
public abstract class ChatInputSuggestorMixin {
    @WrapOperation(
        method = "show",
        at = @At(value = "INVOKE", target = "Lcom/mojang/brigadier/suggestion/Suggestion;getText()Ljava/lang/String;", remap = false)
    )
    private String minebot$measureRobotLabel(Suggestion suggestion, Operation<String> original) {
        return MineBotTeleport.suggestionLabel(original.call(suggestion), suggestion.getTooltip());
    }
}
