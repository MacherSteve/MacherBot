package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.session.TrainingResources;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ProjectileWeaponItem.class)
abstract class ResourceAmmoMixin {
    @WrapOperation(method="useAmmo", at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/LivingEntity;hasInfiniteMaterials()Z"))
    private static boolean pvptrainer$keepRealAmmunition(LivingEntity actor, Operation<Boolean> original) {
        return TrainingResources.keepSupplies(actor) || original.call(actor);
    }
}
