package de.pvptrainer.hooks;

import de.pvptrainer.TrainingTarget;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
abstract class ArenaCrosshairMixin {
    @Unique private static final boolean MACHER_CROSSHAIR = FabricLoader.getInstance().isModLoaded("crosshairindicator");
    @Unique private static final Identifier MACHER_TARGET_SPRITE = Identifier.fromNamespaceAndPath("crosshairindicator", "crosshair");

    @Inject(method="extractCrosshair", at=@At("TAIL"))
    private void pvptrainer$showTrainingTarget(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo info) {
        if (MACHER_CROSSHAIR && TrainingTarget.matches(Minecraft.getInstance().crosshairPickEntity)) {
            int size = 15;
            graphics.blitSprite(RenderPipelines.CROSSHAIR, MACHER_TARGET_SPRITE, (graphics.guiWidth()-size)/2,
                (graphics.guiHeight()-size)/2, size, size);
        }
    }
}
