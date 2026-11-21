package de.pvptrainer.arena;

import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.Mannequin;

public final class TrainingTarget {
    private static volatile UUID current;

    static void select(Entity target) { current = target.getUUID(); }

    public static boolean matches(Entity target) {
        return target instanceof Mannequin && TrainingArena.isArena(target.level())
            && !target.isRemoved() && target.getUUID().equals(current);
    }
}
