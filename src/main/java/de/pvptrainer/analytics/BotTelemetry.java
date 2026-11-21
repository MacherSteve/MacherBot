package de.pvptrainer.analytics;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.TrainerClient;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.kits.TrainingSettings;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/** Raw observations from one active arena round. All times are relative server ticks. */
public final class BotTelemetry {
    public record Window(int start, int end, int hit) {}
    public record Action(int tick, String kind) {}
    public record Snapshot(int ticks, List<Window> windows, List<Action> actions, boolean truncated) {
        public Snapshot {
            windows = windows == null ? List.of() : List.copyOf(windows);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    private static final int LIMIT = 4096;
    private final int startTick;
    private final List<Window> windows = new ArrayList<>();
    private final List<Action> actions = new ArrayList<>();
    private int opened = -1, lastInside = -1, firstHit = -1;
    private boolean truncated;

    public BotTelemetry(int startTick) { this.startTick = startTick; }

    public static boolean eligible(TrainingSettings settings, DrillOptions options) {
        return options.drill() == Drill.RETOTEM || options.drill() == Drill.DUEL && settings.counterAttacks();
    }

    public static void action(ServerLevel level, LivingEntity actor, String kind) {
        TrainingSession session = TrainerClient.session(level.getServer());
        if (session != null) session.observeBotAction(actor, kind);
    }

    public void observe(int serverTick, boolean inside) {
        int tick = serverTick - startTick;
        if (inside) {
            if (opened < 0) {
                if (windows.size() == LIMIT) { truncated = true; return; }
                opened = tick;
            }
            lastInside = tick;
        } else if (opened >= 0) {
            windows.add(new Window(opened, lastInside, firstHit));
            opened = firstHit = -1;
        }
    }

    public void recordAction(int serverTick, String kind) {
        if (actions.size() < LIMIT) actions.add(new Action(serverTick - startTick, kind));
        else truncated = true;
    }

    public void recordDamage(int serverTick, boolean inside) {
        observe(serverTick, inside);
        if (opened >= 0 && firstHit < 0) firstHit = serverTick - startTick;
    }

    public Snapshot snapshot(int serverTick) {
        var result = new ArrayList<>(windows);
        if (opened >= 0) result.add(new Window(opened, firstHit < 0 ? -1 : lastInside, firstHit));
        return new Snapshot(serverTick - startTick, result, actions, truncated);
    }
}
