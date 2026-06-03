package io.github.maahibatra.betterminecarts;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import java.lang.reflect.Method;
public class TestMaxSpeed {
    public static void main(String[] args) {
        try {
            for(Method m : AbstractMinecartEntity.class.getDeclaredMethods()) {
                if(m.getReturnType() == double.class || m.getReturnType() == float.class) {
                    System.out.println(m.getName() + " -> " + m.getReturnType().getName());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
