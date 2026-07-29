package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
import fr.theorozier.webstreamer.display.audio.AudioStreamingBuffer;
import fr.theorozier.webstreamer.display.audio.AudioStreamingSource;
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
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    private FFmpegFrameGrabber grabber;
    private boolean grabberPending = false;
    private volatile boolean grabberReady   = false;
    private boolean grabberFailed  = false;
    private boolean grabberFailureLogged = false;
    private int failedGrabs = 0;
    private long refTimestamp = -1;

    private long lastTickNanos  = 0;
    private long playbackMicros = 0;

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
    }

    @Override
    public int cost() {
        return 30;
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
        if (!this.audioInRange) {
            this.audioSource.stop();
            return;
        }
        this.audioSource.setPosition(pos);
        this.audioSource.setAttenuation(audioDistance);
        this.audioSource.setVolume(audioVolume);
    }

    // -------------------------------------------------------------------------
    // Grabber
    // -------------------------------------------------------------------------

    private void startGrabberAsync() {
        if (this.destroyed) {
            return;
        }

        FFmpegLibrary.ensureInitialized();

        ShortBuffer audioBuf = this.res.allocAudioBuffer();
        if (this.destroyed) {
            this.res.freeAudioBuffer(audioBuf);
            return;
        }

        try {

            AudioStreamingBuffer.resetTimestampTracker();

            // Initialize buffer pool
            for (int i = 0; i < FRAME_BUFFER_POOL_SIZE; i++) {
                this.bufferPool.add(ByteBuffer.allocateDirect(FRAME_BUFFER_SIZE));
            }

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

        } catch (Exception e) {
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
                    this.decodeFinished = true;
                    break;
                }
                if (frame.image != null) {
                    ByteBuffer src = (ByteBuffer) frame.image[0];
                    int srcPos = src.position();
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
            startGrabberAsync();
            if (this.grabberReady && !this.destroyed) {
                backgroundDecodeLoop();
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
        Thread dt = this.decodeThread;
        this.decodeThread = null;
        if (dt != null) {
            dt.interrupt();
            try { dt.join(2000); } catch (InterruptedException ignored) { }
        }
        if (this.grabber != null) {
            try { this.grabber.releaseUnsafe(); } catch (Exception ignored) { }
            this.grabber = null;
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

    private boolean tryRestartNextVideo() {
        if (!(this.display != null && this.display.getSource() instanceof YoutubeDisplaySource youtubeSource)) {
            return false;
        }
        if (!youtubeSource.advanceVideo()) {
            return false;
        }
        URI nextUri = youtubeSource.getUri();
        if (nextUri == null) {
            return false;
        }
        WebStreamerMod.LOGGER.info(makeLog("Restarting YouTube playlist with next video {}"), youtubeSource.getCurrentVideoId());
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
        return true;
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
            return;
        }

        if (!this.grabberPending && !this.grabberReady && !this.decodeFinished) {
            this.grabberPending = true;
            this.startSetup();
            return;
        }

        if (!this.grabberReady) {
            if (!this.decodeFinished) {
                WebStreamerMod.LOGGER.debug(makeLog("Grabber pending, waiting for ready state."));
            }
            return;
        }

        long now = System.nanoTime();

        boolean shouldPause = !this.audioInRange || this.externalPaused;
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
                if (this.audioInRange) {
                    this.audioSource.playFromTimestamp(this.refTimestamp + this.playbackMicros);
                }
            }

            int audioBuffersQueued = 0;
            int framesDisplayed = 0;

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
                    this.tex.uploadRaw(pvf.data, GL11.GL_RGB8, pvf.width, pvf.height, pvf.stride / 3, GL12.GL_BGR, 4);
                    this.bufferPool.add(pvf.data);
                    this.playbackMicros = pvf.timestamp;
                    framesDisplayed++;
                } else {
                    break;
                }
            }

            if (audioBuffersQueued > 0 || framesDisplayed > 0) {
                long currentTimestamp = this.refTimestamp + this.playbackMicros;
                this.audioSource.playFrom(currentTimestamp);
            }

            if (this.decodeFinished) {
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
                    this.tex.uploadRaw(lastFrame.data, GL11.GL_RGB8, lastFrame.width, lastFrame.height, lastFrame.stride / 3, GL12.GL_BGR, 4);
                    this.bufferPool.add(lastFrame.data);
                    framesDisplayed++;
                }
                if (framesDisplayed > 0) {
                    this.audioSource.playFrom(this.refTimestamp + this.playbackMicros);
                }

                if (this.tryRestartNextVideo()) {
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
