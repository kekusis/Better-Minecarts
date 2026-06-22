package io.github.maahibatra.betterminecarts.access;

import java.util.UUID;
import java.util.Deque;
import net.minecraft.util.math.Vec3d;

public interface MinecartLinkAccess {

    Deque<Vec3d> betterminecarts$getPathHistory();

    // The cart this cart follows ("I pull this one along, or it pulls me")
    UUID betterminecarts$getLeaderUuid();
    void betterminecarts$setLeaderUuid(UUID uuid);

    // The cart that follows this cart
    UUID betterminecarts$getFollowerUuid();
    void betterminecarts$setFollowerUuid(UUID uuid);

    // Convenience helpers
    default boolean betterminecarts$hasLeader() { return betterminecarts$getLeaderUuid() != null; }
    default boolean betterminecarts$hasFollower() { return betterminecarts$getFollowerUuid() != null; }
    default boolean betterminecarts$isLinked() {
        return betterminecarts$getLeaderUuid() != null || betterminecarts$getFollowerUuid() != null;
    }
}
