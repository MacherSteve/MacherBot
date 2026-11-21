package de.pvptrainer;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudStatusBarHeightRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class StreakHud {
    private static final Identifier FIRE = Identifier.fromNamespaceAndPath("pvptrainer", "hud/record_fire");
    private static final int FLAME_RGB = 0xff294a;
    private static final ItemStack POP_ICON = new ItemStack(Items.TOTEM_OF_UNDYING);
    private static final ItemStack SLAM_ICON = new ItemStack(Items.MACE);
    static void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        var client = Minecraft.getInstance();
        if (client.gui.hud.isHidden() || client.player == null || client.getCameraEntity() != client.player || client.gameMode == null
            || !client.gameMode.canHurtPlayer() || client.getSingleplayerServer() == null
            || !TrainingArena.isArena(client.player.level())) return;
        var status = TrainerClient.status();
        if (!status.active() || status.drill() == null || status.seriesKey().isEmpty()) return;

        int hearts = HudStatusBarHeightRegistry.getHeight(VanillaHudElements.HEALTH_BAR);
        int armor = HudStatusBarHeightRegistry.getHeight(VanillaHudElements.ARMOR_BAR);
        int top = Math.max(hearts, armor - (client.player.getArmorValue() == 0 ? 10 : 0));
        int right = g.guiWidth() / 2 - 91 - 7;
        int middleY = g.guiHeight() - (hearts + top) / 2 + 4;
        var owner = client.player.getUUID();
        long now = System.currentTimeMillis();
        if (status.drill() == Drill.PERFECT_WIND_CHARGE) {
            String height = WindHeight.text(status.windMeters());
            g.text(client.font, height, right - client.font.width(height) - 2, middleY - 4, WindHeight.color(status.windMeters()), true);
            return;
        }
        if (status.obiTimer() != null) {
            var timer = status.obiTimer();
            drawCounter(g, client.font, timer.text(), right, middleY, timer.burning(), 0, 0, 25, client.options.screenEffectScale().get());
            return;
        }
        if (status.drill() == Drill.LOW_WEB_SMASH) {
            drawMultiplier(g, client.font, TrainingProgress.streak(owner, status.seriesKey()), right, middleY, now, client.options.screenEffectScale().get());
            return;
        }
        if (status.drill() != Drill.PASSIVE)
            middleY = row(g, client, TrainingProgress.streak(owner, status.seriesKey()), status.drill(), right, middleY, now);
        if (status.drill() != Drill.STUN_SLAM)
            middleY = row(g, client, TrainingProgress.streak(owner, TrainingProgress.techniqueKey(status.seriesKey(), Drill.STUN_SLAM)),
                Drill.STUN_SLAM, right, middleY, now);
        if (status.mode().allowsBotTotems() && status.drill() != Drill.TOTEM_POPS)
            row(g, client, TrainingProgress.streak(owner, TrainingProgress.techniqueKey(status.seriesKey(), Drill.TOTEM_POPS)),
                Drill.TOTEM_POPS, right, middleY, now);
    }

    private static int row(GuiGraphicsExtractor g, Minecraft client, TrainingProgress.Streak streak, Drill drill,
                           int right, int y, long now) {
        if (streak.current() == 0) return y;
        ItemStack icon = drill == Drill.STUN_SLAM ? SLAM_ICON : drill == Drill.TOTEM_POPS ? POP_ICON : ItemStack.EMPTY;
        if (!icon.isEmpty()) {
            var pose = g.pose();
            pose.pushMatrix();
            pose.translate(right - 9, y - 5);
            pose.scale(0.62f);
            g.item(icon, 0, 0);
            pose.popMatrix();
            right -= 12;
        }
        drawMultiplier(g, client.font, streak, right, y, now, client.options.screenEffectScale().get());
        return y - (streak.burning() ? 31 : 26);
    }

    private static void drawMultiplier(GuiGraphicsExtractor g, Font font, TrainingProgress.Streak streak,
                                       int right, int middleY, long now, double motion) {
        String text = streak.current() + "x";
        double age = Math.max(0, now - streak.lastWinMillis());
        double beat = accent(age, streak.recordHit() ? 650 : 290) * motion;
        boolean lit = streak.burning();
        double shake = streak.nearRecord() ? motion * (streak.previousBest() == streak.current() ? .8 : .5) : 0;
        drawCounter(g, font, text, right, middleY, lit, beat, shake, 25 + (int)Math.round((streak.recordHit() ? 6 : 3) * beat), motion);
    }

    private static void drawCounter(GuiGraphicsExtractor g, Font font, String text, int right, int middleY,
                                    boolean lit, double beat, double shake, int height, double motion) {
        int width = font.width(text);
        double seconds = System.nanoTime() / 1.0e9;
        // Use the native pixel grid at rest. Only the hit and near-record cues move the counter.
        float scale = (float)Math.min(1 + .13 * beat, (right - 2.0) / width);
        if (scale <= 0) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate((float)(right + shake * Math.sin(seconds * 35)), middleY);
        pose.scale(scale);
        pose.translate(-width - 2, -4);

        if (lit) {
            int span = Math.min(40, width + 6);

            int left = (width - span) / 2;
            // Let the fire taper into the glyphs instead of exposing the flat bottom of the block sprite.
            int bottom = 6;
            flameBand(g, left, bottom - height, span, height, bottom - height, 3, 0xf4);
            flameBand(g, left, bottom - height, span, height, 3, 5, 0xac);
            flameBand(g, left, bottom - height, span, height, 5, bottom, 0x60);
        }
        g.text(font, text, 1, 1, 0xff671f26, false);
        g.text(font, text, 0, 0, lit ? 0xfffff3f3 : 0xfffffaf5, false);
        if (lit) {
            int x = 0;
            for (int i = 0; i < text.length(); i++) {
                String glyph = text.substring(i, i + 1);
                int next = x + font.width(glyph);
                double warmth = .5 + motion * (.32 * Math.sin(seconds * 5 - i * 1.8) + .18 * Math.sin(seconds * 8 + i));
                int emberTop = 5 - (int)Math.round(2 * warmth);
                g.enableScissor(x, emberTop, next, font.lineHeight);
                g.text(font, glyph, x, 0, 0xffff949d, false);
                g.disableScissor();
                g.enableScissor(x, emberTop + 2, next, font.lineHeight);
                int ember = 0xffff002c | ((18 + (int)(27 * warmth)) << 8);
                g.text(font, glyph, x, 0, ember, false);
                g.disableScissor();
                x = next;
            }
        }
        pose.popMatrix();
    }

    private static void flameBand(GuiGraphicsExtractor g, int x, int y, int w, int h, int top, int bottom, int alpha) {
        g.enableScissor(x, top, x + w, bottom);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, FIRE, x, y, w, h, (alpha << 24) | FLAME_RGB);
        g.disableScissor();
    }

    private static double accent(double age, double duration) {
        double t = Math.min(1, age / duration);
        return (1 - Math.exp(-18 * t)) * (1 - t) * (1 - t);
    }
}
