package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Environment(EnvType.CLIENT)
public class DisplayLayerGif extends DisplayLayerSimple {

    private static final int MAX_FAILED_GRABS = 5;
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
    private Frame lastFrame = null;

    private long lastTickNanos = 0;
    private long playbackMicros = 0;

    public DisplayLayerGif(URI uri, DisplayLayerResources res) {
        super(uri, res);
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

    private void startGrabberAsync() {
        if (this.destroyed) return;

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

            if (this.destroyed) {
                deleteTempFile(tmp);
                this.grabberPending = false;
                return;
            }

            FFmpegFrameGrabber fg = new FFmpegFrameGrabber(tmp.toString());
            fg.startUnsafe();

            this.grabber = fg;
            this.tempFile = tmp;
            this.playbackMicros = 0;
            this.lastTickNanos = System.nanoTime();
            this.grabberReady = true;
            this.grabberPending = false;

        } catch (Exception e) {
            WebStreamerMod.LOGGER.error(makeLog("Failed to start GIF grabber."), e);
            deleteTempFile(tmp);
            this.grabberFailed = true;
            this.grabberPending = false;
        }
    }

    private void stopGrabber() {
        if (!this.grabberReady && !this.grabberPending) return;
        if (this.grabber != null) {
            try { this.grabber.releaseUnsafe(); } catch (Exception ignored) { }
            this.grabber = null;
        }
        this.lastFrame = null;
        deleteTempFile(this.tempFile);
        this.tempFile = null;
        this.grabberReady = false;
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

    private void loopGif() {
        WebStreamerMod.LOGGER.debug(makeLog("GIF loop restart"));
        this.playbackMicros = 0;
        this.lastTickNanos = System.nanoTime();
        this.lastFrame = null;
        if (this.grabber != null) {
            try {
                this.grabber.releaseUnsafe();
            } catch (Exception ignored) { }
            try {
                FFmpegFrameGrabber fg = new FFmpegFrameGrabber(this.tempFile.toString());
                fg.startUnsafe();
                this.grabber = fg;
            } catch (Exception e2) {
                WebStreamerMod.LOGGER.error(makeLog("Failed to loop GIF."), e2);
                this.stopGrabber();
                this.grabberFailed = true;
            }
        }
    }

    @Override
    public void tick() {
        if (this.destroyed) return;
        if (this.destroyed) return;

        this.lastUse = System.nanoTime();

        if (this.grabberFailed) {
            if (!this.grabberFailureLogged) {
                WebStreamerMod.LOGGER.info(makeLog("GIF grabber failed."));
                this.grabberFailureLogged = true;
            }
            return;
        }

        if (!this.grabberPending && !this.grabberReady) {
            this.grabberPending = true;
            this.res.getExecutor().submit(this::startGrabberAsync);
            return;
        }

        if (!this.grabberReady) return;

        long now = System.nanoTime();
        if (this.lastTickNanos > 0) {
            this.playbackMicros += (now - this.lastTickNanos) / 1000L;
        }
        this.lastTickNanos = now;

        try {
            if (this.lastFrame != null && this.lastFrame.image != null) {
                if (this.lastFrame.timestamp <= this.playbackMicros) {
                    this.tex.upload(this.lastFrame);
                    this.failedGrabs = 0;
                    this.lastFrame = null;
                } else {
                    return;
                }
            }

            Frame frame;
            while ((frame = this.grabber.grab()) != null) {
                if (frame.image != null) {
                    if (frame.timestamp <= this.playbackMicros) {
                        this.tex.upload(frame);
                        this.failedGrabs = 0;
                    } else {
                        this.lastFrame = frame;
                        break;
                    }
                }
            }

            if (frame == null) {
                this.loopGif();
            }

        } catch (Exception e) {
            this.failedGrabs++;
            WebStreamerMod.LOGGER.error(makeLog("Failed to grab GIF frame ({}/{})."),
                    this.failedGrabs, MAX_FAILED_GRABS, e);
            if (this.failedGrabs >= MAX_FAILED_GRABS) {
                this.stopGrabber();
                this.grabberFailed = true;
            }
        }
    }
}
