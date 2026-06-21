package io.github.maahibatra.betterminecarts.mixin;

import io.github.maahibatra.betterminecarts.access.MinecartLinkAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

@Mixin(AbstractMinecartEntity.class)
public abstract class MinecartLinkMixin implements MinecartLinkAccess {

    // Directed linked list: this cart follows leaderUuid; followerUuid follows this cart.
    @Unique private UUID betterminecarts$leaderUuid = null;
    @Unique private UUID betterminecarts$followerUuid = null;

    // ── Interface implementation ──────────────────────────────────────────────

    @Override public UUID betterminecarts$getLeaderUuid()   { return betterminecarts$leaderUuid; }
    @Override public void betterminecarts$setLeaderUuid(UUID uuid) { this.betterminecarts$leaderUuid = uuid; }

    @Override public UUID betterminecarts$getFollowerUuid()   { return betterminecarts$followerUuid; }
    @Override public void betterminecarts$setFollowerUuid(UUID uuid) { this.betterminecarts$followerUuid = uuid; }

    // ── Per-entity tick injection ─────────────────────────────────────────────
    // Runs at the HEAD of AbstractMinecartEntity.tick(), BEFORE moveOnRail().
    // This is the architecturally correct place — vanilla reads velocity after this
    // and moves the cart along the rail in the same tick.
    @Inject(method = "tick", at = @At("HEAD"))
    private void betterminecarts$tick(CallbackInfo ci) {
        AbstractMinecartEntity self = (AbstractMinecartEntity)(Object)this;

        // Client side and removed entities: skip entirely
        if (self.getEntityWorld().isClient() || self.isRemoved()) return;

        ServerWorld serverWorld = (ServerWorld) self.getEntityWorld();

        // ── Leader cleanup ────────────────────────────────────────────────────
        if (betterminecarts$leaderUuid != null) {
            Entity leaderEntity = serverWorld.getEntity(betterminecarts$leaderUuid);

            if (!(leaderEntity instanceof AbstractMinecartEntity leader) || leader.isRemoved()) {
                // Leader is gone — clean up the link and drop the chain
                betterminecarts$leaderUuid = null;
                self.dropStack(serverWorld, new ItemStack(Items.IRON_CHAIN));
                return;
            }

            // ── Chain Physics (adapted from minecart-trains-fork) ─────────────
            // distance - 1.0 accounts for the physical width of the minecart body (~1 block).
            // So distance == 0 means touching bumper-to-bumper, not same position.
            double distance = self.distanceTo(leader) - 1.0;
            final double TARGET_SPACING = 1.4; // center-to-center = ~2.4 blocks total

            Vec3d dirToLeader = leader.getEntityPos().subtract(self.getEntityPos()).normalize();
            Vec3d leaderVel   = leader.getVelocity();
            double leaderSpeed = leaderVel.length();

            if (distance > TARGET_SPACING) {
                // Too far: catch up.
                // Speed scales with distance gap (damped spring: further = faster catch-up).
                // When leader is stopped, use a small constant to creep up.
                double catchUpSpeed = leaderSpeed > 0.01
                    ? Math.min(leaderSpeed * Math.min(distance, 2.0), 0.4)
                    : 0.05;
                self.setVelocity(dirToLeader.multiply(catchUpSpeed));

            } else if (distance < TARGET_SPACING - 0.2) {
                // Too close: nudge gently backward away from leader
                self.setVelocity(dirToLeader.multiply(-0.05));

            } else {
                // In the sweet spot: exactly match leader's speed.
                // Direction doesn't matter — vanilla moveOnRail() overwrites it from rail shape.
                self.setVelocity(dirToLeader.multiply(Math.min(leaderSpeed, 0.4)));
            }

            // ── Particle Tether ───────────────────────────────────────────────
            // Only render from the follower side to avoid double-rendering each pair.
            Vec3d posA = leader.getEntityPos().add(0, 0.4, 0);
            Vec3d posB = self.getEntityPos().add(0, 0.4, 0);
            Vec3d diff = posB.subtract(posA);
            double totalDist = diff.length();

            if (totalDist > 1.4) {
                Vec3d dir = diff.normalize();
                DustParticleEffect dust = new DustParticleEffect(0x606060, 0.4f);
                for (double d = 0.7; d <= totalDist - 0.7; d += 0.07) {
                    Vec3d p = posA.add(dir.multiply(d));
                    serverWorld.spawnParticles(dust, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0);
                }
            }
        }

        // ── Follower cleanup ─────────────────────────────────────────────────
        if (betterminecarts$followerUuid != null) {
            Entity followerEntity = serverWorld.getEntity(betterminecarts$followerUuid);
            if (!(followerEntity instanceof AbstractMinecartEntity) || followerEntity.isRemoved()) {
                betterminecarts$followerUuid = null;
            }
        }
    }

    // ── Suppress vanilla push-away between linked carts ───────────────────────
    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void betterminecarts$cancelPush(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof AbstractMinecartEntity other)) return;
        UUID otherId = other.getUuid();
        // Cancel if this cart and the other are directly linked (in either direction)
        if (otherId.equals(betterminecarts$leaderUuid) || otherId.equals(betterminecarts$followerUuid)) {
            ci.cancel();
        }
    }

    // ── NBT persistence ───────────────────────────────────────────────────────
    @Inject(method = "writeCustomData", at = @At("TAIL"))
    private void betterminecarts$writeNbt(WriteView nbt, CallbackInfo ci) {
        if (betterminecarts$leaderUuid != null)
            nbt.putString("BM_Leader", betterminecarts$leaderUuid.toString());
        if (betterminecarts$followerUuid != null)
            nbt.putString("BM_Follower", betterminecarts$followerUuid.toString());
    }

    @Inject(method = "readCustomData", at = @At("TAIL"))
    private void betterminecarts$readNbt(ReadView nbt, CallbackInfo ci) {
        nbt.getOptionalString("BM_Leader").ifPresent(s -> betterminecarts$leaderUuid = UUID.fromString(s));
        nbt.getOptionalString("BM_Follower").ifPresent(s -> betterminecarts$followerUuid = UUID.fromString(s));
    }
}
