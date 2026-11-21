package de.pvptrainer;

import java.util.ArrayDeque;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** The integrated server's attack packet contains only the entity ID. Keep the actual client pick for that swing. */
final class WebAim {
    private record Pick(UUID target, Double offset, long time) {}
    private static final ArrayDeque<Pick> clicks = new ArrayDeque<>();

    static synchronized void remember(Entity target, HitResult result) {
        Double offset = result instanceof EntityHitResult hit && hit.getEntity() == target
            ? hit.getLocation().y - target.getY() : null;
        if (clicks.size() >= 32) clicks.removeFirst();
        clicks.addLast(new Pick(target.getUUID(), offset, System.nanoTime()));
    }

    static synchronized Double take(Entity target) {
        while (!clicks.isEmpty()) {
            Pick pick = clicks.removeFirst();
            if (pick.target().equals(target.getUUID()) && System.nanoTime() - pick.time() < 1_500_000_000L) return pick.offset();
        }
        return null;
    }
    static synchronized void clear() { clicks.clear(); }
}
