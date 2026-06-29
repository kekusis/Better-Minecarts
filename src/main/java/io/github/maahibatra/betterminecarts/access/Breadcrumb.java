package io.github.maahibatra.betterminecarts.access;

import net.minecraft.util.math.Vec3d;

public class Breadcrumb {
    public final Vec3d pos;
    public final Vec3d velocity;

    public Breadcrumb(Vec3d pos, Vec3d velocity) {
        this.pos = pos;
        this.velocity = velocity;
    }
}
