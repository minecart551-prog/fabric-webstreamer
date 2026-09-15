package fr.theorozier.webstreamer.server;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Properties;

/**
 * Server-side registry that loads named display sources from
 * {@code config/webstreamer/sources.txt}. Each line maps a name to one or more
 * comma-separated URLs: {@code name:url1,url2,url3}. On load/reload, one URL
 * is randomly selected per source.
 *
 * <p>Local paths (starting with {@code /}) are auto-detected and resolved
 * to HTTP URLs served by the embedded {@link WebStreamerHttpServer}.</p>
 */
public class ServerSourceRegistry {

    private static final String FILE_HEADER = "# WebStreamer server sources — one entry per line, format: name:URL\n# Multiple URLs can be comma-separated; one is picked randomly per restart/reload.\n# Local paths starting with / are served from the files/ directory.";

    private static Map<String, List<String>> sources = Collections.emptyMap();
    private static Map<String, String> selectedSources = Collections.emptyMap();
    private static WebStreamerHttpServer httpServer;
    private static String serverIp = "localhost";

    private ServerSourceRegistry() { }

    /**
     * Load (or reload) the sources file. Creates the file and directory if missing.
     * Also starts the embedded HTTP server for local file serving.
     * Called once from {@link fr.theorozier.webstreamer.WebStreamerMod#onInitialize()}.
     */
    public static void load(Path configDir) {
        Path dir = configDir.resolve("webstreamer");
        Path file = dir.resolve("sources.txt");
        Path filesDir = dir.resolve("files");
        Path propsFile = dir.resolve("server.properties");

        try {
            Files.createDirectories(dir);
            Files.createDirectories(filesDir);
        } catch (IOException e) {
            WebStreamerMod.LOGGER.error("[Server] Failed to create webstreamer config directory: {}", dir, e);
            return;
        }

        // Read server.properties for HTTP port
        int httpPort = WebStreamerHttpServer.DEFAULT_PORT;
        if (Files.exists(propsFile)) {
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(propsFile)) {
                props.load(is);
                httpPort = Integer.parseInt(props.getProperty("http-port", String.valueOf(WebStreamerHttpServer.DEFAULT_PORT)));
            } catch (Exception e) {
                WebStreamerMod.LOGGER.warn("[Server] Failed to read server.properties, using default port {}: {}", WebStreamerHttpServer.DEFAULT_PORT, e.getMessage());
            }
        } else {
            // Create default server.properties
            try (OutputStream os = Files.newOutputStream(propsFile)) {
                Properties props = new Properties();
                props.setProperty("http-port", String.valueOf(WebStreamerHttpServer.DEFAULT_PORT));
                props.setProperty("http-ip", "");
                props.store(os, "WebStreamer server configuration");
                WebStreamerMod.LOGGER.info("[Server] Created default server.properties: {}", propsFile);
            } catch (IOException e) {
                WebStreamerMod.LOGGER.warn("[Server] Failed to create server.properties: {}", e.getMessage());
            }
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

        loadSources(file);
        startHttpServer(filesDir, httpPort);
    }

    /**
     * Re-read sources.txt, pick new random URLs, and resolve them.
     * Returns the number of sources loaded, or -1 on error.
     */
    public static int reload() {
        Path configDir = fr.theorozier.webstreamer.WebStreamerMod.getConfigDir();
        if (configDir == null) {
            WebStreamerMod.LOGGER.error("[Server] Cannot reload: config dir not available");
            return -1;
        }
        Path file = configDir.resolve("webstreamer").resolve("sources.txt");
        if (!Files.exists(file)) {
            WebStreamerMod.LOGGER.error("[Server] Cannot reload: sources.txt does not exist");
            return -1;
        }
        loadSources(file);
        return selectedSources.size();
    }

    private static void loadSources(Path file) {
        HashMap<String, List<String>> loaded = new HashMap<>();
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
                String urlsPart = line.substring(colon + 1).trim();
                if (name.isEmpty() || urlsPart.isEmpty()) {
                    WebStreamerMod.LOGGER.warn("[Server] sources.txt line {}: empty name or URL, skipping", lineNum);
                    continue;
                }
                // Split on commas, trim whitespace around each URL
                String[] rawUrls = urlsPart.split(",");
                List<String> urls = new ArrayList<>();
                for (String raw : rawUrls) {
                    String url = raw.trim();
                    if (!url.isEmpty()) {
                        urls.add(url);
                    }
                }
                if (urls.isEmpty()) {
                    WebStreamerMod.LOGGER.warn("[Server] sources.txt line {}: no valid URLs after parsing, skipping", lineNum);
                    continue;
                }
                loaded.put(name, urls);
            }
        } catch (IOException e) {
            WebStreamerMod.LOGGER.error("[Server] Failed to read sources file: {}", file, e);
            return;
        }

        sources = Collections.unmodifiableMap(loaded);

        // Pick one random URL per source and resolve local paths to HTTP URLs
        HashMap<String, String> selected = new HashMap<>();
        Random rng = new Random();
        for (Map.Entry<String, List<String>> entry : loaded.entrySet()) {
            String name = entry.getKey();
            List<String> urls = entry.getValue();
            String picked = urls.get(rng.nextInt(urls.size()));
            // Resolve local paths to HTTP URLs
            if (picked.startsWith("/")) {
                if (httpServer != null && httpServer.isRunning()) {
                    picked = "http://" + serverIp + ":" + httpServer.getPort() + picked;
                } else {
                    WebStreamerMod.LOGGER.warn("[Server] Source '{}' has local path '{}' but HTTP server is not running", name, entry.getValue().get(0));
                }
            }
            selected.put(name, picked);
        }
        selectedSources = Collections.unmodifiableMap(selected);
        WebStreamerMod.LOGGER.info("[Server] Loaded {} server source(s) from {}", selectedSources.size(), file);
    }

    /**
     * Start the embedded HTTP server for local file serving.
     */
    private static void startHttpServer(Path filesDir, int port) {
        if (httpServer != null && httpServer.isRunning()) {
            return;
        }
        try {
            httpServer = new WebStreamerHttpServer(filesDir, port);
            httpServer.start();
        } catch (IOException e) {
            WebStreamerMod.LOGGER.error("[Server] Failed to start HTTP server on port {}: {}", port, e.getMessage());
            httpServer = null;
        }
    }

    /**
     * Stop the embedded HTTP server. Called when the server shuts down.
     */
    public static void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
    }

    /**
     * Restart the HTTP server if it was stopped (e.g. after closing a singleplayer world).
     */
    public static void restartHttpServerIfNeeded(Path configDir) {
        if (httpServer != null && httpServer.isRunning()) {
            return;
        }
        Path dir = configDir.resolve("webstreamer");
        Path filesDir = dir.resolve("files");
        Path propsFile = dir.resolve("server.properties");

        int httpPort = WebStreamerHttpServer.DEFAULT_PORT;
        if (Files.exists(propsFile)) {
            Properties props = new Properties();
            try (InputStream is = Files.newInputStream(propsFile)) {
                props.load(is);
                httpPort = Integer.parseInt(props.getProperty("http-port", String.valueOf(WebStreamerHttpServer.DEFAULT_PORT)));
            } catch (Exception ignored) { }
        }

        startHttpServer(filesDir, httpPort);
    }

    /**
     * Set the server IP address used in broadcast URLs.
     */
    public static void setServerIp(String ip) {
        if (ip != null && !ip.isEmpty()) {
            serverIp = ip;
        }
    }

    /**
     * Get the current server IP used in broadcast URLs.
     */
    public static String getServerIp() {
        return serverIp;
    }

    /**
     * Read the http-ip setting from webstreamer's server.properties.
     * Returns null if not set or empty.
     */
    public static String readHttpIp(Path configDir) {
        Path propsFile = configDir.resolve("webstreamer").resolve("server.properties");
        if (!Files.exists(propsFile)) return null;
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(propsFile)) {
            props.load(is);
            String ip = props.getProperty("http-ip", "").trim();
            return ip.isEmpty() ? null : ip;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve a source name to its selected URL. Local paths are converted to HTTP URLs.
     * @return The URL string, or {@code null} if the name is not registered.
     */
    public static String resolve(String name) {
        return selectedSources.get(name);
    }

    /**
     * @return An unmodifiable view of all selected sources (name → resolved URL).
     */
    public static Map<String, String> getAll() {
        return selectedSources;
    }

    /**
     * @return The embedded HTTP server, or null if not started.
     */
    public static WebStreamerHttpServer getHttpServer() {
        return httpServer;
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
