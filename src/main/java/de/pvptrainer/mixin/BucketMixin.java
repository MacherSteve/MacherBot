package de.pvptrainer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import de.pvptrainer.session.ArenaSignals;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BucketItem.class)
abstract class BucketMixin {
   @WrapOperation(
      method = "emptyContents",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
      )
   )
   private boolean pvptrainer$lava(Level var1, BlockPos var2, BlockState var3, int var4, Operation<Boolean> var5, LivingEntity var6) {
      boolean var7 = (Boolean)var5.call(new Object[]{var1, var2, var3, var4});
      if (var7 && var3.is(Blocks.LAVA) && var6 instanceof ServerPlayer var8) {
         ArenaSignals.lava(var8, var2);
      }

      return var7;
   }

   @WrapOperation(
      method = "use",
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"
      )
   )
   private ItemStack pvptrainer$pickup(BucketPickup var1, LivingEntity var2, LevelAccessor var3, BlockPos var4, BlockState var5, Operation<ItemStack> var6) {
      ItemStack var7 = (ItemStack)var6.call(new Object[]{var1, var2, var3, var4, var5});
      if (var7.is(Items.WATER_BUCKET) && var2 instanceof ServerPlayer var8) {
         ArenaSignals.water(var8, var4);
      }

      return var7;
   }
}
