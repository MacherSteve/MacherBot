package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.session.TrainingResources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class ResourceLivingMixin {
    @Inject(method="hurtServer", at=@At("HEAD"), cancellable=true)
    private void pvptrainer$skipLandingDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> result) {
        if ((source.is(DamageTypes.FALL) || source.is(DamageTypes.STALAGMITE))
            && TrainingResources.protectFromFall((LivingEntity)(Object)this)) result.setReturnValue(false);
    }

    @WrapOperation(method="checkTotemDeathProtection", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void pvptrainer$preservePlayerTotem(ItemStack stack, int count, Operation<Void> original) {
        if (!TrainingResources.keepHeldTotem((LivingEntity)(Object)this)) original.call(stack, count);
    }
}
