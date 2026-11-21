package de.pvptrainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

final class HudPreferences {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pvptrainer/hud.properties");
    private static volatile boolean showHints = read();

    static boolean hints() { return showHints; }

    private static boolean read() {
        if (!Files.isRegularFile(FILE)) return true;
        var values = new Properties();
        try (var reader = Files.newBufferedReader(FILE)) {
            values.load(reader);
            return !"false".equalsIgnoreCase(values.getProperty("showHints", values.getProperty("showResultToasts")));
        } catch (IOException | IllegalArgumentException error) {
            LoggerFactory.getLogger("pvptrainer").warn("HUD-Einstellungen konnten nicht gelesen werden", error);
            return true;
        }
    }

    static void toggleHints() {
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
