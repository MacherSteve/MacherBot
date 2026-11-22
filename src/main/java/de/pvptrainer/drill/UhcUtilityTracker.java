package de.pvptrainer.drill;

import de.pvptrainer.kits.MacherItems;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

/** Bewertungszustand fuer UHC-Utility-, Fallen- und Heilungsdrills (HEAD_WEB, OBI_TRAP, WEB_DRAIN, RE_LAVA, HEAL). */
final class UhcUtilityTracker {
    private static final int NEVER = -100000;

    private int lavaPlacedAt = NEVER;
    private int webAt = NEVER;
    private int lavaInAt = NEVER;
    private int lavaOut = NEVER;
    private int rescueTick = NEVER;
    private boolean inLava;
    private BlockPos lastLavaSource;
    private final Set<BlockPos> trapSites = new HashSet<>();
    private int trapProgress;
    private BlockPos lastWebSite;
    private BlockPos rescueSource;
    private int startingFood = -1;

    boolean allowRescue(Drill drill, boolean done, int tick, int reactionTicks) {
        return rescueSource == null
            && !done
            && (drill == Drill.WEB_DRAIN && tick >= reactionTicks
                || drill == Drill.RE_LAVA && inLava && lavaInAt != NEVER && tick - lavaInAt >= reactionTicks);
    }

    void clearWebPlacement() {
        webAt = NEVER;
        lastWebSite = null;
    }

    void initStartingFoodIfNeeded(Drill drill, ServerPlayer player) {
        if (startingFood < 0 && drill == Drill.HEAL) {
            startingFood = healingItems(player);
        }
    }

    void checkHeadTrap(DrillAttempt attempt, Drill drill, BlockPos head, Block block) {
        if (drill == Drill.HEAD_WEB && head.equals(lastWebSite) && block == Blocks.COBWEB) {
            attempt.finish(true, "Der Kopf des Bots steckt im selbst platzierten Netz.");
        }
    }

    void checkObsidianTrap(DrillAttempt attempt, Level level, BlockPos feet) {
        trapSites.removeIf(pos -> {
            var state = level.getBlockState(pos);
            return !state.is(Blocks.OBSIDIAN) && !(state.is(Blocks.LAVA) && state.getFluidState().isSource());
        });
        BlockPos head = feet.above();
        boolean bottom = trapSites.contains(feet) && level.getBlockState(feet).is(Blocks.OBSIDIAN);
        boolean top = trapSites.contains(head) && level.getBlockState(head).is(Blocks.OBSIDIAN);
        if (bottom && top && ObsidianEnclosure.closed(level, feet)) {
            attempt.finish(true, "Kopf und Füße sind in deinem Obsidian. Alle Fluchtwege sind zu.");
            return;
        }
        int progress = bottom && top ? 3 : bottom ? 1 : top ? 2 : 0;
        if (progress != trapProgress) {
            trapProgress = progress;
            attempt.step(progress == 3 ? "Obsidian sitzt · Fluchtweg mit Blöcken schließen"
                : bottom ? "Füße zu · Obsidian am Kopf fehlt"
                : top ? "Kopf zu · Obsidian an den Füßen fehlt"
                : "Kopf und Füße offen");
        }
    }

    void trapBlockPlaced(Drill drill, boolean done, BlockPos pos) {
        if (drill.isObiTrap() && !done) {
            trapSites.add(pos.immutable());
        }
    }

    void lavaPlaced(DrillAttempt attempt, Drill drill, int tick, BlockPos pos) {
        lavaPlacedAt = tick;
        lastLavaSource = pos.immutable();
        trapBlockPlaced(drill, attempt.done(), pos);
        attempt.event("Lava gesetzt");
    }

    void webPlaced(DrillAttempt attempt, Drill drill, int tick, int stunUntil, BlockPos pos) {
        if (drill == Drill.HEAD_WEB || drill == Drill.CORNER_WEB_DTAP || tick < stunUntil) {
            webAt = tick;
            lastWebSite = pos.immutable();
            attempt.event(drill == Drill.HEAD_WEB ? "Netz platziert" : (drill == Drill.CORNER_WEB_DTAP ? "Corner-Web platziert" : "Netz nach Stun gesetzt"));
        }
    }

    void waterPlaced(DrillAttempt attempt, int tick, BlockPos pos) {
        rescueSource = pos.immutable();
        rescueTick = tick;
        attempt.step("Bot setzt Rettungswasser");
    }

    BlockPos rescueSource() {
        return rescueSource;
    }

    int rescueTick() {
        return rescueTick;
    }

    void waterDrawn(DrillAttempt attempt, Drill drill, int tick, BlockPos pos, LivingEntity bot) {
        if (drill == Drill.WEB_DRAIN && pos.equals(rescueSource) && inWeb(bot)) {
            attempt.finish(true, "Wasserquelle nach " + (tick - rescueTick) + " Ticks aufgenommen");
        }
    }

    void checkStunWebCatch(DrillAttempt attempt, int tick, int stunUntil, LivingEntity bot) {
        if (!bot.onGround()
            && tick < stunUntil
            && webAt != NEVER
            && lastWebSite != null
            && bot.level().getBlockState(lastWebSite).is(Blocks.COBWEB)
            && bot.getBoundingBox().intersects(new AABB(lastWebSite))) {
            attempt.finish(true, "Bot im neu platzierten Netz, Schild noch deaktiviert");
        }
    }

    void tickWebDrain(DrillAttempt attempt, LivingEntity bot) {
        if (rescueSource != null && !inWeb(bot)) {
            attempt.finish(false, "Bot aus dem Netz entkommen");
        }
    }

    void tickReLava(DrillAttempt attempt, int tick, LivingEntity bot) {
        boolean currentlyInLava = touchesLava(bot);
        if (inLava && !currentlyInLava && rescueSource != null) {
            lavaOut = tick;
            attempt.step("Ersten Lavakontakt gelöscht");
        }
        if (!inLava && currentlyInLava) {
            boolean nearPlacedLava = lastLavaSource != null && bot.blockPosition().distSqr(lastLavaSource) <= 20.0;
            if (nearPlacedLava && lavaOut != NEVER && lavaPlacedAt >= lavaOut) {
                attempt.finish(true, "Zweite Lava auf das gelöschte Ziel gesetzt");
            } else if (nearPlacedLava && lavaInAt == NEVER) {
                lavaInAt = tick;
                attempt.step("Erster Lavakontakt");
            }
        }
        inLava = currentlyInLava;
    }

    void ate(DrillAttempt attempt, Drill drill, float damageOnOwner, ServerPlayer player, ItemStack stack) {
        if (drill == Drill.HEAL && heals(stack)) {
            attempt.event(stack.getHoverName().getString() + " verzehrt · " + DrillAttempt.decimal(damageOnOwner) + " LP Gegenschaden");
            int consumed = Math.max(1, startingFood - healingItems(player));
            attempt.finish(true, "Heilung abgeschlossen · " + consumed + " Heilitem verbraucht");
        }
    }

    static boolean touchesLava(LivingEntity entity) {
        AABB box = entity.getBoundingBox().deflate(0.001);
        Level level = entity.level();
        for (BlockPos pos : BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.LAVA) && pos.getY() + fluid.getHeight(level, pos) > box.minY) {
                return true;
            }
        }
        return false;
    }

    static boolean inWeb(LivingEntity entity) {
        AABB box = entity.getBoundingBox().deflate(0.001);
        for (BlockPos pos : BlockPos.betweenClosed(
            BlockPos.containing(box.minX, box.minY, box.minZ),
            BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            if (entity.level().getBlockState(pos).is(Blocks.COBWEB)) {
                return true;
            }
        }
        return false;
    }

    private static boolean heals(ItemStack stack) {
        return stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE) || MacherItems.isHealingHead(stack);
    }

    private static int healingItems(ServerPlayer player) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (heals(stack)) count += stack.getCount();
        }
        if (heals(player.getOffhandItem())) {
            count += player.getOffhandItem().getCount();
        }
        return count;
    }
}
