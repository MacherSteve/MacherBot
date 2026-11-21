package de.pvptrainer;

import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;

/** Immutable round settings are also read by local client-side item prediction. */
public final class TrainingResources {
    private record Round(UUID owner, UUID bot, DrillOptions options, TrainingSettings.Mode mode) {}
    private static volatile Round round;

    static void begin(LivingEntity owner, LivingEntity bot, DrillOptions options, TrainingSettings.Mode mode) {
        round = new Round(owner.getUUID(), bot.getUUID(), options, mode);
    }

    static void end(UUID owner) {
        Round active = round;
        if (active != null && active.owner().equals(owner)) round = null;
    }

    private static Round activeRound(LivingEntity actor) {
        Round active = round;
        if (active == null || actor == null || !TrainingArena.isArena(actor.level())) return null;
        UUID id = actor.getUUID();
        return (id.equals(active.owner()) || id.equals(active.bot())) ? active : null;
    }

    public static boolean keepSupplies(LivingEntity actor) {
        Round active = activeRound(actor);
        return active != null && !active.options().itemConsumption(active.mode());
    }

    public static boolean keepArmor(LivingEntity actor) {
        Round active = activeRound(actor);
        return active != null && !active.options().armorWear();
    }

    public static boolean protectFromFall(LivingEntity actor) {
        Round active = activeRound(actor);
        return active != null && !active.options().fallDamage();
    }

    public static boolean keepHeldTotem(LivingEntity actor) {
        Round active = activeRound(actor);
        return active != null && !active.options().consumeTotems(active.mode())
            && actor instanceof net.minecraft.world.entity.player.Player;
    }

    private TrainingResources() {}
}
