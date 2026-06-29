package io.github.maahibatra.betterminecarts.mixin;

import io.github.maahibatra.betterminecarts.access.MinecartLinkAccess;
import io.github.maahibatra.betterminecarts.access.Breadcrumb;
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mixin(AbstractMinecartEntity.class)
public abstract class MinecartLinkMixin implements MinecartLinkAccess {

    @Unique private UUID betterminecarts$leaderUuid = null;
    @Unique private UUID betterminecarts$followerUuid = null;

    @Unique private final List<Breadcrumb> betterminecarts$breadcrumbs = new ArrayList<>();

    @Unique private transient WeakReference<AbstractMinecartEntity> betterminecarts$leaderCache = null;
    @Unique private transient WeakReference<AbstractMinecartEntity> betterminecarts$followerCache = null;

    // ── Interface implementation ──────────────────────────────────────────────

    @Override public UUID betterminecarts$getLeaderUuid() { return betterminecarts$leaderUuid; }
    @Override public void betterminecarts$setLeaderUuid(UUID uuid) { 
        this.betterminecarts$leaderUuid = uuid; 
        this.betterminecarts$leaderCache = null;
    }

    @Override public UUID betterminecarts$getFollowerUuid() { return betterminecarts$followerUuid; }
    @Override public void betterminecarts$setFollowerUuid(UUID uuid) { 
        this.betterminecarts$followerUuid = uuid; 
        this.betterminecarts$followerCache = null;
    }

    @Override public List<Breadcrumb> betterminecarts$getBreadcrumbs() { return betterminecarts$breadcrumbs; }

    @Unique
    private AbstractMinecartEntity betterminecarts$resolveLeader(ServerWorld world) {
        if (betterminecarts$leaderUuid == null) return null;
        if (betterminecarts$leaderCache != null) {
            AbstractMinecartEntity cached = betterminecarts$leaderCache.get();
            if (cached != null && !cached.isRemoved() && cached.getUuid().equals(betterminecarts$leaderUuid)) {
                return cached;
            }
        }
        Entity entity = world.getEntity(betterminecarts$leaderUuid);
        if (entity instanceof AbstractMinecartEntity leader) {
            betterminecarts$leaderCache = new WeakReference<>(leader);
            return leader;
        }
        return null;
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void betterminecarts$tick(CallbackInfo ci) {
        AbstractMinecartEntity self = (AbstractMinecartEntity)(Object)this;

        if (self.getEntityWorld().isClient() || self.isRemoved()) return;

        ServerWorld serverWorld = (ServerWorld) self.getEntityWorld();

        // 1. Process Leader Link (Physics & Pulling)
        if (betterminecarts$leaderUuid != null) {
            AbstractMinecartEntity leader = betterminecarts$resolveLeader(serverWorld);

            if (leader != null) {
                if (leader.isRemoved()) {
                    // Leader was destroyed
                    betterminecarts$setLeaderUuid(null);
                    ((MinecartLinkAccess) leader).betterminecarts$setFollowerUuid(null);
                    self.dropStack(serverWorld, new ItemStack(Items.IRON_CHAIN));
                } else {
                    List<Breadcrumb> leaderHistory = ((MinecartLinkAccess) leader).betterminecarts$getBreadcrumbs();
                    double targetDist = 1.45;
                    Vec3d targetPos = null;
                    Vec3d targetVel = null;

                    if (leaderHistory != null && leaderHistory.size() > 1) {
                        double accum = 0.0;
                        for (int i = 1; i < leaderHistory.size(); i++) {
                            Vec3d p1 = leaderHistory.get(i - 1).pos;
                            Vec3d p2 = leaderHistory.get(i).pos;
                            double d = p1.distanceTo(p2);
                            if (accum + d >= targetDist) {
                                double f = (targetDist - accum) / d;
                                targetPos = p1.add(p2.subtract(p1).multiply(f));
                                targetVel = leaderHistory.get(i - 1).velocity.add(
                                    leaderHistory.get(i).velocity.subtract(leaderHistory.get(i - 1).velocity).multiply(f)
                                );
                                break;
                            }
                            accum += d;
                        }
                    }

                    if (targetPos == null) {
                        Vec3d leaderPos = leader.getEntityPos();
                        Vec3d selfPos = self.getEntityPos();
                        Vec3d dir = leaderPos.subtract(selfPos);
                        double dist = dir.length();
                        if (dist > 0.01) {
                            Vec3d normDir = dir.normalize();
                            targetPos = leaderPos.subtract(normDir.multiply(targetDist));
                            targetVel = leader.getVelocity();
                        } else {
                            float yaw = leader.getYaw();
                            Vec3d lookDir = Vec3d.fromPolar(0, yaw);
                            targetPos = leaderPos.subtract(lookDir.multiply(targetDist));
                            targetVel = leader.getVelocity();
                        }
                    }

                    // Direct rigid positioning snap at TAIL of tick!
                    self.setPosition(targetPos.x, targetPos.y, targetPos.z);
                    self.setVelocity(targetVel);

                    // Particle Tether
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

                    // Break if too far
                    if (self.distanceTo(leader) > 12.0) {
                        betterminecarts$setLeaderUuid(null);
                        ((MinecartLinkAccess) leader).betterminecarts$setFollowerUuid(null);
                        self.dropStack(serverWorld, new ItemStack(Items.IRON_CHAIN));
                    }
                }
            }
        }

        // 2. Update Breadcrumbs (if this cart has a follower)
        if (betterminecarts$followerUuid != null) {
            betterminecarts$breadcrumbs.add(0, new Breadcrumb(self.getEntityPos(), self.getVelocity()));
            while (betterminecarts$breadcrumbs.size() > 100) {
                betterminecarts$breadcrumbs.remove(betterminecarts$breadcrumbs.size() - 1);
            }
        } else {
            if (!betterminecarts$breadcrumbs.isEmpty()) {
                betterminecarts$breadcrumbs.clear();
            }
        }
    }

    // ── Suppress vanilla push-away between linked carts ───────────────────────
    @Inject(method = "pushAwayFrom", at = @At("HEAD"), cancellable = true)
    private void betterminecarts$cancelPush(Entity entity, CallbackInfo ci) {
        if (!(entity instanceof AbstractMinecartEntity other)) return;
        UUID otherId = other.getUuid();
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
