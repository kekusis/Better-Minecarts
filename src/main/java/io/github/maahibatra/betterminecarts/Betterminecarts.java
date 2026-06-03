package io.github.maahibatra.betterminecarts;

import io.github.maahibatra.betterminecarts.access.MinecartLinkAccess;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.FurnaceMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class Betterminecarts implements ModInitializer {

    // Temporarily stores the first selected minecart for linking.
    private final Map<UUID, UUID> selectedCarts = new HashMap<>();
    // Prevents double-firing: stores the server tick of each player's last interaction
    private final Map<UUID, Long> lastInteractTick = new HashMap<>();

    @Override
    public void onInitialize() {
        System.out.println("BETTER MINECARTS (RELIABLE OVERHAUL) LOADED SUCCESSFULLY.");

        // Shift-left click minecart to unlink
        AttackEntityCallback.EVENT.register((playerEntity, world, hand, entity, entityHitResult) -> {
            if (!world.isClient() && entity instanceof AbstractMinecartEntity cart && playerEntity.isSneaking()) {
                MinecartLinkAccess access = (MinecartLinkAccess) cart;
                Set<UUID> linkedCarts = access.betterminecarts$getLinkedCarts();

                if (!linkedCarts.isEmpty()) {
                    for (UUID linkedId : linkedCarts) {
                        cart.dropStack((ServerWorld) world, new ItemStack(Items.IRON_CHAIN));
                        Entity linked = ((ServerWorld) world).getEntity(linkedId);
                        if (linked instanceof MinecartLinkAccess otherAccess) {
                            otherAccess.betterminecarts$removeLink(cart.getUuid());
                        }
                    }
                    access.betterminecarts$clearLinks();
                    playerEntity.sendMessage(Text.literal("minecart unlinked."), true);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // When a minecart is destroyed, drop chains and clean up links on neighbors
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!world.isClient() && entity instanceof AbstractMinecartEntity cart) {
                MinecartLinkAccess access = (MinecartLinkAccess) cart;
                Set<UUID> linkedCarts = access.betterminecarts$getLinkedCarts();
                if (!linkedCarts.isEmpty()) {
                    for (UUID linkedId : new HashSet<>(linkedCarts)) {
                        cart.dropStack((ServerWorld) world, new ItemStack(Items.IRON_CHAIN));
                        Entity linked = ((ServerWorld) world).getEntity(linkedId);
                        if (linked instanceof MinecartLinkAccess otherAccess) {
                            otherAccess.betterminecarts$removeLink(cart.getUuid());
                        }
                    }
                    access.betterminecarts$clearLinks();
                }
            }
        });

        // Minecart linking logic
        UseEntityCallback.EVENT.register((playerEntity, world, hand, entity, entityHitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            if (entity instanceof AbstractMinecartEntity cart) {
                boolean holdingChain = playerEntity.getStackInHand(hand).isOf(Items.IRON_CHAIN);

                if (holdingChain) {
                    // Prevent double-fire: skip if we already processed this player this tick
                    long currentTick = world.getServer().getTicks();
                    Long lastTick = lastInteractTick.get(playerEntity.getUuid());
                    if (lastTick != null && lastTick == currentTick) {
                        return ActionResult.SUCCESS;
                    }
                    lastInteractTick.put(playerEntity.getUuid(), currentTick);

                    if (!(world.getBlockState(cart.getBlockPos()).getBlock() instanceof AbstractRailBlock)) {
                        playerEntity.sendMessage(Text.literal("minecart must be on rails."), false);
                        return ActionResult.SUCCESS;
                    }

                    MinecartLinkAccess access = (MinecartLinkAccess) cart;

                    if (!selectedCarts.containsKey(playerEntity.getUuid())) {
                        if (access.betterminecarts$getLinkedCarts().size() >= 2) {
                            playerEntity.sendMessage(Text.literal("this minecart already has two links."), false);
                            return ActionResult.SUCCESS;
                        }
                        System.out.println("[BM DEBUG] FIRST CLICK: stored cart UUID=" + cart.getUuid() + " hand=" + hand);
                        playerEntity.sendMessage(Text.literal("you clicked the first minecart! click another to link."), false);
                        selectedCarts.put(playerEntity.getUuid(), cart.getUuid());
                    } else {
                        UUID storedCartId = selectedCarts.get(playerEntity.getUuid());
                        Entity storedEntity = ((ServerWorld) world).getEntity(storedCartId);

                        System.out.println("[BM DEBUG] SECOND CLICK: clicked cart UUID=" + cart.getUuid() + " storedCartId=" + storedCartId + " hand=" + hand);
                        System.out.println("[BM DEBUG]   storedEntity=" + storedEntity + " isNull=" + (storedEntity == null));

                        if (storedEntity == null || !(storedEntity instanceof AbstractMinecartEntity storedCart) || !storedCart.isAlive()) {
                            System.out.println("[BM DEBUG]   -> INVALID: entity not found or dead");
                            playerEntity.sendMessage(Text.literal("the first minecart is invalid. this is your first minecart, now."), false);
                            selectedCarts.put(playerEntity.getUuid(), cart.getUuid());
                        } else if (storedCart.getUuid().equals(cart.getUuid())) {
                            System.out.println("[BM DEBUG]   -> SAME CART: cancelling");
                            playerEntity.sendMessage(Text.literal("selection cancelled."), false);
                            selectedCarts.remove(playerEntity.getUuid());
                        } else {
                            double dist = cart.getEntityPos().distanceTo(storedCart.getEntityPos());
                            System.out.println("[BM DEBUG]   -> DIFFERENT CART: dist=" + dist);
                            if (dist > 6.0) {
                                playerEntity.sendMessage(Text.literal("minecarts are too far apart! (max 6 blocks)"), false);
                                selectedCarts.remove(playerEntity.getUuid());
                                return ActionResult.SUCCESS;
                            }

                            MinecartLinkAccess storedAccess = (MinecartLinkAccess) storedCart;

                            if (access.betterminecarts$getLinkedCarts().contains(storedCart.getUuid())) {
                                playerEntity.sendMessage(Text.literal("these minecarts are already linked."), false);
                            } else if (access.betterminecarts$getLinkedCarts().size() >= 2 || storedAccess.betterminecarts$getLinkedCarts().size() >= 2) {
                                playerEntity.sendMessage(Text.literal("one of these minecarts already has two links."), false);
                            } else {
                                // Link them
                                access.betterminecarts$addLink(storedCart.getUuid());
                                storedAccess.betterminecarts$addLink(cart.getUuid());
                                
                                System.out.println("[BM DEBUG]   -> LINKED SUCCESSFULLY!");
                                playerEntity.sendMessage(Text.literal("minecarts successfully linked!"), false);
                                if (!playerEntity.isCreative()) {
                                    playerEntity.getStackInHand(hand).decrement(1);
                                }
                            }
                            selectedCarts.remove(playerEntity.getUuid());
                        }
                    }
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // Spring-based synced moving physics
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            Set<String> processed = new HashSet<>();

            for (Entity entity : world.iterateEntities()) {
                if (!(entity instanceof AbstractMinecartEntity cartA)) continue;

                MinecartLinkAccess accessA = (MinecartLinkAccess) cartA;
                Set<UUID> linkedUuids = accessA.betterminecarts$getLinkedCarts();
                if (linkedUuids.isEmpty()) continue;

                for (UUID uuidB : linkedUuids) {
                    Entity linked = world.getEntity(uuidB);
                    if (!(linked instanceof AbstractMinecartEntity cartB)) continue;

                    UUID uuidA = cartA.getUuid();
                    UUID uuidActualB = cartB.getUuid();

                    // Only process each pair once
                    String pairId = uuidA.compareTo(uuidActualB) < 0 ? uuidA + "-" + uuidActualB : uuidActualB + "-" + uuidA;
                    if (!processed.add(pairId)) continue;

                    Vec3d posA = cartA.getEntityPos();
                    Vec3d posB = cartB.getEntityPos();
                    double dist = posA.distanceTo(posB);

                    // If they somehow get extremely far apart (e.g. chunk border issues or teleportation), break the link
                    if (dist > 16.0) {
                        accessA.betterminecarts$removeLink(uuidActualB);
                        ((MinecartLinkAccess) cartB).betterminecarts$removeLink(uuidA);
                        cartA.dropStack(world, new ItemStack(Items.IRON_CHAIN));
                        continue;
                    }

                    // Spring physics target distance is 2.0 blocks
                    double targetDist = 2.4; 
                    Vec3d velA = cartA.getVelocity();
                    Vec3d velB = cartB.getVelocity();
                    double speedA = velA.length();
                    double speedB = velB.length();

                    // If both carts are basically stopped, strictly kill their velocities to stop jittering
                    if (speedA < 0.01 && speedB < 0.01) {
                        if (Math.abs(dist - targetDist) < 0.3) {
                            cartA.setVelocity(Vec3d.ZERO);
                            cartB.setVelocity(Vec3d.ZERO);
                        }
                        continue;
                    }

                    // Determine Leader (Engine) by highest speed
                    AbstractMinecartEntity leader = speedA >= speedB ? cartA : cartB;
                    
                    AbstractMinecartEntity follower = leader == cartA ? cartB : cartA;

                    double leaderSpeed = leader.getVelocity().length();
                    Vec3d leaderDir = leader.getVelocity().normalize();
                    
                    if (leaderSpeed < 0.01) {
                        leaderDir = follower.getEntityPos().subtract(leader.getEntityPos()).normalize().multiply(-1);
                    }

                    // Determine if the Follower is in front of or behind the Leader
                    Vec3d leaderToFollower = follower.getEntityPos().subtract(leader.getEntityPos());
                    boolean followerIsInFront = leaderToFollower.dotProduct(leaderDir) > 0;

                    // Calculate Distance Correction
                    double diff = dist - targetDist;
                    double adjustment = diff * 0.4; // Extremely rigid correction
                    
                    if (followerIsInFront) {
                        // If Follower is in front, and too far (diff > 0), it must SLOW DOWN to let leader catch up
                        // If too close (diff < 0), it must SPEED UP to run away
                        adjustment = -adjustment;
                    }

                    // Apply Fully Rigid Clone Speed
                    double newFollowerSpeed = leaderSpeed + adjustment;

                    // A follower CANNOT physically reverse on the track to fix its distance!
                    // If the adjustment requires it to go backwards, it must simply hit the brakes (speed = 0).
                    newFollowerSpeed = Math.max(0.0, newFollowerSpeed);

                    // Set Follower Velocity exactly along its track
                    Vec3d followerDir = follower.getVelocity().normalize();
                    if (follower.getVelocity().lengthSquared() < 0.0001) {
                        // If stopped, we cannot use leaderDir because of U-turns.
                        // We must use the vector pointing between them.
                        Vec3d toLeader = leader.getEntityPos().subtract(follower.getEntityPos()).normalize();
                        
                        // If Follower is behind, it must move TOWARDS the leader.
                        // If Follower is in front, it must move AWAY from the leader.
                        followerDir = followerIsInFront ? toLeader.multiply(-1) : toLeader;
                    }

                    follower.setVelocity(followerDir.multiply(newFollowerSpeed));
                }
            }
        });
    }
}