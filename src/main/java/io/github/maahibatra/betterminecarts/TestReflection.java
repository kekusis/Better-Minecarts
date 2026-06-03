package io.github.maahibatra.betterminecarts;
import net.minecraft.state.property.Properties;
import java.lang.reflect.Field;
public class TestReflection {
    public static void main(String[] args) {
        System.out.println("=== Properties DECLARED FIELDS ===");
        for (Field f : Properties.class.getDeclaredFields()) {
            if (f.getName().contains("AXIS")) {
                System.out.println("  " + f.getName() + " : " + f.getType().getSimpleName());
            }
        }
    }
}
