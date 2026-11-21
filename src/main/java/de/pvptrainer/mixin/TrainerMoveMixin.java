package de.pvptrainer.mixin;

import de.pvptrainer.session.ArenaSignals;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class TrainerMoveMixin {
    @Shadow public ServerPlayer player;

    // A landing and the next jump can both be processed between two end-of-tick samples.
    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/server/level/ServerPlayer;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V"))
    private void pvptrainer$acceptedMovement(ServerboundMovePlayerPacket packet, CallbackInfo info) {
        ArenaSignals.webMovement(player, player.onGround());
    }
}
