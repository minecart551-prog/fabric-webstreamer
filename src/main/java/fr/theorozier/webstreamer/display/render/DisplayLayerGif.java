package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
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

@Environment(EnvType.CLIENT)
public class DisplayLayerGif extends DisplayLayerSimple {

    private static final int MAX_FAILED_GRABS = 5;
    private static final int FRAME_BUFFER_POOL_SIZE = 4;
    private static final int FRAME_BUFFER_SIZE = 512 * 1024;
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

    private long lastTickNanos = 0;
    private long playbackMicros = 0;

    private final Queue<PooledGifFrame> pendingFrames = new ConcurrentLinkedQueue<>();
    private final LinkedBlockingQueue<ByteBuffer> bufferPool = new LinkedBlockingQueue<>();
    private Thread decodeThread;
    private volatile boolean decodeFinished = false;

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

    private void startSetup() {
        this.decodeThread = new Thread(() -> {
            downloadAndOpenGrabber();
            if (this.grabberReady && !this.destroyed) {
                backgroundDecodeLoop();
            }
        }, "WebStreamer-gif-" + Integer.toHexString(System.identityHashCode(this)));
        this.decodeThread.setDaemon(true);
        this.decodeThread.start();
    }

    private void downloadAndOpenGrabber() {
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

            for (int i = 0; i < FRAME_BUFFER_POOL_SIZE; i++) {
                this.bufferPool.add(ByteBuffer.allocateDirect(FRAME_BUFFER_SIZE));
            }

            this.grabberReady = true;
            this.grabberPending = false;

        } catch (Exception e) {
            WebStreamerMod.LOGGER.error(makeLog("Failed to start GIF grabber."), e);
            deleteTempFile(tmp);
            this.grabberFailed = true;
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
                    this.loopGif();
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
                    this.pendingFrames.add(new PooledGifFrame(buf, frame.imageWidth, frame.imageHeight, frame.imageStride, frame.timestamp));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (!this.destroyed) {
                WebStreamerMod.LOGGER.error(makeLog("Background GIF decode failed"), e);
                this.grabberFailed = true;
                this.decodeFinished = true;
            }
        }
    }

    private void loopGif() {
        WebStreamerMod.LOGGER.debug(makeLog("GIF loop restart"));
        this.playbackMicros = 0;
        this.lastTickNanos = System.nanoTime();
        this.pendingFrames.clear();
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

    private void stopGrabber() {
        if (!this.grabberReady && !this.grabberPending) return;
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
        this.pendingFrames.clear();
        this.bufferPool.clear();
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

    @Override
    public void tick() {
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
            this.startSetup();
            return;
        }

        if (!this.grabberReady) return;

        long now = System.nanoTime();
        if (this.lastTickNanos > 0) {
            this.playbackMicros += (now - this.lastTickNanos) / 1000L;
        }
        this.lastTickNanos = now;

        try {
            PooledGifFrame frame;
            while ((frame = this.pendingFrames.peek()) != null) {
                if (frame.timestamp <= this.playbackMicros) {
                    this.pendingFrames.poll();
                    this.tex.uploadRaw(frame.data, GL11.GL_RGB8, frame.width, frame.height, frame.stride / 3, GL12.GL_BGR, 4);
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
