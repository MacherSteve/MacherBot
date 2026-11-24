package de.pvptrainer;

import com.mojang.blaze3d.platform.InputConstants;
import de.pvptrainer.analytics.BotArchive;
import de.pvptrainer.analytics.CalibrationPlan;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingPreset;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.UpdateChecker;
import de.pvptrainer.ui.TrainingScreen;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** Zentrale In-Game-Smoke-Suite: Mod-ID, CalibrationPlan-Update, Rundenuebergaenge und Arena-Recovery. */
public class SmokeProbe implements ClientModInitializer {
    static final Drill[] DRILLS = {Drill.SPACING, Drill.OBI_TRAP, Drill.HIT_CRYSTAL, Drill.LOW_WEB_SMASH, Drill.OBI_TRAP};
    static final TrainingSettings.Mode[] MODES = {
        TrainingSettings.Mode.SWORD, TrainingSettings.Mode.UHC, TrainingSettings.Mode.CRYSTAL,
        TrainingSettings.Mode.MACE, TrainingSettings.Mode.UHC
    };

    volatile int scene, target;
    volatile boolean armed, clicked, done;
    int age, phase, charges;
    ResourceKey<Level> originDimension;

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void log(String msg) throws Exception {
        Files.writeString(Path.of("checks.txt"), msg + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    void fail(Throwable t) {
        if (done) return;
        done = true;
        t.printStackTrace();
        try {
            log("FAIL scene=" + scene + " phase=" + phase + " " + t);
        } catch (Exception ignored) {
        }
        Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
    }

    @Override
    public void onInitializeClient() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof WindCharge w && w.getOwner() instanceof net.minecraft.server.level.ServerPlayer) {
                charges++;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done) return;
            if (mc.player == null) { ProbeCompat.dismissWorldConfirm(mc); return; }
            try {
                if (ProbeCompat.screen(mc) != null && ProbeCompat.screen(mc).isPauseScreen()) ProbeCompat.setScreen(mc, null);
                if (!armed || clicked || ProbeCompat.screen(mc) != null) return;
                var bot = mc.level.getEntity(target);
                if (bot == null) return;
                boolean hit = scene == 8;
                boolean use = scene % 2 == 1;
                if (hit) mc.player.lookAt(Anchor.EYES, bot.getBoundingBox().getCenter());
                else mc.player.setXRot(-90);
                if (scene == 7) {
                    int slot = 0;
                    while (slot < 9 && !mc.player.getInventory().getItem(slot).is(Items.WIND_CHARGE)) slot++;
                    check(slot < 9, "no wind charge");
                    mc.player.getInventory().setSelectedSlot(slot);
                }
                ProbeCompat.pick(mc, 1f);
                check(
                    hit ? mc.hitResult instanceof EntityHitResult e && e.getEntity() == bot
                        : mc.hitResult.getType() == HitResult.Type.MISS,
                    "incorrect target " + mc.hitResult
                );
                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(use ? 1 : 0));
                clicked = true;
            } catch (Throwable t) {
                fail(t);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done || server.getPlayerList().getPlayers().isEmpty()) return;
            try {
                var p = server.getPlayerList().getPlayers().getFirst();
                var s = TrainerClient.session(server);
                if (s == null || !p.connection.hasClientLoaded() || p.isChangingDimension()) return;
                if (++age > 160) throw new AssertionError("timeout scene=" + scene + " phase=" + phase + " status=" + s.status());
                if (age < 12) return;

                if (phase == 0) {
                    if (scene == 0) {
                        originDimension = p.level().dimension();
                        var loader = FabricLoader.getInstance();
                        String expectedVersion = System.getProperty("macherbot.expectedVersion", "1.11.9");
                        var meta = loader.getModContainer("macherbot").orElseThrow().getMetadata();
                        check(meta.getId().equals("macherbot") && meta.getVersion().getFriendlyString().equals(expectedVersion),
                            "wrong trainer identity: " + meta.getId() + " " + meta.getVersion().getFriendlyString());
                        check(loader.isModLoaded("pvptrainer"), "legacy provides pvptrainer missing");
                        check(expectedVersion.equals(BotArchive.resolveBuildVersion()),
                            "BotArchive resolved wrong version: " + BotArchive.resolveBuildVersion());
                        log("PASS Fabric ID macherbot " + expectedVersion + ", legacy pvptrainer and BotArchive loaded");

                        // Verify CalibrationPlan fresh install + stale-file update
                        Path gameDir = loader.getGameDir();
                        Path staleTarget = gameDir.resolve("MacherBot-Analysen").resolve(CalibrationPlan.FILENAME);
                        Files.createDirectories(staleTarget.getParent());
                        Files.writeString(staleTarget, "<html>VERALTET</html>", StandardCharsets.UTF_8);
                        Path updated = CalibrationPlan.install(gameDir);
                        String content = Files.readString(updated, StandardCharsets.UTF_8);
                        check(!content.contains("VERALTET") && content.contains("HT1"), "CalibrationPlan did not replace stale file");
                        log("PASS CalibrationPlan replaced stale HT1-Testplan with bundled version");

                        // Verify UpdateChecker offline safety + TrainingScreen header badge rendering
                        check(UpdateChecker.available() == null, "unexpected update available in offline sandbox");
                        var mockUpdate = new UpdateChecker.UpdateInfo(
                            expectedVersion,
                            "99.0.0",
                            URI.create("https://github.com/MacherSteve/MacherBot/releases/tag/v99.0.0"),
                            "Test-Release-Hinweis"
                        );
                        UpdateChecker.setForTesting(mockUpdate);
                        var uiCheck = new java.util.concurrent.CompletableFuture<Boolean>();
                        Minecraft.getInstance().execute(() -> {
                            try {
                                var mc = Minecraft.getInstance();
                                var screen = new TrainingScreen();
                                ProbeCompat.setScreen(mc, screen);
                                boolean foundBadge = screen.children().stream().anyMatch(
                                    child -> child instanceof net.minecraft.client.gui.components.AbstractWidget w
                                        && w.getMessage().getString().contains("Update 99.0.0")
                                );
                                // Verify uniform window size across Start, Statistik, Bot-Analyse, Guide, Kits, and Einstellungen
                                Class<?> panelCls = Class.forName("de.pvptrainer.ui.TrainerPanel");
                                var pwF = panelCls.getDeclaredField("panelWidth");
                                var phF = panelCls.getDeclaredField("panelHeight");
                                pwF.setAccessible(true);
                                phF.setAccessible(true);
                                int baseW = pwF.getInt(screen);
                                int baseH = phF.getInt(screen);
                                String[] childScreenNames = {
                                    "de.pvptrainer.ui.TrainingOptionsScreen",
                                    "de.pvptrainer.ui.ProgressScreen",
                                    "de.pvptrainer.ui.BotAnalysisScreen",
                                    "de.pvptrainer.ui.KnowledgeGuideScreen",
                                    "de.pvptrainer.ui.KitShelfScreen"
                                };
                                boolean uniformSize = true;
                                for (String clsName : childScreenNames) {
                                    Class<?> cls = Class.forName(clsName);
                                    net.minecraft.client.gui.screens.Screen sub;
                                    if (clsName.endsWith("BotAnalysisScreen")) {
                                        var ctor = cls.getDeclaredConstructor(net.minecraft.client.gui.screens.Screen.class, TrainingSettings.Mode.class, String.class);
                                        ctor.setAccessible(true);
                                        sub = (net.minecraft.client.gui.screens.Screen) ctor.newInstance(screen, TrainingSettings.Mode.CRYSTAL, null);
                                    } else if (clsName.endsWith("KnowledgeGuideScreen")) {
                                        var ctor = cls.getDeclaredConstructor(net.minecraft.client.gui.screens.Screen.class, TrainingSettings.Mode.class, Drill.class);
                                        ctor.setAccessible(true);
                                        sub = (net.minecraft.client.gui.screens.Screen) ctor.newInstance(screen, TrainingSettings.Mode.CRYSTAL, Drill.D_TAP);
                                    } else if (clsName.endsWith("KitShelfScreen")) {
                                        var ctor = cls.getDeclaredConstructor(TrainingScreen.class, TrainingSettings.Mode.class, boolean.class);
                                        ctor.setAccessible(true);
                                        sub = (net.minecraft.client.gui.screens.Screen) ctor.newInstance(screen, TrainingSettings.Mode.CRYSTAL, false);
                                    } else if (clsName.endsWith("TrainingOptionsScreen")) {
                                        var ctor = cls.getDeclaredConstructor(TrainingScreen.class, TrainingSettings.Mode.class);
                                        ctor.setAccessible(true);
                                        sub = (net.minecraft.client.gui.screens.Screen) ctor.newInstance(screen, TrainingSettings.Mode.CRYSTAL);
                                    } else {
                                        var ctor = cls.getDeclaredConstructor(net.minecraft.client.gui.screens.Screen.class, TrainingSettings.Mode.class);
                                        ctor.setAccessible(true);
                                        sub = (net.minecraft.client.gui.screens.Screen) ctor.newInstance(screen, TrainingSettings.Mode.CRYSTAL);
                                    }
                                    ProbeCompat.setScreen(mc, sub);
                                    if (pwF.getInt(sub) != baseW || phF.getInt(sub) != baseH) {
                                        uniformSize = false;
                                    }
                                }
                                ProbeCompat.setScreen(mc, null);
                                UpdateChecker.clear();
                                uiCheck.complete(foundBadge && uniformSize);
                            } catch (Throwable err) {
                                UpdateChecker.clear();
                                uiCheck.completeExceptionally(err);
                            }
                        });
                        check(Boolean.TRUE.equals(uiCheck.get(5, java.util.concurrent.TimeUnit.SECONDS)),
                            "TrainingScreen did not render Update 99.0.0 header badge");
                        log("PASS UpdateChecker offline-safe and TrainingScreen update badge verified");
                    }
                    int i = scene / 2;
                    var d = DRILLS[i];
                    var settings = TrainingSettings.defaults(MODES[i]).forDrill(d).withAutoRespawn(false);
                    s.start(server, p.getUUID(), settings, TrainingPreset.BEGINNER.setup(settings, d).options());
                    phase = 1;
                    age = 0;
                    return;
                }

                if (phase == 1) {
                    check(TrainingArena.isArena(p.level()), "player did not enter arena dimension");
                    check(s.status().waitingForStart(), "premature start");
                    var f = s.getClass().getDeclaredField("bot");
                    f.setAccessible(true);
                    target = ((LivingEntity) f.get(s)).getId();
                    armed = true;
                    age = 0;
                    phase = 2;
                    return;
                }

                if (phase == 2) {
                    if (!clicked || s.status().waitingForStart()) return;
                    if (scene == 7) check(charges == 1, "first use did not launch exactly one wind charge: " + charges);
                    if (scene == 8) check(s.status().hits() == 1 && s.status().obiTimer().ticks() > 0, "first attack lost or timer stopped");
                    log("PASS " + MODES[scene / 2] + " " + DRILLS[scene / 2] + " " + (scene % 2 == 0 ? "left" : "right")
                        + (scene == 8 ? " first hit + timer" : scene == 7 ? " air, first use threw one wind charge" : " air"));
                    armed = false;
                    clicked = false;
                    s.stop(server);
                    phase = 3;
                    age = 0;
                    return;
                }

                if (phase == 3) {
                    // Verify clean arena exit, gravity reset, and deleted return/inventory journals
                    check(!TrainingArena.isArena(p.level()), "player remained in arena after stop");
                    check(p.level().dimension().equals(originDimension), "wrong return dimension: " + p.level().dimension());
                    check(!p.isNoGravity(), "noGravity still true after leaving arena");
                    Path worldRoot = server.getWorldPath(LevelResource.ROOT);
                    Path retFile = worldRoot.resolve("pvptrainer").resolve("return-" + p.getUUID() + ".dat");
                    Path invFile = worldRoot.resolve("pvptrainer/inventory").resolve(p.getUUID() + ".json");
                    check(!Files.exists(retFile), "return point file leaked: " + retFile);
                    check(!Files.exists(invFile), "inventory backup journal leaked: " + invFile);
                    if (scene == 8) {
                        log("PASS Arena recovery verified (dimension, gravity, return-point and inventory journal clean)");
                        log("PASS complete");
                        done = true;
                        Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                        return;
                    }
                    scene++;
                    phase = 0;
                    age = 0;
                }
            } catch (Throwable t) {
                fail(t);
            }
        });
    }
}
