package de.pvptrainer.combat;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.arena.ForgeArena;
import de.pvptrainer.kits.MacherItems;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.session.TrainingResources;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Round-local tactics for the free UHC duel. Every placement or shot draws from the selected kit. */
final class UhcUtility {
   private static final List<Item> ARROWS = List.of(Items.TIPPED_ARROW, Items.SPECTRAL_ARROW, Items.ARROW);
   private final List<AbstractArrow> fired = new ArrayList<>();
   private ServerLevel changedArena, miningLevel;
   private BlockPos water, lava, web, coverBase, mining;
   private int waterTick, lavaTick, webTick, nextTerrain, nextMeal, nextBolt, seenAt=-100, mineEntityId;
   private float mineProgress;
   private Vec3 lastSeen;
   private final Set<BlockPos> waterBeforeWeb = new HashSet<>();
   private BlockPos rescueWater;
   private int waterSeenAt, drainPunishUntil = -1;
   private ItemStack meal=ItemStack.EMPTY;
   private int mealCount, mealEnd;

   static boolean hasArrow(LivingEntity bot, BotEquipment kit) {
      for (Item item:ARROWS) if (kit.has(bot,item)) return true;
      return false;
   }
   static ItemStack takeArrow(LivingEntity bot, BotEquipment kit) {
      for (Item item:ARROWS) {
         ItemStack ammo=kit.ammunition(bot,item);
         if (!ammo.isEmpty()) return ammo;
      }
      return ItemStack.EMPTY;
   }

   void observe(ServerPlayer opponent, int tick) {
      lastSeen=opponent.position();
      seenAt=tick;
   }

   boolean eating(LivingEntity bot) {
      return bot.isUsingItem() && (MacherItems.isHealingHead(bot.getUseItem()) || bot.getUseItem().is(Items.GOLDEN_APPLE)
            || bot.getUseItem().is(Items.ENCHANTED_GOLDEN_APPLE));
   }

   boolean tacticalAction(ServerLevel level, LivingEntity bot, ServerPlayer target, BotEquipment kit, UhcDefense defense,
                          int tick, DuelSkill skill, DuelReads reads) {
      if (!TrainingArena.isArena(level)) return false;
      if (mealCount>0 && !eating(bot)) {
         if (tick>=mealEnd-1 && (meal.getCount()<mealCount || bot.hasEffect(MobEffects.REGENERATION))) {
            BotTelemetry.action(level,bot,"HEAL");
         }
         mealCount=0; meal=ItemStack.EMPTY;
      }
      if ((bot.isOnFire() || bot.fallDistance>3 && !bot.onGround()) && water==null && kit.has(bot,Items.WATER_BUCKET)) {
         Vec3 feet=bot.position().add(0,.2,0);
         double clutchRange = 1.15 + 2.25 * skill.lead();
         var ground=level.clip(new ClipContext(feet,feet.add(0,-clutchRange,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
         if (ground.getType()==HitResult.Type.BLOCK && ground.getDirection()==Direction.UP) {
            BlockPos landing=ground.getBlockPos().above();
            if (pour(level,bot,kit,landing,Items.WATER_BUCKET,"WATER")) { water=landing;waterTick=tick;return true; }
            if (bot.isInLava() || level.getBlockState(landing).is(Blocks.LAVA)) {
               for (var direction:Direction.Plane.HORIZONTAL) {
                  BlockPos beside=landing.relative(direction);
                  if (pour(level,bot,kit,beside,Items.WATER_BUCKET,"WATER")) {water=beside;waterTick=tick;return true;}
               }
            }
         }
      }
      if (eating(bot)) return true;
      if (water!=null && tick-waterTick>=4 && bot.onGround() && !bot.isOnFire()) {
         if (!level.getBlockState(water).is(Blocks.WATER)) water=null;
         else if (scoop(level,bot,kit,water)) { water=null; return true; }
      }
      if (lava!=null && tick-lavaTick>=8) {
         if (!level.getBlockState(lava).is(Blocks.LAVA)) lava=null;
         else if (scoop(level,bot,kit,lava)) { lava=null; return true; }
      }
      boolean visible=seenAt==tick;
      double distance=bot.distanceToSqr(target);
      if (coverBase!=null) {
         BlockPos upper=coverBase.above();coverBase=null;
         if (placeCover(level,bot,kit,upper)) return true;
      }
      boolean threat=visible && distance>=36 && distance<=324 && reads.bow(skill);
      boolean low=bot.getHealth()<=bot.getMaxHealth()*(.45 + .20 * skill.lead());
      // A low-health fighter can finish eating behind a two-block screen even while an archer holds aim.
      if (tick>=nextTerrain && threat && (low || !kit.has(bot,Items.SHIELD))) {
         Vec3 toward=target.position().subtract(bot.position()).horizontal().normalize();
         BlockPos front=BlockPos.containing(bot.position().add(toward.scale(1.4)));
         if (placeCover(level,bot,kit,front)) { coverBase=front;nextTerrain=tick+60;return true; }
      }
      if (low && tick>=nextMeal && !bot.hasEffect(MobEffects.REGENERATION)
            && (!visible || distance>=16 || holdingTrap(level,web,tick) || bot.getHealth()<=5)) {
         // Reserve the quicker heads for urgent pressure; spend ordinary apples in safe space first.
         boolean urgent=bot.getHealth()<=bot.getMaxHealth()*.45 || visible && distance<36;
         boolean found=urgent && kit.equipMatching(bot,MacherItems::isHealingHead);
         if (!found) found=kit.equipMatching(bot,s->s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE));
         if (!found) found=kit.equipMatching(bot,MacherItems::isHealingHead);
         if (found) {
            bot.stopUsingItem();bot.startUsingItem(InteractionHand.MAIN_HAND);
            if (eating(bot)) {
               meal=bot.getUseItem();mealCount=meal.getCount();mealEnd=tick+meal.getUseDuration(bot);nextMeal=mealEnd+8;return true;
            }
         }
      }
      if (!visible) {
         rescueWater=null;
         if (lastSeen!=null && tick-seenAt<=(mining==null?45:200) && !low && mineCover(level,bot,kit,lastSeen)) return true;
         cancelMining(level);return false;
      }
      cancelMining(level);
      if (web != null && tick-webTick <= 45 && target.distanceToSqr(Vec3.atCenterOf(web)) < 9) {
         BlockPos source = null;
         for (var candidate : BlockPos.betweenClosed(web.offset(-1,0,-1),web.offset(1,1,1))) {
            if (!waterBeforeWeb.contains(candidate) && sourceWater(level,candidate)
                  && reachable(level,bot,candidate,Vec3.atCenterOf(candidate))) {
               if (source==null || candidate.distSqr(web)<source.distSqr(web)) source=candidate.immutable();
            }
         }
         if (source == null) rescueWater=null;
         else if (!source.equals(rescueWater)) { rescueWater=source; waterSeenAt=tick; }
         else if (tick-waterSeenAt >= skill.readTicks() && scoop(level,bot,kit,source)) {
            rescueWater=null;
            drainPunishUntil=tick+16;
            nextTerrain=tick+skill.switchTicks();
            BotTelemetry.action(level,bot,"DRAIN");
            return true;
         }
      } else rescueWater=null;
      if (reads.shield(skill)) return false;
      if (tick>=nextTerrain && distance>=2.25 && distance<=22 && reads.acquired(skill)) {
         boolean eatingTarget=reads.food(skill);
         boolean escaping=reads.trapRetreat(skill, TrainingTactics.withinReach(bot,target));
         BlockPos feet=target.blockPosition();
         if ((defense.justStunned(tick) || eatingTarget || escaping) && tick>drainPunishUntil && !DrillAttempt.inWeb(target)) {
            BlockPos ahead=BlockPos.containing(target.position().add(reads.travel(skill, 2.5)));
            if (placeWeb(level,bot,kit,ahead)) {
               web=ahead;webTick=tick;nextTerrain=tick+4+skill.switchTicks();
               waterBeforeWeb.clear();
               for (var cell:BlockPos.betweenClosed(web.offset(-1,0,-1),web.offset(1,1,1))) {
                  if (sourceWater(level,cell)) waterBeforeWeb.add(cell.immutable());
               }
               return true;
            }
         }
         boolean trapped=holdingTrap(level,web,tick) && DrillAttempt.inWeb(target);
         boolean afterDrain=tick <= drainPunishUntil && web != null && target.distanceToSqr(Vec3.atCenterOf(web)) < 6.25;
         if ((trapped || eatingTarget || afterDrain) && !target.isOnFire() && !target.hasEffect(MobEffects.FIRE_RESISTANCE)
               && lava==null && !(bot.isInWater() || target.isInWater())) {
            // Use the opponent's feet or a supported adjacent tile, always outside the bot's own hitbox.
            var cells=new ArrayList<BlockPos>();cells.add(feet);
            for (var dir:Direction.Plane.HORIZONTAL) cells.add(feet.relative(dir));
            cells.sort((a,b)->Double.compare(Vec3.atCenterOf(a).distanceToSqr(target.position()),Vec3.atCenterOf(b).distanceToSqr(target.position())));
            for (BlockPos pos:cells) {
               if (Vec3.atBottomCenterOf(pos).distanceToSqr(bot.position())<3.24) continue;
               if (pour(level,bot,kit,pos,Items.LAVA_BUCKET,"LAVA")) {lava=pos;lavaTick=tick;nextTerrain=tick+36;return true;}
            }
         }
      }
      return false;
   }

   boolean holdingTrap(ServerLevel level, BlockPos obstruction, int tick) {
      return web!=null && web.equals(obstruction) && tick-webTick<22 && level.getBlockState(web).is(Blocks.COBWEB);
   }

   private static boolean sourceWater(ServerLevel level, BlockPos pos) {
      var state=level.getBlockState(pos);
      return state.is(Blocks.WATER) && state.getFluidState().isSource();
   }

   private static boolean allowed(BlockPos p) {
      int radius = ForgeArena.playRadius();
      return Math.abs(p.getX()) <= radius && Math.abs(p.getZ()) <= radius && p.getY() > ForgeArena.minFloor() && p.getY() < ForgeArena.TOP;
   }
   private static boolean reachable(ServerLevel level, LivingEntity bot, BlockPos p, Vec3 point) {
      if (!allowed(p) || point.distanceToSqr(bot.getEyePosition())>4.5*4.5) return false;
      BlockHitResult hit=level.clip(new ClipContext(bot.getEyePosition(),point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
      return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(p);
   }
   private static boolean supported(ServerLevel level, LivingEntity bot, BlockPos p) {
      return level.isEmptyBlock(p) && level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
            && reachable(level,bot,p,Vec3.atBottomCenterOf(p).add(0,.03,0));
   }
   private boolean placeWeb(ServerLevel level,LivingEntity bot,BotEquipment kit,BlockPos p) {
      if (!supported(level,bot,p) || new AABB(p).intersects(bot.getBoundingBox()) || !kit.equipWeapon(bot,Items.COBWEB)) return false;
      bot.stopUsingItem();
      if (!level.setBlockAndUpdate(p,Blocks.COBWEB.defaultBlockState())) return false;
      bot.getMainHandItem().consume(1, bot);
      executed(level,bot,"WEB");
      return true;
   }
   private boolean placeCover(ServerLevel level,LivingEntity bot,BotEquipment kit,BlockPos p) {
      if (!supported(level,bot,p) || !level.getEntities(null,new AABB(p)).isEmpty() || !kit.equipMatching(bot,s->s.is(ItemTags.PLANKS))) return false;
      bot.stopUsingItem();
      Block block=((BlockItem)bot.getMainHandItem().getItem()).getBlock();
      if (!level.setBlockAndUpdate(p,block.defaultBlockState())) return false;
      bot.getMainHandItem().consume(1, bot);executed(level,bot,"BLOCK");return true;
   }
   private boolean pour(ServerLevel level,LivingEntity bot,BotEquipment kit,BlockPos p,Item bucket,String event) {
      if (!supported(level,bot,p) || !kit.equipWeapon(bot,bucket)) return false;
      bot.stopUsingItem();
      if (!((BucketItem)bucket).emptyContents(bot,level,p,null)) return false;
      bot.getMainHandItem().consume(1, bot);
      if (!TrainingResources.keepSupplies(bot) || !kit.has(bot, Items.BUCKET)) kit.stash(new ItemStack(Items.BUCKET));
      executed(level,bot,event);return true;
   }
   private static boolean scoop(ServerLevel level,LivingEntity bot,BotEquipment kit,BlockPos p) {
      var state=level.getBlockState(p);
      if (!(state.getBlock() instanceof BucketPickup source) || !state.getFluidState().isSource()
            || !reachable(level,bot,p,Vec3.atCenterOf(p)) || !kit.equipWeapon(bot,Items.BUCKET)) return false;
      bot.stopUsingItem();
      ItemStack filled=source.pickupBlock(bot,level,p,state);
      if (filled.isEmpty()) return false;
      bot.getMainHandItem().consume(1, bot);
      if (!TrainingResources.keepSupplies(bot) || !kit.has(bot, filled.getItem())) kit.stash(filled);
      bot.swing(InteractionHand.MAIN_HAND,true);
      return true;
   }
   private void executed(ServerLevel level,LivingEntity bot,String event) {
      changedArena=level;bot.swing(InteractionHand.MAIN_HAND,true);BotTelemetry.action(level,bot,event);
   }

   private boolean mineCover(ServerLevel level,LivingEntity bot,BotEquipment kit,Vec3 seen) {
      var hit=level.clip(new ClipContext(bot.getEyePosition(),seen.add(0,1.2,0),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
      BlockPos p=hit.getBlockPos();BlockState state=level.getBlockState(p);
      // Only interior player-buildable cover. Leave the permanent arena floor, decorations and walls intact.
      if (hit.getType()!=HitResult.Type.BLOCK || Math.abs(p.getX())>12 || Math.abs(p.getZ())>12
            || !(state.is(Blocks.OBSIDIAN) || state.is(Blocks.COBBLESTONE)) || !reachable(level,bot,p,hit.getLocation())
            || !kit.equipMatching(bot,s->s.is(ItemTags.PICKAXES) && s.isCorrectToolForDrops(state))) return false;
      if (!p.equals(mining)) {cancelMining(level);mining=p;miningLevel=level;mineEntityId=bot.getId();}
      bot.stopUsingItem();
      var tool=bot.getMainHandItem();
      float speed=tool.getDestroySpeed(state);
      if (speed>1) {
         // Mannequins do not have the player-only mining efficiency attribute. Apply the held pick's actual modifiers.
         var efficiency=new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.world.entity.ai.attributes.Attributes.MINING_EFFICIENCY,a->{});
         tool.forEachModifier(EquipmentSlot.MAINHAND,(attribute,modifier)->{
            if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.MINING_EFFICIENCY)) efficiency.addOrReplacePermanentModifier(modifier);
         });
         speed+=efficiency.getValue();
      }
      mineProgress+=speed / state.getDestroySpeed(level,p) / (bot.onGround()?30:150);
      if (mineProgress>=1) {
         if (level.destroyBlock(p,false,bot,512)) { tool.hurtAndBreak(1,bot,EquipmentSlot.MAINHAND);executed(level,bot,"MINE"); }
         cancelMining(level);
      } else {
         level.destroyBlockProgress(mineEntityId,p,(int)(mineProgress*10));if (!bot.swinging) bot.swing(InteractionHand.MAIN_HAND,true);
      }
      return true;
   }
   private void cancelMining(ServerLevel level) {
      if (mining!=null) level.destroyBlockProgress(mineEntityId,mining,-1);
      mining=null;miningLevel=null;mineProgress=0;
   }

   BlockPos workingBlock() { return mining; }

   boolean crossbow(ServerLevel level,LivingEntity bot,ServerPlayer opponent,BotEquipment kit,int tick,boolean visible,
                    boolean shield,boolean tracking,DuelSkill skill,DuelReads reads) {
      double d=bot.distanceToSqr(opponent);
      if (!visible || tick<nextBolt || d<25 || d>324 || shield && d<81 || !kit.has(bot,Items.CROSSBOW)) return false;
      boolean already=bot.getMainHandItem().is(Items.CROSSBOW) && CrossbowItem.isCharged(bot.getMainHandItem());
      if (!already && !hasArrow(bot,kit) && !kit.equipMatching(bot,s->s.is(Items.CROSSBOW) && CrossbowItem.isCharged(s))) return false;
      if (!kit.equipWeapon(bot,Items.CROSSBOW)) return false;
      ItemStack bow=bot.getMainHandItem();
      if (bot.isUsingItem() && bot.getUsedItemHand()==InteractionHand.OFF_HAND) bot.stopUsingItem();
      if (!CrossbowItem.isCharged(bow)) {
         if (!bot.isUsingItem()) bot.startUsingItem(InteractionHand.MAIN_HAND);
         if (bot.getTicksUsingItem()<CrossbowItem.getChargeDuration(bow,bot)) return true;
         ItemStack ammo=takeArrow(bot,kit);
         if (ammo.isEmpty()) {bot.stopUsingItem();return false;}
         int count=net.minecraft.world.item.enchantment.EnchantmentHelper.processProjectileCount(level,bow,bot,1);
         bow.set(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.ofNonEmpty(java.util.Collections.nCopies(Math.max(1,count),ammo)));
      }
      bot.stopUsingItem();
      DuelAim.ranged(bot,opponent,3.15,tracking,skill,reads);
      ((CrossbowItem)bow.getItem()).performShooting(level,bot,InteractionHand.MAIN_HAND,bow,3.15F,0,null);
      // Keep vanilla projectiles, including their enchantments, local to this arena round.
      for (var arrow:level.getEntitiesOfClass(AbstractArrow.class,bot.getBoundingBox().inflate(2),a->a.getOwner()==bot)) {
         if (!fired.contains(arrow)) {arrow.pickup=AbstractArrow.Pickup.DISALLOWED;fired.add(arrow);}
      }
      fired.removeIf(a->a.isRemoved());BotTelemetry.action(level,bot,"CROSSBOW");nextBolt=tick+75;return true;
   }
   void clear() {
      fired.forEach(a->a.discard());fired.clear();
      if (miningLevel!=null) cancelMining(miningLevel);
      if (changedArena!=null) {ForgeArena.restore(changedArena);changedArena=null;}
   }
}
