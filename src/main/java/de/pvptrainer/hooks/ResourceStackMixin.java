package de.pvptrainer.hooks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.TrainingResources;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
abstract class ResourceStackMixin {
    @Inject(method="consume", at=@At("HEAD"), cancellable=true)
    private void pvptrainer$preserveSupply(int count, LivingEntity actor, CallbackInfo call) {
        // Moving equipment to a slot or throwing a recoverable weapon still transfers the original item.
        ItemStack stack = (ItemStack)(Object)this;
        if (TrainingResources.keepSupplies(actor) && !stack.isDamageableItem() && !stack.has(DataComponents.EQUIPPABLE)) call.cancel();
    }

    // Some vanilla items call shrink() directly when placed, including grounded rockets and crystals.
    @WrapOperation(method="useOn", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult pvptrainer$keepPlacedStack(Item item, UseOnContext context, Operation<InteractionResult> original) {
        ItemStack stack = context.getItemInHand();
        int before = TrainingResources.keepSupplies(context.getPlayer()) && !stack.isDamageableItem() ? stack.getCount() : 0;
        InteractionResult result = original.call(item, context);
        if (stack.getCount() < before) stack.setCount(before);
        return result;
    }

    @Inject(method="hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V",
        at=@At("HEAD"), cancellable=true)
    private void pvptrainer$preserveArmor(int amount, LivingEntity wearer, EquipmentSlot slot, CallbackInfo call) {
        if (amount > 0 && slot.isArmor() && TrainingResources.keepArmor(wearer)) call.cancel();
    }
}
