package de.pvptrainer.hooks;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Entity.class)
public interface GravityAccess {
    @Accessor("DATA_NO_GRAVITY")
    static EntityDataAccessor<Boolean> pvptrainer$noGravity() { throw new AssertionError(); }
}
