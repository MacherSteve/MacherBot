package de.pvptrainer.mixin;
import de.pvptrainer.arena.TrainingArena;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerExplosion.class)
abstract class ArenaCrystalTerrainMixin {
    @Inject(method="interactsWithBlocks", at=@At("HEAD"), cancellable=true)
    private void pvptrainer$protectArenaBlocks(CallbackInfoReturnable<Boolean> result) {
        ServerExplosion blast=(ServerExplosion)(Object)this;
        if (blast.getDirectSourceEntity() instanceof EndCrystal && TrainingArena.isArena(blast.level())) result.setReturnValue(false);
    }
}