package de.pvptrainer.compat;

import com.mojang.blaze3d.platform.InputConstants;
import de.pvptrainer.mixin.LevelStorageAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
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

/** Minecraft-Versionsadapter (26.2-Implementierung; fuer 1.21.11 per src/mc12111 ueberlagert). */
public final class McCompat {
    private McCompat() {}

    public static String minecraftVersion() {
        return "26.2";
    }

    public static void sendOverlay(Player player, Component message) {
        player.sendOverlayMessage(message);
    }

    public static void sendSystemMessage(LocalPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    public static Screen screen(Minecraft mc) {
        return mc.gui.screen();
    }

    public static void setScreen(Minecraft mc, Screen screen) {
        mc.gui.setScreen(screen);
    }

    public static Overlay overlay(Minecraft mc) {
        return mc.gui.overlay();
    }

    public static ToastManager toastManager(Minecraft mc) {
        return mc.gui.toastManager();
    }

    public static boolean hudHidden(Minecraft mc) {
        return mc.gui.hud.isHidden();
    }

    public static Block redStainedGlass() {
        return (Block) Blocks.STAINED_GLASS.red();
    }

    public static EntityType<?> endCrystalType() {
        return EntityTypes.END_CRYSTAL;
    }

    public static ChargedProjectiles chargedProjectiles(List<ItemStack> stacks) {
        return ChargedProjectiles.ofNonEmpty(stacks);
    }

    public static boolean isInstantaneous(MobEffect effect) {
        return effect.isInstantaneous();
    }

    public static KeyMapping registerKeyMapping(KeyMapping mapping) {
        return KeyMappingHelper.registerKeyMapping(mapping);
    }

    public static boolean keyMatches(KeyMapping mapping, InputConstants.Key key) {
        return mapping.matches(key);
    }

    public static boolean isAttackPacket(Object packet) {
        return packet instanceof ServerboundAttackPacket;
    }

    public static boolean withinAttackRange(Player player, AABB box, double extra) {
        return player.isWithinAttackRange(player.getMainHandItem(), box, extra);
    }

    public static void causeExtraKnockback(LivingEntity attacker, Entity target, float extraKb, Vec3 beforeHit,
                                           DamageSource source, float damage) {
        attacker.causeExtraKnockback(target, extraKb, beforeHit, source, damage, true);
    }

    public static Camera mainCamera(Minecraft mc) {
        return mc.gameRenderer.mainCamera();
    }

    public static Matrix4f viewRotationProjection(Minecraft mc, Camera camera, float partialTick) {
        return camera.getViewRotationProjectionMatrix(new Matrix4f());
    }

    public static void persistSingleplayerOwner(MinecraftServer server, ServerPlayer player, CompoundTag expected) throws IOException {
        if (server.isSingleplayerOwner(new NameAndId(player.getGameProfile()))) {
            Path level = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
            UUID owner = player.getUUID();
            if (!owner.equals(ownerOnDisk(level).orElse(null))) {
                // Vanilla 26.2 reopens the owner's UUID file selected by level.dat.
                // A copied single-player world can still point to the previous account.
                ((LevelStorageAccess) server).pvptrainer$levelStorage().saveDataTag(server.getWorldData(), owner);
                if (!owner.equals(ownerOnDisk(level).orElse(null))) {
                    throw new IOException("Der Eigentümer des gespeicherten Einzelspielers konnte nicht bestätigt werden.");
                }
            }
        }
    }

    private static Optional<UUID> ownerOnDisk(Path level) throws IOException {
        if (Files.notExists(level)) return Optional.empty();
        return NbtIo.readCompressed(level, NbtAccounter.unlimitedHeap())
            .getCompoundOrEmpty("Data").read("singleplayer_uuid", UUIDUtil.CODEC);
    }
}
