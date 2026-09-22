package fr.theorozier.webstreamer.jni;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the WebStreamer native library (Rust cdylib) from disk or extracts
 * it from the mod JAR. Falls back gracefully if the native library is
 * unavailable — callers should check {@link #isAvailable()} before use.
 *
 * <p>The native library provides hardware-accelerated video decoding via
 * FFmpeg with packet caching and chunked HTTP range requests.</p>
 */
@Environment(EnvType.CLIENT)
public final class NativeLibrary {

    private static final String LIB_NAME = "webstreamer_lav";
    private static final String NATIVES_SUBDIR = "webstreamer/natives";

    private static boolean available = false;
    private static boolean initialized = false;
    private static long abiVersion = 0;

    private NativeLibrary() {}

    /**
     * Attempt to load the native library. This is idempotent and safe to
     * call multiple times. If the library cannot be loaded, {@link #isAvailable()}
     * returns false and all native operations become no-ops.
     */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        try {
            Path nativesDir = FabricLoader.getInstance().getGameDir().resolve(NATIVES_SUBDIR);
            String libFileName = mapLibraryName(LIB_NAME);
            Path libPath = nativesDir.resolve(libFileName);

            // Try to load from disk first
            if (Files.exists(libPath)) {
                System.load(libPath.toAbsolutePath().toString());
            } else {
                // Try to extract from mod JAR
                String resourcePath = "/natives/" + libFileName;
                try (InputStream is = NativeLibrary.class.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        Files.createDirectories(nativesDir);
                        Path tempPath = nativesDir.resolve(libFileName + ".tmp");
                        Files.copy(is, tempPath, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(tempPath, libPath, StandardCopyOption.REPLACE_EXISTING);
                        System.load(libPath.toAbsolutePath().toString());
                    } else {
                        WebStreamerMod.LOGGER.info("[Native] Native library not found in JAR or disk");
                        return;
                    }
                }
            }

            // Verify ABI version
            abiVersion = nAbiVersion();
            if (abiVersion < 1) {
                WebStreamerMod.LOGGER.warn("[Native] Invalid ABI version: {}", abiVersion);
                available = false;
                return;
            }

            // Initialize logging
            nInitLogging();

            available = true;
            WebStreamerMod.LOGGER.info("[Native] Loaded successfully (ABI v{})", abiVersion);

        } catch (UnsatisfiedLinkError e) {
            WebStreamerMod.LOGGER.info("[Native] Native library not available: {}", e.getMessage());
            available = false;
        } catch (Exception e) {
            WebStreamerMod.LOGGER.warn("[Native] Failed to load native library", e);
            available = false;
        }
    }

    /** Whether the native library is loaded and usable. */
    public static boolean isAvailable() {
        return available;
    }

    /** The ABI version of the loaded native library. */
    public static long getAbiVersion() {
        return abiVersion;
    }

    // -----------------------------------------------------------------------
    // Native method declarations (implemented in Rust via JNI)
    // -----------------------------------------------------------------------

    private static native long nAbiVersion();
    private static native void nInitLogging();
    static native long nOpen(String url);

    /**
     * Read the next frame as I420 into the provided direct ByteBuffer.
     * @return The number of bytes written, or -1 on error.
     */
    public static native long nReadFrameI420(long handle, ByteBuffer buf, int bufLen, int[] outDims, long[] outPts);

    static native int nSeek(long handle, long positionUs);
    static native int nEnableCache(long handle, long maxDurationUs, long maxBytes);
    static native int nError(long handle, byte[] buf, int bufLen);
    static native void nKill(long handle);
    static native void nClose(long handle);

    private static String mapLibraryName(String name) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return name + ".dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "lib" + name + ".dylib";
        } else {
            return "lib" + name + ".so";
        }
    }
}
