package com.oori.minebot.mixin;

import com.oori.minebot.MineBotEntity;
import net.minecraft.block.spawner.MobSpawnerLogic;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A spawner wakes up for a robot within its player range, as it does for a player.
@Mixin(MobSpawnerLogic.class)
public abstract class MobSpawnerLogicMixin {
    @Shadow
    private int requiredPlayerRange;

    @Inject(method = "isPlayerInRange", at = @At("HEAD"), cancellable = true)
    private void minebot$activateForRobots(World world, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (this.requiredPlayerRange < 0) {
            return;
        }

        Vec3d center = pos.toCenterPos();
        double rangeSquared = (double) this.requiredPlayerRange * this.requiredPlayerRange;
        boolean robotNear = !world.getEntitiesByClass(
            MineBotEntity.class,
            new Box(center, center).expand(this.requiredPlayerRange),
            robot -> robot.isAlive() && robot.squaredDistanceTo(center) < rangeSquared
        ).isEmpty();
        if (robotNear) {
            cir.setReturnValue(true);
        }
    }
}
