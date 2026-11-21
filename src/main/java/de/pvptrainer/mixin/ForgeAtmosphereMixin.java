package de.pvptrainer.mixin;

import de.pvptrainer.arena.TrainingArena;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.attribute.AmbientParticle;
import net.minecraft.world.attribute.AmbientSounds;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.EnvironmentAttributeSystem.Builder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
abstract class ForgeAtmosphereMixin {
   @Inject(method = "addEnvironmentAttributeLayers", at = @At("RETURN"))
   private void pvptrainer$forgeAtmosphere(Builder var1, CallbackInfoReturnable<Builder> var2) {
      if (TrainingArena.isArena((ClientLevel)(Object)this)) {
         ((Builder)var2.getReturnValue())
            .addConstantLayer(
               EnvironmentAttributeMap.builder()
                  .set(EnvironmentAttributes.SKY_COLOR, 3478820)
                  .set(EnvironmentAttributes.FOG_COLOR, 6169638)
                  .set(EnvironmentAttributes.FOG_START_DISTANCE, 4096.0F)
                  .set(EnvironmentAttributes.FOG_END_DISTANCE, 4097.0F)
                  .set(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, 0)
                  .set(EnvironmentAttributes.SUN_ANGLE, 180.0F)
                  .set(EnvironmentAttributes.MOON_ANGLE, 180.0F)
                  .set(EnvironmentAttributes.STAR_BRIGHTNESS, 0.0F)
                  .set(EnvironmentAttributes.CLOUD_COLOR, 0)
                  .set(EnvironmentAttributes.AMBIENT_LIGHT_COLOR, 3418411)
                  .set(EnvironmentAttributes.SKY_LIGHT_COLOR, 15122876)
                  .set(EnvironmentAttributes.SKY_LIGHT_FACTOR, 0.68F)
                  .set(
                     EnvironmentAttributes.AMBIENT_PARTICLES,
                     List.of(new AmbientParticle(ParticleTypes.CRIMSON_SPORE, 0.0045F), new AmbientParticle(ParticleTypes.ASH, 0.0015F))
                  )
                  .set(
                     EnvironmentAttributes.AMBIENT_SOUNDS, new AmbientSounds(Optional.of(SoundEvents.AMBIENT_CRIMSON_FOREST_LOOP), Optional.empty(), List.of())
                  )
                  .build()
            );
      }
   }
}
