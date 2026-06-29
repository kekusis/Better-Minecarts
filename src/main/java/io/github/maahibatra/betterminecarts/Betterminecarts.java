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
        System.out.println("BETTER MINECARTS INITIALIZED.");

        // ── Shift-left-click (Attack) to unlink ───────────────────────────────
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !player.isSneaking()) return ActionResult.PASS;
            if (!(entity instanceof AbstractMinecartEntity cart)) return ActionResult.PASS;

            boolean unlinked = unlinkCart(cart, (ServerWorld) world, player);
            if (unlinked) {
                player.sendMessage(Text.literal("Minecart links severed."), true);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        // ── Destroy cleanup: only on actual destruction, not chunk unload ─────
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (world.isClient() || !(entity instanceof AbstractMinecartEntity cart)) return;
            if (cart.getRemovalReason() == null || !cart.getRemovalReason().shouldDestroy()) return;

            ServerWorld sw = (ServerWorld) world;
            unlinkCart(cart, sw, null);
        });

        // ── Right-click with Lead to link, or Shift-Right-Click to unlink ──────
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient()) return ActionResult.PASS;
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            if (!(entity instanceof AbstractMinecartEntity cart)) return ActionResult.PASS;

            // Pass immediately if this is not a modded interaction (Sneak-click or Chain-click)
            boolean isModInteraction = player.isSneaking() || player.getStackInHand(hand).isOf(Items.IRON_CHAIN);
            if (!isModInteraction) return ActionResult.PASS;

            // Debounce: skip if we already processed this player this tick
            long currentTick = world.getServer().getTicks();
            Long lastTick = lastInteractTick.get(player.getUuid());
            if (lastTick != null && lastTick == currentTick) return ActionResult.SUCCESS;
            lastInteractTick.put(player.getUuid(), currentTick);

            ServerWorld sw = (ServerWorld) world;

            // 1. Shift-Right-Click to unlink
            if (player.isSneaking()) {
                boolean unlinked = unlinkCart(cart, sw, player);
                if (unlinked) {
                    player.sendMessage(Text.literal("Minecart links severed."), true);
                    return ActionResult.SUCCESS;
                }
                return ActionResult.PASS;
            }

            // 2. Normal Right-Click with Chain to link
            if (!player.getStackInHand(hand).isOf(Items.IRON_CHAIN)) return ActionResult.PASS;

            if (!(world.getBlockState(cart.getBlockPos()).getBlock() instanceof AbstractRailBlock)) {
                player.sendMessage(Text.literal("Minecart must be on rails to link."), false);
                return ActionResult.SUCCESS;
            }

            UUID playerUuid = player.getUuid();

            if (!selectedCarts.containsKey(playerUuid)) {
                // --- First click: store this cart as the intended LEADER ---
                MinecartLinkAccess access = (MinecartLinkAccess) cart;
                if (access.betterminecarts$hasFollower()) {
                    player.sendMessage(Text.literal("This cart already has a follower."), false);
                    return ActionResult.SUCCESS;
                }
                selectedCarts.put(playerUuid, cart.getUuid());
                player.sendMessage(Text.literal("Leader selected! Now right-click the follower cart with a Chain."), false);

            } else {
                // --- Second click: make the clicked cart follow the stored leader ---
                UUID leaderId = selectedCarts.remove(playerUuid);
                Entity leaderEntity = sw.getEntity(leaderId);

                if (leaderEntity == null || !(leaderEntity instanceof AbstractMinecartEntity leader) || !leader.isAlive()) {
                    player.sendMessage(Text.literal("Leader cart is gone. Selected this cart as the new leader."), false);
                    selectedCarts.put(playerUuid, cart.getUuid());
                    return ActionResult.SUCCESS;
                }

                if (leader.getUuid().equals(cart.getUuid())) {
                    player.sendMessage(Text.literal("Selection cancelled."), false);
                    return ActionResult.SUCCESS;
                }

                double dist = cart.getEntityPos().distanceTo(leader.getEntityPos());
                if (dist > 5.0) {
                    player.sendMessage(Text.literal("Minecarts are too far apart! (max 5 blocks)"), false);
                    return ActionResult.SUCCESS;
                }

                MinecartLinkAccess leaderAccess = (MinecartLinkAccess) leader;
                MinecartLinkAccess followerAccess = (MinecartLinkAccess) cart;

                if (leaderAccess.betterminecarts$hasFollower()) {
                    player.sendMessage(Text.literal("Leader already has a follower."), false);
                    return ActionResult.SUCCESS;
                }
                if (followerAccess.betterminecarts$hasLeader()) {
                    player.sendMessage(Text.literal("That cart already follows another."), false);
                    return ActionResult.SUCCESS;
                }

                // Establish link
                leaderAccess.betterminecarts$setFollowerUuid(cart.getUuid());
                followerAccess.betterminecarts$setLeaderUuid(leader.getUuid());

                player.sendMessage(Text.literal("Minecarts linked!"), false);
                if (!player.isCreative()) {
                    player.getStackInHand(hand).decrement(1);
                }
            }

            return ActionResult.SUCCESS;
        });
    }

    private boolean unlinkCart(AbstractMinecartEntity cart, ServerWorld world, net.minecraft.entity.player.PlayerEntity player) {
        MinecartLinkAccess access = (MinecartLinkAccess) cart;
        boolean unlinked = false;

        // Sever link to leader
        UUID leaderId = access.betterminecarts$getLeaderUuid();
        if (leaderId != null) {
            access.betterminecarts$setLeaderUuid(null);
            Entity leader = world.getEntity(leaderId);
            if (leader instanceof AbstractMinecartEntity) {
                ((MinecartLinkAccess) leader).betterminecarts$setFollowerUuid(null);
            }
            giveOrDropChain(cart, world, player);
            unlinked = true;
        }

        // Sever link to follower
        UUID followerId = access.betterminecarts$getFollowerUuid();
        if (followerId != null) {
            access.betterminecarts$setFollowerUuid(null);
            Entity follower = world.getEntity(followerId);
            if (follower instanceof AbstractMinecartEntity) {
                ((MinecartLinkAccess) follower).betterminecarts$setLeaderUuid(null);
            }
            giveOrDropChain(cart, world, player);
            unlinked = true;
        }

        return unlinked;
    }

    private void giveOrDropChain(AbstractMinecartEntity cart, ServerWorld world, net.minecraft.entity.player.PlayerEntity player) {
        ItemStack chainStack = new ItemStack(Items.IRON_CHAIN);
        if (player != null && player.isAlive()) {
            if (!player.getInventory().insertStack(chainStack)) {
                cart.dropStack(world, chainStack);
            }
        } else {
            cart.dropStack(world, chainStack);
        }
    }
}
