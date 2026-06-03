package io.github.maahibatra.betterminecarts.access;

import java.util.Set;
import java.util.UUID;

public interface MinecartLinkAccess {
    Set<UUID> betterminecarts$getLinkedCarts();
    boolean betterminecarts$addLink(UUID uuid);
    boolean betterminecarts$removeLink(UUID uuid);
    void betterminecarts$clearLinks();
}
