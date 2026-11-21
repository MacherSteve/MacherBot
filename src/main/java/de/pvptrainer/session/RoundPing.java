package de.pvptrainer.session;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.combat.UhcDefense;

import java.util.ArrayDeque;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** One integrated training session. Each sighting records authoritative state once per server tick. */
public final class RoundPing {
    private static volatile RoundPing active;
    private static final UhcDefense GEOMETRY = new UhcDefense();
    private final ServerPlayer owner;
    private final LivingEntity bot;
    private final int playerMs, botMs;
    private final ArrayDeque<Sight> samples = new ArrayDeque<>();
    private Sight perceived;
    private record Sight(long time, Vec3 pos, AABB box, boolean shield, boolean bow, boolean food, boolean swing) {}

    private RoundPing(ServerPlayer owner, LivingEntity bot, DrillOptions options) {
        this.owner=owner; this.bot=bot; playerMs=options.playerPing(); botMs=options.botPing();
        perceived=sample(System.nanoTime());
    }
    static void start(ServerPlayer owner, LivingEntity bot, DrillOptions options) {
        active=options.playerPing()==0 && options.botPing()==0 ? null : new RoundPing(owner,bot,options);
    }
    static void end(){active=null;}
    public static RoundPing link(Connection connection) {
        var a=active;
        return a!=null && (connection.getReceiving()==PacketFlow.CLIENTBOUND || connection.getPacketListener()==a.owner.connection) ? a : null;
    }
    public int oneWayMicros(){return playerMs*500;}
    private Sight sample(long now) {
        var defense=GEOMETRY;
        return new Sight(now,owner.position(),owner.getBoundingBox(),defense.shieldFaces(bot,owner),defense.aimingBowAt(owner,bot),
            owner.isUsingItem() && owner.getUseItem().has(net.minecraft.core.component.DataComponents.CONSUMABLE),owner.swinging);
    }
    static void observe() {
        var a=active;
        if(a==null || a.botMs==0)return;
        long now=System.nanoTime();
        a.samples.addLast(a.sample(now));
        while(!a.samples.isEmpty() && now-a.samples.getFirst().time()>=a.botMs*1_000_000L)
            a.perceived=a.samples.removeFirst();
    }
    private static Sight sight(ServerPlayer p) {
        var a=active; return a!=null && a.owner==p && a.botMs>0 ? a.perceived : null;
    }
    public static Vec3 position(ServerPlayer p){var s=sight(p);return s==null?p.position():s.pos();}
    public static AABB box(ServerPlayer p){var s=sight(p);return s==null?p.getBoundingBox():s.box();}
    public static Vec3 eyes(ServerPlayer p){return position(p).add(0,p.getEyeHeight(),0);}
    public static boolean shield(ServerPlayer p,boolean now){var s=sight(p);return s==null?now:s.shield();}
    public static boolean bow(ServerPlayer p,boolean now){var s=sight(p);return s==null?now:s.bow();}
    public static boolean food(ServerPlayer p,boolean now){var s=sight(p);return s==null?now:s.food();}
    public static boolean swing(ServerPlayer p){var s=sight(p);return s==null?p.swinging:s.swing();}
}
