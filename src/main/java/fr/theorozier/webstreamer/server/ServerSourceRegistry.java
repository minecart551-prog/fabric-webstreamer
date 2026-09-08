package fr.theorozier.webstreamer.server;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side registry that loads named display sources from
 * {@code config/webstreamer/sources.txt}. Each line maps a name to a URL:
 * {@code name:URL}. The file is read once on server start.
 */
public class ServerSourceRegistry {

    private static final String FILE_HEADER = "# WebStreamer server sources — one entry per line, format: name:URL";

    private static Map<String, String> sources = Collections.emptyMap();

    private ServerSourceRegistry() { }

    /**
     * Load (or reload) the sources file. Creates the file and directory if missing.
     * Called once from {@link fr.theorozier.webstreamer.WebStreamerMod#onInitialize()}.
     */
    public static void load(Path configDir) {
        Path dir = configDir.resolve("webstreamer");
        Path file = dir.resolve("sources.txt");

        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            WebStreamerMod.LOGGER.error("[Server] Failed to create webstreamer config directory: {}", dir, e);
            return;
        }

        if (!Files.exists(file)) {
            try {
                Files.writeString(file, FILE_HEADER + "\n");
                WebStreamerMod.LOGGER.info("[Server] Created empty sources file: {}", file);
            } catch (IOException e) {
                WebStreamerMod.LOGGER.error("[Server] Failed to create sources file: {}", file, e);
                return;
            }
        }

        HashMap<String, String> loaded = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 1) {
                    WebStreamerMod.LOGGER.warn("[Server] sources.txt line {}: missing ':' separator, skipping: {}", lineNum, line);
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String url = line.substring(colon + 1).trim();
                if (name.isEmpty() || url.isEmpty()) {
                    WebStreamerMod.LOGGER.warn("[Server] sources.txt line {}: empty name or URL, skipping", lineNum);
                    continue;
                }
                loaded.put(name, url);
            }
        } catch (IOException e) {
            WebStreamerMod.LOGGER.error("[Server] Failed to read sources file: {}", file, e);
            return;
        }

        sources = Collections.unmodifiableMap(loaded);
        WebStreamerMod.LOGGER.info("[Server] Loaded {} server source(s) from {}", sources.size(), file);
    }

    /**
     * Resolve a source name to its URL.
     * @return The URL string, or {@code null} if the name is not registered.
     */
    public static String resolve(String name) {
        return sources.get(name);
    }

    /**
     * @return An unmodifiable view of all registered sources (name → URL).
     */
    public static Map<String, String> getAll() {
        return sources;
    }

    /**
     * Auto-detect the display source type from a URL.
     * @return {@code "youtube"}, {@code "twitch"}, or {@code "raw"}.
     */
    @Environment(EnvType.CLIENT)
    public static String detectType(String url) {
        if (url == null) {
            return "raw";
        }
        String lower = url.toLowerCase();
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            return "youtube";
        }
        if (lower.contains("twitch.tv")) {
            return "twitch";
        }
        return "raw";
    }

}
