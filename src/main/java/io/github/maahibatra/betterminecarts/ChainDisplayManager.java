package io.github.maahibatra.betterminecarts;

import io.github.maahibatra.betterminecarts.access.MinecartLinkAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChainBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Manages visual chain display entities between linked minecarts.
 * Spawns BlockDisplayEntity showing chain blocks at the midpoint of each link.
 */
public class ChainDisplayManager {

    // Maps pair IDs to their display entity UUIDs
    private final Map<String, UUID> displayEntities = new HashMap<>();

    public static String getPairId(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "-" + b : b + "-" + a;
    }

    /**
     * Called every tick to create/update/remove chain display entities.
     */
    public void tick(ServerWorld world) {
        Set<String> activePairs = new HashSet<>();

        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof AbstractMinecartEntity cartA)) continue;

            MinecartLinkAccess accessA = (MinecartLinkAccess) cartA;
            Set<UUID> linkedUuids = accessA.betterminecarts$getLinkedCarts();
            if (linkedUuids.isEmpty()) continue;

            for (UUID uuidB : linkedUuids) {
                Entity linked = world.getEntity(uuidB);
                if (!(linked instanceof AbstractMinecartEntity cartB)) continue;

                String pairId = getPairId(cartA.getUuid(), cartB.getUuid());
                if (!activePairs.add(pairId)) continue;

                Vec3d posA = cartA.getEntityPos();
                Vec3d posB = cartB.getEntityPos();
                Vec3d midpoint = posA.add(posB).multiply(0.5);

                // Compute the rotation so the chain aligns horizontally between the two carts
                Vec3d direction = posB.subtract(posA);
                float yaw = (float) Math.toDegrees(Math.atan2(direction.z, direction.x));

                UUID displayUuid = displayEntities.get(pairId);
                Entity displayEntity = displayUuid != null ? world.getEntity(displayUuid) : null;

                if (displayEntity == null || !displayEntity.isAlive()) {
                    displayEntity = spawnChainDisplay(world, midpoint, yaw);
                    if (displayEntity != null) {
                        displayEntities.put(pairId, displayEntity.getUuid());
                    }
                } else {
                    // Smoothly update position and rotation
                    displayEntity.setPosition(midpoint);
                    displayEntity.setYaw(yaw);
                }
            }
        }

        // Remove display entities for pairs that no longer exist
        Iterator<Map.Entry<String, UUID>> it = displayEntities.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, UUID> entry = it.next();
            if (!activePairs.contains(entry.getKey())) {
                Entity display = world.getEntity(entry.getValue());
                if (display != null) {
                    display.discard();
                }
                it.remove();
            }
        }
    }

    private Entity spawnChainDisplay(ServerWorld world, Vec3d pos, float yaw) {
        DisplayEntity.BlockDisplayEntity display =
            (DisplayEntity.BlockDisplayEntity) EntityType.BLOCK_DISPLAY.create(world, null);
        if (display == null) return null;

        // Set chain block state (horizontal axis via block property)
        BlockState chainState = Blocks.CHAIN.getDefaultState();
        display.setBlockState(chainState);

        // Position at midpoint, offset down slightly to align with cart body
        display.setPosition(pos.x, pos.y - 0.3, pos.z);
        display.setYaw(yaw);
        display.setNoGravity(true);
        display.setSilent(true);

        // Scale it down to look like a small chain link, centered on the entity origin
        // Translation of -0.5 centers the block model (which is 0-1 range) on the entity position
        AffineTransformation transform = new AffineTransformation(
            new Vector3f(-0.15f, -0.15f, -0.15f),  // translation (center the scaled block)
            null,                                      // left rotation
            new Vector3f(0.3f, 0.3f, 0.3f),           // scale (shrink to 30%)
            null                                       // right rotation
        );
        display.setTransformation(transform);

        // Billboard mode: FIXED (doesn't rotate to face the player)
        display.setBillboardMode(DisplayEntity.BillboardMode.FIXED);

        // Smooth interpolation for movement
        display.setTeleportDuration(2);

        world.spawnEntity(display);
        return display;
    }

    /**
     * Remove a specific pair's display entity.
     */
    public void removePair(ServerWorld world, UUID cartA, UUID cartB) {
        String pairId = getPairId(cartA, cartB);
        UUID displayUuid = displayEntities.remove(pairId);
        if (displayUuid != null) {
            Entity display = world.getEntity(displayUuid);
            if (display != null) display.discard();
        }
    }

    /**
     * Remove all display entities.
     */
    public void removeAll(ServerWorld world) {
        for (UUID uuid : displayEntities.values()) {
            Entity display = world.getEntity(uuid);
            if (display != null) display.discard();
        }
        displayEntities.clear();
    }
}
