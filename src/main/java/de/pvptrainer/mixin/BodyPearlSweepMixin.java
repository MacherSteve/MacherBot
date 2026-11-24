package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.drill.PearlInterceptSolver;
import de.pvptrainer.session.ArenaSignals;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Continuous relative swept collision for fast-falling Body Pearl targets (prevents tunneling & inside-start misses). */
@Mixin(ThrowableProjectile.class)
abstract class BodyPearlSweepMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Lnet/minecraft/world/phys/HitResult;"))
    private HitResult pvptrainer$sweepBodyPearl(Entity entity, Predicate<Entity> filter, Operation<HitResult> original) {
        HitResult hit = original.call(entity, filter);
        if (!(entity instanceof ThrownEnderpearl pearl) || !(pearl.getOwner() instanceof ServerPlayer owner)) {
            return hit;
        }
        LivingEntity target = ArenaSignals.bodyPearlTarget(owner);
        if (target == null || !target.isAlive() || target.isRemoved() || !filter.test(target)) {
            return hit;
        }
        if (hit instanceof EntityHitResult ehr && ehr.getEntity() == target) {
            return hit;
        }
        Vec3 pearlStart = pearl.position();
        Vec3 pearlEnd = pearlStart.add(pearl.getDeltaMovement());
        Vec3 targetStart = new Vec3(target.xo, target.yo, target.zo);
        Vec3 targetEnd = target.position();
        double margin = Math.max(0.30, ProjectileUtil.computeMargin(pearl));
        Optional<Vec3> swept = PearlInterceptSolver.sweepMovingTarget(
            pearlStart, pearlEnd, target.getBoundingBox(), targetStart, targetEnd, margin
        );
        if (swept.isPresent()) {
            Vec3 hitPos = swept.get();
            if (hit.getType() == HitResult.Type.MISS
                || pearlStart.distanceToSqr(hitPos) <= pearlStart.distanceToSqr(hit.getLocation()) + 1.0E-6) {
                return new EntityHitResult(target, hitPos);
            }
        }
        return hit;
    }
}
