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
    private boolean audioInRange = true;
    private ShortBuffer tempAudioBuffer;
    private final AudioStreamingSource audioSource;

    private volatile boolean destroyed = false;

    public DisplayLayerVideo(URI uri, DisplayLayerResources res) {
        super(uri, res);
        this.audioSource = new AudioStreamingSource(this.makeLog("audio"));
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
            this.setInRange(false); // Mark as out of range so tick/logging stops immediately
            this.destroyed = true;
            this.stopGrabber();
            this.audioSource.stop(); // Ensure audio is stopped immediately
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
        if (this.destroyed) {
            return;
        }
        this.audioInRange = audioDistance > 0f && dist <= audioDistance;
        if (!this.audioInRange) {
            WebStreamerMod.LOGGER.info(makeLog("audioInRange=false dist={} max={}"), dist, audioDistance);
            if (this.audioSource.isPlaying()) {
                WebStreamerMod.LOGGER.info(makeLog("Player out of audio range dist={} max={}, stopping audio source"), dist, audioDistance);
            }
            this.audioSource.stop();
            return;
        }

        // Update this layer's audio properties immediately
        // Each layer plays independently, so audio persists across frames
        this.audioSource.setPosition(pos);
        this.audioSource.setAttenuation(audioDistance);
        this.audioSource.setVolume(audioVolume);
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
        if (this.destroyed) {
            return;
        }
        Path tmp = null;
        ShortBuffer audioBuf = this.res.allocAudioBuffer();
        if (this.destroyed) {
            this.res.freeAudioBuffer(audioBuf);
            return;
        }
        WebStreamerMod.LOGGER.info(makeLog("Starting direct video grabber, uri={}"), this.uri);
        try {

            // Reset audio timestamp tracker for new video
            AudioStreamingBuffer.resetTimestampTracker();
            
            // 1. Download to a temp file using Java's HttpClient.
            tmp = Files.createTempFile("webstreamer_yt_", ".mp4");

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

            if (this.destroyed) {
                fg.releaseUnsafe();
                deleteTempFile(tmp);
                this.res.freeAudioBuffer(audioBuf);
                this.grabberPending = false;
                return;
            }

            this.grabber         = fg;
            this.tempFile        = tmp;
            this.tempAudioBuffer = audioBuf;
            this.refTimestamp    = firstFrameTimestamp;  // Store reference timestamp
            this.playbackMicros  = 0;
            this.lastTickNanos   = System.nanoTime();
            this.grabberReady    = true;
            this.grabberPending  = false;
            WebStreamerMod.LOGGER.info(makeLog("Direct video grabber ready, first frame ts={} usec"), firstFrameTimestamp);

        } catch (Exception e) {
            WebStreamerMod.LOGGER.error(makeLog("Failed to start direct video grabber."), e);
            deleteTempFile(tmp);
            this.res.freeAudioBuffer(audioBuf);
            this.grabberFailed  = true;
            this.grabberPending = false;
        }
    }

    private void stopGrabber() {
        // Only stop and log if grabber is actually running or pending
        if (!this.grabberReady && !this.grabberPending) {
            return;
        }
        WebStreamerMod.LOGGER.info(makeLog("Stopping grabber, grabberReady={} grabberPending={}"), this.grabberReady, this.grabberPending);
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
        if (this.destroyed) {
            return;
        }

        // Prevent any ticking if destroyed (extra guard)
        if (this.destroyed) {
            return;
        }

        this.lastUse = System.nanoTime();

        if (this.grabberFailed) {
            // Layer is permanently failed, stop audio
            WebStreamerMod.LOGGER.info(makeLog("Grabber failed, stopping audio."));
            this.audioSource.stop();
            return;
        }

        // Note: pushAudioSource() will be called during rendering to update audio position/volume

        // Submit the download+start task once to the executor.
        if (!this.grabberPending && !this.grabberReady) {
            WebStreamerMod.LOGGER.info(makeLog("Requesting grabber start."));
            this.grabberPending = true;
            this.res.getExecutor().submit(this::startGrabberAsync);
            return;
        }

        // Still waiting for download to finish.
        if (!this.grabberReady) {
            WebStreamerMod.LOGGER.debug(makeLog("Grabber pending, waiting for ready state."));
            return;
        }

        // Advance playback clock by wall-clock elapsed time.
        long now = System.nanoTime();
        if (this.lastTickNanos > 0) {
            this.playbackMicros += (now - this.lastTickNanos) / 1000L;
        }
        this.lastTickNanos = now;

        if (this.audioSource != null && this.audioSource.isPlaying() && this.refTimestamp >= 0) {
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
            // Stop audio if the player is currently out of range
            if (!this.audioInRange && this.audioSource.isPlaying()) {
                this.audioSource.stop();
            }

            // If out of range, do not tick audio or video at all
            if (!this.audioInRange) {
                return;
            }

            // Display the buffered frame if it's ready
            if (this.lastFrame != null && this.lastFrame.image != null) {
                long realTimestamp = this.lastFrame.timestamp - this.refTimestamp;
                WebStreamerMod.LOGGER.debug(makeLog("Buffered frame pending, realTimestamp={} playbackMicros={}"), realTimestamp, this.playbackMicros);
                if (realTimestamp <= this.playbackMicros) {
                    this.tex.upload(this.lastFrame);
                    this.playbackMicros = realTimestamp;
                    this.audioSource.playFrom(this.lastFrame.timestamp);
                    this.failedGrabs = 0;
                    this.lastFrame = null;
                    WebStreamerMod.LOGGER.info(makeLog("Displayed buffered frame at ts={}"), realTimestamp);
                } else {
                    // Buffered frame not ready yet, don't grab more
                    return;
                }
            }

            // Grab all available frames and audio
            Frame frame;
            int framesDisplayed = 0;
            int audioBuffersQueued = 0;
            while ((frame = this.grabber.grab()) != null) {
                if (frame.image != null) {
                    long realTimestamp = frame.timestamp - this.refTimestamp;
                    if (realTimestamp <= this.playbackMicros) {
                        // Frame is ready to display
                        this.tex.upload(frame);
                        this.playbackMicros = realTimestamp;
                        this.audioSource.playFrom(frame.timestamp);
                        this.failedGrabs = 0;
                        framesDisplayed++;
                    } else {
                        // Frame is ahead, buffer it for later
                        this.lastFrame = frame;
                        WebStreamerMod.LOGGER.debug(makeLog("Buffered future frame at ts={} playbackMicros={}"), realTimestamp, this.playbackMicros);
                        break;
                    }
                } else if (frame.samples != null) {
                    // Queue audio frame
                    AudioStreamingBuffer audioBuf = AudioStreamingBuffer.fromFrame(
                            this.tempAudioBuffer, frame);
                    this.audioSource.queueBuffer(audioBuf);
                    audioBuffersQueued++;
                }
            }

            if (audioBuffersQueued > 0) {
                long currentTimestamp = this.refTimestamp + this.playbackMicros;
                WebStreamerMod.LOGGER.debug(makeLog("Audio flush timestamp={} playbackMicros={} lastFrameTs={}"), currentTimestamp, this.playbackMicros, this.lastFrame != null ? this.lastFrame.timestamp : -1L);
                this.audioSource.playFrom(currentTimestamp);
            }

            if (framesDisplayed > 0 || audioBuffersQueued > 0) {
                if (framesDisplayed == 0 && audioBuffersQueued > 0) {
                    WebStreamerMod.LOGGER.warn(makeLog("Audio only tick: audioQueued={} playbackMicros={} queuedFrames={}"), audioBuffersQueued, this.playbackMicros, this.lastFrame != null ? this.lastFrame.timestamp - this.refTimestamp : -1L);
                } else {
                    WebStreamerMod.LOGGER.info(makeLog("tick result: framesDisplayed={} audioQueued={}"), framesDisplayed, audioBuffersQueued);
                }
            }

            if (frame == null) {
                WebStreamerMod.LOGGER.warn(makeLog("Reached EOF, stopping grabber."));
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
                this.audioSource.stop();
            }
        }
    }
}