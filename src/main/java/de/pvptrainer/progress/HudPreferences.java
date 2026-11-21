package de.pvptrainer.progress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

public final class HudPreferences {
    private static final Path FILE = resolveFile();
    private static volatile boolean showHints = read();

    private static Path resolveFile() {
        try {
            var loader = FabricLoader.getInstance();
            return loader == null ? null : loader.getConfigDir().resolve("pvptrainer/hud.properties");
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static boolean hints() { return showHints; }

    private static boolean read() {
        if (FILE == null || !Files.isRegularFile(FILE)) return true;
        var values = new Properties();
        try (var reader = Files.newBufferedReader(FILE)) {
            values.load(reader);
            return !"false".equalsIgnoreCase(values.getProperty("showHints", values.getProperty("showResultToasts")));
        } catch (IOException | IllegalArgumentException error) {
            LoggerFactory.getLogger("pvptrainer").warn("HUD-Einstellungen konnten nicht gelesen werden", error);
            return true;
        }
    }

    public static void toggleHints() {
        if (FILE == null) {
            showHints = !showHints;
            return;
        }
        var temporary = FILE.resolveSibling("hud.properties.tmp");
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(temporary, "showHints=" + !showHints + "\n");
            Files.move(temporary, FILE, StandardCopyOption.REPLACE_EXISTING);
            showHints = !showHints;
        } catch (IOException error) {
            LoggerFactory.getLogger("pvptrainer").warn("HUD-Einstellungen konnten nicht gespeichert werden", error);
        }
    }
}
