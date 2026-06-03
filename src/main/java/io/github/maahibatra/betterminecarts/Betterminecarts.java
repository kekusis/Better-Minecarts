package io.github.maahibatra.betterminecarts;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.*;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.*;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

// note: this is the first time i'm commenting in my code simply because when i fire up intellij the next day it feels like i just woke up stuck in a maze.

public class Betterminecarts implements ModInitializer {

    private final Map<UUID, Set<UUID>> linkMap = new HashMap<>();
    private final Map<UUID, Vec3d> lastPositions = new HashMap<>();
    private final Map<UUID, Long> lastUpdateTimes = new HashMap<>();

    @Override
    public void onInitialize() {
        System.out.println("BETTER MINECARTS LOADED SUCCESSFULLY.");

        HashMap<UUID, AbstractMinecartEntity> map = new HashMap<>();
        Set<List<UUID>> links = new HashSet<>();

        // shift and left click minecart to unlink
        AttackEntityCallback.EVENT.register((playerEntity, world, hand, entity, entityHitResult) -> {
            if(!world.isClient() && entity instanceof AbstractMinecartEntity && playerEntity.isSneaking()) {
                UUID cartId = entity.getUuid();
                Set<UUID> linkedCarts = linkMap.getOrDefault(cartId, Collections.emptySet());

                if(!linkedCarts.isEmpty()) {
                    for(int i = 0; i < linkedCarts.size(); i++) {
                        entity.dropStack((ServerWorld) world, Items.IRON_CHAIN.getDefaultStack());
                    }
                    for(UUID linkedId : linkedCarts) {
                        List<UUID> pair = Arrays.asList(cartId, linkedId);
                        pair.sort(Comparator.naturalOrder());
                        links.remove(pair);

                        Set<UUID> otherLinks = linkMap.get(linkedId);
                        if(otherLinks != null) {
                            otherLinks.remove(cartId);
                            if(otherLinks.isEmpty()) {
                                linkMap.remove(linkedId);
                            }
                        }
                    }
                    linkMap.remove(cartId);
                    return ActionResult.SUCCESS;
                }
            }
            return ActionResult.PASS;
        });

        // break minecart to unlink
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (!world.isClient() && entity instanceof AbstractMinecartEntity) {
                UUID cartId = entity.getUuid();
                Set<UUID> linkedCarts = linkMap.get(cartId);

                if (linkedCarts != null && !linkedCarts.isEmpty()) {
                    entity.dropStack((ServerWorld) world, Items.IRON_CHAIN.getDefaultStack());

                    for (UUID linkedId : linkedCarts) {
                        Set<UUID> otherLinks = linkMap.get(linkedId);
                        if (otherLinks != null) {
                            otherLinks.remove(cartId);
                            if (otherLinks.isEmpty()) {
                                linkMap.remove(linkedId);
                            }
                        }
                    }

                    linkMap.remove(cartId);
                }
            }
        });

        // minecart linking logic
        UseEntityCallback.EVENT.register((playerEntity, world, hand, entity, entityHitResult) -> {
            if(world.isClient()) {
                return ActionResult.SUCCESS;
            }
            if (entity instanceof AbstractMinecartEntity) {
                boolean holdingChain = playerEntity.getMainHandStack().getItem() == Items.IRON_CHAIN;

                if (!holdingChain) {
                    if(entity instanceof MinecartEntity) {
                        if (entity.getPassengerList().isEmpty()) {
                            playerEntity.startRiding(entity);
                        }
                        return ActionResult.SUCCESS;
                    }
                    if(entity instanceof ChestMinecartEntity || entity instanceof FurnaceMinecartEntity || entity instanceof HopperMinecartEntity || entity instanceof TntMinecartEntity) {
                        entity.interact(playerEntity, hand);
                        return ActionResult.SUCCESS;
                    }
                    return ActionResult.PASS;
                }

                BlockPos pos = entity.getBlockPos();
                Block block = world.getBlockState(pos).getBlock();

                if (!(block instanceof AbstractRailBlock)) {
                    playerEntity.sendMessage(Text.literal("minecart must be on rails."), false);
                    return ActionResult.SUCCESS;
                }

                if(map.isEmpty()) {
                    playerEntity.sendMessage(Text.literal("you clicked the first minecart with a chain!"), false);
                    map.put(playerEntity.getUuid(), (AbstractMinecartEntity) entity);
                } else {
                    AbstractMinecartEntity storedCart = map.get(playerEntity.getUuid());

                    if (storedCart == null || !storedCart.isAlive() || storedCart.isRemoved() || storedCart == entity) {
                        playerEntity.sendMessage(Text.literal("the first minecart is invalid. this is your first minecart, now."), false);
                        map.put(playerEntity.getUuid(), (AbstractMinecartEntity) entity);
                    } else {
                        BlockPos pos1 = storedCart.getBlockPos();
                        BlockPos pos2 = entity.getBlockPos();

                        if(pos1.getManhattanDistance(pos2) != 2) {
                            playerEntity.sendMessage(Text.literal("minecarts must be exactly one block apart."), false);
                            map.remove(playerEntity.getUuid());
                            return ActionResult.SUCCESS;
                        }

                        UUID uuid1 = storedCart.getUuid();
                        UUID uuid2 = entity.getUuid();

                        List<UUID> pair = Arrays.asList(uuid1, uuid2);
                        pair.sort(Comparator.naturalOrder());

                        if(links.contains(pair)) {
                            playerEntity.sendMessage(Text.literal("these minecarts are already linked."), false);
                        } else if(linkMap.getOrDefault(uuid1, Collections.emptySet()).size() >= 2 || linkMap.getOrDefault(uuid2, Collections.emptySet()).size() >= 2) {
                            playerEntity.sendMessage(Text.literal("one of these minecarts already has two links and cannot be linked to more."), false);
                        } else {
                            links.add(pair);
                            playerEntity.sendMessage(Text.literal("you clicked the second minecart and linked it!"), false);
                            playerEntity.getMainHandStack().decrement(1);

                            linkMap.computeIfAbsent(uuid1, k -> new HashSet<>()).add(uuid2);
                            linkMap.computeIfAbsent(uuid2, k -> new HashSet<>()).add(uuid1);
                        }

                        map.remove(playerEntity.getUuid());
                    }
                }
            }
            return ActionResult.SUCCESS;
        });

        // minecart synced moving logic
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            long currTime = world.getServer().getTicks();
            Set<String> processed = new HashSet<>();

            for(Entity entity : world.iterateEntities()) { // entity iteration
                if(!(entity instanceof AbstractMinecartEntity cartA)) continue;

                // looks up linked minecarts
                UUID uuidA = cartA.getUuid();
                Set<UUID> linkedUuids = linkMap.getOrDefault(uuidA, Collections.emptySet());

                for(UUID uuidB : linkedUuids) { // iterates over linked carts
                    Entity linked = world.getEntity(uuidB);
                    if(!(linked instanceof AbstractMinecartEntity cartB)) continue;
                    UUID uuidActualB = cartB.getUuid();

                    String pairId = uuidA.compareTo(uuidActualB) < 0 ? uuidA + "-" + uuidActualB : uuidActualB + "-" + uuidA;
                    if(processed.contains(pairId)) continue;
                    processed.add(pairId);

                    // only process if both carts are on rails
                    BlockPos posA = cartA.getBlockPos();
                    BlockPos posB = cartB.getBlockPos();
                    if(!(world.getBlockState(posA).getBlock() instanceof AbstractRailBlock) || !(world.getBlockState(posB).getBlock() instanceof AbstractRailBlock)) continue;

                    // cart moving logic from this point on
                    Vec3d velA = cartA.getVelocity();
                    Vec3d velB = cartB.getVelocity();
                    Vec3d posVecA = cartA.getEntityPos();
                    Vec3d posVecB = cartB.getEntityPos();

                    // which cart is moving (faster)
                    Vec3d lastPosA = lastPositions.get(uuidA);
                    Vec3d lastPosB = lastPositions.get(uuidActualB);
                    double speedA = velA.length();
                    double speedB = velB.length();
                    double diff = Math.abs(speedA - speedB);

                    if(diff > 0.01 || (lastPosA != null && posVecA.subtract(lastPosA).length() > 0.05) || (lastPosB != null && posVecB.subtract(lastPosB).length() > 0.05)) {
                        AbstractMinecartEntity leader, follower;
                        Vec3d leaderPos, followerPos;
                        BlockPos leaderBPos, followerBPos;

                        // determine leader based and movement
                        boolean leadA = speedA > speedB + 0.01;
                        if(diff < 0.01 && lastPosA != null && lastPosB != null) {
                            double moveA = posVecA.subtract(lastPosA).length();
                            double moveB = posVecB.subtract(lastPosB).length();
                            leadA = moveA > moveB;
                        }

                        if(leadA) {
                            leader = cartA;
                            follower = cartB;
                            leaderPos = posVecA;
                            followerPos = posVecB;
                            leaderBPos = posA;
                            followerBPos = posB;
                        } else {
                            leader = cartB;
                            follower = cartA;
                            leaderPos = posVecB;
                            followerPos = posVecA;
                            leaderBPos = posB;
                            followerBPos = posA;
                        }

                        // if a cart is near a corner or on a corner, ignore the forced sync
                        boolean nearCorner = false;

                        for(int dx = -1; dx <= 1; dx++) {
                            for(int dz = -1; dz <= 1; dz++) {
                                BlockPos checkPos = leaderBPos.add(dx, 0, dz);
                                BlockState checkState = world.getBlockState(checkPos);

                                if(checkState.getBlock() instanceof AbstractRailBlock railBlock) {
                                    RailShape shape = checkState.get(railBlock.getShapeProperty());
                                    if(shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST || shape == RailShape.SOUTH_EAST || shape == RailShape.SOUTH_WEST) {
                                        nearCorner = true;
                                        break;
                                    }
                                }
                            }
                            if(nearCorner) break;
                        }

                        if(!nearCorner) {
                            for(int dx = -1; dx <= 1; dx++) {
                                for(int dz = -1; dz <= 1; dz++) {
                                    BlockPos checkPos = followerBPos.add(dx, 0, dz);
                                    BlockState checkState = world.getBlockState(checkPos);

                                    if(checkState.getBlock() instanceof AbstractRailBlock railBlock) {
                                        RailShape shape = checkState.get(railBlock.getShapeProperty());
                                        if(shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST || shape == RailShape.SOUTH_EAST || shape == RailShape.SOUTH_WEST) {
                                            nearCorner = true;
                                            break;
                                        }
                                    }
                                }
                                if(nearCorner) break;
                            }
                        }

                        if(!nearCorner) {
                            Vec3d dir = leaderPos.subtract(followerPos);
                            double currDist = dir.length();

                            // 2 block gap
                            if (Math.abs(currDist - 2.0) > 0.1) {
                                if (currDist > 0.01) {
                                    dir = dir.normalize();
                                    Vec3d target = leaderPos.subtract(dir.multiply(2.0));

                                    Vec3d adjustment = target.subtract(followerPos).multiply(0.5);
                                    follower.setPosition(followerPos.add(adjustment));
                                }
                            }

                            // sync velocities
                            Vec3d leaderVel = leader.getVelocity();
                            if(leaderVel.length() > 0.01) {
                                follower.setVelocity(leaderVel);
                            }
                        }

                        lastUpdateTimes.put(uuidA, currTime);
                        lastUpdateTimes.put(uuidActualB, currTime);
                    }

                    lastPositions.put(uuidA, posVecA);
                    lastPositions.put(uuidActualB, posVecB);
                }
            }
        });
    }
}