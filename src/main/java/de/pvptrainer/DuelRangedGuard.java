package de.pvptrainer;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

final class DuelRangedGuard{
    private boolean active,hadTotem;

    boolean tick(ServerLevel level,LivingEntity bot,ServerPlayer opponent,BotEquipment kit,boolean recognizedAim){
        double distance=bot.distanceToSqr(RoundPing.position(opponent));
        boolean threat=distance>=36 && distance<=256 && bot.getHealth()>=14 && bot.hasLineOfSight(opponent)
                && recognizedAim;
        if(threat && (!bot.isUsingItem()||bot.getUseItem().is(Items.SHIELD))){
            if(!active)hadTotem=bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
            if(kit.equipRangedShield(bot)){
                if(!bot.isUsingItem())bot.startUsingItem(kit.shieldHand);
                if(bot.isUsingItem()&&bot.getUseItem().is(Items.SHIELD)){
                    if(!active)BotTelemetry.action(level,bot,"SHIELD");
                    active=true;return true;
                }
            }
        }
        if(active && bot.isUsingItem() && bot.getUsedItemHand()==kit.shieldHand && bot.getUseItem().is(Items.SHIELD))bot.stopUsingItem();
        if(hadTotem)kit.restoreRangedTotem(bot);
        active=hadTotem=false;
        return false;
    }
}
