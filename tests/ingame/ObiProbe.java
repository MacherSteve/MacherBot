package de.pvptrainer;

import com.mojang.blaze3d.platform.InputConstants;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.ObsidianEnclosure;
import de.pvptrainer.kits.TrainingPreset;
import de.pvptrainer.kits.TrainingSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;

/** In-Game-Regressionsprobe fuer OBI_TRAP: Klickstart, Timer, Besitzpruefung und ObsidianEnclosure-Geometrie. */
public final class ObiProbe implements ClientModInitializer {
    volatile int target;
    volatile boolean armed, clicked, done;
    int phase, age;

    static void log(String msg) throws Exception {
        Files.writeString(Path.of("checks.txt"), msg + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void expect(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    static Object field(Object obj, String name) throws Exception {
        var f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
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
            try {
                if (ProbeCompat.screen(mc) != null && ProbeCompat.screen(mc).isPauseScreen()) {
                    ProbeCompat.setScreen(mc, null);
                }
                if (!armed || clicked || ProbeCompat.screen(mc) != null) return;
                var bot = mc.level.getEntity(target);
                if (bot == null) return;
                mc.player.lookAt(Anchor.EYES, bot.getBoundingBox().getCenter());
                ProbeCompat.pick(mc, 1.0F);
                expect(
                    mc.hitResult instanceof EntityHitResult e && e.getEntity() == bot,
                    "ray missed bot; distance=" + mc.player.distanceTo(bot) + " result=" + mc.hitResult
                );
                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(0));
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
                if (++age > 160) {
                    throw new AssertionError("timeout phase=" + phase + " clicked=" + clicked + " status=" + s.status());
                }
                if (age < 12) return;

                if (phase == 0) {
                    var drill = Drill.OBI_TRAP;
                    var settings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(drill).withAutoRespawn(false);
                    s.start(server, p.getUUID(), settings, TrainingPreset.BEGINNER.setup(settings, drill).options());
                    phase = 1;
                    age = 0;
                    return;
                }

                if (phase == 1) {
                    expect(TrainingArena.isArena(p.level()), "player did not enter arena");
                    expect(s.status().waitingForStart(), "not waiting before start");
                    expect(s.status().obiTimer() != null && s.status().obiTimer().ticks() == 0, "obiTimer not at zero while waiting");
                    var bot = (LivingEntity) field(s, "bot");
                    target = bot.getId();
                    armed = true;
                    phase = 2;
                    age = 0;
                    return;
                }

                if (phase == 2) {
                    if (!clicked || s.status().waitingForStart()) return;
                    expect(s.status().hits() == 1 && s.status().obiTimer().ticks() > 0, "first hit lost or timer stopped: " + s.status());
                    log("PASS first left click on bot starts OBI_TRAP, deals 1 hit and starts obiTimer");

                    var bot = (LivingEntity) field(s, "bot");
                    BlockPos feet = bot.blockPosition();
                    var world = p.level();
                    var attempt = (DrillAttempt) field(s, "attempt");

                    // Clear surrounding area and build test enclosure
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        for (int y = 0; y <= 3; y++) {
                            world.setBlockAndUpdate(feet.relative(dir).above(y), Blocks.AIR.defaultBlockState());
                        }
                    }
                    world.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                    world.setBlockAndUpdate(feet.above(2), Blocks.GLASS.defaultBlockState());
                    world.setBlockAndUpdate(feet, Blocks.OBSIDIAN.defaultBlockState());
                    world.setBlockAndUpdate(feet.above(), Blocks.OBSIDIAN.defaultBlockState());
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        world.setBlockAndUpdate(feet.relative(dir), Blocks.OAK_PLANKS.defaultBlockState());
                    }

                    // Negative 1: unowned arena obsidian must not win
                    attempt.checkObsidianTrap(world, feet);
                    expect(!attempt.done(), "unowned obsidian must not complete drill");

                    // Mark obsidian as player-placed
                    attempt.trapBlockPlaced(feet);
                    attempt.trapBlockPlaced(feet.above());

                    // Negative 2: crawl exit through cobweb
                    world.setBlockAndUpdate(feet.east(), Blocks.COBWEB.defaultBlockState());
                    attempt.checkObsidianTrap(world, feet);
                    expect(!attempt.done(), "crawl exit via cobweb must not complete drill");

                    // Negative 3: jumpable 1-high wall without roof
                    world.setBlockAndUpdate(feet.east(), Blocks.STONE.defaultBlockState());
                    world.setBlockAndUpdate(feet.above(2), Blocks.AIR.defaultBlockState());
                    attempt.checkObsidianTrap(world, feet);
                    expect(!attempt.done(), "jumpable one-high wall must not complete drill");

                    // Positive 1: two-high walls without roof are closed
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        world.setBlockAndUpdate(feet.relative(dir).above(), Blocks.STONE.defaultBlockState());
                    }
                    expect(ObsidianEnclosure.closed(world, feet), "two-high walls without roof must be closed");

                    // Negative 4: missing floor
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        world.setBlockAndUpdate(feet.relative(dir).above(), Blocks.AIR.defaultBlockState());
                    }
                    world.setBlockAndUpdate(feet.above(2), Blocks.GLASS.defaultBlockState());
                    world.setBlockAndUpdate(feet.below(), Blocks.AIR.defaultBlockState());
                    attempt.checkObsidianTrap(world, feet);
                    expect(!attempt.done(), "open floor must not complete drill");

                    // Positive 2: sealed feet row + capped headroom + solid floor + owned obsidian wins
                    world.setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                    attempt.checkObsidianTrap(world, feet);
                    expect(attempt.won(), "sealed feet row with capped headroom must win");

                    log("PASS ObsidianEnclosure accepts capped feet row and two-high open-roof walls; rejects unowned obsidian, crawl exit, jump exit and open floor");
                    armed = false;
                    s.stop(server);
                    phase = 3;
                    age = 0;
                    return;
                }

                if (phase == 3) {
                    expect(!TrainingArena.isArena(p.level()), "player remained in arena after stop");
                    expect(!p.isNoGravity(), "noGravity still true after stop");
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
