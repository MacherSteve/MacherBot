package de.pvptrainer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;

public final class LocalPacketDelay extends ChannelInboundHandlerAdapter {
    private final Connection connection;
    private long lastDue;
    private int pending;
    public LocalPacketDelay(Connection connection){this.connection=connection;}
    @Override public void channelRead(ChannelHandlerContext ctx,Object packet) {
        var round=RoundPing.link(connection);
        int delay=round==null?0:round.oneWayMicros();
        if(delay==0 && pending==0){ctx.fireChannelRead(packet);return;}
        long now=System.nanoTime();
        lastDue=Math.max(pending==0?now:lastDue,now+delay*1000L);
        pending++;
        ctx.executor().schedule(()->{
            pending--;
            if(ctx.channel().isActive() && (round==null || round==RoundPing.link(connection) || !trainingInput(packet)))
                ctx.fireChannelRead(packet);
            else ReferenceCountUtil.release(packet);
        },Math.max(0,lastDue-now),TimeUnit.NANOSECONDS);
    }
    private static boolean trainingInput(Object packet){
        return packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundAttackPacket
            || packet instanceof ServerboundUseItemPacket || packet instanceof ServerboundUseItemOnPacket
            || packet instanceof ServerboundPlayerActionPacket || packet instanceof ServerboundSetCarriedItemPacket
            || packet instanceof ServerboundInteractPacket || packet instanceof ServerboundSwingPacket
            || packet instanceof ServerboundContainerClickPacket || packet instanceof ServerboundContainerButtonClickPacket
            || packet instanceof ServerboundSetCreativeModeSlotPacket || packet instanceof ServerboundPlaceRecipePacket
            || packet instanceof ServerboundPlayerInputPacket || packet instanceof ServerboundPlayerCommandPacket
            || packet instanceof ServerboundMoveVehiclePacket;
    }
}
