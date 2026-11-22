package de.pvptrainer.progress;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

public final class HudPreferences {
    private static final Path FILE = resolveFile();
    private static volatile boolean showHints = true;
    private static volatile boolean checkUpdates = true;
    private static volatile String updateFeedUrl = "";

    static {
        load(FILE);
    }

    private static Path resolveFile() {
        try {
            var loader = FabricLoader.getInstance();
            return loader == null ? null : loader.getConfigDir().resolve("pvptrainer/hud.properties");
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static boolean hints() {
        return showHints;
    }

    public static boolean updateCheck() {
        return checkUpdates;
    }

    public static String updateFeedUrl() {
        return updateFeedUrl;
    }

    static synchronized void load(Path path) {
        showHints = true;
        checkUpdates = true;
        updateFeedUrl = "";
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        var values = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            values.load(reader);
            showHints = !"false".equalsIgnoreCase(values.getProperty("showHints", values.getProperty("showResultToasts")));
            checkUpdates = !"false".equalsIgnoreCase(values.getProperty("checkUpdates"));
            updateFeedUrl = values.getProperty("updateFeedUrl", "").trim();
        } catch (IOException | IllegalArgumentException error) {
            LoggerFactory.getLogger("pvptrainer").warn("HUD-Einstellungen konnten nicht gelesen werden", error);
        }
    }

    public static synchronized void toggleHints() {
        boolean nextHints = !showHints;
        if (persist(FILE, nextHints, checkUpdates, updateFeedUrl)) {
            showHints = nextHints;
        }
    }

    public static synchronized void toggleUpdateCheck() {
        boolean nextUpdates = !checkUpdates;
        if (persist(FILE, showHints, nextUpdates, updateFeedUrl)) {
            checkUpdates = nextUpdates;
        }
    }

    static boolean persist(Path path, boolean hints, boolean updates, String feedUrl) {
        if (path == null) {
            return true;
        }
        var values = new Properties();
        values.setProperty("showHints", Boolean.toString(hints));
        values.setProperty("checkUpdates", Boolean.toString(updates));
        if (feedUrl != null && !feedUrl.isBlank()) {
            values.setProperty("updateFeedUrl", feedUrl.trim());
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                values.store(writer, null);
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            LoggerFactory.getLogger("pvptrainer").warn("HUD-Einstellungen konnten nicht gespeichert werden", error);
            return false;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }
}
