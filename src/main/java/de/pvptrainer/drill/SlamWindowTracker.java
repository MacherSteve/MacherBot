package de.pvptrainer.drill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** Verwaltet Schild-Stun-Fenster, STUN_WEB-Doppelklick und STUN_SLAM-Landungs-/Aufprallpruefung. */
final class SlamWindowTracker {
    private static final int NEVER = -100000;

    private int stunUntil = NEVER;
    private int axeStunAt = NEVER;
    private boolean stunTargetLifted;
    private int firstGuardClick = NEVER;
    private long firstGuardSequence = -2;
    private boolean slamWasAirborne;
    private int supportedTicks;
    private int wallImpactAt = NEVER;
    private double previousSlamY = Double.NaN;
    private double recentPeakFall;
    private int recentFallTick = NEVER;

    void observeFall(int tick, double fallDistance) {
        if (fallDistance > 1.5) {
            if (tick != recentFallTick || fallDistance > recentPeakFall) {
                recentPeakFall = fallDistance;
            }
            recentFallTick = tick;
        }
    }

    boolean recentlyFell(int tick) {
        return tick - recentFallTick <= 3 && recentPeakFall > 1.5;
    }

    float doubleClickDisable(Drill drill, boolean done, int tick, long sequence, float axeSeconds) {
        if (drill != Drill.STUN_WEB || done || sequence <= 0) return 0;
        if (sequence == firstGuardSequence) return 0;
        if (sequence == firstGuardSequence + 1 && tick - firstGuardClick <= 4 && axeSeconds > 0) {
            firstGuardSequence = -2;
            return axeSeconds;
        }
        firstGuardClick = tick;
        firstGuardSequence = sequence;
        return 0;
    }

    boolean shieldWindowOpen(int tick) {
        return tick < stunUntil;
    }

    int stunUntil() {
        return stunUntil;
    }

    int axeStunAt() {
        return axeStunAt;
    }

    void shieldStun(DrillAttempt attempt, Drill drill, int tick, int durationTicks) {
        stunUntil = tick + durationTicks;
        axeStunAt = tick;
        stunTargetLifted = false;
        if (drill == Drill.STUN_SLAM || drill == Drill.CORNER_WEB_DTAP || drill == Drill.SHIELD_TRIPLE_TAP) {
            attempt.clearTimingFault();
        }
        attempt.step("Schild deaktiviert für " + durationTicks + " Ticks");
    }

    void axeContact(DrillAttempt attempt, int tick, boolean targetBlocking, int blockDelayRemaining) {
        if (attempt.done() || axeStunAt == tick || tick < stunUntil) return;
        if (targetBlocking) {
            attempt.timingFault("Axt traf die ungedeckte Seite · kein Schild-Stun");
        } else if (blockDelayRemaining > 0) {
            attempt.timingFault("Axt " + ticksText(blockDelayRemaining) + " zu früh · Schild noch im Hochheben");
        } else {
            attempt.timingFault("Axt traf bei gesenktem Schild · kein Stun");
        }
    }

    void maceContact(DrillAttempt attempt, int tick, double fallDistance, boolean onGround, boolean isSmash) {
        if (attempt.done()) return;
        if (axeStunAt == NEVER) {
            attempt.timingFault("Mace zu früh · vor dem Axt-Stun");
        } else if (tick >= stunUntil) {
            attempt.timingFault("Mace zu spät · Schildfenster seit " + ticksText(tick - stunUntil) + " geschlossen");
        } else if (onGround) {
            attempt.timingFault("Mace zu spät · Bodenkontakt " + ticksText(tick - axeStunAt) + " nach der Axt");
        } else if (!isSmash && fallDistance <= 1.5) {
            attempt.timingFault("Mace zu früh · erst " + DrillAttempt.decimal(fallDistance) + " Bl. Fall, benötigt sind mehr als 1,5");
        } else {
            attempt.timingFault("Mace " + ticksText(tick - axeStunAt) + " nach der Axt · kein wirksamer Smash");
        }
    }

    String slamFailure() {
        return axeStunAt == NEVER
            ? "Axt-Stun fehlt · frontal auf das erhobene Schild schlagen"
            : "Nach dem Axt-Stun bei T+" + axeStunAt + " fehlt ein wirksamer Mace-Smash";
    }

    void slamWallImpact(boolean done, int tick) {
        if (!done && wallImpactAt == NEVER) {
            wallImpactAt = tick;
        }
    }

    void observeSlamLanding(DrillAttempt attempt, int tick, ServerPlayer player) {
        if (attempt.done()) return;
        observeFall(tick, player.fallDistance);
        AABB body = player.getBoundingBox();
        AABB feet = new AABB(body.minX, body.minY - 0.002, body.minZ, body.maxX, body.minY, body.maxZ);
        boolean supported = player.level().getBlockCollisions(player, feet).iterator().hasNext();
        double y = player.getY();
        boolean rising = y > previousSlamY + 0.001 || player.getDeltaMovement().y > 0.01;
        previousSlamY = y;
        if (!supported) slamWasAirborne = true;
        if (rising) wallImpactAt = NEVER;
        boolean settled = supported && (player.onGround() || player.verticalCollisionBelow) && !rising;
        supportedTicks = settled && slamWasAirborne ? supportedTicks + 1 : 0;
        // Give a short grace window after an axe stun so landing on the follow-up tick (e.g. from fast/odd fall heights)
        // never cancels the attempt before the mace packet lands.
        boolean inFreshStunGrace = axeStunAt != NEVER && tick < stunUntil && tick - axeStunAt <= 6;
        int settleThreshold = axeStunAt != NEVER ? 5 : 3;
        if (!inFreshStunGrace && supportedTicks >= settleThreshold) {
            slamLanded(attempt, tick);
        } else if (!inFreshStunGrace && wallImpactAt != NEVER && tick - wallImpactAt >= 3) {
            attempt.finish(false, "Stun Slam: Aufprall ohne Auftrieb");
        }
    }

    void slamLanded(DrillAttempt attempt, int tick) {
        if (axeStunAt != NEVER && attempt.timingHint() == null) {
            attempt.timingFault("Mace-Smash zu spät · Landung " + ticksText(tick - axeStunAt) + " nach der Axt");
        }
        attempt.finish(false, "Stun Slam: Landung");
    }

    boolean unusedSlamWindowExpired(int tick) {
        return tick >= stunUntil && !slamWasAirborne && wallImpactAt == NEVER;
    }

    boolean tickStunWeb(DrillAttempt attempt, int tick, LivingEntity bot) {
        if (axeStunAt == NEVER) return false;
        if (!bot.onGround()) stunTargetLifted = true;
        if (bot.onGround() && (stunTargetLifted || tick - axeStunAt > 1)) {
            attempt.finish(false, "Der Bot hat den Boden vor dem Netz berührt.");
            return true;
        }
        if (tick >= stunUntil) {
            attempt.finish(false, "Das Schild ist wieder aktiv.");
            return true;
        }
        return false;
    }

    static String ticksText(int ticks) {
        return ticks == 1 ? "1 Tick" : ticks + " Ticks";
    }
}
