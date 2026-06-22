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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import java.util.Deque;
import java.util.LinkedList;

@Mixin(AbstractMinecartEntity.class)
public abstract class MinecartLinkMixin implements MinecartLinkAccess {

    @Shadow protected abstract double getMaxSpeed(ServerWorld world);

    // Path history for follower tracking
    @Unique private final Deque<Vec3d> betterminecarts$pathHistory = new LinkedList<>();

    @Override
    public Deque<Vec3d> betterminecarts$getPathHistory() {
        return betterminecarts$pathHistory;
    }

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

        // ── Path History Recording ────────────────────────────────────────────
        if (betterminecarts$followerUuid == null) {
            betterminecarts$pathHistory.clear();
        } else {
            Vec3d currentPos = self.getEntityPos();
            if (betterminecarts$pathHistory.isEmpty() || currentPos.distanceTo(betterminecarts$pathHistory.peekLast()) > 0.05) {
                betterminecarts$pathHistory.add(currentPos);
                while (betterminecarts$pathHistory.size() > 200) {
                    betterminecarts$pathHistory.poll();
                }
            }
        }

        // ── Leader cleanup & physics ──────────────────────────────────────────
        if (betterminecarts$leaderUuid != null) {
            Entity leaderEntity = serverWorld.getEntity(betterminecarts$leaderUuid);

            if (!(leaderEntity instanceof AbstractMinecartEntity leader) || leader.isRemoved()) {
                // Leader is gone — clean up the link and drop the chain
                betterminecarts$leaderUuid = null;
                self.dropStack(serverWorld, new ItemStack(Items.IRON_CHAIN));
                return;
            }

            // ── Auto-Break Check (using actual spatial distance) ─────────────
            double actualDistance = self.distanceTo(leader) - 1.0;
            if (actualDistance > 4.5) {
                // Stretched too far / bugged out: auto-break the link
                UUID oldLeaderUuid = betterminecarts$leaderUuid;
                betterminecarts$leaderUuid = null;

                Entity oldLeader = serverWorld.getEntity(oldLeaderUuid);
                if (oldLeader instanceof AbstractMinecartEntity leaderCart) {
                    ((MinecartLinkAccess) leaderCart).betterminecarts$setFollowerUuid(null);
                }
                self.dropStack(serverWorld, new ItemStack(Items.IRON_CHAIN));
                return;
            }

            // ── Find Target Position in Leader's Path History ────────────────
            final double TARGET_SPACING_CENTER = 2.4;
            Deque<Vec3d> history = ((MinecartLinkAccess) leader).betterminecarts$getPathHistory();
            Vec3d targetPos = leader.getEntityPos();

            if (history != null && !history.isEmpty()) {
                for (Vec3d pos : history) {
                    if (self.getEntityPos().distanceTo(pos) >= TARGET_SPACING_CENTER) {
                        targetPos = pos;
                        break;
                    }
                }
            }

            // ── Path-Based Chain Physics ──────────────────────────────────────
            double centerDistance = self.getEntityPos().distanceTo(targetPos);
            double distance = centerDistance - 1.0;
            final double TARGET_SPACING = 1.4;

            Vec3d dirToTarget = targetPos.subtract(self.getEntityPos()).normalize();
            Vec3d leaderVel   = leader.getVelocity();
            double leaderSpeed = leaderVel.length();
            double maxSpeed = this.getMaxSpeed(serverWorld);
            Vec3d followerVel = self.getVelocity();

            if (distance > TARGET_SPACING) {
                // Too far from target point on leader's path: catch up.
                double excess = distance - TARGET_SPACING;
                double catchUpSpeed = Math.min(leaderSpeed + excess * 2.0, maxSpeed * 3.0);
                
                Vec3d targetVel;
                if (followerVel.lengthSquared() > 0.0001 && followerVel.dotProduct(dirToTarget) > 0.0) {
                    targetVel = followerVel.normalize().multiply(catchUpSpeed);
                } else {
                    targetVel = dirToTarget.multiply(catchUpSpeed);
                }
                self.setVelocity(targetVel);

            } else if (distance < TARGET_SPACING - 0.3 && leaderSpeed < 0.05) {
                // Too close and leader is stopped/stopping: nudge gently backward.
                self.setVelocity(dirToTarget.multiply(-0.05));

            } else {
                // In the sweet spot (or too close but in motion): match speed along path.
                double matchSpeed = Math.min(leaderSpeed, maxSpeed);
                Vec3d targetVel;
                if (followerVel.lengthSquared() > 0.0001 && followerVel.dotProduct(dirToTarget) > 0.0) {
                    targetVel = followerVel.normalize().multiply(matchSpeed);
                } else {
                    targetVel = dirToTarget.multiply(matchSpeed);
                }
                self.setVelocity(targetVel);
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

        // ── Follower slowdown / cleanup ───────────────────────────────────────
        if (betterminecarts$followerUuid != null) {
            Entity followerEntity = serverWorld.getEntity(betterminecarts$followerUuid);
            if (!(followerEntity instanceof AbstractMinecartEntity follower) || follower.isRemoved()) {
                betterminecarts$followerUuid = null;
            } else {
                // Follower is present: if it's lagging, slow down this leader cart to let it catch up.
                // This propagates tension back through the chain, keeping the train cohesive on turns.
                double followerDist = self.distanceTo(follower) - 1.0;
                final double TARGET_SPACING = 1.4;
                if (followerDist > TARGET_SPACING + 0.05) {
                    // Lagging behind: apply a steep slowdown factor to the leader.
                    // If excess is >= 0.5 blocks, the leader is choked down to 5% speed,
                    // giving the follower immediate time to catch up.
                    double excess = followerDist - TARGET_SPACING;
                    double slowdownFactor = Math.max(0.05, 1.0 - excess * 2.0);
                    self.setVelocity(self.getVelocity().multiply(slowdownFactor));
                }
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
