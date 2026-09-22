package fr.theorozier.webstreamer.jni;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A frame grabber backed by the Rust native FFmpeg decoder. Provides the same
 * logical interface as {@link fr.theorozier.webstreamer.display.render.FrameGrabber}
 * but uses the native library for hardware-accelerated decoding with packet
 * caching and chunked HTTP.
 *
 * <p>Falls back gracefully: if the native library is not available, all methods
 * return failure states and the caller can fall back to the JavaCV path.</p>
 *
 * <p><b>Backward compatibility:</b> This class is purely additive. The existing
 * {@code DisplayLayerVideo} continues to use JavaCV by default. Native decoding
 * is only activated when the native library is loaded AND the user/server opts in
 * (e.g. via config).</p>
 */
@Environment(EnvType.CLIENT)
public class NativeFrameGrabber {

    private long handle = 0;
    private boolean started = false;
    private boolean failed = false;

    private int lastWidth = 0;
    private int lastHeight = 0;
    private long lastPtsUs = 0;

    // I420 frame buffer (direct for JNI)
    private ByteBuffer yuvBuffer;
    private static final int MAX_FRAME_SIZE = 3840 * 2160 * 3 / 2; // 4K I420

    public NativeFrameGrabber() {}

    /**
     * Open a decode session for the given URL.
     * @return true if the session was opened successfully.
     */
    public boolean open(URI uri) {
        if (!NativeLibrary.isAvailable()) {
            return false;
        }
        try {
            handle = NativeLibrary.nOpen(uri.toString());
            if (handle < 0) {
                failed = true;
                return false;
            }
            yuvBuffer = ByteBuffer.allocateDirect(MAX_FRAME_SIZE).order(ByteOrder.nativeOrder());
            started = true;
            return true;
        } catch (Throwable t) {
            WebStreamerMod.LOGGER.debug("[Native] Failed to open {}: {}", uri, t.getMessage());
            failed = true;
            return false;
        }
    }

    /**
     * Enable the rolling packet cache for this session.
     * @param maxDurationUs Maximum cache duration in microseconds.
     * @param maxBytes Maximum cache size in bytes.
     */
    public void enableCache(long maxDurationUs, long maxBytes) {
        if (handle > 0) {
            try {
                NativeLibrary.nEnableCache(handle, maxDurationUs, maxBytes);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Read the next frame as I420 and upload it to the given GL texture.
     *
     * @param textureId The OpenGL texture ID to upload to.
     * @return true if a frame was decoded and uploaded.
     */
    public boolean readAndUpload(int textureId) {
        if (handle <= 0 || !started) return false;

        try {
            int[] dims = new int[2];
            long[] pts = new long[1];
            yuvBuffer.clear();
            long written = NativeLibrary.nReadFrameI420(handle, yuvBuffer, MAX_FRAME_SIZE, dims, pts);

            if (written <= 0) return false;

            int w = dims[0];
            int h = dims[1];
            long ptsUs = pts[0];

            if (w <= 0 || h <= 0) return false;

            lastWidth = w;
            lastHeight = h;
            lastPtsUs = ptsUs;

            // Upload I420 as RGB to texture
            uploadI420AsRGB(textureId);

            return true;
        } catch (Throwable t) {
            WebStreamerMod.LOGGER.debug("[Native] Read frame failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Convert I420 to RGB and upload to the GL texture.
     * This is a simple CPU-side conversion. A production implementation would
     * use a shader or the native library's GL interop.
     */
    private void uploadI420AsRGB(int textureId) {
        int w = lastWidth;
        int h = lastHeight;
        int ySize = w * h;
        int uvSize = (w / 2) * (h / 2);

        // Read I420 from the native buffer
        byte[] yuv = new byte[ySize + uvSize * 2];
        yuvBuffer.position(0);
        yuvBuffer.get(yuv, 0, Math.min(yuv.length, yuvBuffer.capacity()));

        // Convert I420 to RGB
        byte[] rgb = new byte[w * h * 3];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int y = yuv[row * w + col] & 0xFF;
                int u = yuv[ySize + (row / 2) * (w / 2) + (col / 2)] & 0xFF;
                int v = yuv[ySize + uvSize + (row / 2) * (w / 2) + (col / 2)] & 0xFF;

                int c = y - 16;
                int d = u - 128;
                int e = v - 128;

                int r = clamp((298 * c + 409 * e + 128) >> 8);
                int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                int b = clamp((298 * c + 516 * d + 128) >> 8);

                int idx = (row * w + col) * 3;
                rgb[idx] = (byte) r;
                rgb[idx + 1] = (byte) g;
                rgb[idx + 2] = (byte) b;
            }
        }

        // Upload to GL texture
        ByteBuffer buf = ByteBuffer.allocateDirect(rgb.length);
        buf.put(rgb);
        buf.flip();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0, GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, buf);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * Seek to a position in microseconds.
     */
    public void seek(long positionUs) {
        if (handle > 0) {
            try {
                NativeLibrary.nSeek(handle, positionUs);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Interrupt any blocking network/decode operation.
     */
    public void kill() {
        if (handle > 0) {
            try {
                NativeLibrary.nKill(handle);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * Close the decode session and free resources.
     */
    public void close() {
        if (handle > 0) {
            try {
                NativeLibrary.nClose(handle);
            } catch (Throwable ignored) {}
            handle = 0;
        }
        started = false;
        yuvBuffer = null;
    }

    public boolean isFailed() { return failed; }
    public boolean isStarted() { return started; }
    public int getLastWidth() { return lastWidth; }
    public int getLastHeight() { return lastHeight; }
    public long getLastPtsUs() { return lastPtsUs; }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}
