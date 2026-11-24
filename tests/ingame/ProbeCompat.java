package de.pvptrainer;

import de.pvptrainer.compat.McCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Test-Versionsbruecke fuer In-Game-Probes (MC 26.2). */
public final class ProbeCompat {
    private ProbeCompat() {}

    public static Screen screen(Minecraft mc) {
        return McCompat.screen(mc);
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        McCompat.setScreen(mc, screen);
    }

    public static void dismissWorldConfirm(Minecraft mc) {
        Screen s = screen(mc);
        if (s instanceof net.minecraft.client.gui.screens.ConfirmScreen
                || s instanceof net.minecraft.client.gui.screens.BackupConfirmScreen) {
            for (var child : s.children()) {
                if (child instanceof net.minecraft.client.gui.components.Button btn && btn.active) {
                    String txt = btn.getMessage().getString();
                    if (txt.contains("Proceed") || txt.contains("I know") || txt.contains("Fortfahren") || txt.contains("Yes") || txt.contains("Ja")) {
                        btn.onPress(null);
                        return;
                    }
                }
            }
        }
    }

    public static void pick(Minecraft mc, float partialTick) throws Exception {
        var pick = Minecraft.class.getDeclaredMethod("pick", float.class);
        pick.setAccessible(true);
        pick.invoke(mc, partialTick);
    }

    public static void startAttack(Minecraft mc) throws Exception {
        var click = Minecraft.class.getDeclaredMethod("startAttack");
        click.setAccessible(true);
        click.invoke(mc);
    }

    public static void sendPlayerAttack(ServerPlayer player, LivingEntity target) {
        player.connection.handleAttack(new ServerboundAttackPacket(target.getId()));
    }

    public static void setLastHurt(LivingEntity entity, float value) throws Exception {
        var last = LivingEntity.class.getDeclaredField("lastHurt");
        last.setAccessible(true);
        last.setFloat(entity, value);
    }

    public static void invokeApplyInput(LivingEntity entity) throws Exception {
        var applyInput = LivingEntity.class.getDeclaredMethod("applyInput");
        applyInput.setAccessible(true);
        applyInput.invoke(entity);
    }

    public static void setUseItemRemaining(LivingEntity entity, int value) throws Exception {
        var field = LivingEntity.class.getDeclaredField("useItemRemaining");
        field.setAccessible(true);
        field.setInt(entity, value);
    }
}
