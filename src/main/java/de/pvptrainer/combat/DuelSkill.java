package de.pvptrainer.combat;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;

import net.minecraft.world.entity.LivingEntity;

/** Training parameters for free duels. Every tier obeys the same weapon and movement mechanics. */
public record DuelSkill(int readTicks, int switchTicks, int swordPause, int recovery, double lead, float turn) {
    private static final DuelSkill HT1 = new DuelSkill(2, 1, 0, 13, 1.00, 52);
    private static final DuelSkill LT1 = new DuelSkill(2, 2, 0, 14, .93, 47);
    private static final DuelSkill HT2 = new DuelSkill(3, 2, 1, 15, .87, 43);
    private static final DuelSkill LT2 = new DuelSkill(4, 2, 2, 16, .78, 37);
    private static final DuelSkill HT3 = new DuelSkill(5, 3, 2, 17, .69, 33);
    private static final DuelSkill LT3 = new DuelSkill(6, 4, 3, 19, .58, 29);
    private static final DuelSkill HT4 = new DuelSkill(7, 4, 4, 20, .49, 25);
    private static final DuelSkill LT4 = new DuelSkill(8, 5, 5, 22, .38, 22);
    private static final DuelSkill HT5 = new DuelSkill(9, 6, 6, 24, .29, 19);
    private static final DuelSkill LT5 = new DuelSkill(11, 7, 8, 26, .18, 16);

    public static DuelSkill of(TrainingSettings settings) {
        boolean high = settings.duelBand() == TrainingSettings.DuelBand.HIGH;
        return switch (settings.duelTier()) {
            case T1 -> high ? HT1 : LT1;
            case T2 -> high ? HT2 : LT2;
            case T3 -> high ? HT3 : LT3;
            case T4 -> high ? HT4 : LT4;
            case T5 -> high ? HT5 : LT5;
        };
    }

    int opening(DrillOptions options) {
        return options.reactionTicks() + readTicks - 2;
    }

    int recovery(LivingEntity bot) {
        return recovery + bot.getRandom().nextInt(1 + switchTicks / 2);
    }
}
