package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.jni.NativeFrameGrabber;
import fr.theorozier.webstreamer.jni.NativeLibrary;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
import fr.theorozier.webstreamer.util.WebStreamerConfig;
import fr.theorozier.webstreamer.display.audio.AudioStreamingBuffer;
import fr.theorozier.webstreamer.display.audio.AudioStreamingSource;
import fr.theorozier.webstreamer.display.source.ServerDisplaySource;
import fr.theorozier.webstreamer.display.source.YoutubeDisplaySource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3i;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Environment(EnvType.CLIENT)
public class DisplayLayerVideo extends DisplayLayerSimple {

    private boolean paused = false;
    private boolean externalPaused = false;
    private long pausedPlaybackMicros = 0;

    private static final int MAX_FAILED_GRABS = 5;
    private static final int FRAME_BUFFER_POOL_SIZE = 24;
    private static final int FRAME_BUFFER_SIZE = 1 * 1024 * 1024;

    /**
     * Dedicated daemon thread for closing native decode sessions. nClose can
     * destroy CUDA/VAAPI contexts (100ms+ of driver work), which must never
     * happen on the render thread.
     */
    private static final ExecutorService NATIVE_CLOSER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WebStreamer-native-closer");
        t.setDaemon(true);
        return t;
    });
    /** Cooldown before an automatic restart attempt after a grabber failure. */
    private static final long RESTART_RETRY_INTERVAL_NS = 2L * 1_000_000_000L;
    /** Bounded automatic restart attempts, so a transient failure while the
     * display is off-screen self-heals instead of staying silent until the
     * renderer rebuilds the layer (which only happens when someone looks back). */
    private static final int MAX_RESTART_RETRIES = 3;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    // Native decode path
    private volatile NativeFrameGrabber nativeGrabber;
    private volatile boolean useNative = false;

    // JavaCV decode path
    private FFmpegFrameGrabber grabber;
    private volatile boolean grabberPending = false;
    private volatile boolean grabberReady   = false;
    private volatile boolean grabberFailed  = false;
    private boolean grabberFailureLogged = false;
    private long lastRestartAttemptNanos = 0;
    private int restartAttempts = 0;
    private int failedGrabs = 0;
    private long refTimestamp = -1;

    private long lastTickNanos  = 0;
    private long playbackMicros = 0;

    // Native path: pts bookkeeping for the playback clock (render thread) and
    // pacing state for the decode worker (decode thread only).
    private long lastNativePtsUs = 0;
    private long pacedPtsUs = -1;
    private long pacedWallNanos = 0;

    private boolean audioInRange = true;
    private ShortBuffer tempAudioBuffer;
    private ByteBuffer tempAudioByteBuf;
    private final AudioStreamingSource audioSource;

    private final DisplayBlockEntity display;
    private URI currentUri;

    private volatile boolean destroyed = false;

    private final Queue<RawAudioChunk> pendingAudioChunks = new ConcurrentLinkedQueue<>();
    private final Queue<PooledVideoFrame> pendingVideoFrames = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<ByteBuffer> bufferPool = new LinkedBlockingQueue<>();
    private Thread decodeThread;
    private volatile boolean decodeFinished = false;
    private long lastDecodedAudioTs = 0;
    private boolean linearFilterApplied = false;

    private static class RawAudioChunk {
        final short[] pcm;
        final int sampleRate;
        final int samples;
        final long timestamp;
        final long duration;

        RawAudioChunk(short[] pcm, int sampleRate, int samples, long timestamp, long duration) {
            this.pcm = pcm;
            this.sampleRate = sampleRate;
            this.samples = samples;
            this.timestamp = timestamp;
            this.duration = duration;
        }
    }

    private static class PooledVideoFrame {
        final ByteBuffer data;
        final int width;
        final int height;
        final int stride;
        final long timestamp;

        PooledVideoFrame(ByteBuffer data, int width, int height, int stride, long timestamp) {
            this.data = data;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.timestamp = timestamp;
        }
    }

    public DisplayLayerVideo(URI uri, DisplayLayerResources res) {
        super(uri, res);
        this.display = null;
        this.currentUri = uri;
        this.audioSource = new AudioStreamingSource(this.makeLog("audio"));
    }

    public DisplayLayerVideo(DisplayLayerNode.Key key, DisplayLayerResources res) {
        super(key.uri(), res);
        this.display = key.display();
        this.currentUri = key.uri();
        this.audioSource = new AudioStreamingSource(this.makeLog("audio"));
        if (this.display != null) {
            this.audioSource.setPosition(this.display.getPos());
            this.audioSource.setAttenuation(this.display.getAudioDistance());
            this.audioSource.setVolume(this.display.getAudioVolume());
        }
    }

    @Override
    public int cost() {
        return 30;
    }

    public URI getCurrentUri() {
        return this.currentUri;
    }

    @Override
    public boolean cleanup(long now) {
        if (super.cleanup(now)) {
            this.setInRange(false);
            this.destroyed = true;
            this.stopGrabber();
            if (this.audioSource.isValid()) {
                this.audioSource.stop();
                this.audioSource.free();
            }
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

    public boolean isInternalPaused() {
        return this.paused;
    }

    @Override
    public void setPlaybackPaused(boolean paused) {
        this.externalPaused = paused;
        if (paused) {
            this.pausePlaybackIfNeeded();
        }
    }

    @Override
    public void setInRange(boolean inRange) {
        super.setInRange(inRange);
        if (!inRange) {
            this.pausePlaybackIfNeeded();
        }
    }

    private void pausePlaybackIfNeeded() {
        if (!this.paused) {
            this.paused = true;
            this.pausedPlaybackMicros = this.playbackMicros;
            if (this.audioSource.isPlaying()) {
                this.audioSource.pause();
            }
        }
    }

    @Override
    public void pushAudioSource(Vec3i pos, float dist, float audioDistance, float audioVolume) {
        if (this.destroyed) {
            return;
        }
        this.audioInRange = audioDistance > 0f && dist <= audioDistance;
        this.audioSource.setPosition(pos);
        this.audioSource.setAttenuation(audioDistance);
        this.audioSource.setVolume(this.audioInRange ? audioVolume : 0f);
    }

    // -------------------------------------------------------------------------
    // Grabber — native path
    // -------------------------------------------------------------------------

    private void startNativeGrabber() {
        if (this.destroyed || !NativeLibrary.isAvailable()) {
            return;
        }

        try {
            NativeFrameGrabber ng = new NativeFrameGrabber();
            if (!ng.open(this.currentUri)) {
                WebStreamerMod.LOGGER.info(makeLog("Native grabber failed to open, falling back to JavaCV"));
                ng.close();
                startJavaCVGrabberAsync();
                return;
            }

            // Enable rolling packet cache for seeking (30s, 32MB)
            ng.enableCache(30_000_000L, 32 * 1024 * 1024);

            if (this.destroyed || this.decodeFinished) {
                // Layer was stopped while the open was in flight.
                ng.close();
                this.grabberPending = false;
                return;
            }

            this.nativeGrabber = ng;
            this.useNative = true;
            this.refTimestamp = 0;
            this.playbackMicros = 0;
            this.lastTickNanos = System.nanoTime();
            this.lastDecodedAudioTs = 0;
            this.grabberReady = true;
            this.grabberPending = false;
            this.failedGrabs = 0;
            this.restartAttempts = 0;
            this.lastRestartAttemptNanos = 0;

            WebStreamerMod.LOGGER.info(makeLog("Native decoder opened for {}"), this.currentUri);

            // Pre-fetch next playlist video
            if (this.display != null) {
                if (this.display.getSource() instanceof YoutubeDisplaySource ytSource && ytSource.hasPlaylist()) {
                    ytSource.prepareNextVideo();
                } else if (this.display.getSource() instanceof ServerDisplaySource srvSource && srvSource.hasPlaylist()) {
                    srvSource.prepareNextVideo();
                }
            }

        } catch (Throwable e) {
            WebStreamerMod.LOGGER.error(makeLog("Failed to start native decoder"), e);
            this.grabberFailed = true;
            this.grabberPending = false;
        }
    }

    /**
     * Open the native grabber (blocking network I/O) and then run the decode
     * loop on a background thread. Called from {@link #tick()} on the render
     * thread, which only spawns the thread — the open itself used to run
     * synchronously here and was the single largest source of frame stalls
     * (Spark: ~19% of the render thread in avformat_open_input + TLS).
     */
    private void startNativeSetup() {
        if (this.destroyed) {
            this.grabberPending = false;
            return;
        }
        this.decodeThread = new Thread(() -> {
            FFmpegFrameGrabber localGrabber = null;
            try {
                // Blocking network open (DNS/TLS/avformat) happens here.
                startNativeGrabber();
                localGrabber = this.grabber;
                if (this.destroyed) {
                    return;
                }
                if (this.grabberReady && this.useNative && !this.decodeFinished) {
                    nativeDecodeLoop();
                } else if (this.grabberReady && !this.useNative) {
                    // Native open failed and fell back to the JavaCV path.
                    backgroundDecodeLoop();
                } else if (this.grabberPending && !this.grabberFailed) {
                    // Nothing was started (e.g. stopped while opening); let
                    // the next tick retry.
                    this.grabberPending = false;
                }
            } finally {
                if (localGrabber != null && (this.destroyed || this.decodeFinished)) {
                    try { localGrabber.releaseUnsafe(); } catch (Exception ignored) { }
                }
            }
        }, "WebStreamer-decode-" + Integer.toHexString(System.identityHashCode(this)));
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    /**
     * Background decode loop for the native path: reads frames (blocking
     * network + decode), converts I420 to BGR off-thread, and publishes them
     * for the render thread to upload. Paces itself to the stream's frame
     * rate and pauses while the layer is not visible (matching
     * visible-upload-only) or while a decoded frame has not been consumed.
     */
    private void nativeDecodeLoop() {
        try {
            while (!this.destroyed && this.useNative && this.grabberReady
                    && this.nativeGrabber != null && !this.decodeFinished) {
                NativeFrameGrabber ng = this.nativeGrabber;
                if (ng == null) {
                    break;
                }

                if (WebStreamerConfig.isVisibleUploadOnly() && !this.isVisible()) {
                    Thread.sleep(50);
                    continue;
                }

                if (!ng.decodeToPending()) {
                    // No frame this pass (end of stream / transient error is
                    // handled inside); avoid busy-spinning.
                    Thread.sleep(10);
                    continue;
                }

                // Pace decoding to the stream's frame rate so this thread
                // doesn't decode faster than realtime.
                long ptsUs = ng.getLastPtsUs();
                long now = System.nanoTime();
                if (this.pacedPtsUs >= 0) {
                    long deltaUs = ptsUs - this.pacedPtsUs;
                    if (deltaUs >= 1_000L && deltaUs <= 500_000L) {
                        long target = this.pacedWallNanos + deltaUs * 1_000L;
                        if (target > now) {
                            long remaining = target - now;
                            Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
                        }
                    } else if (deltaUs < 1_000L) {
                        // Nonsensical pts delta: still throttle to avoid spinning.
                        Thread.sleep(5);
                    }
                }
                this.pacedPtsUs = ptsUs;
                this.pacedWallNanos = System.nanoTime();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!this.destroyed) {
                WebStreamerMod.LOGGER.error(makeLog("Background native decode failed"), e);
                this.grabberFailed = true;
            }
        }
    }

    private void tickNative() {
        long now = System.nanoTime();

        boolean shouldPause = this.externalPaused;
        if (this.lastTickNanos > 0 && !shouldPause) {
            this.playbackMicros += (now - this.lastTickNanos) / 1000L;
        }
        this.lastTickNanos = now;

        if (shouldPause) {
            if (!this.paused) {
                this.paused = true;
                this.pausedPlaybackMicros = this.playbackMicros;
                this.audioSource.pause();
            }
            return;
        } else if (this.paused) {
            this.paused = false;
            this.playbackMicros = this.pausedPlaybackMicros;
            this.audioSource.playFromTimestamp(this.refTimestamp + this.playbackMicros);
        }

        NativeFrameGrabber ng = this.nativeGrabber;
        if (ng == null) {
            return;
        }

        try {
            // Decoding + I420->BGR conversion run on the background decode
            // thread (nativeDecodeLoop); the render thread only uploads the
            // frame that is already converted.
            boolean uploadVisible = !WebStreamerConfig.isVisibleUploadOnly() || this.isVisible();
            if (uploadVisible && ng.uploadPending(this.tex.getGlId())) {
                if (!this.linearFilterApplied) {
                    this.linearFilterApplied = true;
                    this.tex.setLinearFilter();
                }
                long prevPts = this.lastNativePtsUs;
                long newPts = ng.getUploadedPtsUs();
                if (newPts > prevPts && prevPts >= 0) {
                    this.playbackMicros = newPts;
                } else {
                    this.playbackMicros += 33_333;
                }
                this.lastNativePtsUs = newPts;
            }
        } catch (Exception e) {
            WebStreamerMod.LOGGER.debug(makeLog("Native upload failed: {}"), e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Grabber — JavaCV path (original)
    // -------------------------------------------------------------------------

    private void startJavaCVGrabberAsync() {
        if (this.destroyed) {
            return;
        }

        this.useNative = false;
        FFmpegLibrary.ensureInitialized();

        ShortBuffer audioBuf = this.res.allocAudioBuffer();
        if (this.destroyed) {
            this.res.freeAudioBuffer(audioBuf);
            return;
        }

        try {

            AudioStreamingBuffer.resetTimestampTracker();

            for (int i = 0; i < FRAME_BUFFER_POOL_SIZE; i++) {
                this.bufferPool.add(ByteBuffer.allocateDirect(FRAME_BUFFER_SIZE));
            }

            WebStreamerConfig.debugLog(makeLog("Opening FFmpeg with URI: {}"), this.currentUri);
            FFmpegFrameGrabber fg = new FFmpegFrameGrabber(this.currentUri.toString());
            fg.setOption("user_agent", USER_AGENT);
            fg.setOption("headers", "Referer: https://www.youtube.com/");
            fg.startUnsafe();

            Frame frame;
            PooledVideoFrame firstFrame = null;
            while ((frame = fg.grab()) != null) {
                if (frame.image != null) {
                    ByteBuffer buf = this.bufferPool.poll();
                    if (buf != null) {
                        ByteBuffer src = (ByteBuffer) frame.image[0];
                        int srcPos = src.position();
                        int needed = src.remaining();
                        if (buf.capacity() < needed) {
                            buf = ByteBuffer.allocateDirect(needed);
                        }
                        buf.clear();
                        buf.put(src);
                        src.position(srcPos);
                        buf.flip();
                        firstFrame = new PooledVideoFrame(buf, frame.imageWidth, frame.imageHeight, frame.imageStride, frame.timestamp);
                    }
                    break;
                }
            }

            if (this.destroyed) {
                fg.releaseUnsafe();
                this.res.freeAudioBuffer(audioBuf);
                this.bufferPool.clear();
                this.grabberPending = false;
                return;
            }

            ByteBuffer audioByteBuf = ByteBuffer.allocateDirect(8192)
                    .order(ByteOrder.LITTLE_ENDIAN);

            this.grabber            = fg;
            this.tempAudioBuffer    = audioBuf;
            this.tempAudioByteBuf   = audioByteBuf;
            this.refTimestamp       = firstFrame != null ? firstFrame.timestamp : 0;
            this.playbackMicros     = 0;
            this.lastTickNanos      = System.nanoTime();
            this.lastDecodedAudioTs = 0;

            if (firstFrame != null) {
                this.pendingVideoFrames.add(firstFrame);
            }

            this.grabberReady   = true;
            this.grabberPending = false;
            this.failedGrabs = 0;
            this.restartAttempts = 0;
            this.lastRestartAttemptNanos = 0;

            if (this.display != null) {
                if (this.display.getSource() instanceof YoutubeDisplaySource ytSource && ytSource.hasPlaylist()) {
                    ytSource.prepareNextVideo();
                } else if (this.display.getSource() instanceof ServerDisplaySource srvSource && srvSource.hasPlaylist()) {
                    srvSource.prepareNextVideo();
                }
            }

        } catch (Throwable e) {
            WebStreamerMod.LOGGER.error(makeLog("Failed to start video stream."), e);
            this.res.freeAudioBuffer(audioBuf);
            this.bufferPool.clear();
            this.grabberFailed  = true;
            this.grabberPending = false;
        }
    }

    private void backgroundDecodeLoop() {
        try {
            while (!this.destroyed && !this.decodeFinished) {
                ByteBuffer buf = this.bufferPool.poll(100, TimeUnit.MILLISECONDS);
                if (buf == null) {
                    continue;
                }
                Frame frame = this.grabber.grab();
                if (frame == null) {
                    this.bufferPool.add(buf);
                    boolean playlistSource = this.display != null && (
                            (this.display.getSource() instanceof YoutubeDisplaySource yt && yt.hasPlaylist()) ||
                            (this.display.getSource() instanceof ServerDisplaySource srv && srv.hasPlaylist()));
                    if (playlistSource) {
                        this.decodeFinished = true;
                        break;
                    }
                    this.loopVideo();
                    continue;
                }
                if (frame.image != null) {
                    ByteBuffer src = (ByteBuffer) frame.image[0];
                    int srcPos = src.position();
                    int needed = src.remaining();
                    if (buf.capacity() < needed) {
                        buf = ByteBuffer.allocateDirect(needed);
                    }
                    buf.clear();
                    buf.put(src);
                    src.position(srcPos);
                    buf.flip();
                    this.pendingVideoFrames.add(new PooledVideoFrame(buf, frame.imageWidth, frame.imageHeight, frame.imageStride, frame.timestamp));
                } else if (frame.samples != null) {
                    this.bufferPool.add(buf);
                    handleAudioFrame(frame);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!this.destroyed) {
                WebStreamerMod.LOGGER.error(makeLog("Background decode failed"), e);
                this.grabberFailed = true;
                this.decodeFinished = true;
            }
        }
    }

    private void startSetup() {
        this.decodeThread = new Thread(() -> {
            FFmpegFrameGrabber localGrabber = null;
            try {
                startJavaCVGrabberAsync();
                localGrabber = this.grabber;
                if (this.grabberReady && !this.destroyed && !this.useNative) {
                    backgroundDecodeLoop();
                }
            } finally {
                if (localGrabber != null && (this.destroyed || this.decodeFinished)) {
                    try { localGrabber.releaseUnsafe(); } catch (Exception ignored) { }
                }
            }
        }, "WebStreamer-decode-" + Integer.toHexString(System.identityHashCode(this)));
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    private void handleAudioFrame(Frame frame) {
        Buffer raw = frame.samples[0];
        int channels = frame.audioChannels;
        int sampleRate = frame.sampleRate;
        long timestamp = frame.timestamp;

        if (raw == null || channels <= 0 || sampleRate <= 0) {
            return;
        }

        int samples;
        short[] pcm;
        if (raw instanceof ByteBuffer sampleByte) {
            int count = sampleByte.remaining();
            samples = channels == 2 ? count / 2 : count;
            pcm = new short[samples];
            if (channels == 2) {
                for (int i = 0; i < count; i += 2) {
                    short left = (short) (sampleByte.get(i) << 8);
                    short right = (short) (sampleByte.get(i + 1) << 8);
                    pcm[i / 2] = (short) ((left + right) / 2);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    pcm[i] = (short) (sampleByte.get(i) << 8);
                }
            }
        } else if (raw instanceof ShortBuffer sampleShort) {
            int count = sampleShort.remaining();
            samples = channels == 2 ? count / 2 : count;
            pcm = new short[samples];
            if (channels == 2) {
                for (int i = 0; i < count; i += 2) {
                    int left = sampleShort.get(i);
                    int right = sampleShort.get(i + 1);
                    pcm[i / 2] = (short) ((left + right) / 2);
                }
            } else {
                for (int i = 0; i < count; i++) {
                    pcm[i] = sampleShort.get(i);
                }
            }
        } else {
            return;
        }

        long duration = samples * 1000000L / sampleRate;
        if (timestamp <= this.lastDecodedAudioTs || timestamp < 0) {
            timestamp = this.lastDecodedAudioTs + duration;
        }
        this.lastDecodedAudioTs = timestamp;

        this.pendingAudioChunks.add(new RawAudioChunk(pcm, sampleRate, samples, timestamp, duration));
    }

    private void stopGrabber() {
        if (!this.grabberReady && !this.grabberPending) {
            return;
        }
        this.decodeFinished = true;

        // Stop native grabber: kill immediately (non-blocking interrupt flag),
        // then close off the render thread — nClose may destroy a CUDA/VAAPI
        // context, which costs 100ms+ of driver time.
        NativeFrameGrabber ng = this.nativeGrabber;
        this.nativeGrabber = null;
        this.useNative = false;
        if (ng != null) {
            ng.kill();
            NATIVE_CLOSER.execute(() -> {
                try {
                    ng.close();
                } catch (Throwable ignored) {}
            });
        }

        // Stop JavaCV grabber
        Thread dt = this.decodeThread;
        this.decodeThread = null;
        if (dt != null) {
            dt.interrupt();
        }
        if (this.tempAudioBuffer != null) {
            this.res.freeAudioBuffer(this.tempAudioBuffer);
            this.tempAudioBuffer = null;
        }
        this.tempAudioByteBuf = null;
        this.pendingVideoFrames.clear();
        this.pendingAudioChunks.clear();
        this.bufferPool.clear();
        this.grabberReady   = false;
        this.grabberPending = false;
    }

    private int tryRestartNextVideo() {
        if (this.display != null && this.display.getSource() instanceof YoutubeDisplaySource youtubeSource) {
            if (!youtubeSource.hasPlaylist()) {
                return RESTART_NO_PLAYLIST;
            }
            if (!youtubeSource.hasPreparedNextUri()) {
                youtubeSource.prepareNextVideo();
                return RESTART_WAITING;
            }
            if (!youtubeSource.advanceVideo()) {
                return RESTART_NO_PLAYLIST;
            }
            URI nextUri = youtubeSource.consumePreparedNextUri();
            if (nextUri == null) {
                return RESTART_NO_PLAYLIST;
            }
            DisplayNetworking.sendDisplayUpdate(this.display);
            WebStreamerConfig.debugLog(makeLog("Restarting YouTube playlist with next video {}"), youtubeSource.getCurrentVideoId());
            WebStreamerConfig.debugLog(makeLog("Next video URI: {}"), nextUri);
            this.currentUri = nextUri;
            this.failedGrabs = 0;
            this.refTimestamp = -1;
            this.pausedPlaybackMicros = 0;
            this.playbackMicros = 0;
            this.lastTickNanos = 0;
            this.pendingVideoFrames.clear();
            this.pendingAudioChunks.clear();
            this.bufferPool.clear();
            this.lastDecodedAudioTs = 0;
            this.grabberFailed = false;
            this.grabberFailureLogged = false;
            this.audioSource.stop();
            this.stopGrabber();
            this.decodeFinished = false;
            this.grabberPending = true;
            this.startSetup();
            return RESTART_RESTARTED;
        }

        if (this.display != null && this.display.getSource() instanceof ServerDisplaySource serverSource) {
            if (!serverSource.hasPlaylist()) {
                return RESTART_NO_PLAYLIST;
            }
            if (!serverSource.hasPreparedNextUri()) {
                serverSource.prepareNextVideo();
                return RESTART_WAITING;
            }
            if (!serverSource.advanceVideo()) {
                return RESTART_NO_PLAYLIST;
            }
            URI nextUri = serverSource.consumePreparedNextUri();
            if (nextUri == null) {
                return RESTART_NO_PLAYLIST;
            }
            DisplayNetworking.sendDisplayUpdate(this.display);
            WebStreamerConfig.debugLog(makeLog("Restarting server playlist with next video {}"), serverSource.getCurrentVideoId());
            WebStreamerConfig.debugLog(makeLog("Next video URI: {}"), nextUri);
            this.currentUri = nextUri;
            this.failedGrabs = 0;
            this.refTimestamp = -1;
            this.pausedPlaybackMicros = 0;
            this.playbackMicros = 0;
            this.lastTickNanos = 0;
            this.pendingVideoFrames.clear();
            this.pendingAudioChunks.clear();
            this.bufferPool.clear();
            this.lastDecodedAudioTs = 0;
            this.grabberFailed = false;
            this.grabberFailureLogged = false;
            this.audioSource.stop();
            this.stopGrabber();
            this.decodeFinished = false;
            this.grabberPending = true;
            this.startSetup();
            return RESTART_RESTARTED;
        }

        return RESTART_NO_PLAYLIST;
    }

    private void loopVideo() {
        WebStreamerMod.LOGGER.debug(makeLog("Video loop restart"));
        this.playbackMicros = 0;
        this.refTimestamp = -1;
        this.lastTickNanos = System.nanoTime();
        this.lastDecodedAudioTs = 0;
        this.pendingVideoFrames.clear();
        this.pendingAudioChunks.clear();
        if (this.grabber != null) {
            try {
                this.grabber.releaseUnsafe();
            } catch (Exception ignored) { }
            try {
                FFmpegFrameGrabber fg = new FFmpegFrameGrabber(this.currentUri.toString());
                fg.setOption("user_agent", USER_AGENT);
                fg.setOption("headers", "Referer: https://www.youtube.com/");
                fg.startUnsafe();
                this.grabber = fg;
            } catch (Throwable e) {
                WebStreamerMod.LOGGER.error(makeLog("Failed to loop video."), e);
                this.stopGrabber();
                this.grabberFailed = true;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tick (render thread)
    // -------------------------------------------------------------------------

    @Override
    public void tick() {
        if (this.destroyed) {
            return;
        }

        this.lastUse = System.nanoTime();

        if (this.grabberFailed) {
            if (!this.grabberFailureLogged) {
                WebStreamerMod.LOGGER.info(makeLog("Grabber failed, stopping audio."));
                this.grabberFailureLogged = true;
            }
            this.audioSource.stop();
            if (this.restartAttempts < MAX_RESTART_RETRIES
                    && System.nanoTime() - this.lastRestartAttemptNanos >= RESTART_RETRY_INTERVAL_NS) {
                this.lastRestartAttemptNanos = System.nanoTime();
                this.restartAttempts++;
                WebStreamerConfig.debugLog(makeLog("Auto-retrying stream after grabber failure (attempt {}/{})."),
                        this.restartAttempts, MAX_RESTART_RETRIES);
                this.grabberFailed = false;
                this.grabberFailureLogged = false;
                this.failedGrabs = 0;
                this.stopGrabber();
                this.grabberReady = false;
                this.grabberPending = false;
                this.decodeFinished = false;
            }
            return;
        }

        // Start grabber on first tick. The native open performs blocking
        // network I/O (DNS/TLS/avformat), so it runs on a background thread —
        // on the render thread it caused multi-hundred-ms stalls on every
        // video start, playlist advance and layer rebuild.
        if (!this.grabberPending && !this.grabberReady && !this.decodeFinished) {
            this.grabberPending = true;
            if (NativeLibrary.isAvailable()) {
                this.startNativeSetup();
            } else {
                this.startSetup();
            }
            return;
        }

        if (!this.grabberReady) {
            if (!this.decodeFinished) {
                WebStreamerMod.LOGGER.debug(makeLog("Grabber pending, waiting for ready state."));
            }
            return;
        }

        // Dispatch to native or JavaCV tick
        if (this.useNative) {
            tickNative();
        } else {
            tickJavaCV();
        }
    }

    private static final int RESTART_RESTARTED = 1;
    private static final int RESTART_WAITING = -1;
    private static final int RESTART_NO_PLAYLIST = 0;

    private void tickJavaCV() {
        long now = System.nanoTime();

        boolean shouldPause = this.externalPaused;
        if (this.lastTickNanos > 0 && !shouldPause) {
            this.playbackMicros += (now - this.lastTickNanos) / 1000L;
        }
        this.lastTickNanos = now;

        if (this.audioSource != null && this.audioSource.isPlaying() && this.refTimestamp >= 0 && !shouldPause) {
            long audioTs = this.audioSource.getEstimatedPlaybackTimestamp();
            if (audioTs > 0) {
                long audioRelative = audioTs - this.refTimestamp;
                long diff = audioRelative - this.playbackMicros;
                if (diff > 50000L) {
                    this.playbackMicros = audioRelative;
                    WebStreamerMod.LOGGER.debug(makeLog("Synced video clock to audio at {} (diff={}us)"), audioRelative, diff);
                }
            }
        }

        try {
            boolean uploadVisible = !WebStreamerConfig.isVisibleUploadOnly() || this.isVisible();
            if (shouldPause) {
                if (!this.paused) {
                    this.paused = true;
                    this.pausedPlaybackMicros = this.playbackMicros;
                    this.audioSource.pause();
                }
                return;
            } else if (this.paused) {
                this.paused = false;
                this.playbackMicros = this.pausedPlaybackMicros;
                this.audioSource.playFromTimestamp(this.refTimestamp + this.playbackMicros);
            }

            int audioBuffersQueued = 0;
            int framesDisplayed = 0;

            try {
                ShortBuffer audioDataShort = this.tempAudioByteBuf.asShortBuffer();
                RawAudioChunk chunk;
                while ((chunk = this.pendingAudioChunks.poll()) != null) {
                    audioDataShort.clear();
                    audioDataShort.put(chunk.pcm);
                    audioDataShort.flip();
                    AudioStreamingBuffer audioBuf = AudioStreamingBuffer.fromRawData(
                            this.tempAudioBuffer, audioDataShort,
                            1, chunk.sampleRate, chunk.timestamp);
                    if (audioBuf != null) {
                        this.audioSource.queueBuffer(audioBuf);
                        audioBuffersQueued++;
                    }
                }
            } catch (Exception e) {
                WebStreamerConfig.debugLog(makeLog("Audio drain failed: {}"), e.getMessage());
            }

            PooledVideoFrame pvf;
            while ((pvf = this.pendingVideoFrames.peek()) != null) {
                long realTimestamp = pvf.timestamp - this.refTimestamp;
                if (realTimestamp <= this.playbackMicros) {
                    this.pendingVideoFrames.poll();
                    PooledVideoFrame next;
                    while ((next = this.pendingVideoFrames.peek()) != null && next.timestamp - this.refTimestamp <= this.playbackMicros) {
                        this.bufferPool.add(pvf.data);
                        pvf = this.pendingVideoFrames.poll();
                    }
                    if (uploadVisible) {
                        this.tex.uploadRaw(pvf.data, GL11.GL_RGB8, pvf.width, pvf.height, pvf.stride / 3, GL12.GL_BGR, 4);
                        if (!this.linearFilterApplied) {
                            this.linearFilterApplied = true;
                            this.tex.setLinearFilter();
                        }
                    }
                    this.bufferPool.add(pvf.data);
                    this.playbackMicros = pvf.timestamp;
                    framesDisplayed++;
                } else {
                    break;
                }
            }

            if (audioBuffersQueued > 0) {
                this.audioSource.playFrom(this.refTimestamp + this.playbackMicros);
            }

            if (this.decodeFinished) {
                try {
                    RawAudioChunk chunk;
                    while ((chunk = this.pendingAudioChunks.poll()) != null) {
                        ShortBuffer audioDataShort = this.tempAudioByteBuf.asShortBuffer();
                        audioDataShort.clear();
                        audioDataShort.put(chunk.pcm);
                        audioDataShort.flip();
                        AudioStreamingBuffer audioBuf = AudioStreamingBuffer.fromRawData(
                                this.tempAudioBuffer, audioDataShort,
                                1, chunk.sampleRate, chunk.timestamp);
                        if (audioBuf != null) {
                            this.audioSource.queueBuffer(audioBuf);
                            audioBuffersQueued++;
                        }
                    }
                } catch (Exception e) {
                    WebStreamerConfig.debugLog(makeLog("Audio drain failed at end of stream: {}"), e.getMessage());
                }
                if (audioBuffersQueued > 0) {
                    this.audioSource.playFrom(this.refTimestamp + this.playbackMicros);
                }

                PooledVideoFrame lastFrame = null;
                PooledVideoFrame tmp;
                while ((tmp = this.pendingVideoFrames.poll()) != null) {
                    if (lastFrame != null) {
                        this.bufferPool.add(lastFrame.data);
                    }
                    lastFrame = tmp;
                }
                if (lastFrame != null) {
                    if (uploadVisible) {
                        this.tex.uploadRaw(lastFrame.data, GL11.GL_RGB8, lastFrame.width, lastFrame.height, lastFrame.stride / 3, GL12.GL_BGR, 4);
                    }
                    this.bufferPool.add(lastFrame.data);
                    framesDisplayed++;
                }
                if (framesDisplayed > 0) {
                    this.audioSource.playFrom(this.refTimestamp + this.playbackMicros);
                }

                int restartResult = this.tryRestartNextVideo();
                if (restartResult == RESTART_RESTARTED) {
                    if (this.display != null) {
                        if (this.display.getSource() instanceof YoutubeDisplaySource ytSource && ytSource.hasPlaylist()) {
                            ytSource.prepareNextVideo();
                        } else if (this.display.getSource() instanceof ServerDisplaySource srvSource && srvSource.hasPlaylist()) {
                            srvSource.prepareNextVideo();
                        }
                    }
                    return;
                } else if (restartResult == RESTART_WAITING) {
                    return;
                }
                WebStreamerMod.LOGGER.info(makeLog("Reached end of video."));
                this.stopGrabber();
                this.audioSource.stop();
            }

        } catch (Exception e) {
            this.failedGrabs++;
            WebStreamerMod.LOGGER.error(makeLog("Failed to grab frame ({}/{})."),
                    this.failedGrabs, MAX_FAILED_GRABS, e);
            if (this.failedGrabs >= MAX_FAILED_GRABS) {
                WebStreamerMod.LOGGER.error(makeLog("Too many failed grabs, marking layer as lost."));
                this.stopGrabber();
                this.grabberFailed = true;
                this.audioSource.stop();
            }
        }
    }

}
