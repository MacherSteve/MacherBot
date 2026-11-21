package de.pvptrainer.analytics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Util;
import org.slf4j.LoggerFactory;

public final class CalibrationPlan {
    public static final String FILENAME = "HT1-Testplan-1.7.html";
    public static final String RESOURCE = "/assets/pvptrainer/ht1-testplan.html";

    private CalibrationPlan() {}

    public static String open() {
        try {
            Path file = install(FabricLoader.getInstance().getGameDir());
            Util.getPlatform().openPath(file);
            return "Testplan im Browser geöffnet.";
        } catch (IOException | RuntimeException error) {
            LoggerFactory.getLogger("pvptrainer").warn("HT1-Testplan ließ sich nicht öffnen", error);
            return "Öffnen fehlgeschlagen. Der Testplan liegt auch im Ordner MacherBot-Analysen.";
        }
    }

    public static Path install(Path gameDir) throws IOException {
        try (InputStream data = CalibrationPlan.class.getResourceAsStream(RESOURCE)) {
            if (data == null) throw new IOException("Datei fehlt im Mod-Paket: " + RESOURCE);
            return installBytes(gameDir, FILENAME, data.readAllBytes());
        }
    }

    public static Path installBytes(Path gameDir, String filename, byte[] bundled) throws IOException {
        Path directory = Files.createDirectories(gameDir.resolve("MacherBot-Analysen"));
        Path target = directory.resolve(filename);
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bundled)) {
            return target;
        }
        Path tmp = Files.createTempFile(directory, "testplan-", ".tmp");
        try {
            Files.write(tmp, bundled);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }
}
