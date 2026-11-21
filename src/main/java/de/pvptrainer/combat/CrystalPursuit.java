package de.pvptrainer.combat;
import de.pvptrainer.session.RoundPing;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Estimates horizontal movement only from consecutive visible target positions. */
final class CrystalPursuit {
   private Vec3 previous;
   private Vec3 velocity = Vec3.ZERO;
   private double outwardSpeed;
   private double sidewaysSpeed;

   void observe(LivingEntity bot, ServerPlayer target, boolean visible) {
      if (!visible) {
         previous = null;
         velocity = Vec3.ZERO;
         outwardSpeed = sidewaysSpeed = 0;
         return;
      }
      Vec3 position = RoundPing.position(target);
      if (previous != null) {
         Vec3 move = position.subtract(previous).multiply(1, 0, 1);
         velocity = move.lengthSqr() > 1 ? Vec3.ZERO : velocity.lerp(move, 0.75);
      }
      previous = position;
      Vec3 bearing = position.subtract(bot.position()).multiply(1, 0, 1).normalize();
      outwardSpeed = velocity.dot(bearing);
      sidewaysSpeed = Math.abs(velocity.x * bearing.z - velocity.z * bearing.x);
   }

   Vec3 aim(ServerPlayer target) {
      int lead = retreating() ? 4 : 3;
      return RoundPing.position(target).add(velocity.scale(lead));
   }

   boolean retreating() { return outwardSpeed > 0.10; }
   boolean sidestepping() { return sidewaysSpeed > 0.10; }

   boolean aboutToPass(Vec3 crystal, ServerPlayer target) {
      Vec3 offset = RoundPing.position(target).subtract(crystal).multiply(1, 0, 1);
      return velocity.lengthSqr() > 0.01 && offset.dot(velocity) > 0.10;
   }

   int breakDelay(LivingEntity bot, int recovery, Vec3 crystal, ServerPlayer target) {
      if (aboutToPass(crystal,target)) return 1;
      int turn = Math.max(1, recovery / 8);
      if (sidestepping() || retreating()) {
         // An approaching opponent may cross closer to the newly prepared base.
         double speed = velocity.lengthSqr();
         Vec3 toBase = crystal.subtract(RoundPing.position(target)).multiply(1, 0, 1);
         int closestIn = (int)Math.round(toBase.dot(velocity) / Math.max(0.01, speed));
         return Math.clamp(closestIn,1,turn+3);
      }
      // When neither player is committing, quick pressure and holding the crystal are both viable.
      return turn + 1 + bot.getRandom().nextInt(4);
   }

   static boolean inFront(LivingEntity bot, Vec3 point) {
      Vec3 to = point.subtract(bot.position()).multiply(1, 0, 1);
      if (to.lengthSqr() < 0.04) return false;
      double yaw = Math.toRadians(bot.getYRot());
      return -Math.sin(yaw)*to.x + Math.cos(yaw)*to.z >= 0.26*to.horizontalDistance();
   }

   static boolean offensiveSite(LivingEntity bot, ServerPlayer target, Vec3 origin) {
      if (!inFront(bot,origin)) return false;
      Vec3 towardTarget = RoundPing.position(target).subtract(bot.position()).multiply(1,0,1);
      Vec3 towardSite = origin.subtract(bot.position()).multiply(1,0,1);
      return towardTarget.lengthSqr() < 0.04 || towardSite.dot(towardTarget) > 0;
   }
}
