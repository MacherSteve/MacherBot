package de.pvptrainer.mixin;

import de.pvptrainer.arena.TrainingArena;
import de.pvptrainer.arena.TrainingTarget;
import de.pvptrainer.progress.HudPreferences;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class ArenaCrosshairMixin {
    @Unique private static final boolean MACHER_CROSSHAIR = FabricLoader.getInstance().isModLoaded("crosshairindicator");
    @Unique private static final Identifier MACHER_TARGET_SPRITE = Identifier.fromNamespaceAndPath("crosshairindicator", "crosshair");

    @Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void pvptrainer$showTrainingTarget(GuiGraphics graphics, DeltaTracker delta, CallbackInfo info) {
        Minecraft mc = Minecraft.getInstance();
        if (!TrainingTarget.matches(mc.crosshairPickEntity)) {
            return;
        }
        if (MACHER_CROSSHAIR) {
            int size = 15;
            graphics.blitSprite(RenderPipelines.CROSSHAIR, MACHER_TARGET_SPRITE, (graphics.guiWidth() - size) / 2,
                (graphics.guiHeight() - size) / 2, size, size);
        } else if (HudPreferences.reachIndicator() && mc.level != null && TrainingArena.isArena(mc.level)) {
            int cx = graphics.guiWidth() / 2;
            int cy = graphics.guiHeight() / 2;
            int color = 0xFFE84A38;
            graphics.fill(cx - 6, cy - 6, cx - 3, cy - 5, color);
            graphics.fill(cx - 6, cy - 6, cx - 5, cy - 3, color);
            graphics.fill(cx + 4, cy - 6, cx + 7, cy - 5, color);
            graphics.fill(cx + 6, cy - 6, cx + 7, cy - 3, color);
            graphics.fill(cx - 6, cy + 6, cx - 3, cy + 7, color);
            graphics.fill(cx - 6, cy + 4, cx - 5, cy + 7, color);
            graphics.fill(cx + 4, cy + 6, cx + 7, cy + 7, color);
            graphics.fill(cx + 6, cy + 4, cx + 7, cy + 7, color);
        }
    }
}
