package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.session.TrainingResources;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemUtils.class)
abstract class ResourceContainerMixin {
    @WrapOperation(method="createFilledResult(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
        at=@At(value="INVOKE", target="Lnet/minecraft/world/entity/player/Player;hasInfiniteMaterials()Z"))
    private static boolean pvptrainer$keepContainer(Player actor, Operation<Boolean> original) {
        return TrainingResources.keepSupplies(actor) || original.call(actor);
    }
}
