package de.pvptrainer;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** A brief tell may be missed. A longer tell remains exploitable at every tier. */
final class DuelReads {
    private Vec3 previous;
    private Vec3 velocity = Vec3.ZERO;
    private int shield, bow, food, retreat, seen;
    private boolean trapThisEscape;

    void observe(LivingEntity bot, ServerPlayer target, UhcDefense defense) {
        if (!bot.hasLineOfSight(target) || !defense.facing(bot, target)) {
            previous = null;
            velocity = Vec3.ZERO;
            shield = bow = food = retreat = seen = 0;
            return;
        }
        Vec3 position = RoundPing.position(target);
        if (previous != null && previous.distanceToSqr(position) < 2.25) {
            velocity = velocity.lerp(position.subtract(previous).horizontal(), 0.65);
        } else {
            velocity = Vec3.ZERO;
        }
        previous = position;
        seen++;
        shield = RoundPing.shield(target, defense.shieldFaces(bot, target)) ? shield + 1 : 0;
        bow = RoundPing.bow(target, defense.aimingBowAt(target, bot)) ? bow + 1 : 0;
        food = RoundPing.food(target, target.isUsingItem() && target.getUseItem().has(DataComponents.CONSUMABLE)) ? food + 1 : 0;
        double outward = velocity.dot(position.subtract(bot.position()).horizontal().normalize());
        if (outward > .085) {
            if (retreat == 0) {
                // Choose once on the breakaway. A close opponent may still be pressured with a sword.
                trapThisEscape = bot.getRandom().nextDouble() < (outward > .20 ? .73 : .48);
            }
            retreat++;
        } else retreat = 0;
    }

    boolean shield(DuelSkill skill) { return shield >= skill.readTicks(); }
    boolean bow(DuelSkill skill) { return bow >= skill.readTicks(); }
    boolean food(DuelSkill skill) { return food >= skill.readTicks(); }
    boolean retreat(DuelSkill skill) { return retreat >= skill.readTicks(); }
    boolean trapRetreat(DuelSkill skill, boolean stillInReach) {
        return retreat(skill) && (trapThisEscape || !stillInReach);
    }
    boolean acquired(DuelSkill skill) { return seen >= skill.readTicks(); }
    Vec3 travel(DuelSkill skill, double ticks) { return velocity.scale(skill.lead() * ticks); }
}
