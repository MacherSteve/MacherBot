package de.pvptrainer.session;

import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.progress.TrainingProgress;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Flug-, Landungs- und Serien-Zustandsautomat fuer den Cobweb-Smash-Drill (LOW_WEB_SMASH). */
public final class CobwebFlightTracker {
    private boolean chainActive;
    private boolean windPrimed;
    private boolean windAirborne;
    private boolean flightAttempted;
    private boolean flightJudged;
    private int launchTick;
    private int landingTick = -1;
    private int lastSmashTick = -1000;
    private double landingY;

    public static int settleTicks(int playerPingMs, int tickRate) {
        return 3 + (int) Math.ceil(playerPingMs * tickRate / 1000.0);
    }

    public boolean hasPendingLanding() {
        return landingTick >= 0;
    }

    public boolean isFlightJudged() {
        return flightJudged;
    }

    public boolean isChainActive() {
        return chainActive;
    }

    public void onPlayerLaunch(int roundTick, boolean onGround, Runnable finishLanding) {
        if (landingTick >= 0) {
            finishLanding.run();
        }
        windPrimed = true;
        windAirborne = !onGround;
        launchTick = roundTick;
    }

    public void onScored(boolean won, int roundTick) {
        flightJudged = true;
        if (won) {
            chainActive = true;
            lastSmashTick = roundTick;
            landingTick = -1;
        }
    }

    public boolean shouldRecordMissOnLanding(boolean attemptDone) {
        return flightAttempted && !flightJudged && !attemptDone;
    }

    public void observeGround(
        boolean grounded,
        int roundTick,
        ServerPlayer owner,
        DrillOptions options,
        Runnable finishLanding
    ) {
        boolean supported = grounded && feetSupported(owner);
        int settle = settleTicks(options.playerPing(), options.tickRate());
        if (landingTick >= 0) {
            // A near-floor smash can send a ground packet before the Wind Burst motion arrives.
            if (!supported && chainActive && lastSmashTick >= landingTick - settle && owner.getY() > landingY + 0.02) {
                landingTick = -1;
            } else if (roundTick - landingTick >= settle) {
                finishLanding.run();
            }
        }
        if (!supported) {
            if (windPrimed) windAirborne = true;
            if (windAirborne || owner.fallDistance > 1.5 && owner.getMainHandItem().is(Items.MACE)) {
                flightAttempted = true;
            }
        } else if (landingTick < 0 && (flightAttempted || flightJudged
            || windPrimed && (windAirborne || roundTick - launchTick > 8))) {
            landingTick = roundTick;
            landingY = owner.getY();
        }
    }

    public void endChain(UUID ownerId, String historyKey) {
        landingTick = -1;
        lastSmashTick = -1000;
        windPrimed = false;
        windAirborne = false;
        flightAttempted = false;
        flightJudged = false;
        if (!chainActive) return;
        TrainingProgress.endAirChain(ownerId, historyKey);
        chainActive = false;
    }

    private static boolean feetSupported(ServerPlayer owner) {
        AABB box = owner.getBoundingBox();
        AABB feet = new AABB(box.minX + 0.0001, box.minY - 0.002, box.minZ + 0.0001,
            box.maxX - 0.0001, box.minY, box.maxZ - 0.0001);
        return owner.level().getBlockCollisions(owner, feet).iterator().hasNext();
    }
}
