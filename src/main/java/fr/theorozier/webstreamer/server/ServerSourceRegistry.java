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
 * comma-separated URLs: {@code name:url1,url2,url3}. The full URL list per
 * source is broadcast to clients, which pick one randomly per display.
 *
 * <p>Local paths (starting with {@code /}) are auto-detected and resolved
 * to HTTP URLs served by the embedded {@link WebStreamerHttpServer}.</p>
 */
public class ServerSourceRegistry {

    private static final String FILE_HEADER = "# WebStreamer server sources — one entry per line, format: name:URL\n# Multiple URLs can be comma-separated; each display randomly picks one of them,\n# re-randomized on every server restart/reload.\n# Local paths starting with / are served from the files/ directory.";

    private static Map<String, List<String>> sources = Collections.emptyMap();
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

        startHttpServer(filesDir, httpPort);

        // Apply the configured HTTP IP (if any) so local paths get the correct
        // host in their resolved HTTP URLs.
        String configuredIp = readHttpIp(configDir);
        if (configuredIp != null && !configuredIp.isEmpty()) {
            setServerIp(configuredIp);
        }

        loadSources(file);
    }

    /**
     * Re-read sources.txt.
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
        return sources.size();
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

        // URLs are resolved at broadcast time (see resolveList()); no random
        // single-pick happens server-side anymore — each display picks its own
        // URL from the list on the client.
        WebStreamerMod.LOGGER.info("[Server] Loaded {} server source(s) from {}", sources.size(), file);
    }

    /**
     * Start the embedded HTTP server for local file serving.
     */
    private static void startHttpServer(Path filesDir, int port) {
        if (httpServer != null && httpServer.isRunning()) {
            return;
        }
        // Retry the configured port briefly to ride out TIME_WAIT sockets or a
        // slowly exiting previous instance. Without this, a freshly-reopened
        // world in singleplayer could hit "Address already in use".
        Throwable lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                WebStreamerHttpServer candidate = new WebStreamerHttpServer(filesDir, port);
                candidate.start();
                httpServer = candidate;
                return;
            } catch (IOException e) {
                lastError = e;
                if (attempt < 4) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // The configured port is genuinely busy; fall back to an ephemeral port
        // so local sources still resolve and play. All URLs are built from the
        // actual bound port (see resolveUrl), so this is transparent.
        try {
            WebStreamerHttpServer fallback = new WebStreamerHttpServer(filesDir, 0);
            fallback.start();
            httpServer = fallback;
            WebStreamerMod.LOGGER.warn("[Server] Port {} is unavailable ({}); WebStreamer HTTP server bound to ephemeral port {}", port, lastError == null ? "unknown error" : lastError.getMessage(), fallback.getPort());
        } catch (IOException e) {
            WebStreamerMod.LOGGER.error("[Server] Failed to start HTTP server: {}", e.getMessage());
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
     * Get the port the embedded HTTP server is bound to (actual bound port,
     * which may differ from the configured one if it fell back to ephemeral).
     * Returns {@link WebStreamerHttpServer#DEFAULT_PORT} if the server is down.
     */
    public static int getHttpPort() {
        if (httpServer != null && httpServer.isRunning()) {
            return httpServer.getPort();
        }
        return WebStreamerHttpServer.DEFAULT_PORT;
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
     * Resolve a source name to its list of URLs. Local paths (starting with {@code /})
     * are resolved to HTTP URLs served by the embedded {@link WebStreamerHttpServer}.
     * Entries that cannot be resolved (e.g. a local path while the HTTP server is down)
     * are skipped, so the returned list never contains a scheme-less URL.
     *
     * @return The resolved URL list, or an empty list if the name is not registered.
     */
    public static List<String> resolveList(String name) {
        List<String> raws = sources.get(name);
        if (raws == null) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>(raws.size());
        for (String raw : raws) {
            String url = resolveUrl(raw, name);
            if (url != null) {
                resolved.add(url);
            }
        }
        return resolved;
    }

    private static String resolveUrl(String raw, String name) {
        if (raw.startsWith("/")) {
            if (httpServer != null && httpServer.isRunning()) {
                return "http://" + serverIp + ":" + httpServer.getPort() + raw;
            }
            WebStreamerMod.LOGGER.warn("[Server] Source '{}' has local path '{}' but the HTTP server is not running, skipping", name, raw);
            return null;
        }
        return raw;
    }

    /**
     * @return An unmodifiable view of all raw sources (name → list of URLs from sources.txt).
     */
    public static Map<String, List<String>> getAll() {
        return sources;
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
