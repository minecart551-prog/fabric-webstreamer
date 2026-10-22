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

	/**
	 * Whether FFmpeg initialization failed irrecoverably for this JVM session.
	 * A failed class static initializer is sticky (subsequent uses throw
	 * {@code NoClassDefFoundError}), so retrying and re-logging the full stack
	 * trace on every call is pointless and, when called from the render thread,
	 * destroys performance. Once set, all entry points fail fast and silently.
	 */
	private static boolean unavailable = false;

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
	 * <p>If the natives are not ready yet (e.g. download failed), initialization
	 * stays retryable. However, if a FFmpeg/JavaCPP class static initializer
	 * fails, the failure is sticky for the whole JVM session, so it is logged
	 * once and FFmpeg is marked unavailable: later calls fail fast and silently
	 * instead of re-running (and re-logging) a doomed initialization every
	 * frame. Note that this only registers/preloads the native libraries; it
	 * does not link the {@code FFmpegFrameGrabber} classes. Prefer
	 * {@link #initializeFrameGrabber()} before any decoding work.</p>
	 */
	public static void ensureInitialized() {
		synchronized (INIT_LOCK) {
			if (initialized || unavailable) {
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
						// A failure here means a class static initializer already ran
						// and failed, which is sticky for the whole JVM session: every
						// later call would throw NoClassDefFoundError no matter what.
						// Disable FFmpeg and log exactly once instead of retrying (and
						// re-logging full stack traces) on every caller.
						unavailable = true;
						WebStreamerMod.LOGGER.error("[FFmpeg] Failed to load native libraries. The files in <" +
								WebStreamerNativesManager.getNativesSubdir() +
								">/ may be corrupt or mismatched. Video/GIF/HLS playback is disabled.", e);
						return;
					}
				}
			} catch (Throwable e) {
				// Nothing should reach here (setup/preload handle their own errors),
				// but if it does, treat it the same as a sticky failure so a broken
				// environment cannot spam stack traces on every caller.
				unavailable = true;
				WebStreamerMod.LOGGER.error("[FFmpeg] Unexpected error while loading native libraries. " +
						"Video/GIF/HLS playback is disabled.", e);
			}

			// Natives were not ready (e.g. download failed): keep retryable, but a
			// later class-linking failure in initializeFrameGrabber() will disable
			// further attempts for the rest of the session.
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
			if (unavailable) {
				return false;
			}

			ensureInitialized();

			// ensureInitialized() may have discovered an unrecoverable failure;
			// don't attempt (and fail, and log again) class linking in that case.
			if (unavailable) {
				return false;
			}

			try {
				Class.forName("org.bytedeco.ffmpeg.global.avutil");
				Class.forName("org.bytedeco.javacv.FFmpegFrameGrabber");
				frameGrabberLinked = true;
				initialized = true;
				return true;
			} catch (Throwable e) {
				// The classes are now poisoned/sticky in this JVM; disable further
				// attempts and log exactly once — callers may run every frame.
				unavailable = true;
				WebStreamerMod.LOGGER.error("[FFmpeg] Failed to initialize FFmpegFrameGrabber. "
						+ "Video/GIF/HLS playback is disabled.", e);
				return false;
			}
		}
	}

}