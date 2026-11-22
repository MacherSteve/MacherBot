package de.pvptrainer.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface AttackStrengthAccess {
    @Accessor("attackStrengthTicker")
    void pvptrainer$setAttackStrengthTicker(int ticks);
}
