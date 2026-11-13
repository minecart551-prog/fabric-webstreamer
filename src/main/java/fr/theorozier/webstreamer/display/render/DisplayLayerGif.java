package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
import fr.theorozier.webstreamer.util.WebStreamerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Environment(EnvType.CLIENT)
public class DisplayLayerGif extends DisplayLayerSimple {

    private static final int MAX_FAILED_GRABS = 5;
    private static final int MAX_RESTART_RETRIES = 3;
    private static final long RESTART_RETRY_INTERVAL_NS = 2L * 1_000_000_000L;
    private static final int FRAME_BUFFER_POOL_SIZE = 4;
    private static final int FRAME_BUFFER_SIZE = 512 * 1024;
    private static final int MAX_PENDING_FRAMES = 4;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36";

    private FFmpegFrameGrabber grabber;
    private Path tempFile;
    private boolean grabberPending = false;
    private boolean grabberReady = false;
    private boolean grabberFailed = false;
    private boolean grabberFailureLogged = false;
    private int failedGrabs = 0;
    private int restartAttempts = 0;
    private long lastRestartAttemptNanos = 0;

    private long lastTickNanos = 0;
    private long playbackMicros = 0;

    /** Gif-time position (microseconds) of the decode thread's latest decoded image
     * frame. Written by the decode thread, read by the render thread to realign the
     * playback clock when decoding resumes, so it must be volatile. */
    private volatile long decodePositionMicros = 0;

    /** Whether the layer was decodable (in range and on screen) on the last tick,
     * used to detect a resume and realign playback before fast-forwarding. */
    private boolean lastShouldDecode = true;

    /**
     * Set by the decode thread when it restarts the GIF from the beginning
     * (loop or auto-retry after a failure). The playback clock belongs to the
     * render thread, so the decode thread cannot reset it directly — it raises
     * this flag and the next {@link #tick()} rewinds playback instead. Without
     * this, frames timestamped from 0 are all instantly "due" and get flushed
     * in one tick, showing up as the GIF jumping to super speed after every loop.
     */
    private volatile boolean rewindRequested = false;

    private final Queue<PooledGifFrame> pendingFrames = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<ByteBuffer> bufferPool = new LinkedBlockingQueue<>();
    private Thread decodeThread;
    private volatile boolean decodeFinished = false;
    /**
     * Decode-session generation, bumped by every {@link #stopGrabber()} call.
     * A decode thread captures the value when it starts and abandons its loop
     * — releasing only the grabber it opened itself — as soon as it differs.
     * stopGrabber() alone cannot invalidate an old thread: the failure-retry
     * path resets decodeFinished back to false immediately afterwards, which
     * used to resurrect the old thread alongside its replacement (two threads
     * decoding into one layer, and an old finally clobbering the new
     * session's grabber/flags/temp file).
     */
    private final AtomicInteger decodeEpoch = new AtomicInteger();
    private final boolean randomStartFrame;

    private static class PooledGifFrame {
        final ByteBuffer data;
        final int width;
        final int height;
        final int stride;
        final long timestamp;

        PooledGifFrame(ByteBuffer data, int width, int height, int stride, long timestamp) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.timestamp = timestamp;
        }
    }

    public DisplayLayerGif(URI uri, DisplayLayerResources res, boolean randomStartFrame) {
        super(uri, res);
        this.randomStartFrame = randomStartFrame;
    }

    /**
     * Whether background decoding should currently run. Decoding is paused when the
     * display is out of range or out of view (when visible-upload-only is enabled),
     * so the decode thread doesn't accumulate backlog or waste CPU behind your back.
     */
    private boolean shouldDecode() {
        if (!this.isInRange()) {
            return false;
        }
        return !WebStreamerConfig.isVisibleUploadOnly() || this.isVisible();
    }

    @Override
    public void setInRange(boolean inRange) {
        boolean prev = this.isInRange();
        super.setInRange(inRange);
        if (inRange && !prev) {
            // Just re-entered range while decode was paused. Realign the playback
            // clock to where the decoder currently is and drop any stale backlog,
            // so the gif resumes seamlessly instead of fast-forwarding through
            // frames that were decoded while we were away.
            this.pendingFrames.clear();
            this.playbackMicros = this.decodePositionMicros;
            this.lastTickNanos = System.nanoTime();
            this.lastShouldDecode = true;
        }
    }

    @Override
    public int cost() {
        return 5;
    }

    @Override
    public boolean cleanup(long now) {
        if (super.cleanup(now)) {
            this.destroyed = true;
            this.stopGrabber();
            return true;
        }
        return false;
    }

    @Override
    public boolean isLost() {
        return this.grabberFailed;
    }

    public boolean isPermanentlyFailed() {
        return this.grabberFailed && this.restartAttempts >= MAX_RESTART_RETRIES;
    }

    public boolean isGrabberFailed() {
        return this.grabberFailed;
    }

    public boolean isGrabberReady() {
        return this.grabberReady;
    }

    public boolean isGrabberPending() {
        return this.grabberPending;
    }

    public boolean isDecodeFinished() {
        return this.decodeFinished;
    }

    public int getFailedGrabs() {
        return this.failedGrabs;
    }

    public int getRestartAttempts() {
        return this.restartAttempts;
    }

    private void startSetup() {
        final int epoch = this.decodeEpoch.get();
        this.decodeThread = new Thread(() -> {
            FFmpegFrameGrabber localGrabber = null;
            Path localTempFile = null;
            try {
                localGrabber = downloadAndOpenGrabber(epoch);
                if (localGrabber != null) {
                    localTempFile = this.tempFile;
                }
                if (this.grabberReady && !this.destroyed && epoch == this.decodeEpoch.get()) {
                    backgroundDecodeLoop(epoch);
                }
            } finally {
                // Decode thread owns the grabber lifecycle.
                // Release in finally to prevent use-after-free when stopGrabber()
                // signals decodeFinished + interrupt while we're mid-grab().
                // A superseded thread (epoch differs) releases only what it
                // opened itself and leaves the shared flags alone — the
                // replacement session owns those now.
                if (epoch == this.decodeEpoch.get()) {
                    this.grabberReady = false;
                    this.grabberPending = false;
                }
                if (localGrabber != null) {
                    try { localGrabber.releaseUnsafe(); } catch (Exception ignored) { }
                    if (this.grabber == localGrabber) {
                        this.grabber = null;
                    }
                }
                if (localTempFile != null) {
                    deleteTempFile(localTempFile);
                    if (this.tempFile == localTempFile) {
                        this.tempFile = null;
                    }
                }
            }
        }, "WebStreamer-gif-" + Integer.toHexString(System.identityHashCode(this)));
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    private FFmpegFrameGrabber downloadAndOpenGrabber(int epoch) {
        if (this.destroyed || epoch != this.decodeEpoch.get()) return null;

        if (this.uri.getScheme() == null) {
            WebStreamerMod.LOGGER.error("[DisplayLayerGif] URI has no scheme: {}", this.uri);
            return null;
        }

        FFmpegLibrary.ensureInitialized();

        Path tmp = null;
        try {
            tmp = Files.createTempFile("webstreamer_gif_", ".gif");

            HttpRequest req = HttpRequest.newBuilder(this.uri)
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<InputStream> response = this.res.getHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading GIF");
            }

            try (InputStream body = response.body()) {
                Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            if (this.destroyed || epoch != this.decodeEpoch.get()) {
                deleteTempFile(tmp);
                if (epoch == this.decodeEpoch.get()) {
                    this.grabberPending = false;
                }
                return null;
            }

            FFmpegFrameGrabber fg = new FFmpegFrameGrabber(tmp.toString());
            fg.startUnsafe();

            if (this.destroyed || epoch != this.decodeEpoch.get()) {
                // Stopped while startUnsafe() was blocking — this grabber was
                // never published to the layer, so release it right here
                // instead of letting a superseded thread's finally find it.
                try { fg.releaseUnsafe(); } catch (Exception ignored) { }
                deleteTempFile(tmp);
                return null;
            }

            this.grabber = fg;
            this.tempFile = tmp;

            // Random start frame: seek to a random position in the GIF.
            if (this.randomStartFrame) {
                long durationUs = fg.getLengthInTime();
                if (durationUs > 0) {
                    long randomUs = (long) (Math.random() * durationUs);
                    try {
                        fg.setTimestamp(randomUs);
                        this.playbackMicros = randomUs;
                    } catch (Exception e) {
                        WebStreamerMod.LOGGER.warn(makeLog("Failed to seek to random frame, starting from 0: {}"), e.getMessage());
                        this.playbackMicros = 0;
                    }
                } else {
                    this.playbackMicros = 0;
                }
            } else {
                this.playbackMicros = 0;
            }
            this.lastTickNanos = System.nanoTime();

            for (int i = 0; i < FRAME_BUFFER_POOL_SIZE; i++) {
                this.bufferPool.add(ByteBuffer.allocateDirect(FRAME_BUFFER_SIZE));
            }

            this.grabberReady = true;
            this.grabberPending = false;

            return fg;

        } catch (Throwable e) {
            if (!this.destroyed && epoch == this.decodeEpoch.get()) {
                WebStreamerMod.LOGGER.error(makeLog("Failed to start GIF grabber."), e);
                this.grabberFailed = true;
            }
            deleteTempFile(tmp);
            if (epoch == this.decodeEpoch.get()) {
                this.grabberPending = false;
            }
            return null;
        }
    }

    private void backgroundDecodeLoop(int epoch) {
        try {
            while (!this.destroyed && !this.decodeFinished && epoch == this.decodeEpoch.get()) {
                // Pause decoding while the display is out of range or off screen:
                // there is nobody to watch it, so keep the queue from running ahead
                // of the playback clock (which is what caused a fast-forward burst
                // when coming back into range).
                while (!this.destroyed && !this.decodeFinished
                        && epoch == this.decodeEpoch.get() && !this.shouldDecode()) {
                    Thread.sleep(10);
                }
                if (this.destroyed || this.decodeFinished || epoch != this.decodeEpoch.get()) break;

                // Throttle: wait if too many frames are pending (prevents unbounded memory growth)
                while (!this.destroyed && !this.decodeFinished
                        && epoch == this.decodeEpoch.get() && this.pendingFrames.size() >= MAX_PENDING_FRAMES) {
                    Thread.sleep(10);
                }
                if (this.destroyed || this.decodeFinished || epoch != this.decodeEpoch.get()) break;

                ByteBuffer buf = this.bufferPool.poll(100, TimeUnit.MILLISECONDS);
                if (buf == null) {
                    continue;
                }
                FFmpegFrameGrabber fg = this.grabber;
                if (fg == null || epoch != this.decodeEpoch.get()) {
                    // Superseded between the loop check and here; hand the
                    // buffer back and let the replacement thread own the layer.
                    this.bufferPool.add(buf);
                    break;
                }
                Frame frame = fg.grab();
                if (epoch != this.decodeEpoch.get()) {
                    // Stopped or restarted while blocked in grab(): this frame
                    // belongs to the dying session — drop it and exit instead
                    // of queueing it into the replacement session.
                    this.bufferPool.add(buf);
                    break;
                }
                if (frame == null) {
                    this.bufferPool.add(buf);
                    this.loopGif(epoch);
                    continue;
                }
                if (frame.image != null) {
                    ByteBuffer src = (ByteBuffer) frame.image[0];
                    int srcPos = src.position();
                    int needed = src.remaining();
                    if (buf.capacity() < needed) {
                        // Return the pooled buffer before allocating a larger one
                        // to prevent the pool from being drained by oversized frames.
                        this.bufferPool.add(buf);
                        buf = ByteBuffer.allocateDirect(needed);
                    }
                    buf.clear();
                    buf.put(src);
                    src.position(srcPos);
                    buf.flip();
                    this.pendingFrames.add(new PooledGifFrame(buf, frame.imageWidth, frame.imageHeight, frame.imageStride, frame.timestamp));
                    this.decodePositionMicros = frame.timestamp;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!this.destroyed && epoch == this.decodeEpoch.get()) {
                WebStreamerMod.LOGGER.error(makeLog("Background GIF decode failed"), e);
                this.grabberFailed = true;
                this.decodeFinished = true;
            }
        }
    }

    private void loopGif(int epoch) {
        if (epoch != this.decodeEpoch.get()) {
            // Superseded while blocked in grab(): the replacement session owns
            // this.grabber now, so do not seek or recreate it from here.
            return;
        }
        WebStreamerMod.LOGGER.debug(makeLog("GIF loop restart"));
        // The decode thread only owns the grabber side of the loop. The playback
        // clock (playbackMicros/lastTickNanos) belongs to the render thread and is
        // realigned on resume, so it must not be touched here — ask the next
        // tick() to rewind instead (see rewindRequested).
        this.pendingFrames.clear();
        this.decodePositionMicros = 0;
        this.rewindRequested = true;
        if (this.grabber != null) {
            try {
                this.grabber.setTimestamp(0);
                return;
            } catch (Exception ignored) { }
            // Fallback: if seek fails, recreate the grabber.
            try {
                this.grabber.releaseUnsafe();
            } catch (Exception ignored) { }
            try {
                FFmpegFrameGrabber fg = new FFmpegFrameGrabber(this.tempFile.toString());
                fg.startUnsafe();
                this.grabber = fg;
            } catch (Throwable e2) {
                WebStreamerMod.LOGGER.error(makeLog("Failed to loop GIF."), e2);
                this.stopGrabber();
                this.grabberFailed = true;
            }
        }
    }

    private void stopGrabber() {
        // Invalidate any running decode thread before anything else — see
        // decodeEpoch. Bumped even when the flags below say there is nothing
        // to stop: a previous stop may have cleared them while the old thread
        // is still winding down out of grab().
        this.decodeEpoch.incrementAndGet();
        if (!this.grabberReady && !this.grabberPending) return;
        this.decodeFinished = true;
        Thread dt = this.decodeThread;
        this.decodeThread = null;
        if (dt != null) {
            dt.interrupt();
            // Do not join — the decode thread releases the grabber in its
            // finally block. Joining would block the render thread for up to
            // 2 seconds per layer, causing massive freezes when many layers
            // are cleaned up simultaneously (e.g. after the 60 s orphan timeout).
        } else if (this.grabber != null) {
            // Decode thread never started (destroyed during download).
            // Safe to release here since no other thread is using the grabber.
            try { this.grabber.releaseUnsafe(); } catch (Exception ignored) { }
            this.grabber = null;
            deleteTempFile(this.tempFile);
            this.tempFile = null;
            this.grabberReady = false;
            this.grabberPending = false;
        }
        this.pendingFrames.clear();
        this.bufferPool.clear();
        // NOTE: the temp file is NOT deleted here on purpose. The decode thread's
        // finally block owns deletion (it only runs after the grabber released the
        // file). Deleting it from the render thread would retry-block for up to
        // 3x50ms while the file is still open — freezing the frame for every GIF
        // torn down when leaving an area with many displays.
        this.grabberReady = false;
        this.grabberPending = false;
    }

    private void deleteTempFile(Path path) {
        if (path != null) {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    Files.deleteIfExists(path);
                    return;
                } catch (IOException e) {
                    if (attempt < 2) {
                        try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                    } else {
                        WebStreamerMod.LOGGER.warn(makeLog("Could not delete temp file after retries: {}"), path);
                    }
                }
            }
        }
    }

    @Override
    public void tick() {
        if (this.destroyed) return;

        this.lastUse = System.nanoTime();

        if (this.grabberFailed) {
            if (!this.grabberFailureLogged) {
                WebStreamerMod.LOGGER.info(makeLog("GIF grabber failed."));
                this.grabberFailureLogged = true;
            }
            if (this.restartAttempts < MAX_RESTART_RETRIES
                    && System.nanoTime() - this.lastRestartAttemptNanos >= RESTART_RETRY_INTERVAL_NS) {
                this.lastRestartAttemptNanos = System.nanoTime();
                this.restartAttempts++;
                WebStreamerConfig.debugLog(makeLog("Auto-retrying GIF after grabber failure (attempt {}/{})."),
                        this.restartAttempts, MAX_RESTART_RETRIES);
                this.grabberFailed = false;
                this.grabberFailureLogged = false;
                this.failedGrabs = 0;
                this.stopGrabber();
                this.grabberReady = false;
                this.grabberPending = false;
                this.decodeFinished = false;
                this.rewindRequested = true;
            }
            return;
        }

        if (!this.grabberPending && !this.grabberReady && !this.decodeFinished) {
            this.grabberPending = true;
            this.startSetup();
            return;
        }

        if (!this.grabberReady) return;

        boolean shouldDecode = this.shouldDecode();

        long now = System.nanoTime();
        if (this.rewindRequested) {
            // The decode thread restarted from frame 0 (loop/auto-retry): rewind
            // the playback clock with it. Otherwise every freshly decoded frame
            // is instantly due and the GIF plays at decode speed.
            this.pendingFrames.clear();
            this.playbackMicros = 0;
            this.lastTickNanos = now;
            this.lastShouldDecode = true;
            this.rewindRequested = false;
        } else if (shouldDecode && !this.lastShouldDecode) {
            // Decode was paused (off screen) and just became needed again.
            // Realign the playback clock to where the decoder currently is and
            // drop the stale backlog, so playback resumes without fast-forwarding
            // through frames that were skipped while invisible.
            this.pendingFrames.clear();
            this.playbackMicros = this.decodePositionMicros;
            this.lastTickNanos = now;
            this.lastShouldDecode = true;
        } else {
            this.lastShouldDecode = shouldDecode;
            if (this.lastTickNanos > 0) {
                this.playbackMicros += (now - this.lastTickNanos) / 1000L;
            }
            this.lastTickNanos = now;
        }

        try {
            PooledGifFrame frame;
            while ((frame = this.pendingFrames.peek()) != null) {
                if (frame.timestamp <= this.playbackMicros) {
                    this.pendingFrames.poll();
                    // Skip to the latest ready frame if multiple are queued,
                    // discarding stale ones to stay in sync with wall-clock time.
                    PooledGifFrame next;
                    while ((next = this.pendingFrames.peek()) != null && next.timestamp <= this.playbackMicros) {
                        this.bufferPool.add(frame.data);
                        frame = this.pendingFrames.poll();
                    }
                    if (!WebStreamerConfig.isVisibleUploadOnly() || this.isVisible()) {
                        this.tex.uploadRaw(frame.data, GL11.GL_RGB8, frame.width, frame.height, frame.stride / 3, GL12.GL_BGR, 4);
                    }
                    this.bufferPool.add(frame.data);
                    this.failedGrabs = 0;
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            this.failedGrabs++;
            WebStreamerMod.LOGGER.error(makeLog("Failed to process GIF frame ({}/{})."),
                    this.failedGrabs, MAX_FAILED_GRABS, e);
            if (this.failedGrabs >= MAX_FAILED_GRABS) {
                this.stopGrabber();
                this.grabberFailed = true;
            }
        }
    }
}
