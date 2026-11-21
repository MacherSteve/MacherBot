package de.pvptrainer.combat;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.arena.TrainingArena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Additional free-duel choices. The timed totem drills have their own sequence. */
final class CrystalUtility {
    private final DuelPearl pearl = new DuelPearl();
    private final List<Projectile> bolts = new ArrayList<>();
    private boolean firstChoice = true;
    private ItemStack loading = ItemStack.EMPTY;
    private BlockPos anchor;
    private ServerLevel anchorLevel;
    private final List<BlockPos> placedAnchors=new ArrayList<>();
    private int nextChoice, anchorSince;

    boolean act(ServerLevel level, LivingEntity bot, ServerPlayer target, BotEquipment kit, int tick,
                boolean ready, boolean visible, CrystalPursuit motion,boolean trackAim, DuelSkill skill, DuelReads reads) {
        boolean facing = visible && CrystalPursuit.inFront(bot,target.position());
        if (pearl.inFlight()) return true;
        if (anchor != null) return useAnchor(level,bot,target,kit,tick,skill);
        if (!loading.isEmpty()) return continueReload(level,bot,target,kit,facing,trackAim,skill,reads);
        if (!ready || !facing || !reads.acquired(skill) || tick < nextChoice || bot.isUsingItem()) return false;
        double d=bot.distanceToSqr(target);
        Vec3 forecast=target.position().add(reads.travel(skill, 4));
        boolean opening=firstChoice;
        firstChoice=false;
        if (reads.retreat(skill) && (opening || d>64)) {
            // Spend a pearl on a clean intercept. Under pressure prefer a ranged answer or a nearby base.
            int pearlWeight = bot.getHealth() > 10 ? (d > 36 ? 3 : 1) : 0;
            int boltWeight = reads.shield(skill) ? 0 : (d >= 25 ? 3 : 1);
            int choice = bot.getRandom().nextInt(pearlWeight + boltWeight + 2);
            nextChoice=tick+32;
            if (choice < pearlWeight) {
                Vec3 advance=forecast.subtract(bot.position()).horizontal().normalize();
                int flank=bot.getRandom().nextBoolean()?1:-1;
                Vec3 side=new Vec3(-advance.z,0,advance.x).scale(1.5*flank);
                Vec3 ahead=forecast.add(advance.scale(1.4));
                if (pearl.throwAt(level,bot,kit,ahead.add(side),tick) || pearl.throwAt(level,bot,kit,ahead.subtract(side),tick)) return true;
            } else if (choice < pearlWeight + boltWeight && beginCrossbow(level,bot,target,kit,trackAim,skill,reads)) return true;
        }
        if (d>=49 && d<=324 && (!kit.has(bot,Items.END_CRYSTAL) || d>64) && beginCrossbow(level,bot,target,kit,trackAim,skill,reads)) {
            nextChoice=tick+30;
            return true;
        }
        // Close behind cover, a single charged anchor can create a better trade than a large crystal hitbox.
        if (d<=42 && kit.has(bot,Items.RESPAWN_ANCHOR) && kit.has(bot,Items.GLOWSTONE)
                && (target.getY()>bot.getY()+.7 || !kit.has(bot,Items.END_CRYSTAL) || !opening && !motion.retreating())) {
            nextChoice=tick+18;
            return placeAnchor(level,bot,target,kit,tick);
        }
        return false;
    }

    private boolean beginCrossbow(ServerLevel level,LivingEntity bot,ServerPlayer target,BotEquipment kit,
                                 boolean trackAim,DuelSkill skill,DuelReads reads) {
        if (!kit.equipWeapon(bot,Items.CROSSBOW)) return false;
        var bow=bot.getMainHandItem();
        if (CrossbowItem.isCharged(bow)){fire(level,bot,target,bow,trackAim,skill,reads);return true;}
        if (!kit.has(bot,Items.TIPPED_ARROW) && !kit.has(bot,Items.SPECTRAL_ARROW) && !kit.has(bot,Items.ARROW)) return false;
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        if (!bot.isUsingItem()) return false;
        loading=bow;
        return true;
    }

    private boolean continueReload(ServerLevel level,LivingEntity bot,ServerPlayer target,BotEquipment kit,boolean facing,
                                   boolean trackAim,DuelSkill skill,DuelReads reads) {
        if (!facing || bot.getMainHandItem()!=loading || !bot.isUsingItem() || bot.distanceToSqr(target)<2.25) {
            if (bot.getUseItem()==loading) bot.stopUsingItem();
            loading=ItemStack.EMPTY;
            return false;
        }
        if (bot.getTicksUsingItem()<CrossbowItem.getChargeDuration(loading,bot)) return true;
        ItemStack bow=loading;
        loading=ItemStack.EMPTY;
        bot.stopUsingItem();
        ItemStack arrow=ItemStack.EMPTY;
        for(Item type:List.of(Items.TIPPED_ARROW,Items.SPECTRAL_ARROW,Items.ARROW)){
            arrow=kit.ammunition(bot,type);
            if (!arrow.isEmpty())break;
        }
        if (arrow.isEmpty())return false;
        int count=EnchantmentHelper.processProjectileCount(level,bow,bot,1);
        bow.set(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.ofNonEmpty(Collections.nCopies(count,arrow)));
        fire(level,bot,target,bow,trackAim,skill,reads);
        return true;
    }

    private void fire(ServerLevel level,LivingEntity bot,ServerPlayer target,ItemStack bow,boolean trackAim,DuelSkill skill,DuelReads reads) {
        DuelAim.ranged(bot,target,3.15,trackAim,skill,reads);
        // Use the real weapon's player-style sight line at player bolt speed, including its Multishot spread.
        ((CrossbowItem)bow.getItem()).performShooting(level,bot,InteractionHand.MAIN_HAND,bow,3.15F,0,null);
        bolts.removeIf(Entity::isRemoved);
        var volley=level.getEntitiesOfClass(Projectile.class,bot.getBoundingBox().inflate(2),p->p.getOwner()==bot && p.tickCount==0);
        for(var shot:volley)if(shot instanceof AbstractArrow arrow)arrow.pickup=AbstractArrow.Pickup.DISALLOWED;
        bolts.addAll(volley);
        if (!volley.isEmpty())BotTelemetry.action(level,bot,"CROSSBOW");
    }

    private static boolean reachable(ServerLevel level,LivingEntity bot,BlockPos pos){
        Vec3 point=Vec3.atCenterOf(pos);
        if(!CrystalPursuit.inFront(bot,point)||bot.getEyePosition().distanceToSqr(point)>20.25)return false;
        var hit=level.clip(new ClipContext(bot.getEyePosition(),point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,bot));
        return hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(pos);
    }

    private static double exposure(Vec3 center,LivingEntity entity){
        // The source block disappears before the explosion. Start just outside its face when evaluating cover.
        Vec3 toward=entity.getBoundingBox().getCenter().subtract(center);
        double axis=Math.max(Math.abs(toward.x),Math.max(Math.abs(toward.y),Math.abs(toward.z)));
        Vec3 face=axis>.51 ? center.add(toward.scale(.51/axis)) : center;
        return Math.max(0,1-center.distanceTo(entity.position())/10)*ServerExplosion.getSeenPercent(face,entity);
    }
    private static double trade(Vec3 center,LivingEntity bot,ServerPlayer target){
        double enemy=exposure(center,target),own=exposure(center,bot);
        if(enemy<.40 || own>.72 && bot.getHealth()<10)return -1;
        return enemy-own;
    }

    private boolean placeAnchor(ServerLevel level,LivingEntity bot,ServerPlayer target,BotEquipment kit,int tick){
        if(!TrainingArena.isArena(level))return false;
        BlockPos near=target.blockPosition(),best=null;
        double score=.12;
        for(BlockPos candidate:BlockPos.betweenClosed(near.offset(-2,-1,-2),near.offset(2,0,2))){
            if(!level.isLoaded(candidate)||!level.getWorldBorder().isWithinBounds(candidate)||!level.isEmptyBlock(candidate)
                    ||!level.getBlockState(candidate.below()).isFaceSturdy(level,candidate.below(),Direction.UP)
                    ||!level.getEntities(null,new AABB(candidate)).isEmpty()||!reachable(level,bot,candidate))continue;
            double quality=trade(Vec3.atCenterOf(candidate),bot,target);
            if(quality>score){score=quality;best=candidate.immutable();}
        }
        if(best==null || RespawnAnchorBlock.canSetSpawn(level,best) || !kit.equipWeapon(bot,Items.RESPAWN_ANCHOR)
                || !level.setBlockAndUpdate(best,Blocks.RESPAWN_ANCHOR.defaultBlockState()))return false;
        kit.takeOne(bot,Items.RESPAWN_ANCHOR);
        bot.swing(InteractionHand.MAIN_HAND,true);
        level.playSound(null,best,SoundEvents.RESPAWN_ANCHOR_SET_SPAWN,SoundSource.BLOCKS,.8F,1);
        anchor=best;anchorLevel=level;anchorSince=tick;placedAnchors.add(best);
        return true;
    }

    private boolean useAnchor(ServerLevel level,LivingEntity bot,ServerPlayer target,BotEquipment kit,int tick,DuelSkill skill){
        var pos=anchor;var state=level.getBlockState(pos);
        if(!state.is(Blocks.RESPAWN_ANCHOR)){anchor=null;return false;}
        Vec3 center=Vec3.atCenterOf(pos);
        if(!reachable(level,bot,pos) || !bot.hasLineOfSight(target) || !CrystalPursuit.inFront(bot,target.position())
                || trade(center,bot,target)<.02 || tick-anchorSince>2*skill.switchTicks()+8){
            // An abandoned anchor remains available to either combatant until the arena resets.
            anchor=null;return false;
        }
        boolean charged = state.getValue(RespawnAnchorBlock.CHARGE)>0;
        if (tick < anchorSince + skill.switchTicks() * (charged ? 2 : 1)) return true;
        if(!charged){
            if(!kit.equipWeapon(bot,Items.GLOWSTONE)||!kit.takeOne(bot,Items.GLOWSTONE)){anchor=null;return false;}
            RespawnAnchorBlock.charge(bot,level,pos,state);bot.swing(InteractionHand.MAIN_HAND,true);return true;
        }
        if(RespawnAnchorBlock.canSetSpawn(level,pos)){anchor=null;return false;}
        // Switch off glowstone so that the click detonates instead of adding another charge.
        kit.guard(bot);
        if(bot.getMainHandItem().is(Items.GLOWSTONE)&&!kit.equipSword(bot)&&!kit.equipWeapon(bot,Items.OBSIDIAN)) {anchor=null;return false;}
        anchor=null;
        if(!level.removeBlock(pos,false))return false;
        placedAnchors.remove(pos);
        var source=new DamageSource(level.damageSources().badRespawnPointExplosion(center).typeHolder(),null,bot){
            @Override public Vec3 getSourcePosition(){return center;}
            @Override public Vec3 sourcePositionRaw(){return center;}
        };
        var onlyDuel=new ExplosionDamageCalculator(){
            @Override public boolean shouldDamageEntity(Explosion blast,Entity victim){return victim==bot||victim==target;}
        };
        bot.swing(InteractionHand.MAIN_HAND,true);
        BotTelemetry.action(level,bot,"ANCHOR");
        level.explode(null,source,onlyDuel,center,5F,false,Level.ExplosionInteraction.NONE);
        return true;
    }

    void clear(){
        pearl.clear();
        for(var bolt:bolts)bolt.discard();
        bolts.clear();
        for(var pos:placedAnchors)if(anchorLevel.getBlockState(pos).is(Blocks.RESPAWN_ANCHOR))anchorLevel.removeBlock(pos,false);
        placedAnchors.clear();
        anchor=null;loading=ItemStack.EMPTY;
    }
}
