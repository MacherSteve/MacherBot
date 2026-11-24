package de.pvptrainer.session;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;

/** Versionsbruecke fuer Mannequin/LivingEntity-Hooks in TrainingBot (MC 1.21.11). */
abstract class BotCompatBase extends Mannequin {
    private DamageSource activeHurtSource;
    private float activeHurtRemaining;

    BotCompatBase(ServerLevel level) {
        super(level);
    }

    protected final void resetImpulseInBlock() {
        // tryResetCurrentImpulseContext() existiert in MC 1.21.11 nicht.
    }

    protected final void superKnockback(double strength, double x, double z, DamageSource source, float damage, boolean force) {
        super.knockback(strength, x, z);
    }

    protected final void superBlockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount) {
        super.blockUsingItem(level, attacker);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        DamageSource prevSource = this.activeHurtSource;
        float prevRemaining = this.activeHurtRemaining;
        this.activeHurtSource = source;
        this.activeHurtRemaining = amount;
        try {
            return super.hurtServer(level, source, amount);
        } finally {
            this.activeHurtSource = prevSource;
            this.activeHurtRemaining = prevRemaining;
        }
    }

    @Override
    public float applyItemBlocking(ServerLevel level, DamageSource source, float amount) {
        DamageSource prevSource = this.activeHurtSource;
        float prevRemaining = this.activeHurtRemaining;
        this.activeHurtSource = source;
        this.activeHurtRemaining = amount;
        float blocked = super.applyItemBlocking(level, source, amount);
        if (prevSource != null && prevSource != source) {
            this.activeHurtSource = prevSource;
            this.activeHurtRemaining = prevRemaining;
        } else {
            this.activeHurtRemaining = Math.max(0.0F, amount - blocked);
        }
        return blocked;
    }

    @Override
    public final void knockback(double strength, double x, double z) {
        this.onBotKnockback(strength, x, z, this.activeHurtSource, this.activeHurtRemaining, false);
    }

    @Override
    protected final void blockUsingItem(ServerLevel level, LivingEntity attacker) {
        this.onBotBlockUsingItem(level, attacker, this.activeHurtSource, this.activeHurtRemaining);
    }

    protected abstract void onBotKnockback(double strength, double x, double z, DamageSource source, float damage, boolean force);

    protected abstract void onBotBlockUsingItem(ServerLevel level, LivingEntity attacker, DamageSource source, float amount);
}
