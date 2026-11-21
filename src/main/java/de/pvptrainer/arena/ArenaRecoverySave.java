package de.pvptrainer.arena;

import de.pvptrainer.mixin.LevelStorageAccess;
import de.pvptrainer.mixin.RestoredPlayerAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

/** Commit the restored player before removing the inventory and return journals. */
public final class ArenaRecoverySave {
    public static boolean persist(MinecraftServer server, ServerPlayer player) throws IOException {
        var snapshot = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(snapshot);
        ((RestoredPlayerAccess)server.getPlayerList()).pvptrainer$savePlayer(player);
        // The vanilla writer logs and swallows I/O failures. Read the actual primary file, never its fallback.
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat");
        if (!snapshot.buildResult().equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))) {
            throw new IOException("Die wiederhergestellten Spielerdaten konnten nicht bestätigt werden.");
        }
        if (server.isSingleplayerOwner(new NameAndId(player.getGameProfile()))) {
            Path level = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
            UUID owner = player.getUUID();
            if (!owner.equals(ownerOnDisk(level).orElse(null))) {
                // Vanilla 26.2 reopens the owner's UUID file selected by level.dat.
                // A copied single-player world can still point to the previous account.
                ((LevelStorageAccess)server).pvptrainer$levelStorage().saveDataTag(server.getWorldData(), owner);
                if (!owner.equals(ownerOnDisk(level).orElse(null))) {
                    throw new IOException("Der Eigentümer des gespeicherten Einzelspielers konnte nicht bestätigt werden.");
                }
            }
        }
        return true;
    }

    private static Optional<UUID> ownerOnDisk(Path level) throws IOException {
        if (Files.notExists(level)) return Optional.empty();
        return NbtIo.readCompressed(level, NbtAccounter.unlimitedHeap())
                .getCompoundOrEmpty("Data").read("singleplayer_uuid", UUIDUtil.CODEC);
    }
}
