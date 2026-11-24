package de.pvptrainer.progress;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asynchroner, nicht-blockierender In-Game-Update-Hinweis ohne automatischen Download.
 *
 * Unterstuetzt drei JSON-Formate:
 *  1. GitHub Releases API (Standard: https://api.github.com/repos/MacherSteve/MacherBot/releases/latest)
 *  2. Einfaches latest.json ({"version":"1.12.1","minecraft":"26.2","url":"https://...","notes":"..."})
 *  3. Modrinth Versions-API (Array mit version_number, version_type, game_versions, project_id, id)
 */
public final class UpdateChecker {
    public static final String DEFAULT_FEED_URL = "https://api.github.com/repos/MacherSteve/MacherBot/releases/latest";
    public static final String DEFAULT_DOWNLOAD_PAGE = "https://github.com/MacherSteve/MacherBot/releases/latest";
    public static final String TARGET_MINECRAFT = de.pvptrainer.compat.McCompat.minecraftVersion();

    private static final Logger LOG = LoggerFactory.getLogger("pvptrainer-update");
    private static final int MAX_BODY_BYTES = 262_144;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private static final AtomicReference<UpdateInfo> AVAILABLE = new AtomicReference<>();
    private static final AtomicBoolean CHECKED = new AtomicBoolean();
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean();

    public record UpdateInfo(String currentVersion, String latestVersion, URI downloadUri, String summary) {}

    private UpdateChecker() {}

    public static UpdateInfo available() {
        return HudPreferences.updateCheck() ? AVAILABLE.get() : null;
    }

    public static void clear() {
        AVAILABLE.set(null);
        CHECKED.set(false);
        ANNOUNCED.set(false);
    }

    /** Nur fuer Tests/UI-Verifikation gedacht. */
    public static void setForTesting(UpdateInfo info) {
        AVAILABLE.set(info);
        CHECKED.set(true);
        ANNOUNCED.set(false);
    }

    public static void checkAsync() {
        if (!HudPreferences.updateCheck()) {
            return;
        }
        String override = System.getProperty("macherbot.updateUrl", "").trim();
        if ("off".equalsIgnoreCase(override) || "false".equalsIgnoreCase(override)) {
            return;
        }
        boolean allowLocalHttp = !override.isEmpty();
        String configured = !override.isEmpty()
            ? override
            : (!HudPreferences.updateFeedUrl().isBlank() ? HudPreferences.updateFeedUrl() : DEFAULT_FEED_URL);

        URI feedUri = parseSafeUri(configured, allowLocalHttp);
        if (feedUri == null) {
            LOG.debug("Ungueltige Update-Feed-URL ignoriert: {}", configured);
            return;
        }
        if (!CHECKED.compareAndSet(false, true)) {
            return;
        }

        String current = resolveCurrentVersion();
        Thread worker = new Thread(() -> {
            try {
                UpdateInfo found = fetchFrom(feedUri, current, allowLocalHttp);
                if (found != null && HudPreferences.updateCheck()) {
                    AVAILABLE.set(found);
                    LOG.info("MacherBot Update verfuegbar: {} -> {} ({})", found.currentVersion(), found.latestVersion(), found.downloadUri());
                }
            } catch (Exception error) {
                LOG.debug("Update-Pruefung uebersprungen ({}): {}", feedUri, error.toString());
            }
        }, "macherbot-update-check");
        worker.setDaemon(true);
        worker.start();
    }

    public static void announceIfReady(LocalPlayer player) {
        if (player == null || !HudPreferences.updateCheck()) {
            return;
        }
        UpdateInfo info = AVAILABLE.get();
        if (info == null || !ANNOUNCED.compareAndSet(false, true)) {
            return;
        }

        String hoverText = "Öffnet " + info.downloadUri()
            + (info.summary().isEmpty() ? "" : "\n" + info.summary());
        MutableComponent link = Component.literal("[Download öffnen]")
            .withStyle(style -> style
                .withColor(ChatFormatting.GOLD)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(info.downloadUri()))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(hoverText)))
            );

        MutableComponent line = Component.literal("[MacherBot] ")
            .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.literal("Update " + info.latestVersion() + " verfügbar (installiert: " + info.currentVersion() + ") · ")
                .withStyle(ChatFormatting.GRAY))
            .append(link);
        de.pvptrainer.compat.McCompat.sendSystemMessage(player, line);
    }

    public static UpdateInfo fetchFrom(URI feedUri, String currentVersion, boolean allowLocalHttp) throws IOException, InterruptedException {
        if (feedUri == null || parseSafeUri(feedUri.toString(), allowLocalHttp) == null) {
            return null;
        }
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder(feedUri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/vnd.github+json, application/json")
            .header("User-Agent", "MacherBot/" + (currentVersion == null ? "unknown" : currentVersion))
            .GET()
            .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            if (response.statusCode() != 200 || stream == null) {
                return null;
            }
            String body = readBoundedUtf8(stream, MAX_BODY_BYTES);
            return parseResponse(body, currentVersion, allowLocalHttp);
        }
    }

    public static UpdateInfo parseResponse(String jsonBody, String currentVersion, boolean allowLocalHttp) {
        if (jsonBody == null || jsonBody.isBlank() || currentVersion == null || currentVersion.isBlank()) {
            return null;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(jsonBody);
        } catch (RuntimeException error) {
            return null;
        }

        if (root.isJsonArray()) {
            return parseModrinthArray(root.getAsJsonArray(), currentVersion, allowLocalHttp);
        }
        if (!root.isJsonObject()) {
            return null;
        }

        JsonObject obj = root.getAsJsonObject();
        if (obj.has("tag_name")) {
            return parseGitHubRelease(obj, currentVersion, allowLocalHttp);
        }
        if (obj.has("version")) {
            return parseSimpleFeed(obj, currentVersion, allowLocalHttp);
        }
        return null;
    }

    private static UpdateInfo parseGitHubRelease(JsonObject obj, String currentVersion, boolean allowLocalHttp) {
        if (bool(obj, "draft") || bool(obj, "prerelease")) {
            return null;
        }
        String rawTag = str(obj, "tag_name");
        String latest = normalizeDisplayVersion(rawTag);
        if (!isNewer(latest, currentVersion)) {
            return null;
        }
        URI downloadUri = parseSafeUri(firstNonBlank(str(obj, "html_url"), DEFAULT_DOWNLOAD_PAGE), allowLocalHttp);
        if (downloadUri == null) {
            return null;
        }
        String summary = extractSummary(str(obj, "body"), str(obj, "name"), latest);
        return new UpdateInfo(normalizeDisplayVersion(currentVersion), latest, downloadUri, summary);
    }

    private static UpdateInfo parseSimpleFeed(JsonObject obj, String currentVersion, boolean allowLocalHttp) {
        if (!matchesMinecraft(obj.get("minecraft"))) {
            return null;
        }
        String latest = normalizeDisplayVersion(str(obj, "version"));
        if (!isNewer(latest, currentVersion)) {
            return null;
        }
        String rawUrl = firstNonBlank(str(obj, "url"), str(obj, "download_url"), str(obj, "html_url"), DEFAULT_DOWNLOAD_PAGE);
        URI downloadUri = parseSafeUri(rawUrl, allowLocalHttp);
        if (downloadUri == null) {
            return null;
        }
        String summary = extractSummary(firstNonBlank(str(obj, "notes"), str(obj, "summary")), "", latest);
        return new UpdateInfo(normalizeDisplayVersion(currentVersion), latest, downloadUri, summary);
    }

    private static UpdateInfo parseModrinthArray(JsonArray array, String currentVersion, boolean allowLocalHttp) {
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            String type = str(obj, "version_type");
            if (!type.isEmpty() && !"release".equalsIgnoreCase(type)) {
                continue;
            }
            if (!matchesMinecraft(obj.get("game_versions"))) {
                continue;
            }
            String latest = normalizeDisplayVersion(str(obj, "version_number"));
            if (!isNewer(latest, currentVersion)) {
                return null;
            }
            String projectId = str(obj, "project_id");
            String versionId = str(obj, "id");
            String fallbackUrl = (!projectId.isEmpty() && !versionId.isEmpty())
                ? "https://modrinth.com/mod/" + projectId + "/version/" + versionId
                : DEFAULT_DOWNLOAD_PAGE;
            URI downloadUri = parseSafeUri(firstNonBlank(str(obj, "url"), fallbackUrl), allowLocalHttp);
            if (downloadUri == null) {
                return null;
            }
            String summary = extractSummary(str(obj, "changelog"), str(obj, "name"), latest);
            return new UpdateInfo(normalizeDisplayVersion(currentVersion), latest, downloadUri, summary);
        }
        return null;
    }

    private static boolean matchesMinecraft(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return true;
        }
        if (element.isJsonPrimitive()) {
            String value = element.getAsString().trim();
            if (value.isEmpty()) {
                return true;
            }
            for (String token : value.split(",")) {
                if (TARGET_MINECRAFT.equals(token.trim())) {
                    return true;
                }
            }
            return false;
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            if (arr.isEmpty()) {
                return true;
            }
            for (JsonElement entry : arr) {
                if (entry.isJsonPrimitive() && TARGET_MINECRAFT.equals(entry.getAsString().trim())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    public static boolean isNewer(String candidate, String current) {
        ParsedVersion cand = parseSemVer(candidate);
        ParsedVersion curr = parseSemVer(current);
        if (cand == null || curr == null) {
            return false;
        }
        int maxLen = Math.max(cand.numbers.size(), curr.numbers.size());
        for (int i = 0; i < maxLen; i++) {
            int a = i < cand.numbers.size() ? cand.numbers.get(i) : 0;
            int b = i < curr.numbers.size() ? curr.numbers.get(i) : 0;
            if (a != b) {
                return a > b;
            }
        }
        if (cand.preRelease.isEmpty() && !curr.preRelease.isEmpty()) {
            return true;
        }
        if (!cand.preRelease.isEmpty() && curr.preRelease.isEmpty()) {
            return false;
        }
        return cand.preRelease.compareToIgnoreCase(curr.preRelease) > 0;
    }

    public static URI parseSafeUri(String raw, boolean allowLocalHttp) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            if ("https".equals(scheme)) {
                return uri;
            }
            if (allowLocalHttp && "http".equals(scheme)
                && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                return uri;
            }
            return null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String normalizeDisplayVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            trimmed = trimmed.substring(1);
        }
        int plus = trimmed.indexOf('+');
        return plus >= 0 ? trimmed.substring(0, plus) : trimmed;
    }

    private record ParsedVersion(List<Integer> numbers, String preRelease) {}

    private static ParsedVersion parseSemVer(String raw) {
        String normalized = normalizeDisplayVersion(raw);
        if (normalized.isEmpty()) {
            return null;
        }
        int dash = normalized.indexOf('-');
        String core = dash >= 0 ? normalized.substring(0, dash) : normalized;
        String pre = dash >= 0 ? normalized.substring(dash + 1) : "";
        String[] parts = core.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        List<Integer> numbers = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException error) {
                return null;
            }
        }
        return new ParsedVersion(numbers, pre);
    }

    private static String extractSummary(String body, String fallbackName, String version) {
        if (body != null && !body.isBlank()) {
            for (String rawLine : body.lines().toList()) {
                String cleaned = rawLine.trim().replaceFirst("^[#*\\->\\s]+", "").trim();
                if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase("changelog") && !cleaned.equalsIgnoreCase("änderungen")) {
                    return truncate(cleaned, 110);
                }
            }
        }
        if (fallbackName != null && !fallbackName.isBlank() && !fallbackName.contains(version)) {
            return truncate(fallbackName.trim(), 110);
        }
        return "";
    }

    private static String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1).trim() + "…";
    }

    private static String resolveCurrentVersion() {
        try {
            var loader = FabricLoader.getInstance();
            if (loader == null) {
                return "unknown";
            }
            return loader.getModContainer("macherbot")
                .or(() -> loader.getModContainer("pvptrainer"))
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        } catch (RuntimeException error) {
            return "unknown";
        }
    }

    private static String readBoundedUtf8(InputStream stream, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int remaining = maxBytes;
        int read;
        while (remaining > 0 && (read = stream.read(buf, 0, Math.min(buf.length, remaining))) != -1) {
            out.write(buf, 0, read);
            remaining -= read;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String str(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString().trim() : "";
    }

    private static boolean bool(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsBoolean();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
