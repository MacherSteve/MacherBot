package de.pvptrainer.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
abstract class ArenaPingMixin {
    @Inject(method="channelActive",at=@At("TAIL"))
    private void pvptrainer$localPing(ChannelHandlerContext ctx, CallbackInfo info) {
        var connection=(Connection)(Object)this;
        if(connection.isMemoryConnection())
            ctx.pipeline().addBefore(ctx.name(),"pvptrainer_local_latency",new de.pvptrainer.session.LocalPacketDelay(connection));
    }
}

