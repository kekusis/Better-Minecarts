package io.github.maahibatra.betterminecarts.mixin;

import net.minecraft.entity.vehicle.FurnaceMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FurnaceMinecartEntity.class)
public abstract class FurnaceSpeedMixin {

    @Shadow public Vec3d pushVec;
    @Shadow protected abstract boolean isLit();

    @Inject(method = "getMaxSpeed(Lnet/minecraft/server/world/ServerWorld;)D", at = @At("RETURN"), cancellable = true)
    private void betterminecarts$matchRegularSpeed(ServerWorld world, CallbackInfoReturnable<Double> cir) {
        // Force Furnace Minecarts to have the exact same maximum speed limit as a regular minecart (0.4)
        cir.setReturnValue(0.4D);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void betterminecarts$boostPropulsion(CallbackInfo ci) {
        FurnaceMinecartEntity self = (FurnaceMinecartEntity)(Object)this;
        if (self.getEntityWorld().isClient() || self.isRemoved()) return;

        // If the furnace minecart is lit (fueled) and has a push direction set
        if (this.isLit() && this.pushVec != null && this.pushVec.lengthSquared() > 0.0) {
            // Apply a strong locomotive acceleration boost in the push direction.
            // This allows the furnace minecart to pull/push heavy chains of carts without losing all its speed.
            Vec3d pushDir = this.pushVec.normalize();
            self.setVelocity(self.getVelocity().add(pushDir.multiply(0.12)));
        }
    }
}
