package de.pvptrainer.combat;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** One decision at first contact, using the delayed sighting already available to footwork. */
final class DuelOpening {
    private enum Route { UNDECIDED, CUT_INSIDE, DRAW_OUT, PRESS }
    private Route route = Route.UNDECIDED;
    private Vec3 origin, lane;
    private double travel;
    private int started, sprintUntil;
    private boolean baseAngle;

    boolean repositioning() { return route == Route.CUT_INSIDE || route == Route.DRAW_OUT; }
    void commit() { route = Route.PRESS; }
    boolean takeBaseAngle() {
        boolean value = baseAngle;
        baseAngle = false;
        return value;
    }

    Vec3 steer(LivingEntity bot, DuelSkill skill, DrillOptions options, TrainingSettings.Movement movement,
               int tick, Vec3 forward, double gap, Vec3 velocity, Vec3 facing) {
        if (movement == TrainingSettings.Movement.STILL || route == Route.PRESS) return null;
        if (route == Route.UNDECIDED && tick > skill.opening(options) + skill.readTicks()) {
            commit();
            return null;
        }
        if (!bot.onGround() || bot.hurtTime > 0 || bot.isOnFire() || bot.isUsingItem()) {
            if (repositioning()) commit();
            return null;
        }
        if (tick < skill.opening(options) - 2) return null;
        Vec3 side = new Vec3(forward.z, 0, -forward.x);
        double lateral = velocity.dot(side), outward = velocity.dot(forward);
        boolean fleeing = outward > .09 && outward > Math.abs(lateral) * 1.3;
        if (fleeing || gap > 4.1 || gap < 1.15) {
            if (fleeing) chase(tick, skill);
            commit();
            return null;
        }
        if (route == Route.UNDECIDED) {
            double precision = skill.lead();
            boolean crossing = Math.abs(lateral) > .065 + .035 * (1 - precision);
            boolean squared = facing.dot(forward) < -.68;
            double pressure = crossing ? .56 - .36 * precision : squared ? .72 - .20 * precision : 1;
            if (outward < -.13) pressure += .12;
            double choice = bot.getRandom().nextDouble();
            if (choice < pressure) {
                commit();
                return null;
            }
            origin = bot.position();
            started = tick;
            double inside = crossing ? .65 + .19 * precision : .47;
            if (movement == TrainingSettings.Movement.STRAFE && choice < pressure + (1 - pressure) * inside) {
                route = Route.CUT_INSIDE;
                // Work across a crossing opponent's line or off the aim of a stationary player.
                double offAim = facing.dot(side);
                double sign = crossing ? Math.signum(lateral) : Math.abs(offAim) > .12 ? -Math.signum(offAim)
                        : bot.getRandom().nextBoolean() ? 1 : -1;
                lane = side.scale(sign);
                travel = Math.clamp(bot.getBbWidth() + Math.abs(lateral) * (2 + 3 * precision)
                        + bot.getRandom().nextDouble() * .45, .65, 1.85);
                baseAngle = crossing;
            } else {
                route = Route.DRAW_OUT;
                lane = forward.scale(-1);
                // Create room for sprint re-entry. Already near the edge of reach, use a smaller step.
                travel = Math.clamp(3.45 - gap + bot.getRandom().nextDouble() * .4, .4, 1.45);
            }
        }
        boolean reversed = route == Route.CUT_INSIDE && velocity.dot(lane) < -.07 && tick > started + skill.switchTicks();
        if (reversed || bot.position().subtract(origin).dot(lane) >= travel || tick - started >= 17) {
            if (route == Route.DRAW_OUT) chase(tick, skill);
            commit();
            return null;
        }
        Vec3 step = route == Route.DRAW_OUT ? lane : lane.add(forward.scale(Math.clamp((gap - 2.6) * .72, -.30, .78)));
        var next = bot.getBoundingBox().move(step.normalize().scale(.48)).deflate(.025);
        if (!bot.level().noBlockCollision(bot, next) || bot.level().noBlockCollision(bot, next.move(0, -.65, 0))) {
            commit();
            return null;
        }
        return step;
    }

    private void chase(int tick, DuelSkill skill) { sprintUntil = tick + 4 + skill.switchTicks(); }
    boolean sprintIn(int tick) { return sprintUntil > 0 && tick <= sprintUntil; }
}
