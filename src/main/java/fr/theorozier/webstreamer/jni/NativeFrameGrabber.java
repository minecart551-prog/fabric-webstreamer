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
 */
@Environment(EnvType.CLIENT)
public class NativeFrameGrabber {

    private volatile long handle = 0;
    private volatile boolean started = false;
    private boolean failed = false;

    private int lastWidth = 0;
    private int lastHeight = 0;
    private long lastPtsUs = 0;

    // I420 frame buffer (direct for JNI) — decode worker thread only
    private ByteBuffer yuvBuffer;
    private static final int MAX_FRAME_SIZE = 3840 * 2160 * 3 / 2; // 4K I420

    // Pre-allocated conversion buffers (reused across frames to avoid GC pressure)
    private byte[] yuvArray;
    private int allocYuvSize = 0;

    // Frame handoff between the decode worker (fills a slot) and the render
    // thread (uploads the pending slot to GL). Double-buffered so the worker
    // can decode the next frame while GL uploads the current one; the worker
    // blocks when a frame is still pending, which backpressures decoding and
    // effectively pauses it while the layer is not being rendered.
    private final Object handoffLock = new Object();
    private boolean pendingNew = false;
    private int fillSlot = 0;
    private int pendingSlot = -1;
    private final byte[][] rgbArrays = new byte[2][];
    private final ByteBuffer[] rgbBuffers = new ByteBuffer[2];
    private final int[] allocRgbSizes = new int[2];
    private int pendingWidth = 0;
    private int pendingHeight = 0;
    private long pendingPtsUs = 0;
    private long uploadedPtsUs = 0;

    // Render thread only: remembers the size currently stored in the GL
    // texture so unchanged frames can use glTexSubImage2D.
    private int uploadedTexW = 0;
    private int uploadedTexH = 0;

    // Reusable int arrays for JNI output (avoid per-call allocation)
    private final int[] dimsOut = new int[2];
    private final long[] ptsOut = new long[1];

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
     * Decode the next frame and convert it to BGR. Called from the background
     * decode thread only. Blocks (briefly) while the render thread has not yet
     * uploaded the previously decoded frame.
     *
     * @return true if a frame was decoded and published for upload.
     */
    public boolean decodeToPending() {
        if (handle <= 0 || !started) return false;

        try {
            synchronized (handoffLock) {
                while (pendingNew) {
                    if (handle <= 0 || !started) return false;
                    handoffLock.wait(50);
                }
            }

            yuvBuffer.clear();
            long written = NativeLibrary.nReadFrameI420(handle, yuvBuffer, MAX_FRAME_SIZE, dimsOut, ptsOut);

            if (written <= 0) return false;

            int w = dimsOut[0];
            int h = dimsOut[1];
            long ptsUs = ptsOut[0];

            if (w <= 0 || h <= 0) return false;

            lastWidth = w;
            lastHeight = h;
            lastPtsUs = ptsUs;

            // Convert I420 to BGR into the current fill slot (no GL here)
            int slot = fillSlot;
            convertI420ToRgb(slot, w, h);

            synchronized (handoffLock) {
                if (!pendingNew) {
                    pendingSlot = slot;
                    fillSlot = 1 - slot;
                    pendingWidth = w;
                    pendingHeight = h;
                    pendingPtsUs = ptsUs;
                    pendingNew = true;
                    handoffLock.notifyAll();
                }
            }
            return true;
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable t) {
            WebStreamerMod.LOGGER.debug("[Native] Read frame failed: {}", t.getMessage());
            return false;
        }
    }

    /**
     * Upload the most recently decoded frame to the given GL texture.
     * Called from the render thread only.
     *
     * @param textureId The OpenGL texture ID to upload to.
     * @return true if a new frame was uploaded.
     */
    public boolean uploadPending(int textureId) {
        synchronized (handoffLock) {
            if (!pendingNew || pendingSlot < 0) return false;

            ByteBuffer buf = rgbBuffers[pendingSlot];
            int w = pendingWidth;
            int h = pendingHeight;
            if (buf == null || w <= 0 || h <= 0) {
                pendingNew = false;
                handoffLock.notifyAll();
                return false;
            }

            buf.clear();
            buf.limit(w * h * 3);
            buf.position(0);

            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            if (w == uploadedTexW && h == uploadedTexH) {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                        GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, buf);
            } else {
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0,
                        GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, buf);
                uploadedTexW = w;
                uploadedTexH = h;
            }

            uploadedPtsUs = pendingPtsUs;
            pendingNew = false;
            handoffLock.notifyAll();
            return true;
        }
    }

    /**
     * PTS (microseconds) of the frame uploaded by the last successful
     * {@link #uploadPending(int)} call.
     */
    public long getUploadedPtsUs() {
        return uploadedPtsUs;
    }

    /**
     * Convert I420 (from {@link #yuvBuffer}) to BGR into the given slot's
     * pre-allocated buffers. Called from the decode worker thread only.
     * Uses pre-allocated buffers to avoid per-frame GC pressure.
     */
    private void convertI420ToRgb(int slot, int w, int h) {
        int ySize = w * h;
        int uvSize = (w / 2) * (h / 2);
        int yuvSize = ySize + uvSize * 2;
        int rgbSize = w * h * 3;

        // Grow buffers if needed (handles resolution changes)
        if (yuvArray == null || allocYuvSize < yuvSize) {
            allocYuvSize = yuvSize;
            yuvArray = new byte[allocYuvSize];
        }
        if (rgbArrays[slot] == null || allocRgbSizes[slot] < rgbSize) {
            allocRgbSizes[slot] = rgbSize;
            rgbArrays[slot] = new byte[rgbSize];
            rgbBuffers[slot] = ByteBuffer.allocateDirect(rgbSize).order(ByteOrder.nativeOrder());
        }
        byte[] rgbArray = rgbArrays[slot];

        // Bulk read I420 from native buffer
        yuvBuffer.position(0);
        yuvBuffer.get(yuvArray, 0, Math.min(yuvSize, yuvBuffer.capacity()));

        // Convert I420 to RGB — row-optimized with reduced bounds checks
        for (int row = 0; row < h; row++) {
            int yRowStart = row * w;
            int uvRowStart = (row / 2) * (w / 2);
            int rgbRowStart = row * w * 3;

            for (int col = 0; col < w; col++) {
                int y = yuvArray[yRowStart + col] & 0xFF;
                int u = yuvArray[ySize + uvRowStart + (col / 2)] & 0xFF;
                int v = yuvArray[ySize + uvSize + uvRowStart + (col / 2)] & 0xFF;

                int c = y - 16;
                int d = u - 128;
                int e = v - 128;

                int idx = rgbRowStart + col * 3;
                rgbArray[idx]     = (byte) clamp((298 * c + 409 * e + 128) >> 8);
                rgbArray[idx + 1] = (byte) clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
                rgbArray[idx + 2] = (byte) clamp((298 * c + 516 * d + 128) >> 8);
            }
        }

        // Bulk copy into the direct buffer the render thread will upload
        ByteBuffer buf = rgbBuffers[slot];
        buf.clear();
        buf.put(rgbArray, 0, rgbSize);
        buf.flip();
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
        synchronized (handoffLock) {
            yuvBuffer = null;
            yuvArray = null;
            rgbArrays[0] = null;
            rgbArrays[1] = null;
            rgbBuffers[0] = null;
            rgbBuffers[1] = null;
            allocYuvSize = 0;
            allocRgbSizes[0] = 0;
            allocRgbSizes[1] = 0;
            pendingNew = false;
            handoffLock.notifyAll();
        }
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
