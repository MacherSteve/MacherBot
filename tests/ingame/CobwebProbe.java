package de.pvptrainer;

import com.google.gson.JsonParser;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.TrainingProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** In-Game-Regressionstest fuer Cobweb-Smash: Wind-Burst-Flugserie, Landung, Schadensimmunitaet und Gegenprobe. */
public class CobwebProbe implements ClientModInitializer {
    boolean done;
    volatile boolean landedBetween;
    int phase, age, clicks, flyAge;
    volatile int botId, flight;
    double ground;

    static Object field(Object o, String name) throws Exception {
        var f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

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
            log("FAIL phase=" + phase + " cause=" + t);
        } catch (Exception ignored) {
        }
        Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
    }

    void next() {
        phase++;
        age = 0;
    }

    static void attack(ServerPlayer p, LivingEntity bot, double height, boolean groundFlag, boolean smash, ItemStack mace) {
        p.teleportTo(p.level(), bot.getX(), bot.getY() + height, bot.getZ() - 1.6, Set.of(), 0, 0, true);
        p.setOnGround(groundFlag);
        p.setDeltaMovement(new Vec3(0, -.2, 0));
        p.fallDistance = smash ? 4 : 0;
        p.lookAt(Anchor.EYES, bot.position().add(0, .25, 0));
        p.setItemInHand(InteractionHand.MAIN_HAND, mace);
        bot.invulnerableTime = 20;
        try {
            var last = LivingEntity.class.getDeclaredField("lastHurt");
            last.setAccessible(true);
            last.setFloat(bot, 1000f);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        p.connection.handleAttack(new ServerboundAttackPacket(bot.getId()));
    }

    static List<String> outcomes(ServerPlayer p, String key) throws Exception {
        var path = Path.of("config", "pvptrainer-progress", p.getUUID() + ".jsonl");
        if (!Files.exists(path)) return List.of();
        return Files.readAllLines(path).stream()
            .map(l -> JsonParser.parseString(l).getAsJsonObject())
            .filter(o -> o.get("setup").getAsString().equals(key))
            .map(o -> o.get("reason").getAsString())
            .toList();
    }

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done || mc.player == null) return;
            try {
                if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) mc.gui.setScreen(null);
                if (flight == 0 || mc.gui.screen() != null) return;
                var p = mc.player;
                var bot = mc.level.getEntity(botId);
                if (bot == null) return;
                if (flight == 1) {
                    if (!p.onGround() || Math.abs(p.distanceTo(bot) - 1.55) > .15) return;
                    int slot = 0;
                    while (slot < 9 && !p.getInventory().getItem(slot).is(Items.WIND_CHARGE)) slot++;
                    check(slot < 9, "charge slot");
                    p.getInventory().setSelectedSlot(slot);
                    p.setXRot(90);
                    mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                    flyAge = 0;
                    flight = 2;
                    return;
                }
                if (clicks == 1 && p.onGround() && Math.abs(p.getY() - ground) < .002) landedBetween = true;
                if (++flyAge < 5) return;
                int slot = 0;
                while (slot < 9) {
                    var stack = p.getInventory().getItem(slot);
                    if (stack.is(Items.MACE) && stack.getEnchantments().keySet().stream().anyMatch(e -> e.is(Enchantments.WIND_BURST))) break;
                    slot++;
                }
                check(slot < 9, "wind burst slot");
                p.getInventory().setSelectedSlot(slot);
                Vec3 foot = bot.position().add(0, .96, -.501);
                p.lookAt(Anchor.EYES, foot);
                if (p.onGround() || p.getDeltaMovement().y >= 0 || p.getY() > ground + 1.85 || p.fallDistance < 2) return;
                var pick = Minecraft.class.getDeclaredMethod("pick", float.class);
                pick.setAccessible(true);
                pick.invoke(mc, 1f);
                if (!(mc.hitResult instanceof EntityHitResult e && e.getEntity() == bot)) return;
                var click = Minecraft.class.getDeclaredMethod("startAttack");
                click.setAccessible(true);
                click.invoke(mc);
                log("LIVE click=" + (++clicks) + " clientFeet=" + (p.getY() - ground) + " fallDistance=" + p.fallDistance
                    + " rayHeight=" + (mc.hitResult.getLocation().y - ground));
                if (clicks == 2) flight = 0;
                flyAge = 0;
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
                if (++age > 160) throw new AssertionError("timeout phase=" + phase + " launched=" + flight + " clicks=" + clicks);
                if (phase == 0) {
                    if (age < 14) return;
                    s.start(server, p.getUUID(),
                        TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(Drill.LOW_WEB_SMASH).withAutoRespawn(false),
                        DrillOptions.forDrill(Drill.LOW_WEB_SMASH));
                    next();
                    return;
                }
                var bot = (LivingEntity) field(s, "bot");
                String key = s.status().seriesKey();
                if (phase == 1 && age >= 15) {
                    s.releaseStart(p, s.status().startToken());
                    ground = bot.getY();
                    botId = bot.getId();
                    p.teleportTo(p.level(), bot.getX(), ground, bot.getZ() - 1.55, Set.of(), 0, 90, true);
                    flight = 1;
                    next();
                    return;
                }
                if (phase == 2) {
                    if (clicks != 2 || flight != 0 || !p.onGround() || age < 75) return;
                    var rows = outcomes(p, key);
                    check(rows.size() == 2 && rows.stream().allMatch(m -> m.contains("gelandet!")), "live smash verdicts " + rows);
                    check(TrainingProgress.streak(p.getUUID(), key).best() == (landedBetween ? 1 : 2),
                        "live streak vs actual client touchdown " + landedBetween);
                    if (TrainingProgress.streak(p.getUUID(), key).current() != 0) return;
                    log("PASS two vanilla wind-burst smashes, best=" + TrainingProgress.streak(p.getUUID(), key).best()
                        + " interveningTouchdown=" + landedBetween + "; final landing resets combo without a miss");
                    p.teleportTo(p.level(), bot.getX(), ground + .65, bot.getZ() - 1.6, Set.of(), 0, 0, true);
                    p.setOnGround(false);
                    s.playerLaunched(p);
                    s.observeWebGround(false);
                    p.setPos(p.getX(), ground, p.getZ());
                    p.setOnGround(true);
                    s.observeWebGround(true);
                    next();
                    return;
                }
                if (phase == 3) {
                    attack(p, bot, 0, true, true, new ItemStack(Items.MACE));
                    var rows = outcomes(p, key);
                    check(rows.size() == 3 && rows.getLast().contains("gelandet!"), "adjacent landing and accepted smash " + rows);
                    next();
                    return;
                }
                if (phase == 4 && age >= 6) {
                    check(outcomes(p, key).size() == 3, "false miss after accepted smash");
                    check(TrainingProgress.streak(p.getUUID(), key).current() == 0, "ground did not clear");
                    attack(p, bot, .65, false, true, new ItemStack(Items.MACE));
                    var rows = outcomes(p, key);
                    check(rows.size() == 4 && rows.getLast().contains("gelandet!"), "new vanilla smash " + rows);
                    check(TrainingProgress.streak(p.getUUID(), key).current() == 1, "first hit of fresh flight must show 1x");
                    int before = rows.size();
                    attack(p, bot, .5, false, false, new ItemStack(Items.MACE));
                    check(outcomes(p, key).size() == before, "ordinary tap overwrote a scored flight");
                    check(TrainingProgress.streak(p.getUUID(), key).current() == 1, "ordinary tap reset a scored flight");
                    log("PASS vanilla smash recorded during hurt immunity; ordinary tap leaves single completion intact.");
                    log("PASS complete");
                    s.stop(server);
                    done = true;
                    Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                }
            } catch (Throwable t) {
                fail(t);
            }
        });
    }
}
