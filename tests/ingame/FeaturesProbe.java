package de.pvptrainer;

import de.pvptrainer.arena.ForgeArena;
import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.combat.TrainingCrystal;
import de.pvptrainer.combat.TrainingTactics;
import de.pvptrainer.drill.Drill;
import de.pvptrainer.drill.DrillAttempt;
import de.pvptrainer.drill.DrillOptions;
import de.pvptrainer.kits.TrainingSettings;
import de.pvptrainer.session.TrainingSession;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
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
            return hotbar;
        }
        for (int i = 9; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.is(item)) {
                ItemStack prev = p.getInventory().getItem(0);
                p.getInventory().setItem(0, st);
                p.getInventory().setItem(i, prev);
                p.getInventory().setSelectedSlot(0);
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
            if (done || mc.player == null) return;
            if (mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) {
                mc.gui.setScreen(null);
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
                    case 6 -> runStunSlamHeightsAndGroundTouch(s, server, p);
                    case 7 -> runShieldDrain(s, server, p);
                    case 8 -> runDoubleAnchor(s, server, p);
                    case 9 -> runShieldTripleTap(s, server, p);
                    case 10 -> runJumpResetAndHitSelectSpacing(s, server, p);
                    default -> {
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
                mc.gui.setScreen(dtapScreen);
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
                mc.gui.setScreen(tripleScreen);
                List<String> tripleLabels = tripleScreen.children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget)
                    .map(c -> ((net.minecraft.client.gui.components.AbstractWidget) c).getMessage().getString())
                    .toList();
                boolean tripleHasPresets = java.util.Arrays.stream(de.pvptrainer.kits.TrainingPreset.values())
                    .map(pr -> pr.label(Drill.TRIPLE_TAP))
                    .allMatch(presetLabel -> tripleLabels.stream().anyMatch(l -> l.equals(presetLabel)));
                check(tripleHasPresets, "Triple Tap counter-case missing preset buttons: " + tripleLabels);
                mc.gui.setScreen(null);
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

    private void runStunSlamHeightsAndGroundTouch(TrainingSession s, MinecraftServer server, ServerPlayer p) throws Exception {
        if (sub == 0) {
            // Test height 18 (odd height where vanilla ground-touch packet often races mace click!)
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
            // Simulate player dropping right beside bot, hitting Axe stun, then touching ground slightly (onGround=true, fallDistance=0) before Mace click!
            p.teleportTo(p.level(), b.getX(), b.getY() + 1.2, b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.fallDistance = 16.5F;
            equipFromInventory(p, Items.NETHERITE_AXE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).shieldWindowOpen(), "Axe stun failed at height 18");

            // Slight ground touch right before Mace click
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.setOnGround(true);
            p.fallDistance = 0.0F;
            equipFromInventory(p, Items.MACE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).done() && attempt(s).won(), "STUN_SLAM at height 18 with slight ground touch must win! progress=" + attempt(s).progress());

            // Also test height 19 cleanly
            var settings19 = TrainingSettings.defaults(TrainingSettings.Mode.MACE).forDrill(Drill.STUN_SLAM).withAutoRespawn(false);
            var opts19 = DrillOptions.forDrill(Drill.STUN_SLAM)
                .withIndex(DrillOptions.Field.START_HEIGHT, DrillOptions.Field.START_HEIGHT.indexOfNumber(19));
            s.start(server, p.getUUID(), settings19, opts19);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            check(Math.abs(p.getY() - (ForgeArena.FLOOR + 1 + 19)) < 0.1, "Player did not spawn at height 19: y=" + p.getY());
            LivingEntity b = bot(s);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY() + 0.8, b.getZ() - 1.6, java.util.Set.of(), 0.0F, 0.0F, true);
            p.fallDistance = 18.2F;
            equipFromInventory(p, Items.NETHERITE_AXE);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.attack(b);
            check(attempt(s).shieldWindowOpen(), "Axe stun failed at height 19");

            equipFromInventory(p, Items.MACE);
            p.setOnGround(false);
            p.fallDistance = 18.5F;
            p.attack(b);
            check(attempt(s).done() && attempt(s).won(), "STUN_SLAM at height 19 must win! progress=" + attempt(s).progress());
            log("PASS STUN_SLAM verified at height 18 (with ground-touch race) AND height 19");
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
            var settings = TrainingSettings.defaults(TrainingSettings.Mode.CRYSTAL).forDrill(Drill.SHIELD_TRIPLE_TAP).withAutoRespawn(false);
            s.start(server, p.getUUID(), settings, DrillOptions.forDrill(Drill.SHIELD_TRIPLE_TAP));
            sub = 1;
            age = 0;
            return;
        }
        if (sub == 1) {
            LivingEntity b = bot(s);
            check(!b.isBlocking() && !b.getMainHandItem().is(Items.SHIELD), "SHIELD_TRIPLE_TAP bot must NOT hold shield before Pop 1");
            // Opening totem sprint-hit at full 100% charge (real Crystal PvP D-Tap / Triple-Tap opening)
            equipFromInventory(p, Items.TOTEM_OF_UNDYING);
            release(s, p);
            p.teleportTo(p.level(), b.getX(), b.getY(), b.getZ() - 1.8, java.util.Set.of(), 0.0F, 0.0F, true);
            p.lookAt(Anchor.EYES, b.getEyePosition());
            p.setSprinting(true);
            p.attack(b);
            p.setSprinting(false);
            sub = 2;
            age = 0;
            return;
        }
        if (sub == 2) {
            if (age < 5) return; // apex of knockup
            LivingEntity b = bot(s);
            check(b != null, "SHIELD_TRIPLE_TAP ended before Crystal 1! status=" + s.status());
            BlockPos cBase = findOpenAdjacentBase(p.level(), b);
            detonateCrystalAt(s, p, cBase);
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
            sub = 3;
            age = 0;
            return;
        }
        if (sub == 3) {
            LivingEntity b = bot(s);
            check(b != null, "SHIELD_TRIPLE_TAP ended before Crystal 2! status=" + s.status());
            if (age < 11 || b.getY() > ForgeArena.FLOOR + 4.0) return;
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 2 && !attempt(s).done(),
                "Crystal 2 must pop totem 2; pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            sub = 4;
            age = 0;
            return;
        }
        if (sub == 4) {
            LivingEntity b = bot(s);
            check(b != null, "SHIELD_TRIPLE_TAP ended before Crystal 3! status=" + s.status());
            if (age < 11 || b.getY() > ForgeArena.FLOOR + 2.8) return;
            detonateCrystalAt(s, p, findOpenAdjacentBase(p.level(), b));
            check(pops(s) == 3 && attempt(s).done() && attempt(s).won(),
                "Crystal 3 must pop totem 3 and win SHIELD_TRIPLE_TAP; pops=" + pops(s) + " progress=" + attempt(s).progress() + " tl=" + attempt(s).timeline());
            log("PASS SHIELD_TRIPLE_TAP verified in-game (Pop 1 -> bot raises Shield + keeps Offhand Totem -> mid-air Axe break -> Pop 2 + Pop 3)");
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
                check(hitDistanceAtDamage >= 2.2 && hitDistanceAtDamage <= 3.15,
                    "JUMP_RESET bot should strike cleanly near 3-block reach, struck at " + hitDistanceAtDamage);
                log("PASS JUMP_RESET & HIT_SELECT verified (startDist=7 Bl., exact 3.0 reach, bot struck at "
                    + String.format("%.2f", hitDistanceAtDamage) + " Bl.)");
                nextScene(s, server);
            }
        }
    }
}
