package de.pvptrainer;

import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class OutcomeOverlay implements ClientModInitializer {
    private static final Identifier MASK = Identifier.fromNamespaceAndPath("pvptrainer", "textures/gui/outcome_vignette.png");
    private static final int SUCCESS = 0x43dc97;
    private static final int FAILURE = 0xf65c68;
    private static long started;
    private static UUID player;
    private static int tint;

    @Override public void onInitializeClient() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS,
            Identifier.fromNamespaceAndPath("pvptrainer", "round_outcome"), OutcomeOverlay::draw);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("pvptrainer", "streak"), StreakHud::draw);
    }

    static void show(UUID owner, boolean won) {
        player = owner;
        tint = won ? SUCCESS : FAILURE;
        started = System.nanoTime();
    }

    private static void draw(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        var client = Minecraft.getInstance();
        if (player == null) return;
        double elapsed = (System.nanoTime() - started) / 1_000_000.0;
        if (elapsed >= 1400 || client.player == null || !player.equals(client.player.getUUID()) || client.getSingleplayerServer() == null) {
            player = null;
            return;
        }
        double intensity = Math.min(Math.min(elapsed / 100, 1), (1400 - elapsed) / 850);
        int alpha = (int)Math.round(94 * intensity * intensity * (3 - 2 * intensity));
        if (alpha > 0) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, MASK, 0, 0, 0f, 0f, graphics.guiWidth(), graphics.guiHeight(),
                128, 128, 128, 128, (alpha << 24) | tint);
        }
    }
}
