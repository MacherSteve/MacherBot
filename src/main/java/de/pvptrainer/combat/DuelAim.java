package de.pvptrainer.combat;
import de.pvptrainer.session.RoundPing;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Small release correction. Projectiles still travel along the fighter's own sight line. */
final class DuelAim {
    private DuelAim() {}

    static Vec3 ranged(LivingEntity bot, ServerPlayer target, double speed, boolean tracking, DuelSkill skill, DuelReads reads) {
        if (tracking) {
            Vec3 origin = bot.getEyePosition().add(0, -.1, 0);
            Vec3 offset = RoundPing.box(target).getCenter().subtract(origin);
            double flight = offset.horizontalDistance() / speed;
            offset = offset.add(reads.travel(skill, Math.min(8, flight))).add(0, .025 * flight * Math.max(0, flight - 1), 0);
            float yaw = (float)Math.toDegrees(Math.atan2(-offset.x, offset.z));
            float pitch = (float)-Math.toDegrees(Math.atan2(offset.y, offset.horizontalDistance()));
            bot.setYRot(Mth.approachDegrees(bot.getYRot(), yaw, 1 + 2 * (float)skill.lead()));
            bot.setXRot(Mth.approachDegrees(bot.getXRot(), pitch, 3 + 5 * (float)skill.lead()));
            bot.setYHeadRot(bot.getYRot());
        }
        return bot.getViewVector(1);
    }
}
