package de.pvptrainer.drill;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Bewertungszustand fuer Totem-Verteidigungsdrills (RETOTEM und DOUBLE_HAND). */
final class TotemDefenseTracker {
    private static final int NEVER = -100000;

    private int popAt = NEVER;
    private int playerPops;
    private int refillTotal;
    private boolean protectionSeen;
    private boolean offhandReady;

    boolean readyForDefense(Drill drill, ServerPlayer player) {
        if (drill != Drill.RETOTEM && popAt == NEVER) {
            boolean mainHand = player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
            boolean offHand = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
            return offHand && (drill != Drill.DOUBLE_HAND || mainHand);
        }
        return true;
    }

    int playerPops() {
        return playerPops;
    }

    String startRefill(int totalSupply) {
        this.refillTotal = totalSupply;
        return refillProgress();
    }

    String refillProgress() {
        return playerPops + "/" + refillTotal + " Totems verbraucht";
    }

    void playerPop(DrillAttempt attempt, int remainingSupply) {
        if (attempt.done()) return;
        playerPops++;
        refillTotal = Math.max(refillTotal, playerPops + remainingSupply);
        if (playerPops == refillTotal && remainingSupply == 0) {
            attempt.finish(true, refillProgress());
        } else {
            attempt.step(refillProgress());
        }
    }

    void playerPop(DrillAttempt attempt) {
        if (attempt.done()) return;
        playerPops++;
        attempt.event("Eigenes Totem ausgelöst");
    }

    void defenseBlast(
        DrillAttempt attempt,
        Drill drill,
        int tick,
        boolean mainHandTotem,
        boolean offHandTotem,
        boolean popped,
        float damage,
        boolean survived,
        boolean finalBlast
    ) {
        if (drill != Drill.DOUBLE_HAND || attempt.done() || !survived || (!popped && damage <= 0.0F)) {
            return;
        }
        if (popAt != NEVER) {
            boolean protectedHand = mainHandTotem || offHandTotem;
            if (!finalBlast) {
                attempt.step(
                    protectedHand
                        ? "Folgetreffer abgefangen · letzter Kristall offen"
                        : "Zwischentreffer überlebt · vor dem letzten Kristall ein Totem bereithalten"
                );
            } else if (protectedHand) {
                attempt.finish(true, "Folgekristall mit Totemschutz überlebt");
            } else {
                attempt.finish(false, "Totem beim letzten Folgetreffer fehlte");
            }
        } else if (popped) {
            if (mainHandTotem && offHandTotem) {
                popAt = tick;
                attempt.step("Erster Pop mit beiden Händen · Folgekristall offen");
            } else {
                attempt.finish(false, "Beim ersten Pop fehlte ein Totem in einer Hand");
            }
        }
    }

    void tickDoubleHand(DrillAttempt attempt, int tick, ServerPlayer player) {
        if (popAt == NEVER) return;
        boolean mainHand = player.getMainHandItem().is(Items.TOTEM_OF_UNDYING);
        boolean offHand = player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        if (!protectionSeen && (mainHand || offHand)) {
            protectionSeen = true;
            attempt.event("Erneut geschützt nach " + (tick - popAt) + " Ticks");
        }
        if (!offhandReady && offHand) {
            offhandReady = true;
            attempt.step("Nebenhand bereit nach " + (tick - popAt) + " Ticks");
        }
    }
}
