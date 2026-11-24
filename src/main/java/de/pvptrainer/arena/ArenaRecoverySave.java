package de.pvptrainer.arena;

import de.pvptrainer.compat.McCompat;
import de.pvptrainer.mixin.RestoredPlayerAccess;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

/** Commit the restored player before removing the inventory and return journals. */
public final class ArenaRecoverySave {
    public static boolean persist(MinecraftServer server, ServerPlayer player) throws IOException {
        var snapshot = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        player.saveWithoutId(snapshot);
        CompoundTag expected = snapshot.buildResult();
        ((RestoredPlayerAccess)server.getPlayerList()).pvptrainer$savePlayer(player);
        // The vanilla writer logs and swallows I/O failures. Read the actual primary file, never its fallback.
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat");
        if (!expected.equals(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))) {
            throw new IOException("Die wiederhergestellten Spielerdaten konnten nicht bestätigt werden.");
        }
        McCompat.persistSingleplayerOwner(server, player, expected);
        return true;
    }
}
