package de.pvptrainer.session;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.kits.TrainingSettings;

import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
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

    public static Difficulty arenaDifficulty(ServerLevel level) {
        if (level == null || !TrainingArena.isArena(level)) {
            return level == null ? Difficulty.HARD : level.getDifficulty();
        }
        return Difficulty.HARD;
    }

    public static boolean zeroDamageFor(LivingEntity actor) {
        Round active = activeRound(actor);
        if (active == null) return false;
        DrillOptions.ZeroDamage mode = active.options().zeroDamage();
        if (mode == DrillOptions.ZeroDamage.OFF) return false;
        if (mode == DrillOptions.ZeroDamage.BOTH) return true;
        return actor.getUUID().equals(active.owner());
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
