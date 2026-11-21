package de.pvptrainer;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

final class TrainingCrystalItem {
   private static final String MARKER = "pvptrainer:training_crystal";
   private static final Component INACTIVE = Component.literal("Starte zuerst die Trainingsrunde.");

   static ItemStack stack(int var0) {
      ItemStack var1 = new ItemStack(Items.END_CRYSTAL, var0);
      MacherItems.name(var1);
      CustomData.update(DataComponents.CUSTOM_DATA, var1, var0x -> var0x.putBoolean("pvptrainer:training_crystal", true));
      return var1;
   }

   static boolean isTrainingCrystal(ItemStack var0) {
      if (var0.getItem() != Items.END_CRYSTAL) {
         return false;
      }

      CustomData var1 = (CustomData)var0.get(DataComponents.CUSTOM_DATA);
      return var1 != null && var1.copyTag().getBooleanOr("pvptrainer:training_crystal", false);
   }

   static void register() {
      Identifier arenaPlacement = Identifier.fromNamespaceAndPath("pvptrainer", "arena_crystal_placement");
      UseBlockCallback.EVENT.addPhaseOrdering(arenaPlacement, Event.DEFAULT_PHASE);
      UseBlockCallback.EVENT.register(arenaPlacement, (player, level, hand, hit) -> {
         // A predicted client-only entity can occupy the base before our click reaches the server.
         if (TrainingArena.isArena(level) && player.getItemInHand(hand).is(Items.END_CRYSTAL)) {
            return use(player, level, hand, hit);
         }
         return InteractionResult.PASS;
      });
      UseBlockCallback.EVENT.register(TrainingCrystalItem::use);
   }

   private static InteractionResult use(Player var0, Level var1, InteractionHand var2, BlockHitResult var3) {
      ItemStack var4 = var0.getItemInHand(var2);
      if (TrainingArena.isArena(var1) && !var0.isSpectator() && MacherItems.isHealingHead(var4)) {
         return var4.use(var1, var0, var2);
      }
      if (!var4.is(Items.END_CRYSTAL)) {
         return InteractionResult.PASS;
      }

      boolean var5 = var1.dimension().identifier().equals(Identifier.fromNamespaceAndPath("pvptrainer", "arena"));
      if (!var5 && !isTrainingCrystal(var4)) {
         return InteractionResult.PASS;
      }

      BlockPos var6 = var3.getBlockPos();
      if (!isBase(var1.getBlockState(var6))) {
         return InteractionResult.PASS;
      }

      if (!var0.isSpectator() && var1.mayInteract(var0, var6) && (var0.mayBuild() || var4.canPlaceOnBlockInAdventureMode(new BlockInWorld(var1, var6, false)))) {
         if (var1.isClientSide()) {
            if (!Minecraft.getInstance().hasSingleplayerServer()) {
               var0.sendOverlayMessage(INACTIVE);
               return InteractionResult.FAIL;
            } else {
               // The integrated server checks the space, current round and inventory for this click.
               return InteractionResult.SUCCESS_SERVER;
            }
         } else if (var1 instanceof ServerLevel var7 && var0 instanceof ServerPlayer var8) {
            TrainingSession var9 = TrainerClient.session(var7.getServer());
            if (var9 == null || !var9.canPlaceCrystal(var8)) {
               var8.sendOverlayMessage(INACTIVE);
               return InteractionResult.FAIL;
            }

            if (!var9.placeCrystal(var8, var6)) {
               return InteractionResult.FAIL;
            }

            TrainingKit.used(var8);
            var4.consume(1, var8);
            return InteractionResult.SUCCESS_SERVER;
         } else {
            return InteractionResult.FAIL;
         }
      } else {
         return InteractionResult.FAIL;
      }
   }

   private static boolean isBase(BlockState var0) {
      return var0.is(Blocks.OBSIDIAN) || var0.is(Blocks.BEDROCK);
   }

   static boolean canPlace(Level var0, BlockPos var1) {
      BlockPos var2 = var1.above();
      if (var0.isInWorldBounds(var2) && var0.getWorldBorder().isWithinBounds(var2) && isBase(var0.getBlockState(var1)) && var0.isEmptyBlock(var2)) {
         AABB var3 = new AABB(var2).expandTowards(0.0, 1.0, 0.0);
         return var0.getEntities(null, var3).isEmpty();
      } else {
         return false;
      }
   }
}
