package io.github.maahibatra.betterminecarts;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import java.lang.reflect.Method;
public class TestCol {
    public static void main(String[] args) {
        for(Method m : AbstractMinecartEntity.class.getMethods()) {
            if(m.getName().toLowerCase().contains("push") || m.getName().toLowerCase().contains("collide")) {
                System.out.println(m.getName());
            }
        }
    }
}
