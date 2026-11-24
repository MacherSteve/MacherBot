package de.pvptrainer.compat;

import com.mojang.blaze3d.platform.InputConstants;
import de.pvptrainer.mixin.GameRendererAccess;
import de.pvptrainer.mixin.LevelStorageAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Minecraft-Versionsadapter (MC 1.21.11-Implementierung). */
public final class McCompat {
    private McCompat() {}

    public static String minecraftVersion() {
        return "1.21.11";
    }

    public static void sendOverlay(Player player, Component message) {
        player.displayClientMessage(message, true);
    }

    public static void sendSystemMessage(LocalPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    public static Screen screen(Minecraft mc) {
        return mc.screen;
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.setScreen(screen);
    }

    public static Overlay overlay(Minecraft mc) {
        return mc.getOverlay();
    }

    public static ToastManager toastManager(Minecraft mc) {
        return mc.getToastManager();
    }

    public static boolean hudHidden(Minecraft mc) {
        return mc.options.hideGui;
    }

    public static Block redStainedGlass() {
        return Blocks.RED_STAINED_GLASS;
    }

    public static EntityType<?> endCrystalType() {
        return EntityType.END_CRYSTAL;
    }

    public static ChargedProjectiles chargedProjectiles(List<ItemStack> stacks) {
        return ChargedProjectiles.of(stacks);
    }

    public static boolean isInstantaneous(MobEffect effect) {
        return effect.isInstantenous();
    }

    public static KeyMapping registerKeyMapping(KeyMapping mapping) {
        return KeyBindingHelper.registerKeyBinding(mapping);
    }

    public static boolean keyMatches(KeyMapping mapping, InputConstants.Key key) {
        return KeyBindingHelper.getBoundKeyOf(mapping).equals(key);
    }

    public static boolean isAttackPacket(Object packet) {
        // In 1.21.11 werden Angriffe ueber ServerboundInteractPacket gesendet (bereits in LocalPacketDelay abgedeckt).
        return false;
    }

    public static boolean withinAttackRange(Player player, AABB box, double extra) {
        return player.isWithinAttackRange(box, extra);
    }

    public static void causeExtraKnockback(LivingEntity attacker, Entity target, float extraKb, Vec3 beforeHit,
                                           DamageSource source, float damage) {
        attacker.causeExtraKnockback(target, extraKb, beforeHit);
    }

    public static Camera mainCamera(Minecraft mc) {
        return mc.gameRenderer.getMainCamera();
    }

    public static Matrix4f viewRotationProjection(Minecraft mc, Camera camera, float partialTick) {
        float fov = ((GameRendererAccess) mc.gameRenderer).pvptrainer$getFov(camera, partialTick, true);
        Matrix4f proj = mc.gameRenderer.getProjectionMatrix(fov);
        Quaternionf conj = camera.rotation().conjugate(new Quaternionf());
        Matrix4f view = new Matrix4f().rotation(conj);
        return proj.mul(view);
    }

    public static void persistSingleplayerOwner(MinecraftServer server, ServerPlayer player, CompoundTag expected) throws IOException {
        if (server.isSingleplayerOwner(new NameAndId(player.getGameProfile()))) {
            Path level = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
            // Vanilla 1.21.11 loads the singleplayer owner's state from level.dat (Data.Player).
            ((LevelStorageAccess) server).pvptrainer$levelStorage().saveDataTag(
                server.registryAccess(), server.getWorldData(), expected
            );
            CompoundTag saved = Files.exists(level)
                ? NbtIo.readCompressed(level, NbtAccounter.unlimitedHeap())
                    .getCompoundOrEmpty("Data")
                    .getCompoundOrEmpty("Player")
                : new CompoundTag();
            if (!expected.equals(saved)) {
                throw new IOException("Die wiederhergestellten Einzelspielerdaten in level.dat konnten nicht bestätigt werden.");
            }
        }
    }
}
