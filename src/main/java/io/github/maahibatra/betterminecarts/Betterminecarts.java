package io.github.maahibatra.betterminecarts;

import io.github.maahibatra.betterminecarts.access.MinecartLinkAccess;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Betterminecarts implements ModInitializer {

    // Temporarily stores the first selected minecart (the "leader") for a linking operation.
    private final Map<UUID, UUID> selectedCarts = new HashMap<>();
    // Prevents double-firing on the same tick
    private final Map<UUID, Long> lastInteractTick = new HashMap<>();

    @Override
    public void onInitialize() {
        System.out.println("BETTER MINECARTS LOADED.");

        // ── Shift-left-click to unlink ────────────────────────────────────────
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !player.isSneaking()) return ActionResult.PASS;
            if (!(entity instanceof AbstractMinecartEntity cart)) return ActionResult.PASS;

            MinecartLinkAccess access = (MinecartLinkAccess) cart;
            ServerWorld sw = (ServerWorld) world;
            boolean unlinked = false;

            // Sever link to leader
            UUID leaderId = access.betterminecarts$getLeaderUuid();
            if (leaderId != null) {
                access.betterminecarts$setLeaderUuid(null);
                Entity leader = sw.getEntity(leaderId);
                if (leader instanceof AbstractMinecartEntity) {
                    ((MinecartLinkAccess) leader).betterminecarts$setFollowerUuid(null);
                }
                cart.dropStack(sw, new ItemStack(Items.IRON_CHAIN));
                unlinked = true;
            }

            // Sever link to follower
            UUID followerId = access.betterminecarts$getFollowerUuid();
            if (followerId != null) {
                access.betterminecarts$setFollowerUuid(null);
                Entity follower = sw.getEntity(followerId);
                if (follower instanceof AbstractMinecartEntity) {
                    ((MinecartLinkAccess) follower).betterminecarts$setLeaderUuid(null);
                }
                cart.dropStack(sw, new ItemStack(Items.IRON_CHAIN));
                unlinked = true;
            }

            if (unlinked) {
                player.sendMessage(Text.literal("minecart unlinked."), true);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        // ── Destroy cleanup: only on actual destruction, not chunk unload ─────
        // This prevents CME crashes when the server saves chunks on shutdown.
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (world.isClient() || !(entity instanceof AbstractMinecartEntity cart)) return;
            if (cart.getRemovalReason() == null || !cart.getRemovalReason().shouldDestroy()) return;

            MinecartLinkAccess access = (MinecartLinkAccess) cart;
            ServerWorld sw = (ServerWorld) world;

            UUID leaderId = access.betterminecarts$getLeaderUuid();
            if (leaderId != null) {
                access.betterminecarts$setLeaderUuid(null);
                Entity leader = sw.getEntity(leaderId);
                if (leader instanceof AbstractMinecartEntity) {
                    ((MinecartLinkAccess) leader).betterminecarts$setFollowerUuid(null);
                }
                cart.dropStack(sw, new ItemStack(Items.IRON_CHAIN));
            }

            UUID followerId = access.betterminecarts$getFollowerUuid();
            if (followerId != null) {
                access.betterminecarts$setFollowerUuid(null);
                Entity follower = sw.getEntity(followerId);
                if (follower instanceof AbstractMinecartEntity) {
                    ((MinecartLinkAccess) follower).betterminecarts$setLeaderUuid(null);
                }
                cart.dropStack(sw, new ItemStack(Items.IRON_CHAIN));
            }
        });

        // ── Right-click with Iron Chain to link two minecarts ─────────────────
        // First click  → selects the LEADER (front cart)
        // Second click → the clicked cart becomes the FOLLOWER of the stored leader
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(entity instanceof AbstractMinecartEntity cart)) return ActionResult.PASS;
            if (!player.getStackInHand(hand).isOf(Items.IRON_CHAIN)) return ActionResult.PASS;

            // Debounce: skip if we already processed this player this tick
            long currentTick = world.getServer().getTicks();
            Long lastTick = lastInteractTick.get(player.getUuid());
            if (lastTick != null && lastTick == currentTick) return ActionResult.SUCCESS;
            lastInteractTick.put(player.getUuid(), currentTick);

            if (!(world.getBlockState(cart.getBlockPos()).getBlock() instanceof AbstractRailBlock)) {
                player.sendMessage(Text.literal("minecart must be on rails."), false);
                return ActionResult.SUCCESS;
            }

            UUID playerUuid = player.getUuid();

            if (!selectedCarts.containsKey(playerUuid)) {
                // --- First click: store this cart as the intended LEADER ---
                MinecartLinkAccess access = (MinecartLinkAccess) cart;
                if (access.betterminecarts$hasFollower()) {
                    player.sendMessage(Text.literal("this cart already has a follower."), false);
                    return ActionResult.SUCCESS;
                }
                selectedCarts.put(playerUuid, cart.getUuid());
                player.sendMessage(Text.literal("leader selected! now click the follower cart."), false);

            } else {
                // --- Second click: make the clicked cart follow the stored leader ---
                UUID leaderId = selectedCarts.remove(playerUuid);
                ServerWorld sw = (ServerWorld) world;
                Entity leaderEntity = sw.getEntity(leaderId);

                if (leaderEntity == null || !(leaderEntity instanceof AbstractMinecartEntity leader) || !leader.isAlive()) {
                    player.sendMessage(Text.literal("leader cart is gone. this is now the leader."), false);
                    selectedCarts.put(playerUuid, cart.getUuid());
                    return ActionResult.SUCCESS;
                }

                if (leader.getUuid().equals(cart.getUuid())) {
                    player.sendMessage(Text.literal("selection cancelled."), false);
                    return ActionResult.SUCCESS;
                }

                double dist = cart.getEntityPos().distanceTo(leader.getEntityPos());
                if (dist > 6.0) {
                    player.sendMessage(Text.literal("minecarts are too far apart! (max 6 blocks)"), false);
                    return ActionResult.SUCCESS;
                }

                MinecartLinkAccess leaderAccess   = (MinecartLinkAccess) leader;
                MinecartLinkAccess followerAccess  = (MinecartLinkAccess) cart;

                if (leaderAccess.betterminecarts$hasFollower()) {
                    player.sendMessage(Text.literal("leader already has a follower."), false);
                    return ActionResult.SUCCESS;
                }
                if (followerAccess.betterminecarts$hasLeader()) {
                    player.sendMessage(Text.literal("that cart already follows another."), false);
                    return ActionResult.SUCCESS;
                }

                // Establish the directed link: leader → follower
                leaderAccess.betterminecarts$setFollowerUuid(cart.getUuid());
                followerAccess.betterminecarts$setLeaderUuid(leader.getUuid());

                player.sendMessage(Text.literal("minecarts linked!"), false);
                if (!player.isCreative()) {
                    player.getStackInHand(hand).decrement(1);
                }
            }

            return ActionResult.SUCCESS;
        });
    }
}