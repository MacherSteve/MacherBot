package de.pvptrainer.mixin;

import de.pvptrainer.arena.ReadyGate;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class ReadyKeyboardMixin extends ClientInput {
   @Inject(method = "tick", at = @At("RETURN"))
   private void pvptrainer$startRound(CallbackInfo var1) {
      Input var2 = this.keyPresses;
      if (ReadyGate.token() != 0L) {
         this.keyPresses = Input.EMPTY;
         this.moveVector = Vec2.ZERO;
      } else if (ReadyGate.consumingJump()) {
         this.keyPresses = new Input(var2.forward(), var2.backward(), var2.left(), var2.right(), false, var2.shift(), var2.sprint());
      }
   }
}
