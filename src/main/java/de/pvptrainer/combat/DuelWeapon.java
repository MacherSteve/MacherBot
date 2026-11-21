package de.pvptrainer.combat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** A hotbar swap does not grant another fully charged attack. */
final class DuelWeapon {
    private int lastSwing = -1000;

    boolean strike(ServerLevel level, LivingEntity bot, ServerPlayer player, int tick, DamageSource source, float bonus) {
        return strike(level,bot,player,tick,source,bonus,bot.getMainHandItem());
    }

    boolean strike(ServerLevel level, LivingEntity bot, ServerPlayer player, int tick, DamageSource source, float bonus,
                   net.minecraft.world.item.ItemStack chargedWeapon) {
        var attributes = chargedWeapon.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        double speed = attributes.compute(Attributes.ATTACK_SPEED, 4, EquipmentSlot.MAINHAND);
        float charge = (float)Math.clamp((tick - lastSwing + .5) * speed / 20, 0, 1);
        if (bot.isUsingItem()) return false;
        lastSwing = tick;
        return TrainingTactics.strikeCharged(level, bot, player, source, bonus, charge, false, chargedWeapon);
    }
}
