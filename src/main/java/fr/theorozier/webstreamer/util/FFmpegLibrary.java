package fr.theorozier.webstreamer.util;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lazily initializes the FFmpeg native libraries (JavaCPP/FFmpeg) on first actual use.
 *
 * <p>This avoids loading FFmpeg native libraries during mod initialization, which
 * can interfere with GLX/GLFW window creation on some GPU/driver combinations
 * (notably AMD Radeon RX 7900 XTX with the radeonsi driver on Linux).</p>
 *
 * <p>The native libraries are loaded only when the first FFmpeg operation is
 * actually needed (e.g. when a display starts playing a stream), by which time
 * the GLFW window and OpenGL context are already fully initialized.</p>
 */
@Environment(EnvType.CLIENT)
public final class FFmpegLibrary {

    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    private FFmpegLibrary() {
        // Utility class, no instantiation.
    }

    /**
     * Ensures the FFmpeg native libraries are loaded and the log callback is set.
     * This method is idempotent and safe to call multiple times.
     *
     * <p>If the native libraries cannot be loaded (e.g. missing platform binaries),
     * the error is logged and subsequent calls are no-ops.</p>
     */
    public static void ensureInitialized() {
        if (initialized.get()) {
            return;
        }

        if (initialized.compareAndSet(false, true)) {
            try {
                // This triggers JavaCPP's Loader to extract and load the FFmpeg
                // native libraries (libavformat, libavcodec, etc.) into the JVM.
                FFmpegLogCallback.setLevel(avutil.AV_LOG_QUIET);
                WebStreamerMod.LOGGER.info("[FFmpeg] Native libraries loaded successfully.");
            } catch (Exception | UnsatisfiedLinkError e) {
                WebStreamerMod.LOGGER.error("[FFmpeg] Failed to load native libraries.", e);
            }
        }
    }

}