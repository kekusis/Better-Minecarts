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
    // Manages visual chain display entities
    private final ChainDisplayManager chainDisplayManager = new ChainDisplayManager();

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
                    double targetDist = 2.0;
                    double diff = dist - targetDist;
                    
                    if (Math.abs(diff) > 0.05) {
                        // Direction from A to B
                        Vec3d dir = posB.subtract(posA).normalize();
                        
                        // Calculate spring force (pulls them together if diff > 0, pushes apart if diff < 0)
                        double stiffness = 0.15; // Moderate stiffness
                        Vec3d springForce = dir.multiply(diff * stiffness);

                        // Apply force
                        cartA.addVelocity(springForce.x, 0, springForce.z);
                        cartB.addVelocity(-springForce.x, 0, -springForce.z);
                        
                        // Sync velocities moderately to reduce bouncing
                        Vec3d velA = cartA.getVelocity();
                        Vec3d velB = cartB.getVelocity();
                        Vec3d avgVel = velA.add(velB).multiply(0.5);
                        
                        // Blend current velocity with average velocity
                        cartA.setVelocity(velA.lerp(avgVel, 0.2));
                        cartB.setVelocity(velB.lerp(avgVel, 0.2));
                    }
                }
            }

            // Update chain display entities
            chainDisplayManager.tick(world);
        });
    }
}