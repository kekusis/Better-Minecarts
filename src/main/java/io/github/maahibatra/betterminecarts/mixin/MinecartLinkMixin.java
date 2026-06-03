package io.github.maahibatra.betterminecarts.mixin;

import io.github.maahibatra.betterminecarts.access.MinecartLinkAccess;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.storage.WriteView;
import net.minecraft.storage.ReadView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mixin(AbstractMinecartEntity.class)
public abstract class MinecartLinkMixin implements MinecartLinkAccess {

    private final Set<UUID> betterminecarts$linkedCarts = new HashSet<>();

    @Override
    public Set<UUID> betterminecarts$getLinkedCarts() {
        return this.betterminecarts$linkedCarts;
    }

    @Override
    public boolean betterminecarts$addLink(UUID uuid) {
        if (this.betterminecarts$linkedCarts.size() >= 2) return false;
        return this.betterminecarts$linkedCarts.add(uuid);
    }

    @Override
    public boolean betterminecarts$removeLink(UUID uuid) {
        return this.betterminecarts$linkedCarts.remove(uuid);
    }

    @Override
    public void betterminecarts$clearLinks() {
        this.betterminecarts$linkedCarts.clear();
    }

    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void betterminecarts$writeNbt(WriteView nbt, CallbackInfo ci) {
        if (!this.betterminecarts$linkedCarts.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (UUID uuid : this.betterminecarts$linkedCarts) {
                if (sb.length() > 0) sb.append(",");
                sb.append(uuid.toString());
            }
            nbt.putString("BetterMinecartLinks", sb.toString());
        }
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void betterminecarts$readNbt(ReadView nbt, CallbackInfo ci) {
        this.betterminecarts$linkedCarts.clear();
        nbt.getOptionalString("BetterMinecartLinks").ifPresent(str -> {
            if (!str.isEmpty()) {
                for (String s : str.split(",")) {
                    this.betterminecarts$linkedCarts.add(UUID.fromString(s));
                }
            }
        });
    }
}
