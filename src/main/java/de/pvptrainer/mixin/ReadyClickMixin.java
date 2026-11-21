package de.pvptrainer.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import de.pvptrainer.arena.ReadyGate;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyMapping.class)
abstract class ReadyClickMixin {
    @Inject(method = "click", at = @At("HEAD"))
    private static void pvptrainer$roundClick(InputConstants.Key key, CallbackInfo info) {
        ReadyGate.click(key);
    }
}
