package de.pvptrainer.drill;

import net.minecraft.server.level.ServerPlayer;

/** Peak above the last standing surface for a single self-boost. */
public final class WindHeight {
    public static final double PERFECT = 12.0, NEAR = 9.0;
    private double floor, peak, lastY;
    private int launchTick, settledTicks;
    private boolean measuring, leftGround, invalid;

    public WindHeight(double floor) { this.floor = lastY = floor; }

    public void launched(ServerPlayer player, int tick) {
        if (measuring) { invalid = true; return; }
        if (player.getY() - floor > 1.5 || player.isFallFlying()) return;
        measuring = true;
        leftGround = !player.onGround();
        invalid = false;
        settledTicks = 0;
        launchTick = tick;
        lastY = player.getY();
        peak = Math.max(0, lastY - floor);
    }

    public boolean sample(ServerPlayer player, int tick) {
        double y = player.getY();
        if (!measuring) {
            if (player.onGround()) floor = y;
            return false;
        }
        if (player.isFallFlying() || Math.abs(y - lastY) > 3) invalid = true;
        lastY = y;
        if (!invalid) peak = Math.max(peak, y - floor);
        if (!player.onGround()) { leftGround = true; settledTicks = 0; }
        else if (++settledTicks >= 2 && (leftGround || tick - launchTick >= 6)) {
            measuring = false;
            floor = y;
            return !invalid;
        }
        return false;
    }

    public double meters() { return peak; }
    public int duration(int tick) { return tick - launchTick; }
    public static int color(double meters) {
        return meters >= PERFECT ? 0xff58e776 : meters >= NEAR ? 0xffffa238 : 0xffff5360;
    }
    public static String text(double meters) { return String.format(java.util.Locale.GERMANY, "%.2f m", meters); }
}
