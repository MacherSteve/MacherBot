package de.pvptrainer;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;

final class MaceDuel {
    interface Glider { boolean launchGlide(); }
    private final DuelPearl pearl = new DuelPearl();
    private final DuelRangedGuard guard = new DuelRangedGuard();
    private WindCharge charge;
    private FireworkRocketEntity rocket;
    private ItemStack previousChest;
    private int nextAttack, nextLaunch, boostAt;
    private int reconsiderGroundPlan;
    private boolean groundPlan;
    private boolean approaching;
    private int preparedSwordAt = -1000;

    boolean rangedGuard(ServerLevel level, LivingEntity bot, ServerPlayer target, BotEquipment kit, boolean threat) {
        return guard.tick(level,bot,target,kit,threat);
    }

    void tick(ServerLevel level, LivingEntity bot, ServerPlayer target, BotEquipment kit, int tick,
              TrainingSettings settings, int recovery, DrillOptions options, DuelSkill skill, DuelReads reads, DuelWeapon weapon,
              DuelOpening opening) {
        if (tick<options.reactionTicks() || options.holdShield()) return;
        if (air(level,bot,target,kit,tick,settings,options,skill,reads,weapon)) return;
        if (rangedGuard(level,bot,target,kit,reads.bow(skill))) return;
        if (!reads.acquired(skill) || bot.isUsingItem()) return;
        double distance=bot.position().subtract(RoundPing.position(target)).horizontalDistance();
        boolean mobile=settings.movement()!=TrainingSettings.Movement.STILL;
        if (mobile && distance>10 && distance<=18 && bot.getHealth()>8 && bot.onGround() && tick>=nextLaunch) {
            Vec3 flank=bot.position().subtract(RoundPing.position(target)).horizontal().normalize();
            Vec3 intercept = RoundPing.position(target).add(reads.travel(skill, distance / 1.5)).add(flank.scale(2.5));
            if(pearl.throwAt(level,bot,kit,intercept,tick)) {
                nextLaunch=tick+23;
                return;
            }
        }
        if (!bot.onGround()) return;
        if (tick<nextAttack) {
            if(target.getArmorValue()>=12 && kit.equipSword(bot)) preparedSwordAt=tick;
            return;
        }
        boolean facing=bot.getLookAngle().horizontal().dot(RoundPing.position(target).subtract(bot.position()).horizontal().normalize())>0;
        if (!facing) return;
        if (TrainingTactics.withinReach(bot,target) && reads.shield(skill) && kit.equipAxe(bot)) {
            weapon.strike(level,bot,target,tick,bot.damageSources().mobAttack(bot),0);
            nextAttack=tick+recovery;
            return;
        }
        if (opening.repositioning()) return;
        boolean headroom=level.noBlockCollision(bot,bot.getBoundingBox().expandTowards(0,3.8,0));
        double landingRange = RoundPing.position(target).add(reads.travel(skill, 7)).subtract(bot.position()).horizontalDistance();
        boolean canLaunch = mobile && tick>=nextLaunch && landingRange < 4.5 + 3.5 * skill.lead() && headroom
                && kit.has(bot,Items.MACE) && kit.has(bot,Items.WIND_CHARGE);
        boolean inReach = TrainingTactics.withinReach(bot,target);
        if (inReach && canLaunch && tick >= reconsiderGroundPlan) {
            // Both attacks are viable. Press a visibly hurt target; consider launching against a counter-swing.
            double groundWeight = target.hurtTime > 0 ? .72 : RoundPing.swing(target) ? .35 : .48;
            groundPlan = bot.getRandom().nextDouble() < groundWeight;
            reconsiderGroundPlan = tick + recovery + skill.switchTicks();
        }
        boolean punishMeal = inReach && reads.food(skill);
        if (canLaunch && !(inReach && groundPlan || punishMeal) && kit.equipWeapon(bot,Items.WIND_CHARGE)) {
            // Jump and aim a real charge at the floor. The explosion determines the launch and knockback.
            Vec3 foot=bot.position();
            WindCharge wind=new WindCharge(level,foot.x,foot.y+.5,foot.z,new Vec3(0,-1,0));
            wind.setOwner(bot);
            wind.shoot(0,-1,0,1.5F,0);
            if(level.addFreshEntity(wind)) {
                charge=wind;
                bot.getMainHandItem().consume(1, bot);
                bot.jumpFromGround();
                bot.swing(InteractionHand.MAIN_HAND,true);
                BotTelemetry.action(level,bot,"WIND");
                nextLaunch=tick+recovery+16;
                nextAttack=tick+5;
                return;
            }
        }
        if (inReach) {
            ItemStack held=bot.getMainHandItem();
            boolean armed=target.getArmorValue()>=12 && tick>=skill.opening(options)+skill.switchTicks() && preferred(kit,bot,Enchantments.BREACH);
            boolean swap=armed && held.is(net.minecraft.tags.ItemTags.SWORDS) && preparedSwordAt==tick-1
                && bot.getRandom().nextDouble()<skill.lead();
            if (!armed) armed=kit.equipSword(bot)||kit.equipWeapon(bot,Items.MACE)||kit.equipAxe(bot);
            if (armed) {
                if(swap) BotTelemetry.action(level,bot,"BREACH_SWAP");
                weapon.strike(level,bot,target,tick,bot.damageSources().mobAttack(bot),0,swap?held:bot.getMainHandItem());
                nextAttack=tick+recovery;
            }
        }
    }

    boolean air(ServerLevel level, LivingEntity bot, ServerPlayer target, BotEquipment kit, int tick, TrainingSettings settings, DrillOptions options,
                DuelSkill skill, DuelReads reads, DuelWeapon weapon) {
        double horizontal=bot.position().subtract(RoundPing.position(target)).horizontalDistance();
        double height=bot.getY()-RoundPing.position(target).y;
        double approachRange = horizontal;
        if (approaching && bot.hasLineOfSight(target)) {
            Vec3 relative = RoundPing.position(target).add(reads.travel(skill, 3)).subtract(bot.position().add(bot.getDeltaMovement().horizontal().scale(3 * skill.lead())));
            approachRange = Math.min(horizontal, relative.horizontalDistance());
        }
        if (approaching && (bot.onGround() || !bot.hasLineOfSight(target) || approachRange<=2.7 || height<1.8)) {
            bot.stopFallFlying();
            if(previousChest!=null) {
                kit.stash(bot.getItemBySlot(EquipmentSlot.CHEST));
                bot.setItemSlot(EquipmentSlot.CHEST,previousChest);
                previousChest=null;
            }
            approaching=false;
        }
        if (bot.onGround() || !bot.hasLineOfSight(target) || !kit.has(bot,Items.MACE)) return false;
        if (!approaching && height >= 3.4 + .20 * (skill.switchTicks()-1) && horizontal>=4 && horizontal<=20 && bot.getDeltaMovement().y<=0
                && reads.acquired(skill)
                && options.gaze()==DrillOptions.Gaze.TRACK && settings.movement()!=TrainingSettings.Movement.STILL && bot instanceof Glider glider) {
            boolean alreadyWing=LivingEntity.canGlideUsing(bot.getItemBySlot(EquipmentSlot.CHEST),EquipmentSlot.CHEST);
            if(!alreadyWing && kit.equipMatching(bot,s->s.is(Items.ELYTRA)&&LivingEntity.canGlideUsing(s,EquipmentSlot.CHEST))) {
                previousChest=bot.getItemBySlot(EquipmentSlot.CHEST);
                bot.setItemSlot(EquipmentSlot.CHEST,bot.getMainHandItem());
                bot.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            }
            if((alreadyWing||previousChest!=null) && glider.launchGlide()) {
                approaching=true;
                BotTelemetry.action(level,bot,"GLIDE");
            } else if(previousChest!=null) {
                kit.stash(bot.getItemBySlot(EquipmentSlot.CHEST));
                bot.setItemSlot(EquipmentSlot.CHEST,previousChest);
                previousChest=null;
            }
        }
        if(approaching) {
            bot.setXRot(Mth.clamp(bot.getXRot(),-5,20));
            if(horizontal>9 && tick>=boostAt && kit.equipWeapon(bot,Items.FIREWORK_ROCKET)){
                var launched=new FireworkRocketEntity(level,bot.getMainHandItem().copyWithCount(1),bot);
                if(level.addFreshEntity(launched)) {
                    rocket=launched;bot.getMainHandItem().consume(1, bot);boostAt=tick+28;
                    BotTelemetry.action(level,bot,"ROCKET");
                }
            }
            return true;
        }
        if (tick<nextAttack || !MaceItem.canSmashAttack(bot) || !TrainingTactics.withinReach(bot,target)
                || !CrystalPursuit.inFront(bot,RoundPing.position(target))) return false;
        // Break a front-facing shield on the descent. The second input only exists if the actual axe hit disabled it
        // and the fighter still has height and reach on the following tick.
        var shield = target.getItemBlockingWith();
        if (reads.shield(skill) && shield != null && kit.equipAxe(bot)) {
            weapon.strike(level,bot,target,tick,bot.damageSources().mobAttack(bot),0);
            boolean disabled = target.getCooldowns().isOnCooldown(shield);
            boolean timelySwap = disabled && bot.getRandom().nextDouble() < .30 + .62 * skill.lead();
            nextAttack = tick + (timelySwap ? skill.switchTicks() : skill.recovery());
            BotTelemetry.action(level,bot,timelySwap?"STUN_SLAM_ATTEMPT":"STUN_SLAM_MISSED");
            return true;
        }
        boolean chain=level.noBlockCollision(bot, bot.getBoundingBox().expandTowards(0, 4.5, 0))
                && (bot.fallDistance>=3 || target.getHealth()>8 || bot.getHealth()<10);
        var choice=chain?Enchantments.WIND_BURST:(bot.fallDistance>=3||target.getArmorValue()<12?Enchantments.DENSITY:Enchantments.BREACH);
        if (!preferred(kit,bot,choice) && !kit.equipWeapon(bot,Items.MACE)) return false;
        var damage=bot.damageSources().mace(bot);
        float smash=Items.MACE.getAttackDamageBonus(target,6,damage);
        if(weapon.strike(level,bot,target,tick,damage,smash)){
            var burst=bot.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.WIND_BURST);
            if(EnchantmentHelper.getItemEnchantmentLevel(burst,bot.getMainHandItem())>0)
                BotTelemetry.action(level,bot,"WIND_BURST_SMASH");
            bot.resetFallDistance();
            bot.playSound(SoundEvents.MACE_SMASH_GROUND);
            level.sendParticles(ParticleTypes.CRIT,target.getX(),target.getY(.6),target.getZ(),10,.3,.3,.3,.12);
        }
        nextAttack=tick+12;
        return true;
    }
    private static boolean preferred(BotEquipment gear, LivingEntity bot, ResourceKey<Enchantment> key) {
        var enchantment=bot.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
        return gear.equipMatching(bot,s->s.is(Items.MACE)&&EnchantmentHelper.getItemEnchantmentLevel(enchantment,s)>0);
    }
    void clear() {
        pearl.clear();
        if(charge!=null&&!charge.isRemoved())charge.discard();
        if(rocket!=null&&!rocket.isRemoved())rocket.discard();
    }
}
