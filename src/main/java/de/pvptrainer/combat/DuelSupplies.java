package de.pvptrainer.combat;
import de.pvptrainer.analytics.BotTelemetry;
import de.pvptrainer.session.RoundPing;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.HitResult;

/** Use the actual stack and let eating and splash effects resolve through the game. */
final class DuelSupplies {
    private int nextUse;
    private Projectile thrown;
    private static final List<EquipmentSlot> ARMOR = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    boolean tick(ServerLevel level, LivingEntity bot, ServerPlayer player, BotEquipment kit, int tick, UhcDefense defense) {
        double range = bot.distanceToSqr(RoundPing.position(player));
        boolean underFire = range <= 324 && bot.hasLineOfSight(player) && defense.aimingBowAt(player,bot);
        if (bot.isUsingItem() && bot.getUseItem().has(DataComponents.CONSUMABLE)) {
            if ((!underFire && range > 6.25) || bot.getHealth() < 6) return true;
            bot.stopUsingItem();
            nextUse = tick + 12;
        }
        if (tick < nextUse || !bot.onGround() || bot.isUsingItem() || underFire || range < 30.25) return false;
        if (bot.getHealth() <= bot.getMaxHealth() * .65 && !bot.hasEffect(MobEffects.REGENERATION)
                && kit.equipMatching(bot, s -> s.is(Items.GOLDEN_APPLE) || s.is(Items.ENCHANTED_GOLDEN_APPLE))) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            if (bot.isUsingItem()) {
                BotTelemetry.action(level, bot, "HEAL");
                nextUse = tick + 36;
                return true;
            }
        }
        if (kit.equipMatching(bot, stack -> usefulPotion(stack,bot))) {
            ItemStack stack = bot.getMainHandItem();
            return toss(level,bot,new ThrownSplashPotion(level,bot,stack.copyWithCount(1)),tick,"POTION");
        }
        if (range >= 64 && !repairTarget(bot).isEmpty() && kit.equipWeapon(bot,Items.EXPERIENCE_BOTTLE)) {
            return toss(level,bot,new RepairBottle(level,bot,bot.getMainHandItem().copyWithCount(1)),tick,"REPAIR");
        }
        return false;
    }
    private static boolean usefulPotion(ItemStack stack, LivingEntity bot) {
        if (!stack.is(Items.SPLASH_POTION)) return false;
        boolean useful = false;
        for (var effect : stack.getOrDefault(DataComponents.POTION_CONTENTS,PotionContents.EMPTY).getAllEffects()) {
            if (!effect.getEffect().value().isBeneficial()) return false;
            if (effect.is(MobEffects.INSTANT_HEALTH) || effect.is(MobEffects.REGENERATION)) {
                if (bot.getHealth() >= bot.getMaxHealth()*.75) continue;
            }
            var active = bot.getEffect(effect.getEffect());
            if (active == null || active.getAmplifier() < effect.getAmplifier()
                    || active.getAmplifier() == effect.getAmplifier() && active.getDuration() < 40) useful = true;
        }
        return useful;
    }
    private boolean toss(ServerLevel level, LivingEntity bot, Projectile projectile, int tick, String family) {
        projectile.shoot(0,-1,0,.75F,0);
        if (!level.addFreshEntity(projectile)) return false;
        thrown=projectile;
        bot.getMainHandItem().consume(1, bot);
        bot.swing(InteractionHand.MAIN_HAND,true);
        BotTelemetry.action(level,bot,family);
        nextUse = tick + 18;
        return true;
    }
    private static ItemStack repairTarget(LivingEntity bot) {
        ItemStack target=ItemStack.EMPTY;
        for (var slot : ARMOR) {
            var stack=bot.getItemBySlot(slot);
            if (stack.isDamaged() && stack.getDamageValue()>target.getDamageValue()
                    && EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP)) target=stack;
        }
        return target;
    }
    // Mannequins cannot collect XP orbs. A close impact spends this bottle's normal XP roll on worn Mending gear.
    private static final class RepairBottle extends ThrownExperienceBottle {
        RepairBottle(ServerLevel level,LivingEntity bot,ItemStack stack){super(level,bot,stack);}
        @Override protected void onHit(HitResult hit) {
            if (level() instanceof ServerLevel level && getOwner() instanceof LivingEntity bot
                    && bot.isAlive() && distanceToSqr(bot)<4) {
                var armor=repairTarget(bot);
                if (!armor.isEmpty()) {
                    int xp=3+random.nextInt(5)+random.nextInt(5);
                    int capacity=EnchantmentHelper.modifyDurabilityToRepairFromXp(level,armor,xp);
                    if (capacity>0) {
                        int restored=Math.min(armor.getDamageValue(),capacity);
                        armor.setDamageValue(armor.getDamageValue()-restored);
                        int spare=xp-restored*xp/capacity;
                        if (spare>0) ExperienceOrb.award(level,position(),spare);
                        level.levelEvent(2002,blockPosition(),0x385DC6);
                        discard();
                        return;
                    }
                }
            }
            super.onHit(hit);
        }
    }
    void clear(){if(thrown!=null && !thrown.isRemoved()) thrown.discard();}
}
