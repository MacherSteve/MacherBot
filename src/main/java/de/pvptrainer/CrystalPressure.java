package de.pvptrainer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

final class CrystalPressure {
   private final BotEquipment kit;
   private final DrillOptions options;
   private CrystalPressure.Step step = CrystalPressure.Step.SWORD;
   private BlockPos base;
   private TrainingCrystal crystal;
   private boolean armed;
   private Vec3 previousPosition;
   private Vec3 lateralSpeed = Vec3.ZERO;
   private int due;
   private int opened;
   private int detonations;

   Vec3 predict(ServerPlayer var1, int var2) {
      return RoundPing.position(var1).add(this.lateralSpeed.scale(var2));
   }

   CrystalPressure(BotEquipment var1, DrillOptions var2) {
      this.kit = var1;
      this.options = var2;
   }

   void tick(ServerLevel var1, LivingEntity var2, ServerPlayer var3, DrillAttempt var4) {
      int var5 = var4.time();
      Vec3 var6 = RoundPing.position(var3);
      if (this.previousPosition != null) {
         Vec3 var7 = var6.subtract(this.previousPosition).multiply(1.0, 0.0, 1.0);
         this.lateralSpeed = var7.lengthSqr() <= 1.0 ? this.lateralSpeed.lerp(var7, 0.75) : Vec3.ZERO;
      }

      this.previousPosition = var6;
      if (!this.armed) {
         if (!var4.readyForDefense(var3)) {
            return;
         }

         this.armed = true;
         this.due = var5 + var4.reaction();
      }

      if (var5 >= this.due && TrainingArena.isArena(var1)) {
         // Replan a burst if the opponent moved away from the prepared base.
         if (this.base != null && (this.step == Step.PLACE || this.step == Step.DETONATE)
            && (var3.distanceToSqr(Vec3.atBottomCenterOf(this.base.above())) > 25 || !var2.hasLineOfSight(var3))) {
            this.restart(var4);
            return;
         }
         if (this.opened > 0 && var5 - this.opened > Math.max(30, this.options.windowTicks())) {
            this.restart(var4);
         } else {
            switch (this.step) {
               case SWORD:
               case AIR_SWORD:
                  boolean var19 = this.step == CrystalPressure.Step.AIR_SWORD;
                  if (!TrainingTactics.withinReach(var2, var3)) {
                     return;
                  }

                  if (var19 && var3.onGround() && var3.getDeltaMovement().y <= 0.02) {
                     this.restart(var4);
                     return;
                  }

                  boolean var20 = this.kit.equipSword(var2) || this.kit.equipComboItem(var2);
                  if (!var20) {
                     var4.defenseUnavailable("Im Bot-Kit fehlt ein Totem oder Schwert für den Nahkontakt.");
                     return;
                  }

                  DamageSource var9 = var2.damageSources().mobAttack(var2);
                  Vec3 var10 = var3.getDeltaMovement();
                  int var11 = var4.playerPops();
                  boolean var12 = var3.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
                  boolean var13 = var3.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
                  boolean var14 = var3.invulnerableTime > 10;
                  boolean var15 = !var19 && var2.onGround();
                  var2.setSprinting(var15);
                  boolean var16 = TrainingTactics.strike(var1, var2, var3, var9, 0.0F);
                  var2.setSprinting(false);
                  if (!var16 && (!var19 || !var14 || var3.isBlocking())) {
                     this.restart(var4);
                     return;
                  }

                  if (var16) {
                     float var17 = EnchantmentHelper.modifyKnockback(var1, var2.getMainHandItem(), var3, var9, 0.0F);
                     var2.causeExtraKnockback(var3, var17 * 0.5F + (var15 ? 0.5F : 0.0F), var10, var9, 0.0F, true);
                  }

                  if (var11 != var4.playerPops()) {
                     var4.defenseBlast(var12, var13, true, 0.0F, var3.isAlive(), false);
                  }

                  if (!var19) {
                     this.opened = var5;
                  }

                  String var21 = var2.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ? "Totem" : "Schwert";
                  var4.event(
                     var19
                        ? "Bot: Nahkampftreffer 2 in der Luft · " + var21 + (var16 ? " · Treffer" : " · Kontakt im Trefferschutz")
                        : "Bot: Nahkampftreffer 1 · " + var21 + (var15 ? " · Sprinttreffer" : "")
                  );
                  this.step = CrystalPressure.Step.PLACE;
                  this.due = var5 + 1;
                  break;
               case PLACE:
                  if (this.base == null || !TrainingCrystalItem.canPlace(var1, this.base)
                      || !TrainingTactics.usableSite(var1, var2, var3, this.base)) {
                     Vec3 predicted = this.predict(var3, 3);
                     if (this.detonations == 0 && var5 - this.opened <= 2) {
                        predicted = var3.position().add(var3.getDeltaMovement().scale(3));
                     }
                     this.base = this.prepareBase(var1, var2, var3, predicted, var4);
                     if (this.base == null) {
                        this.restart(var4);
                        return;
                     }
                  }

                  if (!this.useSupply(var2, Items.END_CRYSTAL)) {
                     var4.defenseUnavailable("Im Bot-Kit fehlt ein nutzbarer Endkristall.");
                     return;
                  }

                  this.crystal = new TrainingCrystal(var1, Vec3.atBottomCenterOf(this.base.above()), var3, var2);
                  this.crystal.setShowBottom(false);
                  if (!var1.addFreshEntity(this.crystal)) {
                     this.restart(var4);
                     return;
                  }

                  var2.swing(InteractionHand.MAIN_HAND, true);
                  var1.playSound(null, this.base, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 0.65F, 1.2F);
                  this.step = CrystalPressure.Step.DETONATE;
                  this.due = var5 + 1;
                  break;
               case DETONATE:
                  if (this.detonations > 0 && var3.invulnerableTime > 10) {
                     return;
                  }

                  if (this.detonations == 0 && var5 - this.opened >= 10) {
                     this.restart(var4);
                     return;
                  }
                  if (this.crystal == null || this.crystal.isRemoved() || !TrainingTactics.canDetonate(var2, var3, this.crystal)) {
                     this.clear();
                     this.base = null;
                     this.step = Step.PLACE;
                     this.due = var5 + 1;
                     return;
                  }
                  // Let the target rise above the new obsidian before breaking the first crystal.
                  // A stronger hit can still deal damage within the sword's original ten-tick window.
                  if (this.detonations == 0 && var5 - this.opened < 8 && var3.getY() < this.crystal.getY()
                        && (var3.getDeltaMovement().y > 0 || !var3.onGround())) return;

                  this.kit.guard(var2);
                  var2.swing(InteractionHand.MAIN_HAND, true);
                  this.detonations++;
                  boolean var18 = this.detonations == (this.options.tripleBurst() ? 3 : 2);
                  var4.event("Bot: Kristall " + this.detonations);
                  boolean var8 = this.crystal.detonate(var2, var18);
                  this.crystal = null;
                  if (!var8) {
                     var4.event("Kristall ohne Zielschaden · Bot eröffnet erneut");
                  }

                  if (!var18 && var8) {
                     this.step = this.options.tripleBurst() && this.detonations == 1 ? CrystalPressure.Step.AIR_SWORD : CrystalPressure.Step.PLACE;
                     this.due = var5 + 1;
                  } else {
                     this.restart(var4);
                  }
            }
         }
      }
   }

   private BlockPos prepareBase(ServerLevel level, LivingEntity bot, ServerPlayer target, Vec3 predicted, DrillAttempt attempt) {
      BlockPos selected = TrainingTactics.preferredCrystalSite(level, bot, target, predicted, true);
      if (selected == null || TrainingCrystalItem.canPlace(level, selected)) return selected;
      if (!this.useSupply(bot, Items.OBSIDIAN)) {
         BlockPos available = TrainingTactics.preferredCrystalSite(level, bot, target, predicted, false);
         if (available == null) attempt.defenseUnavailable("Im Bot-Kit fehlt ein nutzbarer Obsidianblock.");
         return available;
      }
      if (!level.setBlockAndUpdate(selected, Blocks.OBSIDIAN.defaultBlockState())) return null;
      bot.swing(InteractionHand.MAIN_HAND, true);
      level.playSound(null, selected, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 1, 0.8F);
      return selected;
   }

   private boolean useSupply(LivingEntity var1, Item var2) {
      if (!this.kit.equipWeapon(var1, var2)) {
         return false;
      } else {
         return this.options.drill() == Drill.RETOTEM && var1.getMainHandItem().getCount() == 1 ? true : this.kit.takeOne(var1, var2);
      }
   }

   private void restart(DrillAttempt var1) {
      this.clear();
      this.base = null;
      this.opened = this.detonations = 0;
      this.due = var1.time() + var1.reaction();
      this.step = CrystalPressure.Step.SWORD;
   }

   void clear() {
      if (this.crystal != null && !this.crystal.isRemoved()) {
         this.crystal.discard();
      }

      this.crystal = null;
   }

   private enum Step {
      SWORD,
      PLACE,
      DETONATE,
      AIR_SWORD;
   }
}
