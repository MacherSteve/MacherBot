package de.pvptrainer;

import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingKit;
import de.pvptrainer.kits.TrainingPreset;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.session.TrainingResources;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.session.TrainingStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/** In-Game-Regressionsprobe fuer Crash-/Abbruch-Recovery: return-<uuid>.dat, missingReturn-Fallback und Inventar-Journal. */
public final class RecoveryProbe implements ClientModInitializer {
    private static final String MARKER_NAME = "Welt-Marker-Schwert";
    private static final int MARKER_SLOT = 4;
    private static final int EMERALD_SLOT = 5;
    private static final int EMERALD_COUNT = 7;

    volatile boolean done;
    int phase, age;
    ResourceKey<Level> originDimension;
    Vec3 originPos;
    boolean recoveredA, recoveredB;

    static void log(String msg) throws Exception {
        Files.writeString(Path.of("checks.txt"), msg + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void expect(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    static Path returnFile(MinecraftServer server, ServerPlayer player) {
        return server.getWorldPath(LevelResource.ROOT)
            .resolve("pvptrainer")
            .resolve("return-" + player.getUUID() + ".dat");
    }

    static Path inventoryFile(MinecraftServer server, ServerPlayer player) {
        return server.getWorldPath(LevelResource.ROOT)
            .resolve("pvptrainer/inventory")
            .resolve(player.getUUID() + ".json");
    }

    static void seedMarkerInventory(ServerPlayer player) {
        var inv = player.getInventory();
        inv.clearContent();
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal(MARKER_NAME));
        inv.setItem(MARKER_SLOT, sword);
        inv.setItem(EMERALD_SLOT, new ItemStack(Items.EMERALD, EMERALD_COUNT));
        inv.setSelectedSlot(MARKER_SLOT);
        inv.setChanged();
        player.inventoryMenu.broadcastFullState();
    }

    static boolean hasMarkerInventory(ServerPlayer player) {
        var inv = player.getInventory();
        ItemStack sword = inv.getItem(MARKER_SLOT);
        ItemStack emeralds = inv.getItem(EMERALD_SLOT);
        Component name = sword.get(DataComponents.CUSTOM_NAME);
        return sword.is(Items.DIAMOND_SWORD)
            && name != null
            && MARKER_NAME.equals(name.getString())
            && emeralds.is(Items.EMERALD)
            && emeralds.getCount() == EMERALD_COUNT;
    }

    static void clearActiveKit(UUID playerId) throws Exception {
        var activeField = TrainingKit.class.getDeclaredField("ACTIVE");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<UUID, ?> active = (Map<UUID, ?>) activeField.get(null);
        active.remove(playerId);
    }

    /** Simuliert einen abrupten Spielabsturz: In-Memory-Zustand von Session und Kit ist weg, Dateien und Spieler bleiben in der Arena. */
    static void simulateAbruptCrash(TrainingSession session, ServerPlayer player) throws Exception {
        var endResources = TrainingResources.class.getDeclaredMethod("end", UUID.class);
        endResources.setAccessible(true);
        endResources.invoke(null, player.getUUID());

        var releaseBot = TrainingSession.class.getDeclaredMethod("releaseBot");
        releaseBot.setAccessible(true);
        releaseBot.invoke(session);

        for (String fieldName : new String[]{"ownerId", "owner", "arena", "suspendedAt", "settings", "options", "attempt"}) {
            var f = TrainingSession.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(session, null);
        }
        var waitingField = TrainingSession.class.getDeclaredField("waitingForStart");
        waitingField.setAccessible(true);
        waitingField.setBoolean(session, false);

        var snapshotField = TrainingSession.class.getDeclaredField("snapshot");
        snapshotField.setAccessible(true);
        snapshotField.set(session, TrainingStatus.idle());

        clearActiveKit(player.getUUID());
    }

    void fail(Throwable t) {
        if (done) return;
        done = true;
        t.printStackTrace();
        try {
            log("FAIL phase=" + phase + " " + t);
        } catch (Exception ignored) {
        }
        Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done) return;
            if (mc.player == null) { ProbeCompat.dismissWorldConfirm(mc); return; }
            if (ProbeCompat.screen(mc) != null && ProbeCompat.screen(mc).isPauseScreen()) {
                ProbeCompat.setScreen(mc, null);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done || server.getPlayerList().getPlayers().isEmpty()) return;
            try {
                var p = server.getPlayerList().getPlayers().getFirst();
                var s = TrainerClient.session(server);
                if (s == null || !p.connection.hasClientLoaded() || p.isChangingDimension()) return;
                if (++age > 160) {
                    throw new AssertionError("timeout phase=" + phase + " inArena=" + TrainingArena.isArena(p.level()));
                }
                if (age < 12) return;

                Path retFile = returnFile(server, p);
                Path invFile = inventoryFile(server, p);

                if (phase == 0) {
                    expect(!TrainingArena.isArena(p.level()), "player unexpectedly started in arena");
                    p.setNoGravity(false);
                    seedMarkerInventory(p);
                    expect(hasMarkerInventory(p), "failed to seed world marker inventory");
                    originDimension = p.level().dimension();
                    originPos = p.position();

                    var drill = Drill.LOW_WEB_SMASH;
                    var settings = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(drill).withAutoRespawn(false);
                    s.start(server, p.getUUID(), settings, TrainingPreset.BEGINNER.setup(settings, drill).options());
                    phase = 1;
                    age = 0;
                    return;
                }

                if (phase == 1) {
                    expect(TrainingArena.isArena(p.level()), "Case A: player did not enter arena");
                    expect(p.isNoGravity(), "Case A: expected noGravity=true while suspended in air-start arena");
                    expect(Files.exists(retFile), "Case A: return-<uuid>.dat missing");
                    expect(Files.exists(invFile), "Case A: inventory journal missing");
                    expect(TrainingKit.active(p), "Case A: TrainingKit not active");
                    expect(!hasMarkerInventory(p), "Case A: world marker inventory was not replaced by training kit");

                    simulateAbruptCrash(s, p);
                    expect(!TrainingKit.active(p), "Case A: active kit not cleared during crash simulation");
                    recoveredA = TrainingArena.recoverPending(p);
                    phase = 2;
                    age = 0;
                    return;
                }

                if (phase == 2) {
                    expect(recoveredA, "Case A: recoverPending returned false");
                    expect(!TrainingArena.isArena(p.level()), "Case A: player remained in arena");
                    expect(p.level().dimension().equals(originDimension), "Case A: wrong return dimension " + p.level().dimension());
                    expect(p.position().distanceTo(originPos) < 0.05, "Case A: return position drifted " + p.position() + " vs " + originPos);
                    expect(!p.isNoGravity(), "Case A: noGravity still true after crash recovery");
                    expect(p.getInventory().getSelectedSlot() == MARKER_SLOT, "Case A: selected slot not restored");
                    expect(hasMarkerInventory(p), "Case A: world marker inventory lost during crash recovery");
                    expect(!Files.exists(retFile), "Case A: return-<uuid>.dat leaked");
                    expect(!Files.exists(invFile), "Case A: inventory journal leaked");
                    log("PASS Case A: Crash recovery with return-<uuid>.dat restored dimension, exact position, gravity and world inventory");

                    // Start Case B (missing return-<uuid>.dat while stranded in arena)
                    var drill = Drill.LOW_WEB_SMASH;
                    var settings = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(drill).withAutoRespawn(false);
                    s.start(server, p.getUUID(), settings, TrainingPreset.BEGINNER.setup(settings, drill).options());
                    phase = 3;
                    age = 0;
                    return;
                }

                if (phase == 3) {
                    expect(TrainingArena.isArena(p.level()), "Case B: player did not enter arena");
                    expect(p.isNoGravity(), "Case B: expected noGravity=true in arena");
                    expect(Files.exists(retFile), "Case B: return-<uuid>.dat missing before deletion");
                    expect(Files.exists(invFile), "Case B: inventory journal missing");
                    expect(!hasMarkerInventory(p), "Case B: world marker inventory was not replaced");

                    simulateAbruptCrash(s, p);
                    Files.delete(retFile);
                    expect(!TrainingArena.hasReturnPoint(p), "Case B: return point still exists after delete");
                    recoveredB = TrainingArena.recoverPending(p);
                    phase = 4;
                    age = 0;
                    return;
                }

                if (phase == 4) {
                    expect(recoveredB, "Case B: missingReturn fallback returned false");
                    expect(!TrainingArena.isArena(p.level()), "Case B: player trapped in arena despite missingReturn fallback");
                    expect(!p.isNoGravity(), "Case B: noGravity still true after missingReturn fallback");
                    expect(hasMarkerInventory(p), "Case B: world marker inventory lost in missingReturn fallback");
                    expect(!Files.exists(retFile), "Case B: return file unexpectedly present");
                    expect(!Files.exists(invFile), "Case B: inventory journal leaked after missingReturn fallback");
                    log("PASS Case B: Missing return-<uuid>.dat fallback escaped arena to world spawn, cleared noGravity and restored world inventory");

                    // Case C: Orphaned inventory journal in Overworld without return point
                    TrainingKit.begin(p, TrainingSettings.Mode.SWORD);
                    expect(Files.exists(invFile), "Case C: inventory journal was not written");
                    expect(!Files.exists(retFile), "Case C: unexpected return file");
                    expect(!hasMarkerInventory(p), "Case C: kit did not replace marker inventory");

                    clearActiveKit(p.getUUID());
                    TrainingKit.recover(p);
                    expect(hasMarkerInventory(p), "Case C: orphaned inventory journal did not restore world inventory");
                    expect(!Files.exists(invFile), "Case C: orphaned inventory journal leaked");
                    log("PASS Case C: Orphaned inventory journal in Overworld restored world inventory and cleaned up");

                    log("PASS complete");
                    done = true;
                    Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                }
            } catch (Throwable t) {
                fail(t);
            }
        });
    }
}
