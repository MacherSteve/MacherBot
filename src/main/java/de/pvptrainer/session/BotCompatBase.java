package de.pvptrainer.session;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;

/** Versionsbruecke fuer Mannequin/LivingEntity-Hooks in TrainingBot (MC 26.2). */
abstract class BotCompatBase extends Mannequin {
    BotCompatBase(ServerLevel level) {
        super(level);
    }

    protected final void resetImpulseInBlock() {
        this.tryResetCurrentImpulseContext();
    }

    protected final void superKnockback(double strength, double x, double z, DamageSource source, float damage, boolean force) {
        super.knockback(strength, x, z, source, damage, force);
    }

    protected final void superBlockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount) {
        super.blockUsingItem(level, attacker, source, amount);
    }

    @Override
    public final void knockback(double strength, double x, double z, DamageSource source, float damage, boolean force) {
        this.onBotKnockback(strength, x, z, source, damage, force);
    }

    @Override
    protected final void blockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount) {
        this.onBotBlockUsingItem(level, attacker, source, amount);
    }

    protected abstract void onBotKnockback(double strength, double x, double z, DamageSource source, float damage, boolean force);

    protected abstract void onBotBlockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount);
}
