package de.pvptrainer.combat;
import de.pvptrainer.arena.TrainingArena;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class UhcDefense {
   private int firstAxeTick, followTick = -1;
   private int successfulStun = -1000;
   private int botDisabledAt = -1000;
   private int autoHitAt = -1000;
   private int postAutoHitRamUntil = -1000;
   private int playerShieldReadyAt = -1000;
   private int botHurtAt = -1000;
   private ServerLevel cuttingLevel;
   private int breaker;
   private BlockPos cutting;
   private float cutProgress;

   void onBotShieldDisabled(int tick, DuelSkill skill) {
      botDisabledAt = tick;
      if (skill == null || skill.lead() >= 0.45) {
         int delay = skill == null ? 1 : Math.max(1, 3 - (int) (skill.lead() * 2.0));
         autoHitAt = tick + delay;
      }
   }

   void onBotDamaged(int tick) {
      botHurtAt = tick;
   }

   boolean shouldReactiveGuard(int tick) {
      return tick - botHurtAt >= 1 && tick - botHurtAt <= 6;
   }

   boolean executeAutoHit(ServerLevel level, LivingEntity bot, ServerPlayer opponent, BotEquipment equipment, int tick, DuelWeapon weapon) {
      if (autoHitAt < 0 || tick < autoHitAt) return false;
      autoHitAt = -1000;
      if (bot.isUsingItem() || !TrainingTactics.withinReach(bot, opponent) || !facing(bot, opponent)) return false;
      if (!equipment.equipAxe(bot)) equipment.equipSword(bot);
      boolean landed = weapon == null
         ? TrainingTactics.strike(level, bot, opponent, bot.damageSources().mobAttack(bot), 0)
         : weapon.strike(level, bot, opponent, tick, bot.damageSources().mobAttack(bot), 0);
      if (landed) {
         postAutoHitRamUntil = tick + 12;
      }
      return landed;
   }

   boolean inPostAutoHitRam(int tick) {
      return tick <= postAutoHitRamUntil;
   }

   boolean facing(LivingEntity actor, LivingEntity opponent) {
      Vec3 to = opponent.position().subtract(actor.position()).horizontal();
      return to.lengthSqr() < 1.0e-6 || actor.getLookAngle().horizontal().dot(to.normalize()) > 0.2;
   }

   public boolean aimingBowAt(ServerPlayer opponent, LivingEntity bot) {
      return facing(opponent, bot) && (opponent.isUsingItem() && opponent.getUseItem().is(Items.BOW)
         || CrossbowItem.isCharged(opponent.getMainHandItem()));
   }

   public boolean shieldFaces(LivingEntity bot, ServerPlayer opponent) {
      return opponent.isBlocking() && facing(opponent, bot);
   }

   boolean guard(LivingEntity bot, BotEquipment equipment) {
      if (!equipment.equipShield(bot)) return false;
      if (bot.isUsingItem() && bot.getUsedItemHand() == InteractionHand.MAIN_HAND) bot.stopUsingItem();
      if (!bot.isUsingItem()) bot.startUsingItem(InteractionHand.OFF_HAND);
      return bot.isUsingItem() && bot.getUseItem().is(Items.SHIELD);
   }

   void attack(ServerLevel level, LivingEntity bot, ServerPlayer opponent, BotEquipment equipment, int tick,
               boolean recognizedShield, int secondClickDelay, DuelWeapon weapon) {
      ItemStack blocking = opponent.getItemBlockingWith();
      if (recognizedShield && blocking != null && facing(opponent, bot) && equipment.equipAxe(bot)) {
         if (weapon==null) TrainingTactics.strike(level, bot, opponent, bot.damageSources().mobAttack(bot), 0);
         else weapon.strike(level,bot,opponent,tick,bot.damageSources().mobAttack(bot),0);
         // The first click must actually trigger Minecraft's own player-shield cooldown.
         if (!blocking.isEmpty() && opponent.getCooldowns().isOnCooldown(blocking)) {
            firstAxeTick = tick;
            playerShieldReadyAt = tick + 100;
            followTick = tick + secondClickDelay;
         }
         return;
      }
      // Track the 5s (100-tick) shield cooldown (~4 hits) & 3-heart Axe finish (Swight cooldown-trick / axe-finish).
      boolean shieldReturning = tick >= playerShieldReadyAt - 8 && tick <= playerShieldReadyAt + 12;
      boolean lowUnshieldedFinish = !opponent.isBlocking() && opponent.getHealth() <= 6.0F && bot.fallDistance > 0.1F && !bot.onGround();
      if ((shieldReturning || lowUnshieldedFinish) && !inPostAutoHitRam(tick) && equipment.equipAxe(bot)) {
         if (weapon == null) TrainingTactics.strike(level, bot, opponent, bot.damageSources().mobAttack(bot), 0);
         else weapon.strike(level, bot, opponent, tick, bot.damageSources().mobAttack(bot), 0);
         return;
      }
      if (!equipment.equipSword(bot)) equipment.equipAxe(bot);
      if (inPostAutoHitRam(tick) && !bot.isUsingItem()) bot.setSprinting(true);
      if (weapon==null) TrainingTactics.strike(level, bot, opponent, bot.damageSources().mobAttack(bot), 0);
      else weapon.strike(level,bot,opponent,tick,bot.damageSources().mobAttack(bot),0);
   }

   boolean followUp(ServerLevel level, LivingEntity bot, ServerPlayer opponent, int tick, DuelWeapon weapon) {
      if (followTick < 0) return false;
      if (!TrainingTactics.withinReach(bot, opponent) || !facing(bot, opponent) || obstructingWeb(level, bot, opponent) != null) {
         followTick = -1;
         return false;
      }
      if (tick < followTick) return true;
      followTick = -1;
      ItemStack axe = bot.getMainHandItem();
      if (!axe.is(ItemTags.AXES) || bot.isUsingItem() || opponent.isBlocking()) return false;
      var modifiers = axe.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
      double speed = modifiers.compute(Attributes.ATTACK_SPEED, 4.0, EquipmentSlot.MAINHAND);
      float charge = (float)Math.clamp((tick - firstAxeTick + 0.5) * speed / 20.0, 0.0, 1.0);
      boolean landed = weapon == null ? TrainingTactics.strikeCharged(level, bot, opponent, bot.damageSources().mobAttack(bot), 0, charge)
            : weapon.strike(level,bot,opponent,tick,bot.damageSources().mobAttack(bot),0);
      if (landed) successfulStun=tick;
      return true;
   }

   boolean justStunned(int tick) { return tick-successfulStun<=5; }

   BlockPos obstructingWeb(ServerLevel level, LivingEntity bot, ServerPlayer opponent) {
      BlockPos ownWeb = touchingWeb(level, bot);
      if (ownWeb != null) return ownWeb;
      if (bot.distanceToSqr(opponent) > 324) return null;
      BlockPos between = BlockGetter.traverseBlocks(bot.getEyePosition(), opponent.getBoundingBox().getCenter(), level,
         (world, pos) -> world.getBlockState(pos).is(Blocks.COBWEB) ? pos.immutable() : null, world -> null);
      return between != null ? between : touchingWeb(level, opponent);
   }

   static BlockPos touchingWeb(ServerLevel level, LivingEntity entity) {
      for (BlockPos pos : BlockPos.betweenClosed(entity.getBoundingBox().deflate(1.0e-5))) {
         if (level.getBlockState(pos).is(Blocks.COBWEB)) return pos.immutable();
      }
      return null;
   }

   void handleWeb(ServerLevel level, LivingEntity bot, BlockPos web, BotEquipment equipment, boolean cutAllowed) {
      if (bot.isUsingItem()) bot.stopUsingItem();
      if (!web.equals(cutting)) cancelCut();
      Vec3 aim = Vec3.atCenterOf(web);
      var hit = level.clip(new ClipContext(bot.getEyePosition(), aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
      if (!cutAllowed || !TrainingArena.isArena(level) || new AABB(web).distanceToSqr(bot.getEyePosition()) > 4.5 * 4.5
            || hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(web) || !equipment.equipSword(bot)) {
         cancelCut();
         return;
      }
      if (cutting == null) {
         cutting = web;
         cuttingLevel = level;
         breaker = bot.getId();
         bot.swing(InteractionHand.MAIN_HAND, true);
      }
      BlockState state = level.getBlockState(web);
      ItemStack sword = bot.getMainHandItem();
      float divisor = !state.requiresCorrectToolForDrops() || sword.isCorrectToolForDrops(state) ? 30 : 100;
      float progress = sword.getDestroySpeed(state) / state.getDestroySpeed(level, web) / divisor;
      cutProgress += bot.onGround() ? progress : progress / 5;
      if (cutProgress >= 1) {
         if (level.destroyBlock(web, false, bot, 512)) {
            var tool = sword.get(DataComponents.TOOL);
            if (tool != null) sword.hurtAndBreak(tool.damagePerBlock(), bot, EquipmentSlot.MAINHAND);
         }
         cancelCut();
      } else {
         level.destroyBlockProgress(breaker, web, (int)(cutProgress * 10));
         if (!bot.swinging) bot.swing(InteractionHand.MAIN_HAND, true);
      }
   }

   void cancelCut() {
      if (cutting == null) return;
      cuttingLevel.destroyBlockProgress(breaker, cutting, -1);
      cuttingLevel = null;
      cutting = null;
      cutProgress = 0;
   }

   BlockPos workingBlock() { return cutting; }
}
