package io.github.maahibatra.betterminecarts.mixin;

import net.minecraft.entity.vehicle.FurnaceMinecartEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FurnaceMinecartEntity.class)
public class FurnaceSpeedMixin {
    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void betterminecarts$matchRegularSpeed(CallbackInfoReturnable<Double> cir) {
        // Force Furnace Minecarts to have the exact same maximum speed limit as a regular minecart
        // In vanilla, this is typically 0.4D (8 blocks per second)
        cir.setReturnValue(0.4D);
    }
}
