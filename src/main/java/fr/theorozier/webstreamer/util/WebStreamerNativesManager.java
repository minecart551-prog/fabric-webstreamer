package fr.theorozier.webstreamer.util;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.bytedeco.javacpp.Loader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Manages loading of FFmpeg native libraries from an external directory
 * outside the mod JAR, so the JAR stays small and native libraries persist
 * across mod updates.
 *
 * <p>Native library files are searched for (in order):</p>
 * <ol>
 *   <li>{@code <gameDir>/webstreamer/natives/} — flat directory (recommended)</li>
 *   <li>{@code <gameDir>/webstreamer/natives/org/bytedeco/ffmpeg/<platform>/}
 *       — JavaCPP package-path layout</li>
 * </ol>
 *
 * <p>If neither location has natives, the correct platform JAR is
 * automatically downloaded from Maven Central and extracted into the
 * flat directory.</p>
 *
 * <p>The natives directory is added to {@code java.library.path} (via
 * reflection) so JavaCPP's Loader can find the libraries via
 * {@code System.loadLibrary()}.</p>
 */
@Environment(EnvType.CLIENT)
public final class WebStreamerNativesManager {

    private static final String NATIVES_SUBDIR = "webstreamer/natives";

    // Must match the FFmpeg version from gradle.properties (javacv_version)
    private static final String JAVACV_VERSION = "1.5.10";
    private static final String FFMPEG_VERSION = "6.1.1";
    private static final String EXPECTED_NATIVE_VERSION = FFMPEG_VERSION + "-" + JAVACV_VERSION;
    private static final String VERSION_MARKER = ".version";

    private static boolean downloadAttempted = false;

    private WebStreamerNativesManager() {}

    /**
     * Check for external FFmpeg native libraries and configure them for use.
     * If no natives are found and no download has been attempted yet, the
     * correct platform JAR is downloaded from Maven Central automatically.
     *
     * <p>This method may block the calling thread while downloading.
     * It is designed to be called from the executor thread.</p>
     *
     * <p>JavaCPP's Loader falls back to {@code System.loadLibrary()} which
     * searches {@code java.library.path}. We add our natives directory to
     * {@code java.library.path} via reflection so the Loader can find them.</p>
     *
     * @return {@code true} if natives are available and configured,
     *         {@code false} if they could not be obtained.
     */
    public static boolean setup() {

        String platform;
        try {
            platform = Loader.getPlatform();
        } catch (Exception e) {
            WebStreamerMod.LOGGER.error("[WebStreamer] Failed to detect platform.", e);
            return false;
        }

        Path nativesDir = FabricLoader.getInstance().getGameDir().resolve(NATIVES_SUBDIR);
        Path versionFile = nativesDir.resolve(VERSION_MARKER);

        // 0. Check if cached natives match the expected version.
        //    If stale (version mismatch or no marker), delete old DLLs so fresh ones are downloaded.
        if (!isNativesVersionCurrent(versionFile)) {
            WebStreamerMod.LOGGER.info("[WebStreamer] Natives version mismatch or missing, clearing cached natives");
            clearStaleNatives(nativesDir);
        }

        // 1. Check flat directory (recommended — download goes here)
        if (hasNativeFiles(nativesDir)) {
            addToLibraryPath(nativesDir);
            return true;
        }

        // 2. Check package-path subdirectory (for users who prefer that layout)
        //    e.g. <nativesDir>/org/bytedeco/ffmpeg/<platform>/
        Path packageDir = nativesDir.resolve("org/bytedeco/ffmpeg").resolve(platform);
        if (hasNativeFiles(packageDir)) {
            addToLibraryPath(nativesDir);
            return true;
        }

        // 3. Download from Maven Central (only once per session)
        if (downloadAttempted) {
            return false;
        }
        downloadAttempted = true;

        try {
            downloadPlatformNatives(nativesDir, platform);
            // Write version marker so we can detect stale natives on next launch
            try {
                Files.writeString(versionFile, EXPECTED_NATIVE_VERSION);
            } catch (IOException e) {
                WebStreamerMod.LOGGER.debug("[WebStreamer] Could not write version marker", e);
            }
            addToLibraryPath(nativesDir);
            return true;
        } catch (Exception e) {
            WebStreamerMod.LOGGER.error("[WebStreamer] Failed to download FFmpeg natives for {}. " +
                    "Check your internet connection. Natives will be placed in {}",
                    platform, nativesDir, e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Download
    // -----------------------------------------------------------------------

    private static void downloadPlatformNatives(Path nativesDir, String platform)
            throws IOException, InterruptedException {

        String version = FFMPEG_VERSION + "-" + JAVACV_VERSION;
        String jarUrl = "https://repo1.maven.org/maven2/org/bytedeco/ffmpeg/"
                + version + "/ffmpeg-" + version + "-" + platform + ".jar";

        WebStreamerMod.LOGGER.info("[WebStreamer] Downloading FFmpeg natives from {} ...", jarUrl);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(jarUrl))
                .GET()
                .timeout(Duration.ofMinutes(5))
                .build();

        HttpResponse<InputStream> response = client.send(req,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode()
                    + " downloading FFmpeg platform JAR from " + jarUrl);
        }

        Path tempFile = Files.createTempFile("webstreamer_ffmpeg_", ".jar");
        try {
            try (InputStream body = response.body()) {
                Files.copy(body, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            Files.createDirectories(nativesDir);
            String prefix = "org/bytedeco/ffmpeg/" + platform + "/";

            try (JarFile jar = new JarFile(tempFile.toFile())) {
                Enumeration<JarEntry> entries = jar.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) {
                        continue;
                    }
                    // Extract flat into nativesDir (no platform subdirectory)
                    String name = entry.getName().substring(prefix.length());
                    Path target = nativesDir.resolve(name);
                    Files.createDirectories(target.getParent());
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
                WebStreamerMod.LOGGER.info("[WebStreamer] Extracted {} native files to {}", count, nativesDir);
            }

        } finally {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) { }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * @return the absolute path to the natives directory
     */
    public static Path getNativesDir() {
        return FabricLoader.getInstance().getGameDir().resolve(NATIVES_SUBDIR);
    }

    /**
     * Pre-load all native libraries from the natives directory via
     * {@link System#load(String)} using absolute paths. Uses multiple
     * retry rounds so DLLs are naturally loaded in dependency order.
     *
     * <p>After loading, also registers each JNI library in
     * {@link Loader Loader's} internal {@code loadedLibraries} map so
     * that JavaCPP's Loader skips its {@code System.loadLibrary} fallback.
     * (In Java 17+, {@code System.load(path)} registers by full path
     * while {@code System.loadLibrary(name)} looks up by short name,
     * so pre-loaded DLLs are invisible to the Loader's fallback.)</p>
     */
    public static void preloadNativeLibraries() {
        Path dir = getNativesDir();
        WebStreamerMod.LOGGER.info("[WebStreamer] Pre-loading natives from {}", dir);
        if (!Files.isDirectory(dir)) {
            WebStreamerMod.LOGGER.warn("[WebStreamer] Natives directory does not exist");
            return;
        }

        List<String> loadedJniLibs = new java.util.ArrayList<>();

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir,
                path -> {
                    String n = path.getFileName().toString().toLowerCase();
                    return n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib");
                })) {

            List<Path> remaining = new java.util.ArrayList<>();
            for (Path p : entries) remaining.add(p);

            WebStreamerMod.LOGGER.info("[WebStreamer] Found {} native file(s) to pre-load", remaining.size());
            if (remaining.isEmpty()) return;

            int round = 0;
            int loaded;
            do {
                round++;
                loaded = 0;
                java.util.Iterator<Path> it = remaining.iterator();
                while (it.hasNext()) {
                    Path lib = it.next();
                    try {
                        System.load(lib.toAbsolutePath().toString());
                        it.remove();
                        loaded++;
                        // Track JNI library names for Loader registration below
                        String name = lib.getFileName().toString();
                        if (name.startsWith("jni")) {
                            // Strip extension to get the Loader key (e.g. "jniavutil")
                            int dot = name.indexOf('.');
                            loadedJniLibs.add(dot > 0 ? name.substring(0, dot) : name);
                        }
                        WebStreamerMod.LOGGER.info("[WebStreamer]   [round {}] Loaded: {}",
                                round, name);
                    } catch (UnsatisfiedLinkError e) {
                        // dependency not yet loaded — retry next round
                    }
                }
                WebStreamerMod.LOGGER.info("[WebStreamer]   [round {}] loaded {}, {} remaining",
                        round, loaded, remaining.size());
            } while (!remaining.isEmpty() && loaded > 0);

            if (!remaining.isEmpty()) {
                WebStreamerMod.LOGGER.warn("[WebStreamer] Could not pre-load {} native library(ies): {}",
                        remaining.size(),
                        remaining.stream().map(p -> p.getFileName().toString())
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        } catch (IOException e) {
            WebStreamerMod.LOGGER.warn("[WebStreamer] Failed to scan natives directory", e);
        }

        // Register all loaded JNI libs in JavaCPP's loadedLibraries so the
        // Loader doesn't attempt System.loadLibrary() as a fallback.
        if (!loadedJniLibs.isEmpty()) {
            try {
                Field loadedField = Loader.class.getDeclaredField("loadedLibraries");
                loadedField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Map<String, String> map =
                        (java.util.Map<String, String>) loadedField.get(null);
                for (String lib : loadedJniLibs) {
                    map.putIfAbsent(lib, lib);
                }
                WebStreamerMod.LOGGER.info("[WebStreamer] Registered {} JNI lib(s) in Loader cache: {}",
                        loadedJniLibs.size(), String.join(", ", loadedJniLibs));
            } catch (Exception e) {
                WebStreamerMod.LOGGER.warn("[WebStreamer] Could not register in Loader cache", e);
            }
        }
    }

    private static void addToLibraryPath(Path nativesDir) {
        String dir = nativesDir.toAbsolutePath().toString();
        String existing = System.getProperty("java.library.path", "");
        if (existing.isEmpty()) {
            System.setProperty("java.library.path", dir);
        } else if (!existing.contains(dir)) {
            System.setProperty("java.library.path", dir + File.pathSeparator + existing);
        } else {
            return;
        }
        try {
            Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            sysPathsField.set(null, null);
        } catch (Exception e) {
            WebStreamerMod.LOGGER.debug("[WebStreamer] Could not reset ClassLoader.sys_paths (Java 9+)", e);
        }
    }

    private static boolean hasNativeFiles(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString().toLowerCase();
                if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /**
     * Check if the version marker file matches the expected native version.
     */
    private static boolean isNativesVersionCurrent(Path versionFile) {
        try {
            if (!Files.exists(versionFile)) return false;
            String stored = Files.readString(versionFile).trim();
            return EXPECTED_NATIVE_VERSION.equals(stored);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Delete all native library files and the version marker from the natives directory.
     */
    private static void clearStaleNatives(Path nativesDir) {
        if (!Files.isDirectory(nativesDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(nativesDir)) {
            int deleted = 0;
            for (Path entry : stream) {
                String name = entry.getFileName().toString().toLowerCase();
                if (name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")
                        || name.equals(VERSION_MARKER)) {
                    Files.deleteIfExists(entry);
                    deleted++;
                }
            }
            if (deleted > 0) {
                WebStreamerMod.LOGGER.info("[WebStreamer] Deleted {} stale native file(s)", deleted);
            }
        } catch (IOException e) {
            WebStreamerMod.LOGGER.warn("[WebStreamer] Failed to clean stale natives", e);
        }
    }

    /**
     * @return the subdirectory name used for native libraries
     */
    public static String getNativesSubdir() {
        return NATIVES_SUBDIR;
    }
}
