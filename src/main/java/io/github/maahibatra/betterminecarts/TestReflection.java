package io.github.maahibatra.betterminecarts;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import java.lang.reflect.Method;
public class TestReflection {
    public static void main(String[] args) {
        for (Method m : Entity.class.getMethods()) {
            if (m.getReturnType() == Vec3d.class && m.getParameterCount() == 0) {
                System.out.println("FOUND VEC3D METHOD: " + m.getName());
            }
        }
    }
}
