package de.pvptrainer.ui;

import de.pvptrainer.drill.Drill;
import de.pvptrainer.kits.TrainingSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Crisp 12x12 Apple-style UI icons (rendered 1:1 with ARGB tint) and unified Minecraft item icons for all drills. */
final class UiIcons {
    static final Identifier GEAR = icon("gear");
    static final Identifier CLOCK = icon("clock");
    static final Identifier ARENA = icon("arena");
    static final Identifier ZERO_DAMAGE = icon("zero_damage");
    static final Identifier PING = icon("ping");
    static final Identifier INFINITY = icon("infinity");
    static final Identifier FALL_DAMAGE = icon("fall_damage");
    static final Identifier PEARL_GROUND = icon("pearl_ground");
    static final Identifier HELP = icon("help");
    static final Identifier RESET = icon("reset");
    static final Identifier CHEVRON_RIGHT = icon("chevron_right");
    static final Identifier CHEVRON_DOWN = icon("chevron_down");

    private UiIcons() {}

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath("pvptrainer", "textures/gui/icons/" + name + ".png");
    }

    static void draw(GuiGraphicsExtractor graphics, Identifier texture, int x, int y, int argbColor) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, 12, 12, 12, 12, 12, 12, argbColor);
    }

    static ItemStack drillIcon(Drill drill, TrainingSettings.Mode mode) {
        return new ItemStack(switch (drill) {
            case DUEL -> switch (mode) {
                case CRYSTAL -> Items.END_CRYSTAL;
                case MACE -> Items.MACE;
                case SWORD -> Items.DIAMOND_SWORD;
                case UHC -> Items.GOLDEN_APPLE;
                case HUGOSMP -> Items.WIND_CHARGE;
            };
            case PASSIVE -> Items.TARGET;
            case TOTEM_POPS -> Items.TOTEM_OF_UNDYING;
            case SHIELD, SHIELD_TIMING, SHIELD_DRAIN -> Items.SHIELD;
            case HIT_CRYSTAL, CORNER_HIT_CRYSTAL, D_TAP, TRIPLE_TAP -> Items.END_CRYSTAL;
            case SHIELD_TRIPLE_TAP -> Items.DIAMOND_AXE;
            case SELF_WEB_CRYSTAL -> Items.COBWEB;
            case LEDGE_DASH -> Items.OBSIDIAN;
            case SAFE_ANCHOR, DOUBLE_ANCHOR -> Items.RESPAWN_ANCHOR;
            case TAP_ANCHOR -> Items.GLOWSTONE;
            case RETOTEM, DOUBLE_HAND -> Items.TOTEM_OF_UNDYING;
            case SMASH, WIND_CHAIN, PERFECT_WIND_CHARGE -> Items.WIND_CHARGE;
            case STUN_SLAM, WIND_STUN_SLAM -> Items.MACE;
            case BACKSTAB -> Items.NETHERITE_SWORD;
            case PEARL_CATCH, BODY_PEARL -> Items.ENDER_PEARL;
            case PEARL_CATCH_GUIDE -> Items.ENDER_EYE;
            case ELYTRA_SMASH, ELY_STUN_SLAM -> Items.ELYTRA;
            case LOW_WEB_SMASH -> Items.COBWEB;
            case SPACING -> Items.DIAMOND_SWORD;
            case HIT_SELECT -> Items.IRON_SWORD;
            case PUNISH_CRIT -> Items.GOLDEN_SWORD;
            case JUMP_RESET -> Items.FEATHER;
            case UPPERCUT -> Items.STONE_SWORD;
            case PCRIT_BREAK -> Items.NETHERITE_SWORD;
            case S_TAP -> Items.DIAMOND_BOOTS;
            case STUN_WEB, HEAD_WEB -> Items.COBWEB;
            case WEB_DRAIN -> Items.BUCKET;
            case RE_LAVA, WEB_DUNK -> Items.LAVA_BUCKET;
            case HEAL, HEAL_PUNISH -> Items.GOLDEN_APPLE;
            case OBI_TRAP, CORNER_OBI_TRAP -> Items.OBSIDIAN;
            case MOMENTUM_BREAK -> Items.DIAMOND_AXE;
            case ANTI_DRAIN -> Items.WATER_BUCKET;
            case MACE_COUNTER_DTAP -> Items.MACE;
            case CORNER_WEB_DTAP -> Items.COBWEB;
        });
    }
}
