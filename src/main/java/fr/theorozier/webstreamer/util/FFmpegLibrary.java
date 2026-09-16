package fr.theorozier.webstreamer.util;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;

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
 *
 * <p>All initialization is single-flight and synchronized: concurrent callers
 * block until the first caller finishes instead of running ahead of a half-done
 * state. This is important because JavaCPP's first-time native extraction/load
 * is not race-safe — two decode threads initializing {@code FFmpegFrameGrabber}
 * at the same time can deadlock or throw, and a failed static initializer is
 * <em>sticky</em> for the whole JVM (every later grabber then fails with
 * {@code NoClassDefFoundError}). Use {@link #initializeFrameGrabber()} to fully
 * link the grabber classes on the render thread <em>before</em> any background
 * decode thread can start.</p>
 */
@Environment(EnvType.CLIENT)
public final class FFmpegLibrary {

	private static final Object INIT_LOCK = new Object();

	/** Whether the native libraries were successfully preloaded. */
	private static boolean initialized = false;

	/** Whether the FFmpegFrameGrabber classes were successfully linked. */
	private static boolean frameGrabberLinked = false;

	private FFmpegLibrary() {
		// Utility class, no instantiation.
	}

	/**
	 * Ensures the FFmpeg native libraries are loaded and the log callback is set.
	 * This method is idempotent and safe to call multiple times and from multiple
	 * threads.
	 *
	 * <p>If the native libraries cannot be loaded (e.g. missing platform binaries),
	 * the error is logged and the initialization is reset so a later call can retry.
	 * Note that this only registers/preloads the native libraries; it does not link
	 * the {@code FFmpegFrameGrabber} classes. Prefer {@link #initializeFrameGrabber()}
	 * before any decoding work.</p>
	 */
	public static void ensureInitialized() {
		synchronized (INIT_LOCK) {
			if (initialized) {
				return;
			}

			try {
				// 1. Find or download FFmpeg natives.
				boolean nativesReady = WebStreamerNativesManager.setup();

				if (nativesReady) {
					// 2. Pre-load native DLLs via absolute paths so Java 9+ can find
					//    them even if java.library.path is cached from JVM start.
					WebStreamerNativesManager.preloadNativeLibraries();

					try {
						// 3. Trigger JavaCPP's Loader; if our pre-load succeeded this
						//    should find libraries already registered.
						FFmpegLogCallback.setLevel(avutil.AV_LOG_QUIET);
						WebStreamerMod.LOGGER.info("[FFmpeg] Native libraries loaded successfully.");
						initialized = true;
						return;
					} catch (Throwable e) {
						WebStreamerMod.LOGGER.error("[FFmpeg] Failed to load native libraries. " +
								"The files in <" + WebStreamerNativesManager.getNativesSubdir() +
								">/ may be corrupt or mismatched.", e);
					}
				}
			} catch (Throwable e) {
				WebStreamerMod.LOGGER.error("[FFmpeg] Unexpected error while loading native libraries.", e);
			}

			// Keep retryable: a transient failure (e.g. interrupted download) must
			// not permanently disable FFmpeg for the rest of the session.
			initialized = false;
		}
	}

	/**
	 * Fully initializes (links) the {@code FFmpegFrameGrabber} classes and their
	 * transitive JavaCPP dependencies ({@code avutil}, {@code avformat}, ...) on
	 * the calling thread.
	 *
	 * <p>This must be called on a single thread (the render thread, from layer
	 * creation) before any background decode/grabber thread is started. Class
	 * static initialization is done exactly once here, so concurrent decode
	 * threads afterwards only instantiate already-linked classes and can never
	 * race JavaCPP's first-time native load.</p>
	 *
	 * @return True if FFmpegFrameGrabber is usable, false if a fatal error
	 *         occurred (native libraries missing/corrupt). Callers should then
	 *         let their layers fail cleanly instead of retrying.
	 */
	public static boolean initializeFrameGrabber() {
		synchronized (INIT_LOCK) {
			if (frameGrabberLinked) {
				return true;
			}

			ensureInitialized();

			try {
				Class.forName("org.bytedeco.ffmpeg.global.avutil");
				Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
				frameGrabberLinked = true;
				initialized = true;
				return true;
			} catch (Throwable e) {
				WebStreamerMod.LOGGER.error("[FFmpeg] Failed to initialize FFmpegFrameGrabber. "
						+ "Video/GIF/HLS playback is disabled.", e);
				// The classes are now poisoned/sticky in this JVM; keep
				// frameGrabberLinked false and don't reset initialized, since
				// retrying the class linking cannot recover.
				return false;
			}
		}
	}

}