package de.pvptrainer;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow.Pickup;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.ClipContext.Block;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;

final class TrainingTactics {
   private final List<TrainingTactics.DrillArrow> arrows = new ArrayList<>();
   private final List<TrainingCrystal> otherCrystals = new ArrayList<>();
   private TrainingCrystal crystal;
   private final BotEquipment equipment;
   private final DrillOptions options;
   private BlockPos prepared;
   private boolean openCrystalCombo = true;
   private final CrystalPursuit crystalPursuit = new CrystalPursuit();
   private final CrystalUtility crystalUtility = new CrystalUtility();
   final CrystalCover crystalCover = new CrystalCover();
   private final DuelRangedGuard crystalGuard = new DuelRangedGuard();
   private int crystalPlacedAt;
   private int nextCrystalSword;
   private int nextAttack = 18;
   private int lowerShieldAt;
   final DuelOpening opening = new DuelOpening();
   final SwordDuel swordDuel = new SwordDuel(opening);
   private final DuelSupplies supplies = new DuelSupplies();
   private final MaceDuel maceDuel = new MaceDuel();
   private boolean maceAirborne;
   private final UhcUtility uhcUtility = new UhcUtility();
   private int nextBowShot;
   private final UhcDefense uhcDefense = new UhcDefense();
   private final DuelReads reads = new DuelReads();
   private final DuelWeapon duelWeapon = new DuelWeapon();
   private final CrystalPressure pressure;
   private boolean swingAnnounced;

   TrainingTactics(BotEquipment var1, DrillOptions var2) {
      this.equipment = var1;
      this.options = var2;
      this.nextAttack = var2.reactionTicks();
      this.pressure = var2.drill().totemDefense() ? new CrystalPressure(var1, var2) : null;
   }

   Vec3 pursuitPoint(ServerPlayer var1) {
      return this.pressure == null ? var1.position() : this.pressure.predict(var1, 3);
   }

   BlockPos workingBlock() {
      if (crystalCover.workingBlock() != null) return crystalCover.workingBlock();
      if (uhcUtility.workingBlock() != null) return uhcUtility.workingBlock();
      return uhcDefense.workingBlock();
   }

   void practiceTick(ServerLevel var1, LivingEntity var2, ServerPlayer var3, TrainingSettings var4, DrillAttempt var5) {
      if (var5.allowRescue() && TrainingArena.isArena(var1)) {
         BlockPos var6 = this.options.drill() == Drill.RE_LAVA ? var2.blockPosition() : var2.blockPosition().above();
         if (var1.getBlockState(var6).canBeReplaced() && !var6.equals(var5.rescueSource())) {
            if (!this.equipment.equipWeapon(var2, Items.WATER_BUCKET)) {
               var5.finish(false, "Für diese Übung braucht der Bot eine freie Hand und einen Wassereimer.");
               return;
            }

            BucketItem var7 = (BucketItem)Items.WATER_BUCKET;
            if (!var7.emptyContents(var2, var1, var6, null)) {
               return;
            }

            this.equipment.takeOne(var2, Items.WATER_BUCKET);
            if (this.equipment.consumeSupplies) {
               var2.setItemInHand(InteractionHand.MAIN_HAND, MacherItems.name(new ItemStack(Items.BUCKET)));
            } else if (!this.equipment.has(var2, Items.BUCKET)) {
               this.equipment.stash(new ItemStack(Items.BUCKET));
            }
            var2.swing(InteractionHand.MAIN_HAND, true);
            var5.waterPlaced(var6);
         }
      }

      if (this.options.drill() == Drill.RE_LAVA
         && var5.rescueSource() != null
         && var5.time() - var5.rescueTick() == 12
         && var1.getBlockState(var5.rescueSource()).is(Blocks.WATER)) {
         var1.removeBlock(var5.rescueSource(), false);
      }

      if (this.pressure != null) {
         this.pressure.tick(var1, var2, var3, var5);
      } else {
         if (this.options.drill().needsSwordAttack()) {
            boolean var8 = withinReach(var2, var3)
               && (var4.mode() != TrainingSettings.Mode.UHC || uhcDefense.obstructingWeb(var1, var2, var3) == null);
            if (!var8) {
               this.swingAnnounced = false;
            }

            if (this.options.usesJumpCountdown() && var8 && !this.swingAnnounced && var5.time() >= this.nextAttack - 3) {
               this.swingAnnounced = true;
               this.nextAttack = var5.time() + 3;
               if (this.options.hints()) {
                  var5.announceSwing();
                  var3.sendOverlayMessage(Component.literal("Schlag in 3 Ticks"));
               }
               var1.playSound(null, var2.blockPosition(), (SoundEvent)SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.65F, 1.0F);
            }

            if (var5.time() >= this.nextAttack && var8) {
               this.equipment.equipSword(var2);
               strike(var1, var2, var3, var2.damageSources().mobAttack(var2), 0.0F);
               this.nextAttack = var5.time() + var5.reaction();
               this.swingAnnounced = false;
            }
         } else if (this.options.drill() != Drill.PASSIVE && var4.counterAttacks()) {
            this.tick(var1, var2, var3, var4, var5.time());
         }
      }
   }

   void tick(ServerLevel var1, LivingEntity var2, ServerPlayer var3, TrainingSettings var4, int var5) {
      DuelSkill skill = null;
      if (this.options.drill() == Drill.DUEL) {
         skill = DuelSkill.of(var4);
         reads.observe(var2, var3, uhcDefense);
         // The fighter can see a wall going up before they are ready to act.
         boolean acquired = reads.acquired(skill);
         if (var4.mode() == TrainingSettings.Mode.UHC && acquired) uhcUtility.observe(var3,var5);
         if (var4.mode() == TrainingSettings.Mode.CRYSTAL) {
            crystalPursuit.observe(var2, var3, var2.hasLineOfSight(var3) && uhcDefense.facing(var2,var3));
            if (acquired) crystalCover.observe(var3);
         }
         if (reads.shield(skill) || reads.food(skill) || reads.bow(skill)) opening.commit();
         if (var5 < skill.opening(options)) {
            // A raised, aimed ranged weapon can be answered while preparing the first offensive input.
            if (!options.holdShield()) {
               boolean shot = reads.bow(skill);
               switch (var4.mode()) {
                  case CRYSTAL -> crystalGuard.tick(var1,var2,var3,equipment,shot);
                  case MACE -> maceDuel.rangedGuard(var1,var2,var3,equipment,shot);
                  case UHC -> {
                     if (shot && var2.distanceToSqr(var3) <= 324 && !withinReach(var2,var3)) uhcDefense.guard(var2,equipment);
                     else if (var2.isUsingItem() && var2.getUseItem().is(Items.SHIELD)) var2.stopUsingItem();
                  }
                  case SWORD -> {}
               }
            }
            return;
         }
      }
      if (this.options.drill()==Drill.DUEL && var4.mode()!=TrainingSettings.Mode.UHC
            && !this.options.holdShield() && var5>=this.options.reactionTicks()
            && supplies.tick(var1,var2,var3,equipment,var5,uhcDefense)) return;
      int var6 = skill != null ? skill.recovery(var2) : switch (var4.difficulty()) {
         case EASY -> 32;
         case NORMAL -> 22;
         case HARD -> 14;
      };
      switch (var4.mode()) {
         case CRYSTAL:
            if (this.options.drill()==Drill.DUEL && var5>=options.reactionTicks() && !options.holdShield()
                  && maceDuel.air(var1,var2,var3,equipment,var5,var4,options,skill,reads,duelWeapon)) break;
            this.crystal(var1, var2, var3, var6, var5, skill);
            break;
         case MACE:
            if (this.options.drill()==Drill.DUEL) maceDuel.tick(var1,var2,var3,equipment,var5,var4,var6,this.options,skill,reads,duelWeapon,opening);
            else this.mace(var1, var2, var3, var4, var6, var5);
            break;
         case SWORD:
            if (this.options.drill() == Drill.DUEL) {
               this.swordDuel.attack(var1, var2, var3, var4, this.options, this.equipment, var5);
            } else {
               this.sword(var1, var2, var3, var6, var5);
            }
            break;
         case UHC:
            this.uhc(var1, var2, var3, var6, var5, skill);
      }
   }

   interface MeleeReach { double meleeReach(); }

   static double meleeReach(LivingEntity actor) {
      return actor instanceof MeleeReach policy ? policy.meleeReach() : 3.0;
   }

   static boolean withinReach(LivingEntity var0, ServerPlayer var1) {
      return withinReach(var0, var1, meleeReach(var0));
   }

   static boolean withinReach(LivingEntity var0, ServerPlayer var1, double reach) {
      return RoundPing.box(var1).distanceToSqr(var0.getEyePosition()) <= reach * reach && var0.hasLineOfSight(var1);
   }

   static boolean strike(ServerLevel var0, LivingEntity var1, ServerPlayer var2, DamageSource var3, float var4) {
      return strikeCharged(var0, var1, var2, var3, var4, 1.0F);
   }

   static boolean strikeCharged(ServerLevel var0, LivingEntity var1, ServerPlayer var2, DamageSource var3, float var4, float charge) {
      return strikeCharged(var0, var1, var2, var3, var4, charge, false);
   }

   static boolean strikeCharged(ServerLevel var0, LivingEntity var1, ServerPlayer var2, DamageSource var3, float var4, float charge, boolean critical) {
      return strikeCharged(var0,var1,var2,var3,var4,charge,critical,var1.getMainHandItem());
   }

   static boolean strikeCharged(ServerLevel var0, LivingEntity var1, ServerPlayer var2, DamageSource var3,
         float var4, float charge, boolean critical, ItemStack attackAttributes) {
      if (var1.isUsingItem() || !withinReach(var1, var2) || var2.getBoundingBox().distanceToSqr(var1.getEyePosition()) > Math.pow(meleeReach(var1), 2)) {
         return false;
      }

      var1.swing(InteractionHand.MAIN_HAND, true);
      ItemStack var5 = var1.getMainHandItem();
      BotTelemetry.action(var0, var1, var5.is(Items.MACE) ? "MACE" : "MELEE");
      // Equipment may have switched in this tick. Include the current stack and active effects immediately.
      var attack = new net.minecraft.world.entity.ai.attributes.AttributeInstance(Attributes.ATTACK_DAMAGE, unused -> {});
      attack.setBaseValue(1);
      java.util.function.BiConsumer<net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute>,
              net.minecraft.world.entity.ai.attributes.AttributeModifier> apply = (attribute, modifier) -> {
         if (attribute.equals(Attributes.ATTACK_DAMAGE)) attack.addOrUpdateTransientModifier(modifier);
      };
      for (EquipmentSlot slot : EquipmentSlot.values()) (slot==EquipmentSlot.MAINHAND ? attackAttributes : var1.getItemBySlot(slot)).forEachModifier(slot,apply);
      for (var effect : var1.getActiveEffects()) effect.getEffect().value().createModifiers(effect.getAmplifier(),apply);
      float var7 = (float)attack.getValue();
      float var8 = EnchantmentHelper.modifyDamage(var0, var5, var2, var3, var7 + var4);
      if (charge < 1.0F || critical) {
         float base = var7 + var4;
         var8 = base * (0.2F + 0.8F * charge * charge) * (critical ? 1.5F : 1) + (var8 - base) * charge;
      }
      boolean var9 = var2.hurtServer(var0, var3, var8);
      if (var9) {
         EnchantmentHelper.doPostAttackEffectsWithItemSource(var0, var2, var3, var5);
      }

      return var9;
   }

   private void sword(ServerLevel var1, LivingEntity var2, ServerPlayer var3, int var4, int var5) {
      if (!this.options.holdShield() && var2.isUsingItem() && var5 >= this.lowerShieldAt) {
         var2.stopUsingItem();
      }

      if (var5 >= this.nextAttack && withinReach(var2, var3)) {
         this.equipment.equipSword(var2);
         strike(var1, var2, var3, var2.damageSources().mobAttack(var2), 0.0F);
         this.nextAttack = var5 + var4;
         if (!this.options.holdShield() && var2.getOffhandItem().is(Items.SHIELD)) {
            var2.startUsingItem(InteractionHand.OFF_HAND);
            this.lowerShieldAt = this.nextAttack - 5;
         }
      }
   }

   private void mace(ServerLevel var1, LivingEntity var2, ServerPlayer var3, TrainingSettings var4, int var5, int var6) {
      if (this.equipment.has(var2, Items.MACE)) {
         if (this.maceAirborne) {
            if (var2.onGround()) {
               this.maceAirborne = false;
            } else if (MaceItem.canSmashAttack(var2) && withinReach(var2, var3) && this.equipment.equipWeapon(var2, Items.MACE)) {
               DamageSource var9 = var2.damageSources().mace(var2);
               float var10 = Items.MACE.getAttackDamageBonus(var3, 6.0F, var9);
               if (strike(var1, var2, var3, var9, var10)) {
                  var2.resetFallDistance();
                  var2.playSound(SoundEvents.MACE_SMASH_GROUND);
                  var1.sendParticles(ParticleTypes.CRIT, var3.getX(), var3.getY(0.6), var3.getZ(), 10, 0.3, 0.3, 0.3, 0.12);
               }

               this.maceAirborne = false;
            }
         } else if (var6 >= this.nextAttack && var2.onGround() && !(var2.distanceToSqr(var3) > 20.25) && var2.hasLineOfSight(var3)) {
            boolean var7 = var4.movement() != TrainingSettings.Movement.STILL
               && var1.noBlockCollision(var2, var2.getBoundingBox().expandTowards(0.0, 3.5, 0.0));
            if (var7 && this.equipment.equipWeapon(var2, Items.WIND_CHARGE) && this.equipment.takeOne(var2, Items.WIND_CHARGE)) {
               Vec3 var8 = var2.getDeltaMovement();
               var2.swing(InteractionHand.MAIN_HAND, true);
               var2.setDeltaMovement(var8.x, 0.94, var8.z);
               var2.setOnGround(false);
               var2.hurtMarked = true;
               var2.playSound((SoundEvent)SoundEvents.WIND_CHARGE_BURST.value());
               var1.sendParticles(ParticleTypes.GUST, var2.getX(), var2.getY(), var2.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
               this.maceAirborne = true;
               BotTelemetry.action(var1, var2, "WIND");
               this.nextAttack = var6 + var5 + 28;
            } else if (withinReach(var2, var3) && this.equipment.equipWeapon(var2, Items.MACE)) {
               strike(var1, var2, var3, var2.damageSources().mobAttack(var2), 0.0F);
               this.nextAttack = var6 + var5;
            }
         }
      }
   }

   double uhcSpacing(LivingEntity bot, ServerPlayer opponent, double distance) {
      if (uhcUtility.eating(bot)) return 5.0;
      if (distance >= 4.5 && bot.isUsingItem() && (bot.getUseItem().is(Items.BOW) || bot.getUseItem().is(Items.CROSSBOW))
            && !uhcDefense.shieldFaces(bot, opponent)) {
         return Math.min(distance, 6.0);
      }
      return 2.0;
   }

   private void uhc(ServerLevel level, LivingEntity bot, ServerPlayer opponent, int recovery, int tick, DuelSkill skill) {
      this.arrows.removeIf(Entity::isRemoved);
      boolean duel = options.drill() == Drill.DUEL;
      if (uhcDefense.followUp(level, bot, opponent, tick, duel ? duelWeapon : null)) return;
      if (duel && uhcUtility.tacticalAction(level, bot, opponent, equipment, uhcDefense, tick, skill, reads)) return;

      double distance = bot.distanceToSqr(opponent);
      BlockPos web = !duel || reads.acquired(skill) ? uhcDefense.obstructingWeb(level, bot, opponent) : UhcDefense.touchingWeb(level,bot);
      if (web != null) {
         if (duel && uhcUtility.holdingTrap(level,web,tick)) {
            uhcDefense.guard(bot,equipment);
            return;
         }
         uhcDefense.handleWeb(level, bot, web, equipment, options.drill() == Drill.DUEL);
         return;
      }
      uhcDefense.cancelCut();
      boolean visible = bot.hasLineOfSight(opponent);
      boolean incomingShot = visible && distance <= 324 && (duel ? reads.bow(skill) : uhcDefense.aimingBowAt(opponent, bot));
      if (incomingShot && !withinReach(bot, opponent) && uhcDefense.guard(bot, equipment)) return;
      if (duel && !reads.acquired(skill)) {
         if (!options.holdShield() && bot.isUsingItem()) bot.stopUsingItem();
         return;
      }
      if (duel && opening.repositioning()) return;
      boolean targetGuarding = visible && (duel ? reads.shield(skill) : uhcDefense.shieldFaces(bot, opponent));
      if (duel && !options.holdShield() && uhcUtility.crossbow(level,bot,opponent,equipment,tick,visible,targetGuarding,
            options.gaze()==DrillOptions.Gaze.TRACK,skill,reads)) return;
      boolean ranged = tick >= nextBowShot && distance >= 36 && distance <= 324 && visible && !targetGuarding && UhcUtility.hasArrow(bot,equipment);
      if (ranged && equipment.equipWeapon(bot, Items.BOW)) {
         if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.OFF_HAND) bot.stopUsingItem();
         if (!bot.isUsingItem()) bot.startUsingItem(InteractionHand.MAIN_HAND);
         if (bot.getTicksUsingItem() < 16) return;
         ItemStack ammo = UhcUtility.takeArrow(bot,equipment);
         if (!ammo.isEmpty()) {
            DrillArrow arrow = new DrillArrow(level, bot, opponent, ammo);
            Vec3 aim;
            if (duel) aim = DuelAim.ranged(bot,opponent,2.3,options.gaze()==DrillOptions.Gaze.TRACK,skill,reads);
            else {
               aim = opponent.getBoundingBox().getCenter().add(opponent.getDeltaMovement().scale(2)).subtract(arrow.position());
               aim = aim.add(0, aim.horizontalDistance() * .025, 0);
            }
            arrow.shoot(aim.x, aim.y, aim.z, 2.3F, 0.0F);
            if (level.addFreshEntity(arrow)) {
               BotTelemetry.action(level, bot, "BOW");
               arrows.add(arrow);
               bot.getMainHandItem().hurtAndBreak(1, bot, EquipmentSlot.MAINHAND);
               bot.playSound(SoundEvents.ARROW_SHOOT);
            } else if (equipment.consumeSupplies) equipment.stash(ammo);
         }
         bot.stopUsingItem();
         nextBowShot = tick + recovery * 3;
      }
      if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND) bot.stopUsingItem();
      if (tick >= nextAttack && withinReach(bot, opponent) && uhcDefense.facing(bot, opponent)) {
         if (options.holdShield()) return;
         bot.stopUsingItem();
         uhcDefense.attack(level, bot, opponent, equipment, tick, targetGuarding,
               duel ? skill.switchTicks() + 1 : Math.clamp(recovery / 7, 2, 4), duel ? duelWeapon : null);
         nextAttack = tick + recovery;
         return;
      }
      if (bot.getMainHandItem().is(Items.BOW)) equipment.equipSword(bot);
      if (visible && (distance < 36 || targetGuarding && distance <= 324)) uhcDefense.guard(bot, equipment);
      else if (!options.holdShield() && bot.isBlocking()) bot.stopUsingItem();
   }

   private boolean meleeWhenReady(ServerLevel level, LivingEntity bot, ServerPlayer target, int tick, int recovery) {
      if (tick < nextAttack || !withinReach(bot, target)) return false;
      if (bot.isBlocking()) bot.stopUsingItem();
      equipment.equipSword(bot);
      boolean hit = options.drill()==Drill.DUEL ? duelWeapon.strike(level,bot,target,tick,bot.damageSources().mobAttack(bot),0)
            : strike(level, bot, target, bot.damageSources().mobAttack(bot), 0);
      nextAttack = tick + recovery;
      return hit;
   }

   private void crystal(ServerLevel level, LivingEntity bot, ServerPlayer target, int recovery, int tick, DuelSkill skill) {
      boolean visible = bot.hasLineOfSight(target) && (skill == null || uhcDefense.facing(bot,target));
      if (skill == null) crystalPursuit.observe(bot, target, visible);
      if(options.drill()==Drill.DUEL){
         if(crystalCover.tick(level,bot,target,equipment,visible))return;
         if(crystalGuard.tick(level,bot,target,equipment,reads.bow(skill)))return;
      }
      if (this.crystal != null) {
         if (!this.crystal.isRemoved()) {
            if (visible && canDetonate(bot, target, this.crystal)
                  && (skill == null || favorableBlast(this.crystal.position(), bot, target, skill))) {
               boolean crossing = crystalPursuit.aboutToPass(crystal.position(),target)
                     && (skill == null || tick - crystalPlacedAt >= skill.switchTicks());
               // Preserve a follow-up against a hurt opponent on an exposed trade. If they are escaping, use the opening now.
               if (skill != null && skill.lead() >= .58 && target.hurtTime > 2 && !crossing && bot.hurtTime == 0
                     && blastImpact(this.crystal.position(), bot) > .55) return;
               if (tick < nextAttack && !crossing) return;
               this.equipment.guard(bot);
               bot.swing(InteractionHand.MAIN_HAND, true);
               this.crystal.detonate(bot);
               this.crystal = null;
               this.openCrystalCombo = true;
               this.nextAttack = tick + recovery;
               return;
            }
            if (tick - crystalPlacedAt < 6) return;
            // The opponent can still punish an abandoned placement.
            otherCrystals.removeIf(Entity::isRemoved);
            otherCrystals.add(crystal);
         }
         this.crystal = null;
         this.openCrystalCombo = true;
      }
      if (this.options.drill()==Drill.DUEL && reads.acquired(skill) && tick>=nextAttack && visible && !bot.isUsingItem()) {
         otherCrystals.removeIf(Entity::isRemoved);
         for (TrainingCrystal candidate : otherCrystals) {
            if (canDetonate(bot,target,candidate) && favorableBlast(candidate.position(),bot,target,skill)
                  && blastImpact(candidate.position(),target)>blastImpact(candidate.position(),bot)+.14) {
               equipment.guard(bot);
               bot.swing(InteractionHand.MAIN_HAND,true);
               candidate.detonate(bot);
               nextAttack=tick+recovery;
               return;
            }
         }
         if (reads.shield(skill) && target.getItemBlockingWith()!=null && withinReach(bot,target) && CrystalPursuit.inFront(bot,target.position())
               && target.getLookAngle().horizontal().dot(bot.position().subtract(target.position()).horizontal())>0
               && equipment.equipAxe(bot)) {
            duelWeapon.strike(level,bot,target,tick,bot.damageSources().mobAttack(bot),0);
            nextAttack=tick+recovery;
            return;
         }
      }
      if (this.options.drill()==Drill.DUEL && prepared==null &&
            crystalUtility.act(level,bot,target,equipment,tick,tick>=nextAttack,visible,crystalPursuit,options.gaze()==DrillOptions.Gaze.TRACK,skill,reads)) return;
      if (skill != null && !reads.acquired(skill)) return;
      if (skill != null && opening.repositioning()) return;
      if (tick < nextAttack || !visible || bot.distanceToSqr(target) > 64 || bot.isUsingItem()) return;
      Vec3 predicted = skill == null ? crystalPursuit.aim(target) : target.position().add(reads.travel(skill, reads.retreat(skill) ? 4 : 3));
      boolean swordReady = tick >= nextCrystalSword && withinReach(bot,target) && CrystalPursuit.inFront(bot,target.position());
      boolean openingBase = skill != null && opening.takeBaseAngle();
      if (this.openCrystalCombo && swordReady && !openingBase && !crystalPursuit.retreating()
            && (!crystalPursuit.sidestepping() || bot.distanceToSqr(predicted) <= 6.25)
            && this.equipment.equipSword(bot)) {
         nextCrystalSword = tick + recovery;
         meleeWhenReady(level, bot, target, tick, recovery);
         this.openCrystalCombo = false;
         this.nextAttack = tick + (crystalPursuit.sidestepping() ? 2 : 1) + (skill == null ? 0 : skill.switchTicks() - 1);
         return;
      }
      if (!equipment.has(bot,Items.END_CRYSTAL)) {
         if (swordReady && equipment.equipSword(bot))meleeWhenReady(level,bot,target,tick,recovery);
         return;
      }
      BlockPos base = this.prepared;
      this.prepared = null;
      if (base == null || !TrainingCrystalItem.canPlace(level, base) || !usableSite(level, bot, target, base)
            || predicted.distanceToSqr(Vec3.atBottomCenterOf(base.above())) > 20.25
            || skill != null && !favorableBlast(Vec3.atBottomCenterOf(base.above()),bot,target,skill)) {
         base = preferredCrystalSite(level, bot, target, predicted, true, skill);
         if (base != null && !TrainingCrystalItem.canPlace(level, base)) {
            if (TrainingArena.isArena(level) && this.equipment.equipWeapon(bot, Items.OBSIDIAN)
                  && level.setBlockAndUpdate(base, Blocks.OBSIDIAN.defaultBlockState())) {
               this.equipment.takeOne(bot,Items.OBSIDIAN);
               this.prepared = base;
               bot.swing(InteractionHand.MAIN_HAND, true);
               level.playSound(null, base, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 1.0F, 0.8F);
               this.nextAttack = tick + (skill == null ? 1 : skill.switchTicks());
               return;
            }
            base = preferredCrystalSite(level, bot, target, predicted, false, skill);
         }
      }
      if (base != null && this.equipment.equipWeapon(bot, Items.END_CRYSTAL)) {
         TrainingCrystal placed = new TrainingCrystal(level, Vec3.atBottomCenterOf(base.above()), target, bot);
         placed.setShowBottom(false);
         if (level.addFreshEntity(placed)) {
            this.equipment.takeOne(bot,Items.END_CRYSTAL);
            bot.swing(InteractionHand.MAIN_HAND, true);
            level.playSound(null, base, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1, 1.2F);
            this.crystal = placed;
            this.crystalPlacedAt = tick;
            this.nextAttack = tick + crystalPursuit.breakDelay(bot,recovery,placed.position(),target) + (skill == null ? 0 : skill.switchTicks()-1);
            return;
         }
      }
      this.openCrystalCombo = true;
      if (swordReady && tick >= nextCrystalSword && equipment.equipSword(bot)) {
         nextCrystalSword = tick + recovery;
         meleeWhenReady(level, bot, target, tick, recovery);
      } else if (tick >= nextAttack) this.nextAttack = tick + 3;
   }

   static boolean canReach(ServerLevel var0, LivingEntity var1, BlockPos var2) {
      Vec3 var3 = Vec3.atBottomCenterOf(var2.above());
      if (var3.distanceToSqr(var1.getEyePosition()) > 20.25) {
         return false;
      }

      BlockHitResult var4 = var0.clip(new ClipContext(var1.getEyePosition(), var3.add(0.0, -0.01, 0.0), Block.COLLIDER, Fluid.NONE, var1));
      return var4.getType() == Type.MISS || var4.getBlockPos().equals(var2);
   }

   private static final double MIN_IMPACT = 0.18;

   // Vanilla scales the explosion's strength at an entity by range and the visible fraction of its hitbox.
   private static double blastImpact(Vec3 origin, LivingEntity entity) {
      return Math.max(0.0, 1.0 - origin.distanceTo(entity.position()) / 12.0) * ServerExplosion.getSeenPercent(origin, entity);
   }

   private static boolean favorableBlast(Vec3 origin, LivingEntity bot, ServerPlayer target, DuelSkill skill) {
      return favorableBlast(bot, skill, blastImpact(origin,bot), blastImpact(origin,target));
   }

   private static boolean favorableBlast(LivingEntity bot, DuelSkill skill, double self, double opponent) {
      double health = (bot.getHealth() + bot.getAbsorptionAmount()) / bot.getMaxHealth();
      boolean protectedHand = bot.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
      // Nearby shelter can make the very same crystal a good trade. Apply extra caution with no held totem or little health.
      double tolerated = .47 - .36 * skill.lead();
      if (health < .45 && !protectedHand) tolerated -= .22 * skill.lead();
      return self < .32 || self - opponent <= tolerated;
   }

   static boolean canDetonate(LivingEntity bot, ServerPlayer target, TrainingCrystal crystal) {
      return CrystalPursuit.offensiveSite(bot,target,crystal.position())
         && crystal.getBoundingBox().distanceToSqr(bot.getEyePosition()) <= 9.0
         && bot.hasLineOfSight(crystal) && crystal.distanceToSqr(target) <= 25.0
         && blastImpact(crystal.position(), target) >= MIN_IMPACT;
   }

   private static boolean reachableCrystal(ServerLevel level, LivingEntity bot, Vec3 origin) {
      var shape = EntityTypes.END_CRYSTAL.getDimensions();
      if (shape.makeBoundingBox(origin).distanceToSqr(bot.getEyePosition()) > 9.0) return false;
      Vec3 crystalEye = origin.add(0, shape.eyeHeight(), 0);
      return level.clip(new ClipContext(bot.getEyePosition(), crystalEye, Block.COLLIDER, Fluid.NONE, bot)).getType() == Type.MISS;
   }

   static boolean usableSite(ServerLevel level, LivingEntity bot, ServerPlayer target, BlockPos base) {
      Vec3 origin = Vec3.atBottomCenterOf(base.above());
      return CrystalPursuit.offensiveSite(bot,target,origin) && canReach(level, bot, base) && reachableCrystal(level, bot, origin)
         && target.distanceToSqr(origin) <= 25 && blastImpact(origin, target) >= MIN_IMPACT;
   }

   static BlockPos preferredCrystalSite(ServerLevel level, LivingEntity bot, ServerPlayer target, Vec3 predicted, boolean allowBuild) {
      return preferredCrystalSite(level,bot,target,predicted,allowBuild,null);
   }

   private static BlockPos preferredCrystalSite(ServerLevel level, LivingEntity bot, ServerPlayer target, Vec3 predicted,
                                               boolean allowBuild, DuelSkill skill) {
      double best = Double.NEGATIVE_INFINITY;
      BlockPos result = null;
      BlockPos center = bot.blockPosition();
      Vec3 travel = predicted.subtract(target.position());
      AABB approach = target.getBoundingBox().expandTowards(travel.x, 0, travel.z);
      for (BlockPos base : BlockPos.betweenClosed(center.offset(-4, -2, -4), center.offset(4, 1, 4))) {
         Vec3 origin = Vec3.atBottomCenterOf(base.above());
         double range = target.distanceToSqr(origin);
         if (range > 25 || !CrystalPursuit.offensiveSite(bot,target,origin)
               || !level.isLoaded(base) || !level.getWorldBorder().isWithinBounds(base)) continue;
         boolean ready = TrainingCrystalItem.canPlace(level, base);
         if (!ready && (!allowBuild || !level.isEmptyBlock(base) || !level.isEmptyBlock(base.above())
               || !level.getBlockState(base.below()).isFaceSturdy(level, base.below(), Direction.UP)
               || approach.intersects(new AABB(base))
               || !level.getEntities(null, new AABB(base).expandTowards(0, 2, 0)).isEmpty())) continue;
         if (!canReach(level, bot, base) || !reachableCrystal(level, bot, origin)) continue;
         double impact = blastImpact(origin, target);
         if (impact < MIN_IMPACT) continue;
         double own = blastImpact(origin, bot);
         if (skill != null && !favorableBlast(bot,skill,own,impact)) continue;
         double score = impact + (ready ? 0.055 : 0.0) - 0.13 * origin.distanceTo(predicted)
            - 0.12 * Math.max(0.0, origin.y - Math.max(target.getY(), predicted.y))
            - (skill == null ? .15 : .15 + .48 * skill.lead()) * own;
         if (score > best) {
            best = score;
            result = base.immutable();
         }
      }
      return result;
   }

   boolean placeCrystal(ServerLevel var1, LivingEntity var2, ServerPlayer var3, BlockPos var4) {
      if (!TrainingCrystalItem.canPlace(var1, var4)) {
         return false;
      }

      BlockPos var5 = var4.above();
      TrainingCrystal var6 = new TrainingCrystal(var1, Vec3.atBottomCenterOf(var5), var3, var2);
      var6.setShowBottom(false);
      if (!var1.addFreshEntity(var6)) {
         return false;
      }

      this.otherCrystals.removeIf(Entity::isRemoved);
      this.otherCrystals.add(var6);
      var1.gameEvent(var3, GameEvent.ENTITY_PLACE, var5);
      return true;
   }

   void clear() {
      crystalUtility.clear();
      crystalCover.cancel();
      supplies.clear();
      maceDuel.clear();
      uhcUtility.clear();
      uhcDefense.cancelCut();
      if (this.pressure != null) {
         this.pressure.clear();
      }

      if (this.crystal != null) {
         this.crystal.discard();
      }

      this.crystal = null;
      this.otherCrystals.forEach(Entity::discard);
      this.otherCrystals.clear();
      this.arrows.forEach(Entity::discard);
      this.arrows.clear();
   }

   private static final class DrillArrow extends Arrow {
      private final ServerPlayer target;

      DrillArrow(ServerLevel var1, LivingEntity var2, ServerPlayer var3, ItemStack ammo) {
         super(var1, var2, ammo, var2.getMainHandItem());
         this.target = var3;
         this.pickup = Pickup.DISALLOWED;
      }

      protected boolean canHitEntity(Entity var1) {
         return var1 == this.target && super.canHitEntity(var1);
      }

      protected void onHitBlock(BlockHitResult var1) {
         this.discard();
      }

      public void tick() {
         if (this.tickCount >= 80) {
            this.discard();
         } else {
            super.tick();
         }
      }

      public boolean shouldBeSaved() {
         return false;
      }
   }
}
