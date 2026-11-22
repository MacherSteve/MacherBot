package de.pvptrainer.combat;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.session.RoundPing;
import de.pvptrainer.kits.TrainingSettings;

import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Short-term visual memory and movement for a single free duel. */
public final class DuelFootwork {
    private record Sighting(int tick, Vec3 feet, Vec3 eyes, Vec3 facing, Vec3 velocity) {}
    private final DuelSkill skill;
    private final Sighting[] memory;
    private Sighting previous, target;
    private double flank;
    private int committedUntil;
    private float yawSpeed, pitchSpeed;
    private double uhcDistance = 2.0;

    public DuelFootwork(DuelSkill skill, LivingEntity bot) {
        this.skill = skill;
        memory = new Sighting[skill.readTicks() + 1 + bot.getRandom().nextInt(2)];
        flank = bot.getRandom().nextBoolean() ? 1 : -1;
    }

    private void observe(LivingEntity bot, ServerPlayer player, int tick) {
        Vec3 bearing = RoundPing.eyes(player).subtract(bot.getEyePosition());
        boolean inView = bearing.lengthSqr() < .25 || bot.getLookAngle().dot(bearing.normalize()) > .08;
        Sighting now = null;
        if (inView && bot.hasLineOfSight(player)) {
            Vec3 feet = RoundPing.position(player), velocity = Vec3.ZERO;
            if (previous != null && feet.distanceToSqr(previous.feet()) <= 2.25) {
                velocity = previous.velocity().scale(.35).add(feet.subtract(previous.feet()).horizontal().scale(.65));
            }
            now = new Sighting(tick, feet, RoundPing.eyes(player), player.getLookAngle().horizontal(), velocity);
        }
        previous = now;
        int slot = tick % memory.length;
        memory[slot] = now;
        Sighting seen = memory[(slot + 1) % memory.length];
        if (seen != null) target = seen;
    }

    public void steer(LivingEntity bot, ServerPlayer player, TrainingSettings settings, DrillOptions options,
               TrainingTactics tactics, int tick) {
        observe(bot, player, tick);
        boolean track = options.gaze() == DrillOptions.Gaze.TRACK;
        var workingBlock = tactics.workingBlock();
        if (previous == null && workingBlock != null) {
            if (track) turn(bot, Vec3.atCenterOf(workingBlock), options.turnRate());
            move(bot, Vec3.ZERO, false);
            return;
        }
        int age = target == null ? Integer.MAX_VALUE : tick - target.tick();
        if (age > 28) {
            move(bot, Vec3.ZERO, false);
            // A lost opponent can reappear on either side. Search from our own orientation.
            if (track && tick > memory.length + 3) {
                bot.setYRot(bot.getYRot() + (float)(flank * Math.min(8, options.turnRate())));
                bot.setYHeadRot(bot.getYRot());
                bot.setYBodyRot(bot.getYRot());
            }
            yawSpeed = pitchSpeed = 0;
            return;
        }

        boolean fresh = age < memory.length + 2;
        Vec3 lead = fresh ? target.velocity().scale(1.8 * skill.lead()) : Vec3.ZERO;
        if (track) turn(bot, target.eyes().add(lead), options.turnRate());
        if (settings.movement() == TrainingSettings.Movement.STILL || options.slamScene() != DrillOptions.SlamScene.FREE) {
            move(bot, Vec3.ZERO, false);
            return;
        }

        Vec3 path = target.feet().add(lead).subtract(bot.position()).horizontal();
        double distance = path.length();
        Vec3 forward = path.normalize(), side = new Vec3(forward.z, 0, -forward.x);
        if (!fresh) {
            boolean follow = distance > 1.7 && (!bot.onGround() || supported(bot, forward));
            move(bot, follow ? forward.scale(.7) : Vec3.ZERO, false);
            return;
        }
        Vec3 entryStep = tactics.opening.steer(bot, skill, options, settings.movement(), tick,
                forward, distance, target.velocity(), target.facing());
        if (entryStep != null) {
            move(bot, entryStep, false);
            return;
        }
        if (tactics.opening.sprintIn(tick) && distance > 1.8 && supported(bot, forward)) {
            move(bot, forward, true);
            return;
        }

        if (settings.mode() == TrainingSettings.Mode.UHC && previous != null)
            uhcDistance = tactics.uhcSpacing(bot, player, distance);
        double spacing = switch (settings.mode()) {
            case CRYSTAL, HUGOSMP -> 2.65;
            case UHC -> uhcDistance;
            default -> 2.0;
        };
        if (spacing < 4.0) spacing = Math.min(spacing, options.botReach() - .15);
        double advance = Mth.clamp((distance - spacing) * 1.35, -(.2 + .75 * skill.lead()), 1.0);
        double chase = target.velocity().dot(forward);
        if (chase > .09 && distance >= spacing) advance = 1.0;
        double across = 0;
        if (settings.movement() == TrainingSettings.Movement.STRAFE && distance < spacing + 3.0) {
            double crossing = target.velocity().dot(side);
            double lookSide = target.facing().dot(side);
            if (tick >= committedUntil) {
                double preferred = flank;
                if (Math.abs(crossing) > .08 && skill.lead() >= .6) preferred = Math.signum(crossing);
                else if (Math.abs(lookSide) > .24) preferred = -Math.signum(lookSide);
                if (bot.horizontalCollision || !walkable(bot, side.scale(preferred))) preferred = -preferred;
                flank = preferred;
                committedUntil = tick + 3 + 2 * skill.switchTicks() + bot.getRandom().nextInt(4);
            }
            across = flank * (chase > .09 ? .32 : .66);
        }
        Vec3 step = forward.scale(advance).add(side.scale(across));
        if (bot.onGround() && !supported(bot, step)) step = Vec3.ZERO;
        move(bot, step, advance > .85 && (distance > spacing + 1.2 || chase > .09));
    }

    private void turn(LivingEntity bot, Vec3 aim, int configuredLimit) {
        float yaw = bot.getYRot(), pitch = bot.getXRot();
        bot.lookAt(Anchor.EYES, aim);
        float dy = Mth.wrapDegrees(bot.getYRot() - yaw), dx = bot.getXRot() - pitch;
        float limit = Math.min(configuredLimit, skill.turn());
        yawSpeed = nextTurn(yawSpeed, dy, limit);
        pitchSpeed = nextTurn(pitchSpeed, dx, limit);
        bot.setYRot(yaw + yawSpeed);
        bot.setXRot(Mth.clamp(pitch + pitchSpeed, -90, 90));
        bot.setYHeadRot(bot.getYRot());
        bot.setYBodyRot(bot.getYRot());
    }

    private static float nextTurn(float last, float gap, float limit) {
        float desired = Mth.clamp(gap, -limit, limit);
        float step = last + Mth.clamp(desired - last, -limit * .55F, limit * .55F);
        return Math.signum(gap) == Math.signum(step) ? Math.copySign(Math.min(Math.abs(gap), Math.abs(step)), gap) : step;
    }

    private static boolean walkable(LivingEntity bot, Vec3 step) {
        var next = bot.getBoundingBox().move(step.normalize().scale(.48)).deflate(.025);
        return bot.level().noBlockCollision(bot, next) && !bot.level().noBlockCollision(bot, next.move(0, -.65, 0));
    }

    private static boolean supported(LivingEntity bot, Vec3 step) {
        var next = bot.getBoundingBox().move(step.normalize().scale(.48)).deflate(.025);
        return !bot.level().noBlockCollision(bot, next.move(0, -.65, 0));
    }

    private static void move(LivingEntity bot, Vec3 step, boolean sprint) {
        if (step.lengthSqr() > 1) step = step.normalize();
        double yaw = Math.toRadians(bot.getYRot());
        bot.zza = (float)(-step.x * Math.sin(yaw) + step.z * Math.cos(yaw));
        bot.xxa = (float)(step.x * Math.cos(yaw) + step.z * Math.sin(yaw));
        bot.setSprinting(sprint && bot.zza > .5 && !bot.isUsingItem());
        bot.setJumping(bot.horizontalCollision && step.lengthSqr() > .12);
    }
}
