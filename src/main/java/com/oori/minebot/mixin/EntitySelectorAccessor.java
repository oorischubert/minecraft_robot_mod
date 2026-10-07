package com.oori.minebot.mixin;

import net.minecraft.command.EntitySelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// The plain name a selector was given (a player name to vanilla), or null for @-selectors and UUIDs.
@Mixin(EntitySelector.class)
public interface EntitySelectorAccessor {
    @Accessor("playerName")
    String minebot$getPlayerName();
}
