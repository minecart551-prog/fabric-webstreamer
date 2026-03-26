package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.audio.AudioStreamingBuffer;
import fr.theorozier.webstreamer.display.audio.AudioStreamingSource;
import fr.theorozier.webstreamer.WebStreamerClientMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3i;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Display layer for direct video URLs (e.g. YouTube progressive MP4 streams).
 *
 * Downloads the video to a temp file using Java's HttpClient (which handles
 * HTTPS reliably), then opens FFmpeg on the local file path. This avoids both
 * the 8 MB buffer limit of FrameGrabber and FFmpeg's HTTPS issues in javacv 1.5.7.
 *
 * The temp file is deleted when the layer is cleaned up.
 */
@Environment(EnvType.CLIENT)
public class DisplayLayerVideo extends DisplayLayerSimple {

    private static final int MAX_FAILED_GRABS = 5;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Safari/537.36";

    // Grabber state
    private FFmpegFrameGrabber grabber;
    private Path tempFile;
    private boolean grabberPending = false;
    private boolean grabberReady   = false;
    private boolean grabberFailed  = false;
    private int failedGrabs = 0;
    private long refTimestamp = -1;  // First image frame timestamp from FFmpeg
    private Frame lastFrame = null;   // Buffered frame for later playback

    // Timing
    private long lastTickNanos  = 0;
    private long playbackMicros = 0;

    // Audio
    private ShortBuffer tempAudioBuffer;
    private final AudioStreamingSource audioSource;
    private Vec3i nearestAudioPos;
    private float nearestAudioDist     = Float.MAX_VALUE;
    private float nearestAudioDistance = 0f;
    private float nearestAudioVolume   = 0f;

    public DisplayLayerVideo(URI uri, DisplayLayerResources res) {
        super(uri, res);
        this.audioSource = new AudioStreamingSource();
    }

    // -------------------------------------------------------------------------
    // DisplayLayerNode overrides
    // -------------------------------------------------------------------------

    @Override
    public int cost() {
        return 30;
    }

    @Override
    public boolean cleanup(long now) {
        if (super.cleanup(now)) {
            this.stopGrabber();
            this.audioSource.free();
            return true;
        }
        return false;
    }

    @Override
    public boolean isLost() {
        return this.grabberFailed;
    }

    // -------------------------------------------------------------------------
    // Audio
    // -------------------------------------------------------------------------

    @Override
    public void pushAudioSource(Vec3i pos, float dist, float audioDistance, float audioVolume) {
        if (dist < this.nearestAudioDist) {
            this.nearestAudioPos      = pos;
            this.nearestAudioDist     = dist;
            this.nearestAudioDistance = audioDistance;
            this.nearestAudioVolume   = audioVolume;
        }
    }

    private void flushAudioSource() {
        if (this.nearestAudioPos != null) {
            this.audioSource.setPosition(this.nearestAudioPos);
            this.audioSource.setAttenuation(this.nearestAudioDistance);
            this.audioSource.setVolume(this.nearestAudioVolume);
        } else {
            this.audioSource.stop();
        }
        this.nearestAudioPos      = null;
        this.nearestAudioDist     = Float.MAX_VALUE;
        this.nearestAudioDistance = 0f;
        this.nearestAudioVolume   = 0f;
    }

    // -------------------------------------------------------------------------
    // Grabber
    // -------------------------------------------------------------------------

    /**
     * Called on the executor thread.
     * Downloads the video to a temp file via Java HttpClient, then opens
     * FFmpeg on the local path, bypassing FFmpeg's HTTPS stack entirely.
     */
    private void startGrabberAsync() {
        Path tmp = null;
        ShortBuffer audioBuf = this.res.allocAudioBuffer();
        try {
            // Reset audio timestamp tracker for new video
            AudioStreamingBuffer.resetTimestampTracker();
            
            // 1. Download to a temp file using Java's HttpClient.
            tmp = Files.createTempFile("webstreamer_yt_", ".mp4");
            WebStreamerMod.LOGGER.info(makeLog("Downloading video to: {}"), tmp);

            HttpRequest req = HttpRequest.newBuilder(this.uri)
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://www.youtube.com/")
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<InputStream> response = WebStreamerClientMod.YOUTUBE_CLIENT.getHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading video");
            }

            try (InputStream body = response.body()) {
                Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
            }

            // 2. Open FFmpeg on the local file — no HTTPS needed.
            FFmpegFrameGrabber fg = new FFmpegFrameGrabber(tmp.toString());
            fg.startUnsafe();

            // Find the first image frame to use as reference timestamp
            Frame frame;
            long firstFrameTimestamp = 0;
            while ((frame = fg.grab()) != null) {
                if (frame.image != null) {
                    firstFrameTimestamp = frame.timestamp;
                    this.lastFrame = frame;  // Buffer the first frame
                    break;
                }
            }

            this.grabber         = fg;
            this.tempFile        = tmp;
            this.tempAudioBuffer = audioBuf;
            this.refTimestamp    = firstFrameTimestamp;  // Store reference timestamp
            this.playbackMicros  = 0;
            this.lastTickNanos   = System.nanoTime();
            this.grabberReady    = true;

        } catch (Exception e) {
            WebStreamerMod.LOGGER.error(makeLog("Failed to start direct video grabber."), e);
            deleteTempFile(tmp);
            this.res.freeAudioBuffer(audioBuf);
            this.grabberFailed  = true;
            this.grabberPending = false;
        }
    }

    private void stopGrabber() {
        if (this.grabber != null) {
            try { this.grabber.releaseUnsafe(); } catch (Exception ignored) { }
            this.grabber = null;
        }
        if (this.tempAudioBuffer != null) {
            this.res.freeAudioBuffer(this.tempAudioBuffer);
            this.tempAudioBuffer = null;
        }
        this.lastFrame = null;
        this.refTimestamp = -1;
        deleteTempFile(this.tempFile);
        this.tempFile       = null;
        this.grabberReady   = false;
        this.grabberPending = false;
    }

    private void deleteTempFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                WebStreamerMod.LOGGER.warn(makeLog("Could not delete temp file: {}"), path);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tick (render thread)
    // -------------------------------------------------------------------------

    @Override
    public void tick() {

        if (this.grabberFailed) {
            this.flushAudioSource();
            return;
        }

        // Submit the download+start task once to the executor.
        if (!this.grabberPending && !this.grabberReady) {
            this.grabberPending = true;
            this.res.getExecutor().submit(this::startGrabberAsync);
            this.flushAudioSource();
            return;
        }

        // Still waiting for download to finish.
        if (!this.grabberReady) {
            this.flushAudioSource();
            return;
        }

        // Advance playback clock by wall-clock elapsed time.
        long now = System.nanoTime();
        if (this.lastTickNanos > 0) {
            this.playbackMicros += (now - this.lastTickNanos) / 1000L;
        }
        this.lastTickNanos = now;

        try {
            // Display the buffered frame if it's ready
            if (this.lastFrame != null && this.lastFrame.image != null) {
                long realTimestamp = this.lastFrame.timestamp - this.refTimestamp;
                if (realTimestamp <= this.playbackMicros) {
                    this.tex.upload(this.lastFrame);
                    this.audioSource.playFrom(this.lastFrame.timestamp);
                    this.failedGrabs = 0;
                    this.lastFrame = null;
                } else {
                    // Buffered frame not ready yet, don't grab more
                    this.flushAudioSource();
                    return;
                }
            }

            // Grab all available frames and audio
            Frame frame;
            int framesDisplayed = 0;
            while ((frame = this.grabber.grab()) != null) {
                if (frame.image != null) {
                    long realTimestamp = frame.timestamp - this.refTimestamp;
                    if (realTimestamp <= this.playbackMicros) {
                        // Frame is ready to display
                        this.tex.upload(frame);
                        this.audioSource.playFrom(frame.timestamp);
                        this.failedGrabs = 0;
                        framesDisplayed++;
                    } else {
                        // Frame is ahead, buffer it for later
                        this.lastFrame = frame;
                        break;
                    }
                } else if (frame.samples != null) {
                    // Queue audio frame
                    AudioStreamingBuffer audioBuf = AudioStreamingBuffer.fromFrame(
                            this.tempAudioBuffer, frame);
                    this.audioSource.queueBuffer(audioBuf);
                }
            }

            if (frame == null) {
                WebStreamerMod.LOGGER.info(makeLog("Video stream ended, freeing layer."));
                this.stopGrabber();
                this.grabberFailed = true;
            }

        } catch (Exception e) {
            this.failedGrabs++;
            WebStreamerMod.LOGGER.error(makeLog("Failed to grab frame ({}/{})."),
                    this.failedGrabs, MAX_FAILED_GRABS, e);
            if (this.failedGrabs >= MAX_FAILED_GRABS) {
                WebStreamerMod.LOGGER.error(makeLog("Too many failed grabs, marking layer as lost."));
                this.stopGrabber();
                this.grabberFailed = true;
            }
        }

        this.flushAudioSource();
    }
}