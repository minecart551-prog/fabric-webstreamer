package fr.theorozier.webstreamer.util;

import fr.theorozier.webstreamer.WebStreamerMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * WebStreamer configuration read from {@code config/webstreamer/config.properties}.
 * <p>Currently exposes a single {@code debug} flag that gates verbose per-video
 * logging (source picks, resolved URIs, FFmpeg URLs, per-video YouTube outcomes).</p>
 */
public class WebStreamerConfig {

    private static final String FILE_NAME = "config.properties";
    private static final String DEBUG_KEY = "debug";

    private static volatile boolean debug = false;

    private WebStreamerConfig() {
    }

    /**
     * Load the configuration from the given config directory. Creates a default
     * file if it doesn't exist. Safe to call again (e.g. from a config reload).
     */
    public static void load(Path configDir) {
        if (configDir == null) {
            return;
        }
        Path file = configDir.resolve("webstreamer").resolve(FILE_NAME);
        if (!Files.exists(file)) {
            writeDefaults(file);
        }
        boolean dbg = false;
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(file)) {
            props.load(is);
            dbg = Boolean.parseBoolean(props.getProperty(DEBUG_KEY, "false"));
        } catch (Exception e) {
            WebStreamerMod.LOGGER.warn("[Config] Failed to read config.properties, using defaults: {}", e.getMessage());
        }
        debug = dbg;
    }

    /**
     * Whether verbose debug logging is enabled.
     */
    public static boolean isDebug() {
        return debug;
    }

    /**
     * Log at INFO level only when debug logging is enabled.
     */
    public static void debugLog(String format, Object... args) {
        if (debug) {
            WebStreamerMod.LOGGER.info(format, args);
        }
    }

    /**
     * Log at WARN level only when debug logging is enabled.
     */
    public static void debugWarn(String format, Object... args) {
        if (debug) {
            WebStreamerMod.LOGGER.warn(format, args);
        }
    }

    private static void writeDefaults(Path file) {
        try {
            Path dir = file.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Properties defaults = new Properties();
            defaults.setProperty(DEBUG_KEY, "false");
            try (OutputStream os = Files.newOutputStream(file)) {
                defaults.store(os, "WebStreamer configuration");
            }
            WebStreamerMod.LOGGER.info("[Config] Created default config.properties: {}", file);
        } catch (IOException e) {
            WebStreamerMod.LOGGER.warn("[Config] Failed to create config.properties: {}", e.getMessage());
        }
    }

}