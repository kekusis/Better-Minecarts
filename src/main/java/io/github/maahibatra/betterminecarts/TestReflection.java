package io.github.maahibatra.betterminecarts;
import net.minecraft.entity.decoration.DisplayEntity;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
public class TestReflection {
    public static void main(String[] args) {
        System.out.println("=== BlockDisplayEntity DECLARED METHODS ===");
        for (Method m : DisplayEntity.BlockDisplayEntity.class.getDeclaredMethods()) {
            StringBuilder params = new StringBuilder();
            for (Class<?> p : m.getParameterTypes()) params.append(p.getSimpleName()).append(", ");
            System.out.println("  " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
        }
        System.out.println("=== BlockDisplayEntity DECLARED FIELDS ===");
        for (Field f : DisplayEntity.BlockDisplayEntity.class.getDeclaredFields()) {
            System.out.println("  " + f.getName() + " : " + f.getType().getSimpleName());
        }
        System.out.println("=== DisplayEntity DECLARED METHODS (setters) ===");
        for (Method m : DisplayEntity.class.getDeclaredMethods()) {
            if (m.getName().startsWith("set") || m.getName().contains("Scale") || m.getName().contains("Transform") || m.getName().contains("Billboard")) {
                StringBuilder params = new StringBuilder();
                for (Class<?> p : m.getParameterTypes()) params.append(p.getSimpleName()).append(", ");
                System.out.println("  " + m.getName() + "(" + params + ") -> " + m.getReturnType().getSimpleName());
            }
        }
    }
}
