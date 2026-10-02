package com.oori.minebot.mixin;

import com.oori.minebot.MineBotEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A mob hurt by a robot remembers a player hit, as it does for a player or a tamed wolf, so a robot's kill
// drops what a player's kill drops: blaze rods, wither skeleton skulls, experience. The robot's fake player
// stands in, since loot conditions want a player.
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @Inject(method = "setAttackingPlayer", at = @At("HEAD"), cancellable = true)
    private void minebot$creditRobotKills(DamageSource source, CallbackInfoReturnable<PlayerEntity> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (source.getAttacker() instanceof MineBotEntity && self.getEntityWorld() instanceof ServerWorld world) {
            PlayerEntity player = MineBotEntity.killCreditPlayer(world);
            self.setAttacking(player, 100);
            cir.setReturnValue(player);
        }
    }
}
