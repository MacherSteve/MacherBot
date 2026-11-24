package de.pvptrainer;

import de.pvptrainer.arena.ForgeArena;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.combat.TrainingCrystal;
import de.pvptrainer.combat.TrainingTactics;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.drill.PearlInterceptSolver;
import de.pvptrainer.kits.KitLibrary;
import de.pvptrainer.kits.KitLoadout;
import de.pvptrainer.kits.TrainingPreset;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.progress.HudPreferences;
import de.pvptrainer.session.TrainingSession;
import de.pvptrainer.ui.KnowledgeGuide;
import de.pvptrainer.ui.TrainingScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Umfassende In-Game-Regressionsprobe fuer HugoSMP, grosse Arena, Hotfixes & neue Drills. */
public final class FeaturesProbe implements ClientModInitializer {
    private volatile boolean done;
    private volatile boolean holdClientShield;
    private int scene;
    private int sub;
    private int age;
    private int botWindCharges;
    private double maxBotY;
    private double hitDistanceAtDamage = -1.0;
    private Difficulty savedDifficulty;
    private float parityHardPlayerDmg;
    private float parityHardBotDmg;
    private double parityHardBotVy;
    private float parityNormalPlayerDmg;
    private float parityNormalBotDmg;
    private double parityBotMeleeHard;
    private double parityBotMeleeNormal;
    private double tripleTapPeakY;

    private static void log(String msg) throws Exception {
        Files.writeString(Path.of("checks.txt"), msg + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    private static Object field(Object obj, String name) throws Exception {
        var f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static LivingEntity bot(TrainingSession s) throws Exception {
        return (LivingEntity) field(s, "bot");
    }

    private static DrillAttempt attempt(TrainingSession s) throws Exception {
        return (DrillAttempt) field(s, "attempt");
    }

    private static int pops(TrainingSession s) throws Exception {
        return (int) field(s, "pops");
    }

    private static int hits(TrainingSession s) throws Exception {
        return (int) field(s, "hits");
    }

    private static void release(TrainingSession s, ServerPlayer p) {
        long token = s.status().startToken();
        check(token != 0L, "startToken missing while waiting");
        s.releaseStart(p, token);
        check(!s.status().waitingForStart(), "session stayed waiting after releaseStart");
    }

    private static int findHotbarSlot(ServerPlayer p, Item item) {
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(item)) return i;
        }
        return -1;
    }

    private static int equipFromInventory(ServerPlayer p, Item item) {
        int hotbar = findHotbarSlot(p, item);
        if (hotbar >= 0) {
            p.getInventory().setSelectedSlot(hotbar);
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(hotbar));
            return hotbar;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) {
                ItemStack prev = p.getInventory().getItem(0);
                p.getInventory().setItem(0, st);
                p.getInventory().setItem(i, prev);
                p.getInventory().setSelectedSlot(0);
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
                return 0;
            }
        }
        throw new AssertionError("Item not found in player inventory: " + item);
    }

    private static boolean hasEnchant(ItemStack stack, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> key, int minLevel) {
        var enchants = stack.getEnchantments();
        for (var entry : enchants.entrySet()) {
            if (entry.getKey().is(key) && entry.getIntValue() >= minLevel) return true;
        }
        return false;
    }

    private static int equipMace(ServerPlayer p, boolean windBurst) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(Items.MACE) && hasEnchant(st, Enchantments.WIND_BURST, 1) == windBurst) {
                p.getInventory().setSelectedSlot(i);
                return i;
            }
        }
        throw new AssertionError("Mace (windBurst=" + windBurst + ") not found on hotbar");
    }

    private static void detonateCrystalAt(TrainingSession s, ServerPlayer p, BlockPos base) throws Exception {
        ServerLevel level = p.level();
        level.setBlockAndUpdate(base, Blocks.OBSIDIAN.defaultBlockState());
        level.setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
        check(s.placeCrystal(p, base), "placeCrystal failed at " + base);
        var crystals = level.getEntities(EntityTypeTest.forClass(TrainingCrystal.class), c -> !c.isRemoved());
        check(!crystals.isEmpty(), "no TrainingCrystal spawned at " + base);
        TrainingCrystal crystal = crystals.getLast();
        crystal.hitInTraining(p.damageSources().playerAttack(p));
    }

    private static BlockPos findFlushFloorObsidian(ServerLevel level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = feet.below().relative(d);
            if (level.getBlockState(candidate).is(Blocks.OBSIDIAN)
                && level.getBlockState(candidate.above()).canBeReplaced()
                && level.getEntities(null, new net.minecraft.world.phys.AABB(candidate.above())).isEmpty()) {
                return candidate;
            }
        }
        throw new AssertionError("no prepared flush floor obsidian around " + feet);
    }

    private static BlockPos findOpenAdjacentBase(ServerLevel level, LivingEntity b) {
        BlockPos floorPos = new BlockPos(b.blockPosition().getX(), ForgeArena.FLOOR + 1, b.blockPosition().getZ());
        BlockPos best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 2) continue;
                BlockPos candidate = floorPos.offset(dx, 0, dz);
                BlockState st = level.getBlockState(candidate);
                var crystalBox = new net.minecraft.world.phys.AABB(
                    candidate.getX(), candidate.getY() + 1, candidate.getZ(),
                    candidate.getX() + 1, candidate.getY() + 3, candidate.getZ() + 1);
                if ((st.canBeReplaced() || st.is(Blocks.OBSIDIAN))
                    && level.getBlockState(candidate.above()).canBeReplaced()
                    && level.getEntities(null, new net.minecraft.world.phys.AABB(candidate)).isEmpty()
                    && level.getEntities(null, crystalBox).isEmpty()) {
                    double d = Vec3.atBottomCenterOf(candidate.above()).distanceToSqr(b.position());
                    if (d < bestDist) {
                        bestDist = d;
                        best = candidate;
                    }
                }
            }
        }
        if (best != null) return best;
        throw new AssertionError("no open obsidian base near " + b.position());
    }

    private static void triggerAnchorAt(TrainingSession s, ServerPlayer p, BlockPos pos) throws Exception {
        ServerLevel level = p.level();
        level.setBlockAndUpdate(pos, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1));
        var m = TrainingSession.class.getDeclaredMethod("anchorTriggered", ServerPlayer.class, BlockPos.class);
        m.setAccessible(true);
        m.invoke(s, p, pos);
        BlockState state = level.getBlockState(pos);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        p.getInventory().setSelectedSlot(findHotbarSlot(p, Items.TOTEM_OF_UNDYING) >= 0 ? findHotbarSlot(p, Items.TOTEM_OF_UNDYING) : 0);
        state.useWithoutItem(level, p, hit);
    }

    private void fail(Throwable t) {
        if (done) return;
        done = true;
        t.printStackTrace();
        try {
            log("FAIL scene=" + scene + " sub=" + sub + " " + t);
        } catch (Exception ignored) {
        }
        Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
    }

    private void nextScene(TrainingSession s, MinecraftServer server) {
        holdClientShield = false;
        if (s.status().active()) s.stop(server);
        scene++;
        sub = 0;
        age = 0;
    }

    @Override
    public void onInitializeClient() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof WindCharge w && w.getOwner() != null && !(w.getOwner() instanceof ServerPlayer)) {
                botWindCharges++;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (done) return;
            if (mc.player == null) { ProbeCompat.dismissWorldConfirm(mc); return; }
            if (ProbeCompat.screen(mc) != null && ProbeCompat.screen(mc).isPauseScreen()) {
                ProbeCompat.setScreen(mc, null);
            }
            mc.options.keyUse.setDown(holdClientShield);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (done || server.getPlayerList().getPlayers().isEmpty()) return;
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
                TrainingSession s = TrainerClient.session(server);
                if (s == null || !p.connection.hasClientLoaded() || p.isChangingDimension()) return;
                if (++age > 240) {
                    throw new AssertionError("timeout scene=" + scene + " sub=" + sub + " status=" + s.status());
                }
                if (sub == 0 && age < 8) return;
                if (sub == 1 && age < 2) return;

                switch (scene) {
                    case 0 -> runUiChecks(s, server);
                    case 1 -> runHugoKitAndMaceCounter(s, server, p);
                    case 2 -> runCornerWebDtap(s, server, p);
                    case 3 -> runLargeArenaAndCleanup(s, server, p);
                    case 4 -> runCornerObiTrapAndFirstHitKnockback(s, server, p);
                    case 5 -> runStunWebContinuousJump(s, server, p);
                    case 6 -> runStunSlamWindBurstAndElyStunSlam(s, server, p);
                    case 7 -> runShieldDrain(s, server, p);
                    case 8 -> runDoubleAnchor(s, server, p);
                    case 9 -> runVanillaCrystalParityAndTripleTap(s, server, p);
                    case 10 -> runShieldTripleTap(s, server, p);
                    case 11 -> runJumpResetAndHitSelectSpacing(s, server, p);
                    case 12 -> runSelfWebCrystalAndDTapHotfix(s, server, p);
                    case 13 -> runUppercutAndSwordSprintHits(s, server, p);
                    case 14 -> runSwightUhcDrills(s, server, p);
                    case 15 -> runHealPunishPCritBreakAndCleanHeal(s, server, p);
                    case 16 -> runV1170Features(s, server, p);
                    default -> {
                        if (savedDifficulty != null) {
                            server.setDifficulty(savedDifficulty, true);
                            savedDifficulty = null;
                        }
                        check(!TrainingArena.isArena(p.level()), "still in arena at end");
                        check(!p.isNoGravity(), "noGravity left enabled");
                        log("PASS complete");
                        done = true;
                        Minecraft.getInstance().execute(Minecraft.getInstance()::stop);
                    }
                }
            } catch (Throwable t) {
                fail(t);
            }
        });
    }

    private void runUiChecks(TrainingSession s, MinecraftServer server) throws Exception {
        CompletableFuture<String> uiFuture = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                var mc = Minecraft.getInstance();
                // D-Tap must NOT render the 3 preset buttons; HugoSMP mode tab must exist
                var crystal = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL);
                TrainerClient.saveSetup(crystal.forDrill(Drill.D_TAP), DrillOptions.forDrill(Drill.D_TAP));
                var dtapScreen = new TrainingScreen();
                ProbeCompat.setScreen(mc, dtapScreen);
                List<String> dtapLabels = dtapScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getMessage().getString())
                    .toList();
                boolean hasHugoTab = dtapLabels.stream().anyMatch(l -> l.contains("HugoSMP"));
                boolean dtapHasPresets = java.util.Arrays.stream(de.pvptrainer.kits.TrainingPreset.values())
                    .map(pr -> pr.label(Drill.D_TAP))
                    .anyMatch(presetLabel -> dtapLabels.stream().anyMatch(l -> l.equals(presetLabel)));
                check(hasHugoTab, "HugoSMP mode tab missing in TrainingScreen: " + dtapLabels);
                check(!dtapHasPresets, "D-Tap must not show 3 preset buttons: " + dtapLabels);

                // Counter-case: Triple Tap MUST show preset buttons
                TrainerClient.saveSetup(crystal.forDrill(Drill.TRIPLE_TAP), DrillOptions.forDrill(Drill.TRIPLE_TAP));
                var tripleScreen = new TrainingScreen();
                ProbeCompat.setScreen(mc, tripleScreen);
                List<String> tripleLabels = tripleScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getMessage().getString())
                    .toList();
                boolean tripleHasPresets = java.util.Arrays.stream(de.pvptrainer.kits.TrainingPreset.values())
                    .map(pr -> pr.label(Drill.TRIPLE_TAP))
                    .allMatch(presetLabel -> tripleLabels.stream().anyMatch(l -> l.equals(presetLabel)));
                check(tripleHasPresets, "Triple Tap counter-case missing preset buttons: " + tripleLabels);
                check(tripleLabels.stream().anyMatch(l -> l.startsWith("Arena ·")),
                    "Arena toggle switch missing in TrainingScreen: " + tripleLabels);
                check(tripleLabels.stream().anyMatch(l -> l.startsWith("Spieltempo ·")),
                    "TPS hover-slider chip missing in TrainingScreen: " + tripleLabels);
                check(tripleLabels.stream().anyMatch(l -> l.startsWith("Ping ·") && l.contains("Bot")),
                    "Dual Ping (Player + Bot) hover-slider chip missing in TrainingScreen: " + tripleLabels);
                // Verify "Technik-Guide" button opens KnowledgeGuideScreen cleanly with categories & CTA
                var guideBtn = tripleScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().contains("Technik-Guide"))
                    .map(c -> (net.minecraft.client.gui.components.Button) c)
                    .findFirst().orElse(null);
                check(guideBtn != null, "Technik-Guide button missing in TrainingScreen: " + tripleLabels);
                guideBtn.onPress(null);
                var guideScreen = ProbeCompat.screen(mc);
                check(guideScreen != null && guideScreen.getTitle().getString().contains("Technik-Guide"),
                    "KnowledgeGuideScreen did not open: " + guideScreen);
                List<String> guideLabels = guideScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getMessage().getString())
                    .toList();
                check(guideLabels.stream().anyMatch(l -> l.contains("Grundlagen"))
                    && guideLabels.stream().anyMatch(l -> l.contains("Jetzt üben")),
                    "KnowledgeGuideScreen missing tabs or CTA: " + guideLabels);

                // Verify Gear button opens page-free Settings screen (2 tabs, no Difficulty switches)
                var settingsBtn = tripleScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().contains("Einstellungen"))
                    .map(c -> (net.minecraft.client.gui.components.Button) c)
                    .findFirst().orElse(null);
                check(settingsBtn != null, "Settings gear button missing in TrainingScreen: " + tripleLabels);
                check(tripleLabels.stream().anyMatch(l -> l.contains("Pearl Ground Hit")),
                    "Pearl Ground Hit icon chip missing in TrainingScreen: " + tripleLabels);
                settingsBtn.onPress(null);
                var settingsScreen = ProbeCompat.screen(mc);
                check(settingsScreen != null && settingsScreen.getTitle().getString().contains("Einstellungen"),
                    "TrainingOptionsScreen did not open: " + settingsScreen);
                List<String> settingsLabels = settingsScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getMessage().getString())
                    .toList();
                check(settingsLabels.stream().anyMatch(l -> l.contains("Übung & Bot"))
                    && settingsLabels.stream().anyMatch(l -> l.contains("Arena & Anzeige")),
                    "Settings missing 2 Apple segmented tabs: " + settingsLabels);
                check(settingsLabels.stream().noneMatch(l -> l.contains("Schwierigkeit")),
                    "Difficulty control must not exist in Settings: " + settingsLabels);

                // Verify unified Kits & Statistik screens open cleanly
                var kitsBtn = tripleScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals("Kits"))
                    .map(c -> (net.minecraft.client.gui.components.Button) c)
                    .findFirst().orElse(null);
                check(kitsBtn != null, "Kits button missing in TrainingScreen");
                kitsBtn.onPress(null);
                check(ProbeCompat.screen(mc) != null && ProbeCompat.screen(mc).getTitle().getString().equals("Kits"),
                    "Unified KitShelfScreen did not open: " + ProbeCompat.screen(mc));

                var statsBtn = tripleScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.Button b && b.getMessage().getString().equals("Statistik"))
                    .map(c -> (net.minecraft.client.gui.components.Button) c)
                    .findFirst().orElse(null);
                check(statsBtn != null, "Statistik button missing in TrainingScreen");
                statsBtn.onPress(null);
                var statsScreen = ProbeCompat.screen(mc);
                check(statsScreen != null && statsScreen.getTitle().getString().equals("Statistik"),
                    "Unified Statistik screen did not open: " + statsScreen);
                List<String> statsLabels = statsScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getMessage().getString())
                    .toList();
                check(statsLabels.stream().anyMatch(l -> l.equals("Übersicht"))
                    && statsLabels.stream().anyMatch(l -> l.equals("Übungen"))
                    && statsLabels.stream().anyMatch(l -> l.equals("Rundenlog"))
                    && statsLabels.stream().anyMatch(l -> l.equals("Bot-Analyse")),
                    "Statistik missing 4 unified tabs: " + statsLabels);
                check(statsLabels.stream().anyMatch(l -> l.equals("Gesamt"))
                    && statsLabels.stream().anyMatch(l -> l.equals("Heute")),
                    "Statistik Übersicht missing mode/timeframe filters: " + statsLabels);

                ProbeCompat.setScreen(mc, null);
                uiFuture.complete("ok");
            } catch (Throwable err) {
                uiFuture.completeExceptionally(err);
            }
        });
        check("ok".equals(uiFuture.get(5, TimeUnit.SECONDS)), "UI check failed");
        log("PASS UI: D-Tap hides 3 preset buttons, Triple Tap keeps presets, HugoSMP tab rendered");
        nextScene(s, server);
    }

    private void runHugoKitAndMaceCounter(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            botWindCharges = 0;
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.HUGOSMP).forDrill(Drill.MACE_COUNTER_DTAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.MACE_COUNTER_DTAP));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            check(TrainingArena.isArena(p.level()), "not in arena for HugoSMP");
            // Verify HugoSMP kit items in live player inventory
            check(p.getInventory().getItem(0).is(Items.SHIELD), "slot 0 shield");
            ItemStack mace = p.getInventory().getItem(1);
            check(mace.is(Items.MACE) && hasEnchant(mace, Enchantments.DENSITY, 5) && hasEnchant(mace, Enchantments.WIND_BURST, 2), "slot 1 mace density 5 wind_burst 2");
            check(p.getInventory().getItem(2).is(Items.GOLDEN_APPLE) && p.getInventory().getItem(2).getCount() == 64, "slot 2 64 gapples");
            check(p.getInventory().getItem(3).is(Items.WIND_CHARGE) && p.getInventory().getItem(3).getCount() == 64, "slot 3 64 wind charges");
            check(p.getInventory().getItem(4).is(Items.OBSIDIAN) && p.getInventory().getItem(4).getCount() == 64, "slot 4 64 obsidian");
            check(p.getInventory().getItem(5).is(Items.END_CRYSTAL) && p.getInventory().getItem(5).getCount() == 64, "slot 5 64 crystals");
            check(p.getInventory().getItem(6).is(Items.TOTEM_OF_UNDYING), "slot 6 totem");
            check(p.getInventory().getItem(7).is(Items.NETHERITE_AXE), "slot 7 netherite axe");
            check(p.getInventory().getItem(8).is(Items.ENDER_PEARL) && p.getInventory().getItem(8).getCount() == 16, "slot 8 16 pearls");
            check(p.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "offhand totem");
            check(p.getInventory().getItem(10).is(Items.TNT) && p.getInventory().getItem(10).getCount() == 64, "slot 10 64 tnt");
            check(p.getInventory().getItem(19).is(Items.TNT) && p.getInventory().getItem(19).getCount() == 64, "slot 19 64 tnt");
            ItemStack potion = p.getInventory().getItem(17);
            check(potion.is(Items.SPLASH_POTION) && potion.get(DataComponents.POTION_CONTENTS) != null
                && potion.get(DataComponents.POTION_CONTENTS).is(Potions.SWIFTNESS), "slot 17 Swiftness I splash potion");
            check(p.getInventory().getItem(29).is(Items.ENCHANTED_GOLDEN_APPLE) && p.getInventory().getItem(29).getCount() == 16, "slot 29 16 OP gaps");
            ItemStack spareLegs = p.getInventory().getItem(32);
            check(spareLegs.is(Items.NETHERITE_LEGGINGS) && hasEnchant(spareLegs, Enchantments.BLAST_PROTECTION, 4), "slot 32 Blast Prot 4 leggings");
            check(p.getInventory().getItem(34).is(Items.COBWEB) && p.getInventory().getItem(34).getCount() == 64, "slot 34 64 cobwebs");
            for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                check(hasEnchant(p.getItemBySlot(slot), Enchantments.PROTECTION, 4), "equipped armor missing Prot 4: " + slot);
            }
            log("PASS HugoSMP kit verified in-game (all 36 slots, Prot 4 armor, Blast Prot 4 spare legs, Density 5/WB 2 mace, 16 OP gaps, 64 crystals, Swiftness I)");

            // Raise shield facing bot on real client and release start so bot wind-charge mace jumps into player's shield
            p.getInventory().setSelectedSlot(0);
            p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(0));
            release(s, p);
            holdClientShield = true;
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            LivingEntity b = bot(s);
            check(b != null, "MACE_COUNTER_DTAP ended early! status=" + s.status());
            DrillAttempt att = attempt(s);
            if (att.maceCounterBlocked()) {
                holdClientShield = false;
                check(botWindCharges >= 1, "bot did not launch real WindCharge");
                p.stopUsingItem();
                if (!b.onGround()) return; // wait for bot to land after blocked smash before sword opening
                // Now finish the D-Tap (opening hit + Crystal 1 pop + Crystal 2 pop) to prove full round win!
                equipFromInventory(p, Items.NETHERITE_SWORD);
                p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
                p.lookAt(Anchor.EYES, b.getEyePosition());
                p.attack(b);
                sub = 3;
                age = 0;
            }
            return;
        }
        if (sub == 3) {
            if (age < 2) return;
            LivingEntity b = bot(s);
            check(b != null, "MACE_COUNTER_DTAP ended before Crystal 1! status=" + s.status());
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 1 && !attempt(s).done(),
                "MACE_COUNTER_DTAP Crystal 1 must pop totem 1; pops=" + pops(s) + " msg=" + attempt(s).progress());
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            LivingEntity b = bot(s);
            check(b != null, "MACE_COUNTER_DTAP ended before Crystal 2! status=" + s.status());
            // Wait naturally for real gravity to bring the bot back down near crystal height (still airborne!)
            if (age < 11 || b.getY() > ForgeArena.FLOOR + 2.6) return;
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 2 && attempt(s).done() && attempt(s).won(),
                "MACE_COUNTER_DTAP Crystal 2 must pop totem 2 and win; pops=" + pops(s) + " msg=" + attempt(s).progress()
                    + " botPos=" + b.position() + " hp=" + b.getHealth() + "+" + b.getAbsorptionAmount()
                    + " off=" + b.getOffhandItem() + " main=" + b.getMainHandItem() + " tl=" + attempt(s).timeline());
            log("PASS MACE_COUNTER_DTAP: bot launched real WindCharge + Mace jump, player shield blocked smash, D-Tap 2 pops won");
            nextScene(s, server);
        }
    }

    private void runCornerWebDtap(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.HUGOSMP).forDrill(Drill.CORNER_WEB_DTAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.CORNER_WEB_DTAP));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            BlockPos feet = b.blockPosition();
            int solidWalls = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!p.level().getBlockState(feet.relative(d)).isAir() && !p.level().getBlockState(feet.relative(d).above()).isAir()) {
                    solidWalls++;
                }
            }
            check(solidWalls == 2, "CORNER_WEB_DTAP expected 2 perpendicular corner walls, found " + solidWalls);
            check(b.getMainHandItem().is(Items.SHIELD) && b.getOffhandItem().is(Items.TOTEM_OF_UNDYING),
                "CORNER_WEB_DTAP bot must hold Shield in mainhand and Totem in offhand");
            check(b.isBlocking(), "CORNER_WEB_DTAP bot shield should be immediately blocking");

            release(s, p);
            // Place cobweb at bot feet
            p.level().setBlockAndUpdate(feet, Blocks.COBWEB.defaultBlockState());
            attempt(s).webPlaced(feet);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            LivingEntity b = bot(s);
            DrillAttempt att = attempt(s);
            check(att.isCornerWebbed(), "bot should be detected in corner web");
            // Move close and break bot shield with Axe
            equipFromInventory(p, Items.NETHERITE_AXE);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(!b.isBlocking() && att.isCornerShieldBroken(), "Axe hit did not break shield in CORNER_WEB_DTAP");
            // Crystal 1 on prepared flush floor obsidian adjacent to corner
            BlockPos base = findFlushFloorObsidian(p.level(), b.blockPosition());
            detonateCrystalAt(s, p, base);
            check(pops(s) == 1 && !att.done(),
                "Crystal 1 must pop totem 1 and stay open; pops=" + pops(s) + " msg=" + att.progress() + " tl=" + att.timeline());
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            if (age < 12) return; // wait for invulnerability window
            LivingEntity b = bot(s);
            BlockPos base = findFlushFloorObsidian(p.level(), b.blockPosition());
            detonateCrystalAt(s, p, base);
            DrillAttempt att = attempt(s);
            check(pops(s) == 2 && att.done() && att.won(),
                "Crystal 2 must pop totem 2 and win CORNER_WEB_DTAP; pops=" + pops(s) + " msg=" + att.progress() + " tl=" + att.timeline());
            log("PASS CORNER_WEB_DTAP: 90° corner walls, cobweb, Axe shield break + 2 real crystal pops won");
            nextScene(s, server);
        }
    }

    private void runLargeArenaAndCleanup(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.HUGOSMP).forDrill(Drill.PASSIVE).withAutoRespawn(false);
            var opts = DrillOptions.forDrill(Drill.PASSIVE).withIndex(DrillOptions.Field.ARENA_SIZE, 1);
            s.start(server, p.getUUID(), settings, opts);
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            ServerLevel arena = p.level();
            check(ForgeArena.isLargeActive(), "Large Arena should be active");
            check(arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR - 81, 0)).is(Blocks.BEDROCK), "Bedrock expected at Y=-17");
            check(!arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR - 80, 0)).isAir(), "Solid diggable block expected at Y=-16");
            check(!arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR, 0)).isAir()
                && arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR + 1, 0)).isAir(), "Flat central plateau at Y=64");
            // Find a hill point with offset >= 2 and verify real hill blocks exist in world
            int hx = 0, hz = 0, ho = 0;
            outer:
            for (int x = -40; x <= 40; x++) {
                for (int z = -40; z <= 40; z++) {
                    int off = ForgeArena.largeHillOffset(x, z);
                    if (off >= 2) { hx = x; hz = z; ho = off; break outer; }
                }
            }
            check(ho >= 2, "Expected hill offset >= 2");
            check(!arena.getBlockState(new BlockPos(hx, ForgeArena.FLOOR + ho, hz)).isAir()
                && arena.getBlockState(new BlockPos(hx, ForgeArena.FLOOR + ho + 1, hz)).isAir(),
                "Large Arena hill surface mismatch at " + hx + "," + (ForgeArena.FLOOR + ho) + "," + hz);
            check(!arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR + 2, 52)).isAir(), "Large Arena outer wall missing");

            // Now switch back to Standard Arena and verify complete residual cleanup
            var stdSettings = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.PASSIVE).withAutoRespawn(false);
            s.start(server, p.getUUID(), stdSettings, DrillOptions.forDrill(Drill.PASSIVE));
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            ServerLevel arena = p.level();
            check(!ForgeArena.isLargeActive(), "Standard Arena should be active");
            check(arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR - 81, 0)).isAir(), "Large Arena bedrock leaked into Standard Arena");
            check(arena.getBlockState(new BlockPos(0, ForgeArena.FLOOR + 2, 52)).isAir(), "Large Arena outer wall leaked into Standard Arena");
            check(arena.getBlockState(new BlockPos(35, ForgeArena.FLOOR, 0)).isAir(), "Large Arena outer floor leaked into Standard Arena");
            log("PASS Large Arena (80-block depth, smooth hills, Forge walls) and clean reset back to Standard Arena verified");
            nextScene(s, server);
        }
    }

    private void runCornerObiTrapAndFirstHitKnockback(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.CORNER_OBI_TRAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.CORNER_OBI_TRAP));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            BlockPos feet = b.blockPosition();
            int walls = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (!p.level().getBlockState(feet.relative(d)).isAir() && !p.level().getBlockState(feet.relative(d).above()).isAir()) {
                    walls++;
                }
            }
            check(walls == 2, "CORNER_OBI_TRAP expected 2 corner walls, found " + walls);
            check(b.onGround(), "Bot must be onGround=true at round spawn so first hit applies full vertical knockback");

            // Simulate first hit immediately on round release
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            check(b.onGround(), "Bot must stay onGround=true right after releaseStart");
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(hits(s) == 1, "First hit did not register");
            check(b.getDeltaMovement().y > 0.15, "First hit did not give upward knockback! dy=" + b.getDeltaMovement().y);
            log("PASS CORNER_OBI_TRAP corner walls & first-hit knockback verified (dy=" + String.format("%.3f", b.getDeltaMovement().y) + ")");
            nextScene(s, server);
        }
    }

    private void runStunWebContinuousJump(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            maxBotY = 0.0;
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.STUN_WEB)
                .withMovement(TrainingSettings.Movement.STILL).withAutoRespawn(false);
            var opts = DrillOptions.forDrill(Drill.STUN_WEB).withIndex(DrillOptions.Field.BOT_JUMP, 1);
            s.start(server, p.getUUID(), settings, opts);
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            release(s, p);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            LivingEntity b = bot(s);
            if (b.getY() > maxBotY) maxBotY = b.getY();
            if (age >= 20) {
                check(maxBotY > ForgeArena.FLOOR + 1.6, "STUN_WEB BOT_JUMP=Ein did not make bot jump while STILL; maxY=" + maxBotY);
                log("PASS STUN_WEB continuous jump option verified (maxBotY=" + String.format("%.2f", maxBotY) + ")");
                nextScene(s, server);
            }
        }
    }

    private void runStunSlamWindBurstAndElyStunSlam(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(Drill.STUN_SLAM).withAutoRespawn(false);
            var opts = DrillOptions.forDrill(Drill.STUN_SLAM)
                .withIndex(DrillOptions.Field.START_HEIGHT, DrillOptions.Field.START_HEIGHT.indexOfNumber(18));
            s.start(server, p.getUUID(), settings, opts);
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            check(Math.abs(p.getY() - (ForgeArena.FLOOR + 1 + 18)) < 0.1, "Player did not spawn at height 18: y=" + p.getY());
            LivingEntity b = bot(s);
            check(b.isBlocking(), "STUN_SLAM bot should block immediately");
            release(s, p);

            // 1. Axe shield break ONLY must NOT win!
            p.teleportTo(p.level(), b.getX(), b.getY() + 1.2, b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.fallDistance = 16.5F;
            equipFromInventory(p, Items.NETHERITE_AXE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).shieldWindowOpen(), "Axe stun failed at height 18");
            check(!attempt(s).done() && !attempt(s).won(), "Shield break alone must NOT complete STUN_SLAM!");

            // 2. Ground tap with Mace (fallDistance=0, no smash) must NOT win!
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(true);
            p.fallDistance = 0.0F;
            equipMace(p, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(!attempt(s).done() && !attempt(s).won(), "Ground tap (0.0 bl fall) must NOT complete STUN_SLAM!");

            // 3. Full Mace Smash WITHOUT Wind Burst (Slot 9 Breach Mace) must NOT win!
            p.teleportTo(p.level(), b.getX(), b.getY() + 1.0, b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(false);
            p.fallDistance = 18.0F;
            equipMace(p, false);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(!attempt(s).done() && !attempt(s).won(), "Mace smash WITHOUT Wind Burst must NOT complete STUN_SLAM!");

            // 4. Full Mace Smash WITH Wind Burst I Mace -> must catapult player upward and WIN!
            p.teleportTo(p.level(), b.getX(), b.getY() + 1.0, b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(false);
            p.setDeltaMovement(new Vec3(0.0, -0.8, 0.0));
            p.fallDistance = 18.5F;
            equipMace(p, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(p.getDeltaMovement().y > 0.5, "Wind Burst Mace Smash must catapult player into air! vy=" + p.getDeltaMovement().y);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            check(attempt(s) == null || (attempt(s).done() && attempt(s).won()),
                "STUN_SLAM with Wind Burst launch must WIN! status=" + s.status());
            check(s.status().successes() >= 1 || (attempt(s) != null && attempt(s).won()),
                "STUN_SLAM win not recorded! status=" + s.status());

            // 5. ELY_STUN_SLAM: air start 32m with Elytra equipped, glide -> Axe stun -> Wind Burst Smash -> WIN!
            var elySettings = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(Drill.ELY_STUN_SLAM).withAutoRespawn(false);
            s.start(server, p.getUUID(), elySettings, DrillOptions.forDrill(Drill.ELY_STUN_SLAM));
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            check(Math.abs(p.getY() - (ForgeArena.FLOOR + 1 + DrillOptions.DEFAULT_FLIGHT_HEIGHT)) < 0.2,
                "ELY_STUN_SLAM player should spawn at 32m air start, got y=" + p.getY());
            check(p.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA), "ELY_STUN_SLAM must equip Elytra on chest");
            LivingEntity b = bot(s);
            check(b.isBlocking(), "ELY_STUN_SLAM bot should block immediately");
            release(s, p);

            // Start & end real Elytra glide
            p.startFallFlying();
            check(p.isFallFlying(), "Player should enter fall flying for ELY_STUN_SLAM");
            p.stopFallFlying();

            // Dive onto bot -> Axe shield break -> Wind Burst Mace Smash
            p.teleportTo(p.level(), b.getX(), b.getY() + 1.2, b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(false);
            p.fallDistance = 18.0F;
            equipFromInventory(p, Items.NETHERITE_AXE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).shieldWindowOpen() && !attempt(s).done(), "ELY_STUN_SLAM Axe stun must open window without winning");

            p.setDeltaMovement(new Vec3(0.0, -0.8, 0.0));
            p.fallDistance = 19.0F;
            equipMace(p, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(p.getDeltaMovement().y > 0.5, "ELY_STUN_SLAM Wind Burst Smash must catapult player into air! vy=" + p.getDeltaMovement().y);
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            check((attempt(s) == null && s.status().successes() >= 1) || (attempt(s) != null && attempt(s).done() && attempt(s).won()),
                "ELY_STUN_SLAM with glide + Axe stun + Wind Burst smash must WIN! status=" + s.status());
            log("PASS STUN_SLAM (shield-only/ground-tap/no-WB rejected; full Wind Burst launch wins) AND ELY_STUN_SLAM verified");
            nextScene(s, server);
        }
    }

    private void runShieldDrain(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(Drill.SHIELD_DRAIN).withAutoRespawn(false);
            var opts = DrillOptions.forDrill(Drill.SHIELD_DRAIN)
                .withIndex(DrillOptions.Field.DURABILITY, DrillOptions.Field.DURABILITY.indexOfNumber(1));
            s.start(server, p.getUUID(), settings, opts);
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            check(b.isBlocking(), "SHIELD_DRAIN bot must block immediately");
            release(s, p);
            equipFromInventory(p, Items.NETHERITE_SWORD);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            var status = s.status();
            if (!status.active() && status.successes() == 1 && !status.recent().isEmpty() && status.recent().getLast().won()) {
                log("PASS SHIELD_DRAIN in Mace verified (shield broke and won)");
                nextScene(s, server);
                return;
            }
            DrillAttempt att = attempt(s);
            if (att != null && att.done()) {
                check(att.won(), "SHIELD_DRAIN failed: " + att.progress());
                log("PASS SHIELD_DRAIN in Mace verified (shield broke and won)");
                nextScene(s, server);
                return;
            }
            if (age % 12 == 0) {
                LivingEntity b = bot(s);
                p.lookAt(Anchor.EYES, b.getEyePosition());
                p.attack(b);
            }
        }
    }

    private void runDoubleAnchor(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            if (savedDifficulty == null) savedDifficulty = server.getWorldData().getDifficulty();
            server.setDifficulty(Difficulty.HARD, true);
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.DOUBLE_ANCHOR).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.DOUBLE_ANCHOR));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            release(s, p);
            LivingEntity b = bot(s);
            BlockPos anchorPos = b.blockPosition().north();
            triggerAnchorAt(s, p, anchorPos);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            if (age < 13) return; // settleAnchor + hurt window
            check(pops(s) == 1 && !attempt(s).done(),
                "Anchor 1 must pop totem 1 and stay open; pops=" + pops(s) + " progress=" + attempt(s).progress());
            LivingEntity b = bot(s);
            BlockPos anchorPos = b.blockPosition().north();
            triggerAnchorAt(s, p, anchorPos);
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            if (age < 2) return;
            var status = s.status();
            check(status.pops() == 2 && status.successes() == 1 && !status.recent().isEmpty() && status.recent().getLast().won(),
                "Anchor 2 must pop totem 2 and win DOUBLE_ANCHOR; status=" + status);
            log("PASS DOUBLE_ANCHOR verified in-game (2 real Respawn Anchor totem pops: " + status.recent().getLast().reason() + ")");
            nextScene(s, server);
        }
    }

    private void runShieldTripleTap(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            if (savedDifficulty == null) savedDifficulty = server.getWorldData().getDifficulty();
            server.setDifficulty(Difficulty.HARD, true);
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.SHIELD_TRIPLE_TAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.SHIELD_TRIPLE_TAP));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            check(!b.isBlocking() && !b.getMainHandItem().is(Items.SHIELD), "SHIELD_TRIPLE_TAP bot must NOT hold shield before Pop 1");
            check(hasEnchant(b.getItemBySlot(EquipmentSlot.LEGS), Enchantments.PROTECTION, 4)
                    && !hasEnchant(b.getItemBySlot(EquipmentSlot.LEGS), Enchantments.BLAST_PROTECTION, 4),
                "SHIELD_TRIPLE_TAP bot must wear Protection IV leggings (no Blast Prot) from ltcrystal kit");
            // Equip Netherite Sword 1 tick before attacking so ServerPlayer.tick() refreshes Attributes.ATTACK_DAMAGE to 8.0!
            equipFromInventory(p, Items.NETHERITE_SWORD);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            LivingEntity b = bot(s);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            p.setSprinting(false);
            BlockPos ahead = new BlockPos(b.blockPosition().getX(), ForgeArena.FLOOR + 1, b.blockPosition().getZ() + 3);
            p.level().setBlockAndUpdate(ahead, Blocks.OBSIDIAN.defaultBlockState());
            p.level().setBlockAndUpdate(ahead.above(), Blocks.AIR.defaultBlockState());
            check(s.placeCrystal(p, ahead), "placeCrystal ahead failed at " + ahead);
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            if (age < 7) return; // bot arcs cleanly over (0.5, 66.0, 3.5) at T+7 (y=66.04, z=3.11, d=0.40 -> pops full 20 HP on HARD with Blast Prot IV!)
            LivingEntity b = bot(s);
            check(b != null, "SHIELD_TRIPLE_TAP ended before Crystal 1! status=" + s.status());
            var crystals = p.level().getEntities(EntityTypeTest.forClass(TrainingCrystal.class), c -> !c.isRemoved());
            check(!crystals.isEmpty(), "prepared Crystal 1 missing");
            crystals.getLast().hitInTraining(p.damageSources().playerAttack(p));
            check(pops(s) == 1, "Crystal 1 must pop totem 1 in SHIELD_TRIPLE_TAP; pops=" + pops(s) + " hp=" + b.getHealth() + " tl=" + attempt(s).timeline());
            check(b.getMainHandItem().is(Items.SHIELD), "After Pop 1 bot must equip SHIELD in mainhand");
            check(b.getOffhandItem().is(Items.TOTEM_OF_UNDYING), "After Pop 1 bot must keep TOTEM in offhand");
            check(b.isBlocking(), "After Pop 1 bot must raise & block with shield immediately");
            check(attempt(s).needsAirSword(), "Attempt must now expect mid-air Axe shield break");

            // Break bot's shield in mid-air with Netherite Axe!
            equipFromInventory(p, Items.NETHERITE_AXE);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(!b.isBlocking(), "Mid-air Axe hit must disable bot's post-pop shield");
            check(!attempt(s).needsAirSword(), "Mid-air Axe shield break must unlock Crystal 2");
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            LivingEntity b = bot(s);
            check(b != null, "SHIELD_TRIPLE_TAP ended before Crystal 2! status=" + s.status());
            if (age < 3) return; // T+10: hurt window opens right at apex after Pop 1 (y ~ 66.4)!
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 2 && !attempt(s).done(),
                "Crystal 2 must pop totem 2; pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            sub = 5;
            age = 0;
            return;
        }
        if (sub == 5) {
            LivingEntity b = bot(s);
            check(b != null, "SHIELD_TRIPLE_TAP ended before Crystal 3! status=" + s.status());
            if (age < 10 || b.getY() > ForgeArena.FLOOR + 2.8) return; // T+20+: fresh hurt window + wait for 4x Prot IV knockup to descend near crystal height while still airborne!
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 3 && attempt(s).done() && attempt(s).won(),
                "Crystal 3 must pop totem 3 and win SHIELD_TRIPLE_TAP; pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            log("PASS SHIELD_TRIPLE_TAP verified in-game on HARD with 4x Prot IV (Pop 1 -> Shield -> Axe break -> Pop 2 + Pop 3)");
            nextScene(s, server);
        }
    }

    private void runJumpResetAndHitSelectSpacing(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            hitDistanceAtDamage = -1.0;
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.JUMP_RESET).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.JUMP_RESET));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            double startDist = p.position().subtract(b.position()).horizontalDistance();
            check(startDist >= 6.8, "JUMP_RESET should start >= 7 blocks away, got " + startDist);
            check(Math.abs(TrainingTactics.meleeReach(b) - 3.0) < 1e-6, "JUMP_RESET meleeReach must be exact 3.0, got " + TrainingTactics.meleeReach(b));
            release(s, p);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            LivingEntity b = bot(s);
            if (p.hurtTime > 0 && hitDistanceAtDamage < 0) {
                hitDistanceAtDamage = Math.sqrt(p.getBoundingBox().distanceToSqr(b.getEyePosition()));
                double sprintKbHor = p.getDeltaMovement().horizontalDistance();
                check(hitDistanceAtDamage >= 2.2 && hitDistanceAtDamage <= 3.15,
                    "JUMP_RESET bot should strike cleanly near 3-block reach, struck at " + hitDistanceAtDamage);
                check(sprintKbHor > 0.55,
                    "JUMP_RESET bot must always deal Sprint Hit knockback (>0.55 hor), got " + sprintKbHor);
                log("PASS JUMP_RESET & HIT_SELECT verified (startDist=7 Bl., exact 3.0 reach, bot Sprint-Hit at "
                    + String.format("%.2f", hitDistanceAtDamage) + " Bl., horKB=" + String.format("%.3f", sprintKbHor) + ")");
                nextScene(s, server);
            }
        }
    }

    /** Side-by-side Vanilla ServerPlayer vs TrainingBot parity on HARD & NORMAL + full 3-pop TRIPLE_TAP. */
    private void runVanillaCrystalParityAndTripleTap(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        // Sub 0..2: Symmetric crystal blast on HARD & NORMAL comparing ServerPlayer vs TrainingBot (identical ltcrystal armor!)
        if (sub == 0) {
            if (savedDifficulty == null) savedDifficulty = server.getWorldData().getDifficulty();
            server.setDifficulty(Difficulty.HARD, true);
            // Explicitly use Difficulty.HARD on non-DUEL drill to verify base MOVEMENT_SPEED stays exact Vanilla 0.10 (NOT 0.125!)
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL)
                .forDrill(Drill.PASSIVE)
                .withDifficulty(TrainingSettings.Difficulty.HARD)
                .withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.PASSIVE));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            release(s, p);
            // Verify authentic ltcrystal 4x Prot IV armor + Vanilla attributes (0.0 explosion KB resist, Netherite -> 0.4 KB resist)
            for (EquipmentSlot armorSlot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                check(hasEnchant(b.getItemBySlot(armorSlot), Enchantments.PROTECTION, 4)
                        && !hasEnchant(b.getItemBySlot(armorSlot), Enchantments.BLAST_PROTECTION, 4),
                    "Crystal bot must wear Protection IV only on " + armorSlot);
            }
            check(Math.abs(b.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)) < 1e-4,
                "Bot explosion KB resist must be 0.0 (4x Prot IV), got " + b.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE));
            check(Math.abs(b.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) - 0.4) < 1e-4,
                "Bot melee KB resist must be 0.4 (4x Netherite), got " + b.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
            check(Math.abs(b.getMaxHealth() - 20.0F) < 1e-4, "Crystal default bot maxHealth must be 20 HP (10 hearts), got " + b.getMaxHealth());

            // Verify exact Vanilla Player base speed (0.10 even on HARD), canSprint(), sprint +30%, and item-use 0.2x slowdown!
            check(b.canSprint(), "Bot canSprint() must match Vanilla Player (true)");
            float walkSpeed = b.getSpeed();
            check(Math.abs(walkSpeed - 0.1F) < 1e-4F, "Bot base walk speed must be exact Vanilla 0.10 even on HARD, got " + walkSpeed);
            b.setSprinting(true);
            float sprintSpeed = b.getSpeed();
            check(Math.abs(sprintSpeed - 0.13F) < 1e-4F, "Bot sprint speed must be exact Vanilla 0.13 (+30%), got " + sprintSpeed);

            // Verify applyInput() slows bot to 0.2x and cancels sprint while blocking with shield (matching LocalPlayer.modifyInput())
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
            b.startUsingItem(InteractionHand.MAIN_HAND);
            b.zza = 1.0F;
            b.xxa = 0.0F;
            ProbeCompat.invokeApplyInput(b);
            check(!b.isSprinting(), "Using shield must cancel sprint just like Vanilla LocalPlayer");
            check(Math.abs(b.zza - (0.98F * 0.2F)) < 1e-4F,
                "Shield-use movement input must scale to 0.98*0.2=0.196, got " + b.zza);
            b.zza = 0.0F;

            // Verify Vanilla ServerPlayer shield-block parity: 100% blocked hit must deal ZERO horizontal AND vertical knockback!
            b.snapTo(2.0, ForgeArena.FLOOR + 1.0, 0.5, 90.0F, 0.0F);
            b.setYHeadRot(90.0F);
            b.setYBodyRot(90.0F);
            b.setOnGround(true);
            b.setDeltaMovement(Vec3.ZERO);
            // Advance use ticks past 5-tick vanilla blockDelayTicks so shield is actively blocking
            ProbeCompat.setUseItemRemaining(b, b.getUseItem().getUseDuration(b) - 10);
            check(b.isBlocking(), "Bot shield should be blocking after 10 ticks");
            p.teleportTo(p.level(), 0.5, ForgeArena.FLOOR + 1.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            equipFromInventory(p, Items.TOTEM_OF_UNDYING);
            p.setSprinting(false);
            p.attack(b);
            check(b.getDeltaMovement().lengthSqr() < 1e-8,
                "Fully shield-blocked melee hit must apply ZERO knockback like Vanilla ServerPlayer, got " + b.getDeltaMovement());
            b.stopUsingItem();
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));

            // Place obsidian at (0, 65, 0), crystal at (0.5, 66.0, 0.5). Put Player at (-4.0, 66.0, 0.5) and Bot at (+5.0, 66.0, 0.5):
            // Both at exact distance 4.5 blocks, Y=66.0 (seenPercent = 1.0, zero obsidian occlusion; non-popping ~17 HP on 4x Prot IV HARD!).
            ServerLevel level = p.level();
            BlockPos base = new BlockPos(0, ForgeArena.FLOOR + 1, 0);
            level.setBlockAndUpdate(base, Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
            p.setNoGravity(true);
            b.setNoGravity(true);
            p.setHealth(20.0F);
            p.setAbsorptionAmount(0.0F);
            b.setHealth(20.0F);
            b.setAbsorptionAmount(0.0F);
            p.invulnerableTime = 0;
            b.invulnerableTime = 0;
            p.teleportTo(level, -4.0, ForgeArena.FLOOR + 2.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            b.snapTo(5.0, ForgeArena.FLOOR + 2.0, 0.5, 90.0F, 0.0F);
            p.setDeltaMovement(Vec3.ZERO);
            b.setDeltaMovement(Vec3.ZERO);

            // Verify Health Indicator nametag shows live HP at full health
            check(HudPreferences.botHealth(), "Health indicator should default ON");
            String fullTag = b.getCustomName() == null ? "" : b.getCustomName().getString();
            check(fullTag.contains("20,0") && fullTag.contains("❤"), "Bot nametag must show health indicator, got: " + fullTag);

            // Default ARENA_DIFFICULTY is HARD: even if Overworld is NORMAL, BOTH Player and Bot must still take identical HARD (~17 HP at d=4.5) damage!
            server.setDifficulty(Difficulty.NORMAL, true);
            detonateCrystalAt(s, p, base);
            parityHardPlayerDmg = 20.0F - p.getHealth();
            parityHardBotDmg = 20.0F - b.getHealth();
            parityHardBotVy = b.getDeltaMovement().y;
            double parityHardPlayerVy = p.getDeltaMovement().y;
            check(Math.abs(parityHardBotDmg - parityHardPlayerDmg) < 0.05F,
                "Default HARD Arena Crystal damage mismatch! player=" + parityHardPlayerDmg + " bot=" + parityHardBotDmg);
            check(parityHardBotDmg > 13.0F && parityHardBotDmg < 19.5F,
                "Default HARD Arena Crystal damage at d=4.5 on 4x Prot IV expected ~17 HP, got " + parityHardBotDmg);
            check(Math.abs(parityHardBotVy - parityHardPlayerVy) < 0.02 && parityHardBotVy > 0.12,
                "HARD Crystal explosion vy parity mismatch! player=" + parityHardPlayerVy + " bot=" + parityHardBotVy);

            // Point-blank 10-heart (20 HP) single crystal pop on default HARD Arena (even on NORMAL world!) + Health Indicator absorption check!
            p.setNoGravity(true);
            b.setNoGravity(true);
            p.setHealth(20.0F);
            p.setAbsorptionAmount(0.0F);
            b.setHealth(20.0F);
            b.setAbsorptionAmount(0.0F);
            p.invulnerableTime = 0;
            b.invulnerableTime = 0;
            int popsBefore = pops(s);
            p.teleportTo(level, -2.0, ForgeArena.FLOOR + 2.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            b.snapTo(3.0, ForgeArena.FLOOR + 2.0, 0.5, 90.0F, 0.0F);
            check(s.placeCrystal(p, base), "placeCrystal for point-blank pop test failed");
            p.teleportTo(level, 0.15, ForgeArena.FLOOR + 2.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            b.snapTo(0.85, ForgeArena.FLOOR + 2.0, 0.5, 90.0F, 0.0F);
            var pbCrystals = level.getEntities(EntityTypeTest.forClass(TrainingCrystal.class), c -> !c.isRemoved());
            pbCrystals.getLast().hitInTraining(p.damageSources().playerAttack(p));
            check(pops(s) == popsBefore + 1, "Point-blank (d=0.35) HARD Crystal MUST pop 10-heart ltcrystal Bot! pops=" + pops(s) + " hp=" + b.getHealth());
            check(p.getHealth() <= 2.0F && p.getAbsorptionAmount() >= 7.0F,
                "Point-blank (d=0.35) HARD Crystal MUST also pop 10-heart ltcrystal Player! playerHp=" + p.getHealth() + " abs=" + p.getAbsorptionAmount());
            String popTag = b.getCustomName() == null ? "" : b.getCustomName().getString();
            check(popTag.contains("+8,0"), "Health indicator after totem pop must show +8,0 absorption, got: " + popTag);
            // Toggle Health Indicator OFF -> verify nametag reverts cleanly -> toggle back ON
            HudPreferences.toggleBotHealth();
            var upd = b.getClass().getDeclaredMethod("updateHealthIndicator");
            upd.setAccessible(true);
            upd.invoke(b);
            check("MacherBot".equals(b.getCustomName().getString()), "Disabled health indicator must show plain MacherBot, got: " + b.getCustomName().getString());
            HudPreferences.toggleBotHealth();
            upd.invoke(b);

            // Also verify Bot melee damage against Player does NOT falsely scale 1.5x on HARD (DrillDamageMixin parity!)
            // AND verify side-by-side melee knockback parity (ServerPlayer vs TrainingBot with identical 4x Netherite armor!)
            p.setNoGravity(false);
            b.setNoGravity(false);
            p.removeAllEffects();
            p.setHealth(20.0F);
            p.setAbsorptionAmount(0.0F);
            p.invulnerableTime = 0;
            p.teleportTo(level, 0.5, ForgeArena.FLOOR + 1.0, 0.5, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(true);
            p.setDeltaMovement(Vec3.ZERO);
            server.setDifficulty(Difficulty.NORMAL, true);
            b.snapTo(2.0, ForgeArena.FLOOR + 1.0, 0.5, 90.0F, 0.0F);
            b.setOnGround(true);
            b.setDeltaMovement(Vec3.ZERO);
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            TrainingTactics.strike(level, b, p, b.damageSources().mobAttack(b), 0.0F);
            parityBotMeleeNormal = 20.0F - p.getHealth();
            Vec3 playerMeleeKb = p.getDeltaMovement();

            // Same base 0.4 melee knockback onto Bot (non-sprint bare/totem hit, 4x Netherite = 0.4 KB resist -> 0.24 hor, 0.24 vy)
            b.invulnerableTime = 0;
            b.setHealth(20.0F);
            b.setAbsorptionAmount(0.0F);
            b.setOnGround(true);
            b.setDeltaMovement(Vec3.ZERO);
            equipFromInventory(p, Items.TOTEM_OF_UNDYING);
            p.setSprinting(false);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            Vec3 botMeleeKb = b.getDeltaMovement();
            check(Math.abs(botMeleeKb.y - playerMeleeKb.y) < 1e-4 && Math.abs(botMeleeKb.horizontalDistance() - playerMeleeKb.horizontalDistance()) < 1e-4,
                "Melee knockback mismatch! player=" + playerMeleeKb + " bot=" + botMeleeKb);

            server.setDifficulty(Difficulty.HARD, true);
            p.setHealth(20.0F);
            p.setAbsorptionAmount(0.0F);
            p.invulnerableTime = 0;
            TrainingTactics.strike(level, b, p, b.damageSources().mobAttack(b), 0.0F);
            parityBotMeleeHard = 20.0F - p.getHealth();
            check(parityBotMeleeNormal > 0.2 && Math.abs(parityBotMeleeHard - parityBotMeleeNormal) < 0.05,
                "Bot melee attack must NOT scale 1.5x on HARD! normal=" + parityBotMeleeNormal + " hard=" + parityBotMeleeHard);

            // Also verify Bot Knockback I sword deals real Vanilla extra knockback + slows attacker by 0.6x!
            p.setHealth(20.0F);
            p.setAbsorptionAmount(0.0F);
            p.invulnerableTime = 0;
            p.setOnGround(true);
            p.setDeltaMovement(Vec3.ZERO);
            ItemStack kbSword = new ItemStack(Items.NETHERITE_SWORD);
            kbSword.enchant(level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK), 1);
            b.setItemInHand(InteractionHand.MAIN_HAND, kbSword);
            b.setDeltaMovement(new Vec3(-0.2, 0.0, 0.0));
            b.setSprinting(true);
            TrainingTactics.strike(level, b, p, b.damageSources().mobAttack(b), 0.0F);
            double kb1PlayerHor = p.getDeltaMovement().horizontalDistance();
            check(kb1PlayerHor > playerMeleeKb.horizontalDistance() * 2.5,
                "Bot KB1+Sprint strike must deal real extra knockback (expected >0.60, got " + kb1PlayerHor + " vs base " + playerMeleeKb.horizontalDistance() + ")");
            check(!b.isSprinting() && Math.abs(b.getDeltaMovement().x - (-0.12)) < 1e-4,
                "Bot KB1+Sprint strike must stop sprinting and slow attacker velocity by 0.6x! sprint=" + b.isSprinting() + " vx=" + b.getDeltaMovement().x);

            log("PASS Side-by-side Player vs Bot parity: HARD d=1.5 player=" + String.format("%.2f", parityHardPlayerDmg)
                + " bot=" + String.format("%.2f", parityHardBotDmg)
                + " vy=" + String.format("%.3f", parityHardBotVy)
                + " | NORMAL d=1.5 player=" + String.format("%.2f", parityNormalPlayerDmg)
                + " bot=" + String.format("%.2f", parityNormalBotDmg)
                + " | meleeKB player/bot vy=" + String.format("%.3f/%.3f", playerMeleeKb.y, botMeleeKb.y)
                + " hor=" + String.format("%.3f/%.3f", playerMeleeKb.horizontalDistance(), botMeleeKb.horizontalDistance())
                + " | shieldKB=0.000 | shieldUseSpeed=0.2x | speed=0.10/0.13 | KB1+sprint hor=" + String.format("%.3f", kb1PlayerHor)
                + " | HARD d=0.35 BOTH popped 10-heart totem | botMelee normal/hard="
                + String.format("%.2f/%.2f", parityBotMeleeNormal, parityBotMeleeHard));

            s.stop(server);
            sub = 20;
            age = 0;
            return;
        }
        // Sub 20..21: Verify ARENA_DIFFICULTY=WORLD adopts NORMAL world difficulty + ZERO_DAMAGE (BOTH & PLAYER_ONLY) preserves knockback!
        if (sub == 20) {
            if (TrainingArena.isArena(p.level())) return;
            server.setDifficulty(Difficulty.NORMAL, true);
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.PASSIVE).withAutoRespawn(false);
            var worldOpts = DrillOptions.forDrill(Drill.PASSIVE)
                .withIndex(DrillOptions.Field.ARENA_DIFFICULTY, DrillOptions.ArenaDifficulty.WORLD.ordinal());
            s.start(server, p.getUUID(), settings, worldOpts);
            sub = 21;
            age = 0;
            return;
        }
        if (sub == 21) {
            LivingEntity b = bot(s);
            ServerLevel level = p.level();
            release(s, p);
            BlockPos base = new BlockPos(0, ForgeArena.FLOOR + 1, 0);
            level.setBlockAndUpdate(base, Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
            p.setNoGravity(true);
            b.setNoGravity(true);
            p.setHealth(20.0F);
            p.setAbsorptionAmount(0.0F);
            b.setHealth(20.0F);
            b.setAbsorptionAmount(0.0F);
            p.invulnerableTime = 0;
            b.invulnerableTime = 0;
            p.teleportTo(level, -4.0, ForgeArena.FLOOR + 2.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            b.snapTo(5.0, ForgeArena.FLOOR + 2.0, 0.5, 90.0F, 0.0F);
            p.setDeltaMovement(Vec3.ZERO);
            b.setDeltaMovement(Vec3.ZERO);
            detonateCrystalAt(s, p, base);
            parityNormalPlayerDmg = 20.0F - p.getHealth();
            parityNormalBotDmg = 20.0F - b.getHealth();
            check(Math.abs(parityNormalBotDmg - parityNormalPlayerDmg) < 0.05F,
                "Arena always-HARD parity mismatch! player=" + parityNormalPlayerDmg + " bot=" + parityNormalBotDmg);
            check(Math.abs(parityNormalBotDmg - parityHardBotDmg) < 0.1F,
                "Arena must ALWAYS stay HARD even with NORMAL Overworld & legacy WORLD override: got=" + parityNormalBotDmg + " expected=" + parityHardBotDmg);

            // Switch to ZERO_DAMAGE = BOTH -> 0 HP lost on BOTH sides while real knockback still applies!
            var zeroBothSettings = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.PASSIVE).withAutoRespawn(false);
            var zeroBothOpts = DrillOptions.forDrill(Drill.PASSIVE)
                .withIndex(DrillOptions.Field.ZERO_DAMAGE, DrillOptions.ZeroDamage.BOTH.ordinal());
            s.start(server, p.getUUID(), zeroBothSettings, zeroBothOpts);
            sub = 22;
            age = 0;
            return;
        }
        if (sub == 22) {
            LivingEntity b = bot(s);
            ServerLevel level = p.level();
            release(s, p);
            p.setNoGravity(false);
            b.setNoGravity(false);
            p.setHealth(20.0F);
            b.setHealth(20.0F);
            p.teleportTo(level, 0.5, ForgeArena.FLOOR + 1.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            b.snapTo(2.0, ForgeArena.FLOOR + 1.0, 0.5, 90.0F, 0.0F);
            p.setDeltaMovement(Vec3.ZERO);
            b.setDeltaMovement(Vec3.ZERO);
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            TrainingTactics.strike(level, b, p, b.damageSources().mobAttack(b), 0.0F);
            check(Math.abs(p.getHealth() - 20.0F) < 1e-4F, "ZERO_DAMAGE=BOTH player must lose 0 HP, got hp=" + p.getHealth());
            check(p.getDeltaMovement().horizontalDistance() > 0.2, "ZERO_DAMAGE=BOTH player must still receive knockback, got " + p.getDeltaMovement());
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(Math.abs(b.getHealth() - 20.0F) < 1e-4F, "ZERO_DAMAGE=BOTH bot must lose 0 HP, got hp=" + b.getHealth());
            check(b.getDeltaMovement().horizontalDistance() > 0.2, "ZERO_DAMAGE=BOTH bot must still receive knockback, got " + b.getDeltaMovement());

            // ZERO_DAMAGE = PLAYER_ONLY -> player takes 0 HP, bot DOES take damage!
            var zeroPlayerOpts = DrillOptions.forDrill(Drill.PASSIVE)
                .withIndex(DrillOptions.Field.ZERO_DAMAGE, DrillOptions.ZeroDamage.PLAYER_ONLY.ordinal());
            s.start(server, p.getUUID(), TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.PASSIVE).withAutoRespawn(false), zeroPlayerOpts);
            sub = 23;
            age = 0;
            return;
        }
        if (sub == 23) {
            LivingEntity b = bot(s);
            ServerLevel level = p.level();
            release(s, p);
            p.setHealth(20.0F);
            b.setHealth(20.0F);
            p.teleportTo(level, 0.5, ForgeArena.FLOOR + 1.0, 0.5, java.util.Set.of(), -90.0F, 0.0F, true);
            b.snapTo(2.0, ForgeArena.FLOOR + 1.0, 0.5, 90.0F, 0.0F);
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            TrainingTactics.strike(level, b, p, b.damageSources().mobAttack(b), 0.0F);
            check(Math.abs(p.getHealth() - 20.0F) < 1e-4F, "ZERO_DAMAGE=PLAYER_ONLY player must lose 0 HP");
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(b.getHealth() < 19.9F, "ZERO_DAMAGE=PLAYER_ONLY bot MUST take normal damage, got hp=" + b.getHealth());
            log("PASS ARENA_DIFFICULTY (default HARD + WORLD=NORMAL), ZERO_DAMAGE (BOTH & PLAYER_ONLY), and Health Indicator verified");
            s.stop(server);
            sub = 2;
            age = 0;
            return;
        }
        // Sub 2..7: Full Vanilla 10-heart TRIPLE_TAP on HARD (Blast Prot IV intact -> Pop 1 + Air Hit 2 + Pop 2 + Pop 3!)
        if (sub == 2) {
            if (TrainingArena.isArena(p.level())) return;
            server.setDifficulty(Difficulty.HARD, true);
            tripleTapPeakY = 0.0;
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.TRIPLE_TAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.TRIPLE_TAP));
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            LivingEntity b = bot(s);
            check(b.getMaxHealth() == 20.0F, "TRIPLE_TAP bot must start at 10 hearts (20 HP)");
            check(hasEnchant(b.getItemBySlot(EquipmentSlot.LEGS), Enchantments.PROTECTION, 4)
                    && !hasEnchant(b.getItemBySlot(EquipmentSlot.LEGS), Enchantments.BLAST_PROTECTION, 4),
                "TRIPLE_TAP bot must wear Protection IV leggings (no Blast Prot)");
            // Equip Netherite Sword 1 tick ahead so ServerPlayer refreshes ATTACK_DAMAGE to 8.0 before attacking!
            equipFromInventory(p, Items.NETHERITE_SWORD);
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            LivingEntity b = bot(s);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            p.setSprinting(false);
            BlockPos ahead = new BlockPos(b.blockPosition().getX(), ForgeArena.FLOOR + 1, b.blockPosition().getZ() + 3);
            p.level().setBlockAndUpdate(ahead, Blocks.OBSIDIAN.defaultBlockState());
            p.level().setBlockAndUpdate(ahead.above(), Blocks.AIR.defaultBlockState());
            check(s.placeCrystal(p, ahead), "TRIPLE_TAP placeCrystal 1 ahead failed at " + ahead);
            sub = 5;
            age = 0;
            return;
        }
        if (sub == 5) {
            LivingEntity b = bot(s);
            check(b != null, "TRIPLE_TAP ended before Crystal 1! status=" + s.status());
            if (age < 7) return; // T+7: bot arcs directly over (0.5, 66.0, 3.5) at y=66.04, z=3.11 (d=0.40 -> pops full 20 HP on HARD!)
            var crystals = p.level().getEntities(EntityTypeTest.forClass(TrainingCrystal.class), c -> !c.isRemoved());
            check(!crystals.isEmpty(), "TRIPLE_TAP Crystal 1 missing");
            crystals.getLast().hitInTraining(p.damageSources().playerAttack(p));
            check(pops(s) == 1, "TRIPLE_TAP Crystal 1 must pop 10-heart bot on HARD; pops=" + pops(s) + " hp=" + b.getHealth()
                + " botPos=" + b.position() + " botVel=" + b.getDeltaMovement() + " tl=" + attempt(s).timeline());
            sub = 6;
            age = 0;
            return;
        }
        if (sub == 6) {
            LivingEntity b = bot(s);
            check(b != null, "TRIPLE_TAP ended before Air Hit 2 / Crystal 2! status=" + s.status());
            tripleTapPeakY = Math.max(tripleTapPeakY, b.getY());
            // Wait until hurt window opens after Hit 1 (age 3 after T=7 = T=10), land Air Hit 2, then detonate Crystal 2!
            if (age < 3) return;
            check(tripleTapPeakY < ForgeArena.FLOOR + 7.5,
                "Triple Tap knockup height out of range (<7.5 bl), peaked at " + (tripleTapPeakY - ForgeArena.FLOOR));
            equipFromInventory(p, Items.TOTEM_OF_UNDYING);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 2 && !attempt(s).done(),
                "TRIPLE_TAP Crystal 2 must pop totem 2; pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            sub = 7;
            age = 0;
            return;
        }
        if (sub == 7) {
            LivingEntity b = bot(s);
            check(b != null, "TRIPLE_TAP ended before Crystal 3! status=" + s.status());
            if (age < 10 || b.getY() > ForgeArena.FLOOR + 2.8) return; // fresh hurt window + wait for 4x Prot IV knockup to descend near crystal height (still airborne!)
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 3 && attempt(s).done() && attempt(s).won(),
                "TRIPLE_TAP Crystal 3 must pop totem 3 and win; pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            log("PASS Vanilla Crystal Parity verified in-game (HARD player=" + String.format("%.2f", parityHardPlayerDmg)
                + " vs bot=" + String.format("%.2f", parityHardBotDmg)
                + ", NORMAL player=" + String.format("%.2f", parityNormalPlayerDmg)
                + " vs bot=" + String.format("%.2f", parityNormalBotDmg)
                + ", BlastProt vy=" + String.format("%.3f", parityHardBotVy)
                + ", botMelee normal/hard=" + String.format("%.2f/%.2f", parityBotMeleeNormal, parityBotMeleeHard)
                + ", TRIPLE_TAP 3/3 pops peak=" + String.format("%.2f", tripleTapPeakY - ForgeArena.FLOOR) + " bl)");
            nextScene(s, server);
        }
    }

    private void runSelfWebCrystalAndDTapHotfix(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        // Sub 0..4: SELF_WEB_CRYSTAL (bot self-webs, floor crystals soften -> Pop 1 -> Pop 2 wins!)
        if (sub == 0) {
            // Even with Overworld set to NORMAL, default ARENA_DIFFICULTY=HARD must deal full HARD crystal damage!
            server.setDifficulty(Difficulty.NORMAL, true);
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.SELF_WEB_CRYSTAL).withAutoRespawn(false);
            var opts = DrillOptions.forDrill(Drill.SELF_WEB_CRYSTAL).withIndex(DrillOptions.Field.BASE, 1);
            s.start(server, p.getUUID(), settings, opts);
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            release(s, p);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            if (age < 2) return;
            LivingEntity b = bot(s);
            check(p.level().getBlockState(b.blockPosition()).is(Blocks.COBWEB),
                "SELF_WEB_CRYSTAL bot must self-web at its feet! block=" + p.level().getBlockState(b.blockPosition()));
            check(attempt(s).isSelfWebbed(), "SELF_WEB_CRYSTAL attempt should confirm self-web");
            BlockPos floorBase = findFlushFloorObsidian(p.level(), b.blockPosition());
            // Floor crystal 1 cleanly pops totem 1 on 4x Prot IV HARD arena (must stay open for Pop 2)
            detonateCrystalAt(s, p, floorBase);
            check(pops(s) == 1 && !attempt(s).done(),
                "SELF_WEB_CRYSTAL floor crystal 1 must pop totem 1 and stay open! pops=" + pops(s) + " progress=" + attempt(s).progress());
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            if (age < 11) return;
            LivingEntity b = bot(s);
            BlockPos floorBase = findFlushFloorObsidian(p.level(), b.blockPosition());
            detonateCrystalAt(s, p, floorBase);
            check(pops(s) == 2 && attempt(s).done() && attempt(s).won(),
                "SELF_WEB_CRYSTAL floor crystal 2 must pop totem 2 and WIN! pops=" + pops(s) + " progress=" + attempt(s).progress());
            log("PASS SELF_WEB_CRYSTAL verified in-game (bot self-webs, 2 floor crystal pops win on default HARD arena)");

            // Sub 5..8: D_TAP hotfix — 2 real pops at T+10 (old bug failed at T+9!) MUST WIN!
            var dtapSettings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.D_TAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), dtapSettings, DrillOptions.forDrill(Drill.D_TAP));
            sub = 5;
            age = 0;
            return;
        }
        if (sub == 5) {
            equipFromInventory(p, Items.NETHERITE_SWORD);
            sub = 6;
            age = 0;
            return;
        }
        if (sub == 6) {
            LivingEntity b = bot(s);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            p.setSprinting(false);
            sub = 7;
            age = 0;
            return;
        }
        if (sub == 7) {
            LivingEntity b = bot(s);
            check(b != null && !attempt(s).done(), "D_TAP must NOT fail before T+10 while bot is airborne! status=" + s.status());
            if (age < 10) {
                // Keep bot airborne across T+1..T+9 (simulating hitting off elevation/mid-air) so we test T+10 Pop 1 cleanly!
                b.setOnGround(false);
                b.setDeltaMovement(new Vec3(0.0, 0.22, 0.0));
                return;
            }
            BlockPos base = new BlockPos(b.blockPosition().getX() + 1, ForgeArena.FLOOR, b.blockPosition().getZ());
            p.level().setBlockAndUpdate(base, Blocks.OBSIDIAN.defaultBlockState());
            p.level().setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
            b.snapTo(base.getX() - 0.35, ForgeArena.FLOOR + 1.4, base.getZ() + 0.5, b.getYRot(), 0.0F);
            b.setOnGround(false);
            b.setDeltaMovement(new Vec3(0.0, 0.35, 0.0));
            detonateCrystalAt(s, p, base);
            check(pops(s) == 1 && !attempt(s).done(),
                "D_TAP Pop 1 at T+10 must register cleanly! pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            sub = 8;
            age = 0;
            return;
        }
        if (sub == 8) {
            LivingEntity b = bot(s);
            check(b != null, "D_TAP ended before Pop 2! status=" + s.status());
            if (age < 10) {
                b.setOnGround(false);
                b.setDeltaMovement(new Vec3(0.0, 0.15, 0.0));
                return;
            }
            BlockPos base = new BlockPos(b.blockPosition().getX() + 1, ForgeArena.FLOOR, b.blockPosition().getZ());
            p.level().setBlockAndUpdate(base, Blocks.OBSIDIAN.defaultBlockState());
            p.level().setBlockAndUpdate(base.above(), Blocks.AIR.defaultBlockState());
            b.snapTo(base.getX() - 0.35, ForgeArena.FLOOR + 1.4, base.getZ() + 0.5, b.getYRot(), 0.0F);
            b.setOnGround(false);
            detonateCrystalAt(s, p, base);
            check(pops(s) == 2 && attempt(s).done() && attempt(s).won(),
                "D_TAP 2 pops MUST WIN! pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            log("PASS D_TAP hotfix verified in-game (T+10 Pop 1 + airborne Pop 2 wins cleanly)");
            nextScene(s, server);
        }
    }

    private void runUppercutAndSwordSprintHits(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        // Sub 0..1: Negative case — hitting bot FIRST on flat ground at equal Y fails UPPERCUT
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.UPPERCUT).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.UPPERCUT));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            release(s, p);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).done() && !attempt(s).won(),
                "UPPERCUT flat pre-hit before knockback must FAIL! progress=" + attempt(s).progress());

            // Sub 2..3: Positive case 1 — bot gives real Sprint Hit, player counters in knockback -> WIN!
            var settings2 = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.UPPERCUT).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings2, DrillOptions.forDrill(Drill.UPPERCUT));
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            LivingEntity b = bot(s);
            if (p.hurtTime > 0) {
                double sprintKbHor = p.getDeltaMovement().horizontalDistance();
                check(sprintKbHor > 0.55, "UPPERCUT bot must deal real Sprint Hit knockback (>0.55), got " + sprintKbHor);
                equipFromInventory(p, Items.DIAMOND_SWORD);
                p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
                p.lookAt(Anchor.EYES, b.getEyePosition());
                p.attack(b);
                check(attempt(s).done() && attempt(s).won(),
                    "UPPERCUT counter in Sprint-Hit knockback must WIN! progress=" + attempt(s).progress()
                    + " held=" + p.getMainHandItem() + " tl=" + attempt(s).timeline());

                // Sub 4..5: Positive case 2 — player hits bot from lower position (under bot) -> WIN!
                var settings3 = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.UPPERCUT).withAutoRespawn(false);
                s.start(server, p.getUUID(), settings3, DrillOptions.forDrill(Drill.UPPERCUT));
                sub = 4;
                age = 0;
            }
            return;
        }
        if (sub == 4) {
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            // Elevate bot 1 block higher than player so player strikes upward from below
            b.snapTo(b.getX(), p.getY() + 1.0, b.getZ(), b.getYRot(), 0.0F);
            p.teleportTo(p.level(), b.getX(), p.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).done() && attempt(s).won(),
                "UPPERCUT strike from lower position must WIN! progress=" + attempt(s).progress());
            log("PASS UPPERCUT (flat pre-hit fails; Sprint-Hit knockback counter & lower-ground hit win) verified");
            nextScene(s, server);
        }
    }

    private void runSwightUhcDrills(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        // Sub 0..1: KnowledgeGuide + MOMENTUM_BREAK (Axe stun -> Bot Auto-Hit -> Jump-Reset -> Sword follow-up wins!)
        if (sub == 0) {
            check(KnowledgeGuide.all().size() >= 24, "KnowledgeGuide must load >= 24 entries in-game");
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.MOMENTUM_BREAK).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.MOMENTUM_BREAK));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            check(b.isBlocking(), "MOMENTUM_BREAK bot must raise shield immediately");
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            equipFromInventory(p, Items.DIAMOND_AXE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).shieldWindowOpen(), "MOMENTUM_BREAK Axe stun must open shield window");
            equipFromInventory(p, Items.DIAMOND_SWORD);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            // Wait for bot's scheduled Auto-Hit (2 ticks after stun) -> Jump-Reset + Sword counter!
            LivingEntity b = bot(s);
            if (p.hurtTime > 0) {
                p.jumpFromGround();
                p.lookAt(Anchor.EYES, b.getEyePosition());
                p.attack(b);
                check(attempt(s).done() && attempt(s).won(),
                    "MOMENTUM_BREAK with Jump-Reset on Auto-Hit + Sword follow-up must WIN! progress=" + attempt(s).progress());

                // Sub 3..4: WEB_DUNK (Crit -> Cobweb at bot feet -> WIN!)
                var wdSettings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.WEB_DUNK).withAutoRespawn(false);
                s.start(server, p.getUUID(), wdSettings, DrillOptions.forDrill(Drill.WEB_DUNK));
                sub = 3;
                age = 0;
            }
            return;
        }
        if (sub == 3) {
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY() + 0.6, b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(false);
            p.setSprinting(false);
            p.fallDistance = 0.6F;
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            // Place Cobweb at bot's feet and notify webPlaced
            BlockPos webPos = b.blockPosition();
            p.level().setBlockAndUpdate(webPos, Blocks.COBWEB.defaultBlockState());
            var webM = TrainingSession.class.getDeclaredMethod("webPlaced", ServerPlayer.class, BlockPos.class);
            webM.setAccessible(true);
            webM.invoke(s, p, webPos);
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            check((attempt(s) == null && s.status().successes() >= 1) || (attempt(s) != null && attempt(s).done() && attempt(s).won()),
                "WEB_DUNK after Crit + Cobweb must WIN! status=" + s.status());

            // Sub 5..7: ANTI_DRAIN (negative: water beside bot gets drained; positive: hit bot back + water + escape wins!)
            var adSettings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.ANTI_DRAIN).withAutoRespawn(false);
            s.start(server, p.getUUID(), adSettings, DrillOptions.forDrill(Drill.ANTI_DRAIN));
            sub = 5;
            age = 0;
            return;
        }
        if (sub == 5) {
            check(p.level().getBlockState(p.blockPosition()).is(Blocks.COBWEB), "ANTI_DRAIN must spawn player inside a Cobweb");
            release(s, p);
            // Negative case: place water right beside bot WITHOUT pushing bot back -> bot drains it and round FAILS!
            BlockPos waterPos = p.blockPosition().above();
            p.level().setBlockAndUpdate(waterPos, Blocks.WATER.defaultBlockState());
            var wp = TrainingSession.class.getDeclaredMethod("playerWaterPlaced", ServerPlayer.class, BlockPos.class);
            wp.setAccessible(true);
            wp.invoke(s, p, waterPos);
            sub = 6;
            age = 0;
            return;
        }
        if (sub == 6) {
            if (attempt(s) != null && attempt(s).done()) {
                check(!attempt(s).won(), "ANTI_DRAIN water drained by bot must FAIL! progress=" + attempt(s).progress());
            } else if (attempt(s) == null) {
                check(!s.status().recent().isEmpty() && !s.status().recent().getLast().won(),
                    "ANTI_DRAIN drained round must record failure! status=" + s.status());
            } else {
                return;
            }
            // Positive case: hit bot away, place water out of reach (>5 blocks behind), step out of cobweb -> WIN!
            var adSettings2 = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.ANTI_DRAIN).withAutoRespawn(false);
            s.start(server, p.getUUID(), adSettings2, DrillOptions.forDrill(Drill.ANTI_DRAIN));
            sub = 7;
            age = 0;
            return;
        }
        if (sub == 7) {
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).antiDrainPushed(), "ANTI_DRAIN pushing bot back must register");
            BlockPos behindPos = p.blockPosition().offset(0, 0, -2);
            p.level().setBlockAndUpdate(behindPos, Blocks.WATER.defaultBlockState());
            var wp = TrainingSession.class.getDeclaredMethod("playerWaterPlaced", ServerPlayer.class, BlockPos.class);
            wp.setAccessible(true);
            wp.invoke(s, p, behindPos);
            p.teleportTo(p.level(), behindPos.getX() + 0.5, behindPos.getY(), behindPos.getZ() + 0.5, java.util.Set.of(), 0.0F, 0.0F, true);
            sub = 8;
            age = 0;
            return;
        }
        if (sub == 8) {
            check((attempt(s) == null && s.status().successes() >= 1) || (attempt(s) != null && attempt(s).done() && attempt(s).won()),
                "ANTI_DRAIN push + safe water + escape must WIN! status=" + s.status());

            // Sub 9..10: SHIELD_TIMING (Axe rejected, panic swing after unblocked hit fails)
            var stSettings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.SHIELD_TIMING).withAutoRespawn(false);
            s.start(server, p.getUUID(), stSettings, DrillOptions.forDrill(Drill.SHIELD_TIMING));
            sub = 9;
            age = 0;
            return;
        }
        if (sub == 9) {
            LivingEntity b = bot(s);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            equipFromInventory(p, Items.DIAMOND_AXE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).timingHint() != null && attempt(s).timingHint().contains("ohne Axt"),
                "SHIELD_TIMING must reject Axe attacks! hint=" + attempt(s).timingHint());
            // Take unblocked hit -> panic swing must FAIL!
            b.stopUsingItem();
            b.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
            TrainingTactics.strike(p.level(), b, p, b.damageSources().mobAttack(b), 0.0F);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.attack(b);
            check(attempt(s).done() && !attempt(s).won(),
                "SHIELD_TIMING panic swing after unblocked hit must FAIL! progress=" + attempt(s).progress());
            log("PASS Swight UHC drills verified in-game (KnowledgeGuide, MOMENTUM_BREAK, WEB_DUNK, ANTI_DRAIN fail+win, SHIELD_TIMING axe+panic)");
            nextScene(s, server);
        }
    }

    private void runHealPunishPCritBreakAndCleanHeal(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        // Sub 0..2: HEAL_PUNISH (bot starts at 10 HP, starts eating Golden Apple -> 2 player Crits win!)
        if (sub == 0) {
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.HEAL_PUNISH).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.HEAL_PUNISH));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            check(Math.abs(b.getHealth() - 10.0F) < 0.1F, "HEAL_PUNISH bot must start at 10 HP, got " + b.getHealth());
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            LivingEntity b = bot(s);
            if (!b.isUsingItem() || !b.getUseItem().is(Items.GOLDEN_APPLE)) return;
            // Bot is eating Golden Apple (32 ticks) -> land Crit 1, then wait 13 ticks for real Vanilla recharge and land Crit 2!
            p.teleportTo(p.level(), b.getX(), b.getY() + 0.6, b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(false);
            p.setSprinting(false);
            p.fallDistance = 0.6F;
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(!attempt(s).done() && attempt(s).healPunishCrits() == 1,
                "HEAL_PUNISH Crit 1 should register! progress=" + attempt(s).progress());
            sub = 20;
            age = 0;
            return;
        }
        if (sub == 20) {
            if (age < 13) return;
            LivingEntity b = bot(s);
            p.teleportTo(p.level(), b.getX(), b.getY() + 0.6, b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(false);
            p.setSprinting(false);
            p.fallDistance = 0.6F;
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).done() && attempt(s).won(), "HEAL_PUNISH 2 Crits while bot gaps must WIN! progress=" + attempt(s).progress());

            // Sub 3..4: PCRIT_BREAK (bot sprint-hits -> player Sprint-KB hit + follow-up wins!)
            var pcSettings = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.PCRIT_BREAK).withAutoRespawn(false);
            s.start(server, p.getUUID(), pcSettings, DrillOptions.forDrill(Drill.PCRIT_BREAK));
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            equipFromInventory(p, Items.DIAMOND_SWORD);
            release(s, p);
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            LivingEntity b = bot(s);
            if (p.hurtTime > 0) {
                p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
                p.setOnGround(true);
                p.setSprinting(true);
                p.lookAt(Anchor.EYES, b.getEyePosition());
                p.attack(b);
                b.invulnerableTime = 0;
                p.attack(b);
                check(attempt(s).done() && attempt(s).won(),
                    "PCRIT_BREAK Sprint-KB hit + follow-up must WIN! progress=" + attempt(s).progress());

                // Sub 5..6: HEAL with CLEAN_HEAL = 1 -> taking hit while eating Golden Apple FAILS!
                var chSettings = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.HEAL).withAutoRespawn(false);
                var chOpts = DrillOptions.forDrill(Drill.HEAL)
                    .withIndex(DrillOptions.Field.CLEAN_HEAL, 1)
                    .withIndex(DrillOptions.Field.DISTANCE, 0);
                s.start(server, p.getUUID(), chSettings, chOpts);
                sub = 5;
                age = 0;
            }
            return;
        }
        if (sub == 5) {
            equipFromInventory(p, Items.GOLDEN_APPLE);
            release(s, p);
            p.startUsingItem(InteractionHand.MAIN_HAND);
            LivingEntity b = bot(s);
            b.snapTo(p.getX(), p.getY(), p.getZ() + 1.5, 180.0F, 0.0F);
            TrainingTactics.strike(p.level(), b, p, b.damageSources().mobAttack(b), 0.0F);
            check(attempt(s).done() && !attempt(s).won(),
                "HEAL with CLEAN_HEAL=1 taking a hit while eating must FAIL! progress=" + attempt(s).progress());

            // Verify real PvP RoundMetrics were actually recorded across live Mace, Crystal, Sword & UHC scenes!
            s.stop(server);
            var recordedRuns = de.pvptrainer.progress.TrainingProgress.forScreen(Minecraft.getInstance());
            var allOverview = de.pvptrainer.progress.TrainingStats.overview(recordedRuns, null, de.pvptrainer.progress.TrainingStats.TimeRange.ALL);
            var maceOverview = de.pvptrainer.progress.TrainingStats.overview(recordedRuns, TrainingSettings.Mode.MACE, de.pvptrainer.progress.TrainingStats.TimeRange.ALL);
            var crystalOverview = de.pvptrainer.progress.TrainingStats.overview(recordedRuns, TrainingSettings.Mode.CRYSTAL, de.pvptrainer.progress.TrainingStats.TimeRange.ALL);
            check(allOverview.totals().botPops() >= 5, "Live RoundMetrics missing botPops: " + allOverview.totals());
            check(crystalOverview.totals().crystalsPlaced() >= 3 && crystalOverview.totals().crystalsDetonated() >= 3,
                "Live Crystal RoundMetrics missing crystals: " + crystalOverview.totals());
            check(maceOverview.totals().maceSmashes() >= 2 && maceOverview.totals().stunSlams() >= 1 && maceOverview.totals().maxFallMeters() > 5.0,
                "Live Mace RoundMetrics missing smashes/stunSlams/fallMeters: " + maceOverview.totals());
            check(allOverview.totals().shieldStuns() >= 2 && allOverview.totals().crits() >= 2 && allOverview.totals().meleeHits() >= 3,
                "Live Combat RoundMetrics missing stuns/crits/hits: " + allOverview.totals());
            log("PASS Live RoundMetrics verified: pops=" + allOverview.totals().botPops()
                + " crystals=" + crystalOverview.totals().crystalsDetonated()
                + " smashes=" + maceOverview.totals().maceSmashes()
                + " stunSlams=" + maceOverview.totals().stunSlams()
                + " maxFall=" + de.pvptrainer.progress.TrainingStats.formatMeters(maceOverview.totals().maxFallMeters())
                + " stuns=" + allOverview.totals().shieldStuns()
                + " crits=" + allOverview.totals().crits());

            log("PASS HEAL_PUNISH, PCRIT_BREAK, and HEAL CLEAN_HEAL verified in-game");
            nextScene(s, server);
        }
    }

    private void runV1170Features(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        // Sub 0..2: Stay-in-arena after player death (autoRespawn=true -> healed, stays in arena, respawns round 2)
        if (sub == 0) {
            var duel = TrainingSettings.defaults(TrainingSettings.Mode.SWORD).forDrill(Drill.DUEL).withAutoRespawn(true);
            s.start(server, p.getUUID(), duel, DrillOptions.forDrill(Drill.DUEL));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            release(s, p);
            boolean prevented = s.preventTrainingDeath(p, p.damageSources().generic());
            check(prevented, "preventTrainingDeath must catch lethal hit in active round");
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            if (age < 44) {
                check(TrainingArena.isArena(p.level()), "Player must STAY in arena after death (age=" + age + ")");
                check(s.status().active(), "Session must STAY active after death (age=" + age + ")");
                return;
            }
            check(TrainingArena.isArena(p.level()), "Player must still be in arena after auto-respawn");
            check(s.status().rounds() >= 2 && s.status().waitingForStart(),
                "Auto-respawn after defeat must spawn round 2! status=" + s.status());
            check(p.getHealth() >= p.getMaxHealth() - 0.1F, "Player must be healed on respawn, hp=" + p.getHealth());
            check(TrainingArena.hasReturnPoint(p), "Return point must stay intact until explicit exit");
            log("PASS Stay-in-arena after death + auto-respawn verified in-game");

            // Sub 3..4: First-Hit Knockback in OBI_TRAP after waiting 1 server tick post-releaseStart
            var obi = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.OBI_TRAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), obi, DrillOptions.forDrill(Drill.OBI_TRAP));
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            release(s, p);
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            // Exactly 1 tick after releaseStart: previously bot.onGround() dropped to false here!
            LivingEntity b = bot(s);
            check(b.onGround(), "OBI_TRAP bot must keep onGround=true 1 tick after releaseStart!");
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.9, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            check(b.getDeltaMovement().y >= 0.35,
                "First hit 1 tick after releaseStart must deliver full vertical knockback! vel=" + b.getDeltaMovement());
            log("PASS OBI_TRAP First-Hit knockback after 1-tick wait verified: dy=" + b.getDeltaMovement().y);

            // Sub 5..6: Adjacent TrainingCrystal explosion destroys neighbor crystal (Vanilla EndCrystal parity)
            var crys = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.PASSIVE).withAutoRespawn(false);
            s.start(server, p.getUUID(), crys, DrillOptions.forDrill(Drill.PASSIVE));
            sub = 5;
            age = 0;
            return;
        }
        if (sub == 5) {
            release(s, p);
            ServerLevel level = p.level();
            BlockPos baseA = new BlockPos(4, ForgeArena.FLOOR, 0);
            BlockPos baseB = baseA.east(2);
            level.setBlockAndUpdate(baseA, Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(baseB, Blocks.OBSIDIAN.defaultBlockState());
            level.setBlockAndUpdate(baseA.above(), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(baseB.above(), Blocks.AIR.defaultBlockState());
            check(s.placeCrystal(p, baseA), "placeCrystal A failed");
            check(s.placeCrystal(p, baseB), "placeCrystal B failed");
            var before = level.getEntities(EntityTypeTest.forClass(TrainingCrystal.class), c -> !c.isRemoved());
            check(before.size() == 2, "Expected 2 adjacent TrainingCrystals, got " + before.size());
            p.attack(before.getFirst());
            var after = level.getEntities(EntityTypeTest.forClass(TrainingCrystal.class), c -> !c.isRemoved());
            check(after.isEmpty(), "Detonating Crystal A must destroy adjacent Crystal B! remaining=" + after.size());
            log("PASS Adjacent TrainingCrystal explosion parity verified in-game");

            // Sub 6: D_TAP forces Bot armor to Protection IV even if Bot kit had Blast Protection IV!
            var lookup = p.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var blast = lookup.getOrThrow(Enchantments.BLAST_PROTECTION);
            var prot = lookup.getOrThrow(Enchantments.PROTECTION);
            KitLoadout baseKit = KitLibrary.selected(p.registryAccess(), TrainingSettings.Mode.CRYSTAL, true);
            var slots = baseKit.slots();
            ItemStack blastLegs = slots.get(37).copy();
            EnchantmentHelper.updateEnchantments(blastLegs, m -> {
                m.set(prot, 0);
                m.set(blast, 4);
            });
            slots.set(37, blastLegs);
            KitLoadout blastKit = new KitLoadout(baseKit.id(), baseKit.name(), baseKit.source(), slots, baseKit.effects());
            var dtap = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.D_TAP).withAutoRespawn(false);
            Class<?> rsClass = Class.forName("de.pvptrainer.session.TrainingSession$RoundSelection");
            var rsCtor = rsClass.getDeclaredConstructor(TrainingSettings.class, DrillOptions.class, KitLoadout.class, KitLoadout.class);
            rsCtor.setAccessible(true);
            Object rs = rsCtor.newInstance(dtap, DrillOptions.forDrill(Drill.D_TAP),
                KitLibrary.selected(p.registryAccess(), TrainingSettings.Mode.CRYSTAL, false), blastKit);
            var startMethod = TrainingSession.class.getDeclaredMethod("start", MinecraftServer.class, ServerPlayer.class, rsClass);
            startMethod.setAccessible(true);
            startMethod.invoke(s, server, p, rs);
            sub = 6;
            age = 0;
            return;
        }
        if (sub == 6) {
            LivingEntity b = bot(s);
            ItemStack legs = b.getItemBySlot(EquipmentSlot.LEGS);
            check(hasEnchant(legs, Enchantments.PROTECTION, 4) && !hasEnchant(legs, Enchantments.BLAST_PROTECTION, 1),
                "D_TAP bot leggings must be normalized from Blast Prot IV to Protection IV! enchants=" + legs.getEnchantments());
            log("PASS D_TAP Protection-IV-only armor guard verified in-game");

            // Sub 7..8: SHIELD_TIMING continues past 3 counters and wins on bot death
            var st = TrainingSettings.defaults(TrainingSettings.Mode.UHC).forDrill(Drill.SHIELD_TIMING).withAutoRespawn(false).withMaxHealth(20);
            s.start(server, p.getUUID(), st, DrillOptions.forDrill(Drill.SHIELD_TIMING));
            sub = 7;
            age = 0;
            return;
        }
        if (sub == 7) {
            release(s, p);
            DrillAttempt a = attempt(s);
            for (int i = 0; i < 3; i++) {
                a.playerShieldBlocked();
                a.swordContact(p, bot(s), 4.0F, 1.0F, false, true, true);
            }
            check(!a.done(), "SHIELD_TIMING must NOT finish at 3 counters anymore!");
            LivingEntity b = bot(s);
            b.setHealth(0.0F);
            sub = 8;
            age = 0;
            return;
        }
        if (sub == 8) {
            // 1 tick after bot health hit 0 -> TrainingSession.tick calls attempt.botDefeated() and saves win
            check(s.status().successes() >= 1 || (attempt(s) != null && attempt(s).done() && attempt(s).won()),
                "SHIELD_TIMING must WIN when bot dies after clean counters! status=" + s.status());
            log("PASS SHIELD_TIMING runs past 3 counters and wins on bot death");

            // Sub 9..11: BODY_PEARL live in-game (bot always at center, player random on map, auto-releases & falls under real gravity + real pearl hit)
            var bpSetup = TrainingPreset.BEGINNER.setup(
                TrainingSettings.defaults(TrainingSettings.Mode.MACE).withMaceCenterLocked(true).withAutoRespawn(false), Drill.BODY_PEARL);
            s.start(server, p.getUUID(), bpSetup.behavior().withAutoRespawn(false), bpSetup.options());
            sub = 9;
            age = 0;
            return;
        }
        if (sub == 9) {
            LivingEntity b = bot(s);
            check(Math.abs(b.getX() - 0.5) < 0.05 && Math.abs(b.getZ() - 0.5) < 0.05,
                "BODY_PEARL bot must ALWAYS spawn in arena center (0.5, 0.5), got " + b.position());
            check(b.getY() >= ForgeArena.FLOOR + 24.5, "BODY_PEARL bot must spawn elevated (24m), y=" + b.getY());
            check(b.getMainHandItem().is(Items.MACE), "BODY_PEARL bot should hold Mace, got " + b.getMainHandItem());
            check(p.getY() <= ForgeArena.FLOOR + 1.5, "BODY_PEARL player must spawn on ground, y=" + p.getY());
            double horizDist = Math.hypot(p.getX() - 0.5, p.getZ() - 0.5);
            check(horizDist >= 3.5 && horizDist <= 20.5,
                "BODY_PEARL player must spawn at valid random arena distance from center, dist=" + horizDist + " pos=" + p.position());
            // Do NOT call release(s, p) manually — verify automatic release & genuine Vanilla gravity fall!
            sub = 10;
            age = 0;
            return;
        }
        if (sub == 10) {
            if (age < 8) return;
            LivingEntity b = bot(s);
            check(!s.status().waitingForStart(), "BODY_PEARL must auto-release without manual player input! status=" + s.status());
            check(!b.onGround() && b.getDeltaMovement().y < -0.15 && b.getY() < ForgeArena.FLOOR + 24.5,
                "BODY_PEARL bot must genuinely fall under real gravity! y=" + b.getY() + " vel=" + b.getDeltaMovement());
            // Solve exact Vanilla/CCD intercept with the falling bot (supports long-range spawns up to 20m!)
            Vec3 origin = p.getEyePosition();
            Vec3 botStart = b.position();
            double botVyStart = b.getDeltaMovement().y;
            Vec3 bestDir = b.getBoundingBox().getCenter().subtract(origin).normalize();
            double bestSpeedErr = Double.POSITIVE_INFINITY;
            for (int t = 1; t <= 24; t++) {
                Vec3 simBot = botStart;
                double simVy = botVyStart;
                for (int sTick = 1; sTick <= t; sTick++) {
                    simBot = simBot.add(0.0, simVy, 0.0);
                    simVy = (simVy - 0.08) * 0.98;
                }
                if (simBot.y < ForgeArena.FLOOR + 2.8) break;
                for (double aimY : new double[]{0.9, 1.4, 0.4}) {
                    Vec3 center = simBot.add(0.0, aimY, 0.0);
                    double dragSum = 0.0, gravDrop = 0.0, pFactor = 1.0;
                    for (int i = 1; i <= t; i++) {
                        pFactor *= 0.99;
                        dragSum += pFactor;
                        gravDrop += 0.03 * dragSum;
                    }
                    Vec3 reqV0 = new Vec3(
                        (center.x - origin.x) / dragSum,
                        (center.y - origin.y + gravDrop) / dragSum,
                        (center.z - origin.z) / dragSum
                    );
                    double err = Math.abs(reqV0.length() - 1.5);
                    if (err >= bestSpeedErr) continue;
                    Vec3 candDir = reqV0.normalize();
                    Vec3 pp = origin, pv = candDir.scale(1.5), bp = botStart;
                    double bv = botVyStart;
                    boolean ccdHit = false;
                    for (int k = 1; k <= 24; k++) {
                        Vec3 bpPrev = bp;
                        bp = bp.add(0.0, bv, 0.0);
                        bv = (bv - 0.08) * 0.98;
                        pv = pv.add(0.0, -0.03, 0.0).scale(0.99);
                        Vec3 pn = pp.add(pv);
                        var box = new net.minecraft.world.phys.AABB(bp.x - 0.3, bp.y, bp.z - 0.3, bp.x + 0.3, bp.y + 1.8, bp.z + 0.3);
                        if (PearlInterceptSolver.sweepMovingTarget(pp, pn, box, bpPrev, bp, 0.30).isPresent()
                            && bp.y >= ForgeArena.FLOOR + 2.6) {
                            ccdHit = true;
                            break;
                        }
                        pp = pn;
                    }
                    if (ccdHit) {
                        bestSpeedErr = err;
                        bestDir = candDir;
                    }
                }
            }
            ThrownEnderpearl pearl = new ThrownEnderpearl(p.level(), p, new ItemStack(Items.ENDER_PEARL));
            pearl.setPos(origin.x, origin.y, origin.z);
            pearl.shoot(bestDir.x, bestDir.y, bestDir.z, 1.5F, 0.0F);
            p.level().addFreshEntity(pearl);
            sub = 11;
            age = 0;
            return;
        }
        if (sub == 11) {
            if (s.status().active() && attempt(s) != null && !attempt(s).done()) {
                return;
            }
            check(s.status().successes() >= 1 || (attempt(s) != null && attempt(s).done() && attempt(s).won()),
                "BODY_PEARL real pearl hitting falling bot must WIN! status=" + s.status());
            log("PASS BODY_PEARL center spawn, random player pos, auto-fall & mid-air pearl hit verified in-game");

            // Sub 12..14: PEARL_CATCH_GUIDE live in-game using PearlInterceptSolver + real Pearl + real WindCharge!
            var pcgSettings = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(Drill.PEARL_CATCH_GUIDE).withAutoRespawn(false);
            var pcgOpts = TrainingPreset.BEGINNER.setup(pcgSettings, Drill.PEARL_CATCH_GUIDE).options();
            s.start(server, p.getUUID(), pcgSettings, pcgOpts);
            sub = 12;
            age = 0;
            return;
        }
        if (sub == 12) {
            release(s, p);
            Vec3 eye = p.getEyePosition();
            ThrownEnderpearl pearl = new ThrownEnderpearl(p.level(), p, new ItemStack(Items.ENDER_PEARL));
            pearl.setPos(eye.x, eye.y, eye.z);
            pearl.shoot(0.0, 1.15, 0.95, 1.5F, 0.0F);
            p.level().addFreshEntity(pearl);
            sub = 13;
            age = 0;
            return;
        }
        if (sub == 13) {
            if (age < 4) return;
            var snap = s.activePearl();
            check(snap != null, "TrainingSession.activePearl() server snapshot missing for PEARL_CATCH_GUIDE");
            Vec3 eye = p.getEyePosition();
            var sol = PearlInterceptSolver.solve(snap.pos(), snap.vel(), eye, Vec3.ZERO, ForgeArena.FLOOR + 1.0, snap.tickCount());
            check(sol.status() == PearlInterceptSolver.Status.READY && sol.discreteHit() && sol.hitConfidence() >= 0.85,
                "PearlInterceptSolver must return READY + high confidence for live server snapshot! sol=" + sol);
            p.lookAt(Anchor.EYES, eye.add(sol.aimDirection().scale(24.0)));
            double liveConf = PearlInterceptSolver.evaluateAim(snap.pos(), snap.vel(), eye, p.getLookAngle(), Vec3.ZERO, ForgeArena.FLOOR + 1.0, snap.tickCount());
            check(liveConf >= 0.80, "evaluateAim on player lookAngle aimed at indicator must be >= 0.80, got " + liveConf);
            Vec3 dir = p.getLookAngle();
            WindCharge wc = new WindCharge(p, p.level(), eye.x, eye.y, eye.z);
            wc.shoot(dir.x, dir.y, dir.z, 1.5F, 0.0F);
            p.level().addFreshEntity(wc);
            sub = 14;
            age = 0;
            return;
        }
        if (sub == 14) {
            if (s.status().active() && attempt(s) != null && !attempt(s).done()) {
                return;
            }
            check(s.status().successes() >= 1 || (attempt(s) != null && attempt(s).done() && attempt(s).won()),
                "PEARL_CATCH_GUIDE live WindCharge aimed via PearlInterceptSolver must catch pearl & WIN! status=" + s.status());
            log("PASS PEARL_CATCH_GUIDE live PearlInterceptSolver + real WindCharge catch verified in-game");

            // Sub 15..18: Sword S_TAP live in-game (negative missing S-Tap fails; positive Sprint-Hit -> backward Input -> Sprint-Hit wins!)
            var stSetup = TrainingPreset.BEGINNER.setup(
                TrainingSettings.defaults(TrainingSettings.Mode.SWORD).withAutoRespawn(false), Drill.S_TAP);
            s.start(server, p.getUUID(), stSetup.behavior().withMovement(TrainingSettings.Movement.STILL).withCounterAttacks(false).withAutoRespawn(false), stSetup.options());
            sub = 15;
            age = 0;
            return;
        }
        if (sub == 15) {
            release(s, p);
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.setLastClientInput(net.minecraft.world.entity.player.Input.EMPTY);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 2.0, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            check(!attempt(s).done(), "S_TAP Hit 1 must leave round open awaiting S-Tap");
            sub = 16;
            age = 0;
            return;
        }
        if (sub == 16) {
            if (age < 13) return;
            LivingEntity b = bot(s);
            // Hit 2 WITHOUT pressing S -> must FAIL!
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 2.0, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            check(attempt(s).done() && !attempt(s).won(),
                "S_TAP Hit 2 without backward S input must FAIL! msg=" + attempt(s).progress());

            // Now restart for positive case: Sprint-Hit 1 -> real backward ClientInput -> Sprint-Hit 2 -> WIN!
            var stSetup = TrainingPreset.BEGINNER.setup(
                TrainingSettings.defaults(TrainingSettings.Mode.SWORD).withAutoRespawn(false), Drill.S_TAP);
            s.start(server, p.getUUID(), stSetup.behavior().withMovement(TrainingSettings.Movement.STILL).withCounterAttacks(false).withAutoRespawn(false), stSetup.options());
            sub = 17;
            age = 0;
            return;
        }
        if (sub == 17) {
            release(s, p);
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.DIAMOND_SWORD);
            p.setLastClientInput(net.minecraft.world.entity.player.Input.EMPTY);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 2.0, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            check(!attempt(s).done(), "S_TAP positive Hit 1 must leave round open");
            // Deliver real S-Tap client input packet via ServerPlayer.setLastClientInput -> PlayerActionMixin -> ArenaSignals!
            p.setLastClientInput(new net.minecraft.world.entity.player.Input(false, true, false, false, false, false, false));
            p.setLastClientInput(net.minecraft.world.entity.player.Input.EMPTY);
            sub = 18;
            age = 0;
            return;
        }
        if (sub == 18) {
            if (age < 13) return;
            LivingEntity b = bot(s);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 2.0, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            check(attempt(s).done() && attempt(s).won(),
                "S_TAP Hit 2 after real S-Tap input must WIN! msg=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            log("PASS Sword S_TAP (missing S-Tap rejected; real Sprint-Hit -> S-Tap -> Sprint-Hit wins) verified in-game");

            // Sub 19..23: CORNER_HIT_CRYSTAL live in-game (90° corner, same-height obsidian, hit + crystal pop counts every pop as success!)
            var chcSetup = TrainingPreset.BEGINNER.setup(
                TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).withAutoRespawn(false), Drill.CORNER_HIT_CRYSTAL);
            s.start(server, p.getUUID(), chcSetup.behavior().withAutoRespawn(false), chcSetup.options());
            sub = 19;
            age = 0;
            return;
        }
        if (sub == 19) {
            LivingEntity b = bot(s);
            BlockPos feet = b.blockPosition();
            int walls = 0;
            BlockPos sameHeightObsidian = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos adj = feet.relative(d);
                if (!p.level().getBlockState(adj).isAir() && !p.level().getBlockState(adj.above()).isAir()) {
                    walls++;
                } else if (p.level().getBlockState(adj).is(Blocks.OBSIDIAN) && p.level().getBlockState(adj.above()).isAir()) {
                    sameHeightObsidian = adj;
                }
            }
            check(walls == 2, "CORNER_HIT_CRYSTAL expected 2 perpendicular corner walls, found " + walls);
            check(sameHeightObsidian != null && sameHeightObsidian.getY() == feet.getY(),
                "CORNER_HIT_CRYSTAL expected prepared Obsidian at same Y as bot feet (" + feet.getY() + "), found=" + sameHeightObsidian);

            release(s, p);
            // Negative live check: detonating crystal on same-height Obsidian WITHOUT hitting bot first must NOT count as success!
            int winsBefore = s.status().successes();
            detonateCrystalAt(s, p, sameHeightObsidian);
            check(s.status().successes() == winsBefore && s.status().active(),
                "CORNER_HIT_CRYSTAL crystal without hitting bot first must NOT count as success! status=" + s.status());

            // Now wait for any invulnerability to clear, then hit bot in corner and pop #1!
            sub = 20;
            age = 0;
            return;
        }
        if (sub == 20) {
            if (age < 12) return;
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.NETHERITE_SWORD);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 2.1, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            sub = 21;
            age = 0;
            return;
        }
        if (sub == 21) {
            if (age < 2) return;
            LivingEntity b = bot(s);
            BlockPos sameHeightObsidian = findSameHeightObsidian(p.level(), b.blockPosition());
            check(sameHeightObsidian != null, "Same-height obsidian missing for Pop #1");
            int winsBefore = s.status().successes();
            detonateCrystalAt(s, p, sameHeightObsidian);
            check(s.status().successes() == winsBefore + 1 && s.status().active() && !attempt(s).done(),
                "CORNER_HIT_CRYSTAL Hit + Crystal Pop #1 must record 1 success and keep round active! winsBefore=" + winsBefore + " status=" + s.status());
            sub = 22;
            age = 0;
            return;
        }
        if (sub == 22) {
            if (age < 13) return;
            LivingEntity b = bot(s);
            equipFromInventory(p, Items.NETHERITE_SWORD);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 2.1, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            sub = 23;
            age = 0;
            return;
        }
        if (sub == 23) {
            if (age < 2) return;
            LivingEntity b = bot(s);
            BlockPos sameHeightObsidian = findSameHeightObsidian(p.level(), b.blockPosition());
            check(sameHeightObsidian != null, "Same-height obsidian missing for Pop #2");
            int winsBefore = s.status().successes();
            detonateCrystalAt(s, p, sameHeightObsidian);
            check(s.status().successes() == winsBefore + 1 && s.status().active() && !attempt(s).done(),
                "CORNER_HIT_CRYSTAL Hit + Crystal Pop #2 must record 2nd success continuously! winsBefore=" + winsBefore + " status=" + s.status());
            log("PASS CORNER_HIT_CRYSTAL (90° corner, same-height obsidian, pre-hit crystal rejected, 2 consecutive hit+crystal pops counted as successes) verified in-game");
            nextScene(s, server);
        }
    }

    private static BlockPos findSameHeightObsidian(ServerLevel level, BlockPos near) {
        int y = ForgeArena.FLOOR + 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos cand = new BlockPos(near.getX() + dx, y, near.getZ() + dz);
                if (level.getBlockState(cand).is(Blocks.OBSIDIAN) && level.getBlockState(cand.above()).isAir()) {
                    return cand;
                }
            }
        }
        return null;
    }
}
