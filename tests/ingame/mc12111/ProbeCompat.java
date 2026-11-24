package de.pvptrainer;

import de.pvptrainer.compat.McCompat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** Test-Versionsbruecke fuer In-Game-Probes (MC 1.21.11 / Intermediary-Laufzeit). */
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

    public static void pick(Minecraft mc, float partialTick) {
        mc.gameRenderer.pick(partialTick);
    }

    public static void startAttack(Minecraft mc) throws Exception {
        Method m = findMethod(Minecraft.class, "method_1536", "startAttack");
        m.setAccessible(true);
        m.invoke(mc);
    }

    public static void sendPlayerAttack(ServerPlayer player, LivingEntity target) {
        player.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(target, player.isShiftKeyDown()));
    }

    public static void setLastHurt(LivingEntity entity, float value) throws Exception {
        Field f = findField(LivingEntity.class, "field_6253", "lastHurt");
        f.setAccessible(true);
        f.setFloat(entity, value);
    }

    public static void invokeApplyInput(LivingEntity entity) throws Exception {
        Method m = findMethod(LivingEntity.class, "method_66282", "applyInput");
        m.setAccessible(true);
        m.invoke(entity);
    }

    public static void setUseItemRemaining(LivingEntity entity, int value) throws Exception {
        Field f = findField(LivingEntity.class, "field_6222", "useItemRemaining");
        f.setAccessible(true);
        f.setInt(entity, value);
    }

    private static Field findField(Class<?> cls, String intermediary, String named) throws NoSuchFieldException {
        try {
            return cls.getDeclaredField(intermediary);
        } catch (NoSuchFieldException ignored) {
            return cls.getDeclaredField(named);
        }
    }

    private static Method findMethod(Class<?> cls, String intermediary, String named, Class<?>... params) throws NoSuchMethodException {
        try {
            return cls.getDeclaredMethod(intermediary, params);
        } catch (NoSuchMethodException ignored) {
            return cls.getDeclaredMethod(named, params);
        }
    }
}
