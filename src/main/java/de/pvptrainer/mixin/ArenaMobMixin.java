package de.pvptrainer.mixin;
import de.pvptrainer.arena.TrainingArena;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class ArenaMobMixin {
    @Inject(method="addEntity", at=@At("HEAD"), cancellable=true)
    private void pvptrainer$denyArenaMobs(Entity entity, CallbackInfoReturnable<Boolean> result) {
        if (entity instanceof Mob && TrainingArena.isArena((ServerLevel)(Object)this)) result.setReturnValue(false);
    }
}