package fr.theorozier.webstreamer.jni;

import fr.theorozier.webstreamer.WebStreamerMod;
import com.mojang.blaze3d.platform.GlStateManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;

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

    // Raw frame buffer from the native decoder (direct for JNI) — decode
    // worker thread only. The native side outputs packed BGR via swscale
    // (w*h*3 bytes), the same byte order as JavaCV frames.
    private ByteBuffer frameBuffer;
    private static final int MAX_FRAME_SIZE = 3840 * 2160 * 3; // 4K packed BGR

    // PCM audio drain buffer (direct for JNI) — decode worker thread only.
    // Chunk size matches DisplayLayerVideo.tempAudioByteBuf (8192 bytes).
    private ByteBuffer audioBuffer;
    private static final int MAX_AUDIO_CHUNK = 8192;

    // Pre-allocated copy buffers (reused across frames to avoid GC pressure)
    private static final int MAX_SLOTS = 24;

    // Frame handoff between the decode worker (fills slots) and the render
    // thread (uploads due slots to GL). A small queue of decoded frames lets
    // the worker run AHEAD of playback — mirroring the JavaCV path's 24-frame
    // buffer pool — which gives the audio sink a cushion of already-decoded
    // PCM. With the previous single-slot handoff, audio chunks arrived at the
    // edge of realtime and the OpenAL source underran on any jitter
    // (choppy/glitchy audio). The worker blocks only when the queue is full,
    // which still backpressures decoding while the layer is not rendered.
    private final Object handoffLock = new Object();
    private final ArrayDeque<Integer> readySlots = new ArrayDeque<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int slotsTotal = 0;
    private final byte[][] rgbArrays = new byte[MAX_SLOTS][];
    private final ByteBuffer[] rgbBuffers = new ByteBuffer[MAX_SLOTS];
    private final int[] allocRgbSizes = new int[MAX_SLOTS];
    private final int[] slotWidths = new int[MAX_SLOTS];
    private final int[] slotHeights = new int[MAX_SLOTS];
    private final long[] slotPtsUs = new long[MAX_SLOTS];
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
            frameBuffer = ByteBuffer.allocateDirect(MAX_FRAME_SIZE).order(ByteOrder.nativeOrder());
            audioBuffer = ByteBuffer.allocateDirect(MAX_AUDIO_CHUNK).order(ByteOrder.nativeOrder());
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
     * Decode the next frame (native outputs packed BGR). Called from the
     * background decode thread only. Blocks while the decode-ahead queue is
     * full (the render thread has not consumed enough frames yet).
     *
     * @return true if a frame was decoded and published for upload.
     */
    public boolean decodeToPending() {
        if (handle <= 0 || !started) return false;

        int slot = -1;
        try {
            // Wait for a free slot so the worker can run a bounded amount of
            // frames ahead of the render thread (the queue depth adapts to
            // the frame size, see queueDepth()).
            synchronized (handoffLock) {
                while (true) {
                    if (handle <= 0 || !started) return false;
                    Integer free = freeSlots.pollFirst();
                    if (free != null) {
                        slot = free;
                        break;
                    }
                    if (slotsTotal < queueDepth()) {
                        slot = slotsTotal++;
                        break;
                    }
                    handoffLock.wait(50);
                }
            }

            frameBuffer.clear();
            long written = NativeLibrary.nReadFrameI420(handle, frameBuffer, MAX_FRAME_SIZE, dimsOut, ptsOut);

            if (written <= 0) {
                returnSlot(slot);
                return false;
            }

            int w = dimsOut[0];
            int h = dimsOut[1];
            long ptsUs = ptsOut[0];

            if (w <= 0 || h <= 0) {
                returnSlot(slot);
                return false;
            }

            lastWidth = w;
            lastHeight = h;
            lastPtsUs = ptsUs;

            // Copy the native BGR frame into the acquired slot (no GL here)
            copyFrameToSlot(slot, w, h);

            synchronized (handoffLock) {
                slotWidths[slot] = w;
                slotHeights[slot] = h;
                slotPtsUs[slot] = ptsUs;
                readySlots.addLast(slot);
                handoffLock.notifyAll();
            }
            return true;
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
            returnSlot(slot);
            return false;
        } catch (Throwable t) {
            WebStreamerMod.LOGGER.debug("[Native] Read frame failed: {}", t.getMessage());
            returnSlot(slot);
            return false;
        }
    }

    /** Give a slot back to the free pool (decode failed after acquiring it). */
    private void returnSlot(int slot) {
        if (slot < 0) {
            return;
        }
        synchronized (handoffLock) {
            freeSlots.addLast(slot);
            handoffLock.notifyAll();
        }
    }

    /**
     * Decode-ahead queue depth, adapted to the frame size so a layer's slot
     * memory stays around 48 MB at any resolution (24 slots at 360p,
     * 7 at 1080p, 4 at 4K).
     */
    private int queueDepth() {
        long w = Math.max(lastWidth, 1);
        long h = Math.max(lastHeight, 1);
        long frameBytes = w * h * 3;
        return (int) Math.max(4, Math.min(MAX_SLOTS, 48L * 1024 * 1024 / frameBytes));
    }

    /**
     * Upload decoded frames that are due at {@code duePtsUs} to the given GL
     * texture. Called from the render thread only. Frames that are due but
     * superseded by a newer due frame are dropped (catch-up, matching the
     * JavaCV path); frames not yet due stay queued for a later tick.
     *
     * @param textureId The OpenGL texture ID to upload to.
     * @param duePtsUs Only frames with pts &lt;= this are consumed.
     * @param uploadVisible Whether to actually upload to GL (false consumes
     *                     and drops due frames without touching GL).
     * @return true if a new frame was uploaded.
     */
    public boolean uploadPending(int textureId, long duePtsUs, boolean uploadVisible) {
        synchronized (handoffLock) {
            // Pick the newest due frame; give older skipped slots back.
            int slot = -1;
            Integer front;
            while ((front = readySlots.peekFirst()) != null && slotPtsUs[front] <= duePtsUs) {
                int skipped = readySlots.pollFirst();
                if (slot >= 0) {
                    freeSlots.addLast(slot);
                }
                slot = skipped;
            }
            if (slot < 0) {
                return false;
            }

            ByteBuffer buf = rgbBuffers[slot];
            int w = slotWidths[slot];
            int h = slotHeights[slot];
            boolean uploaded = false;

            if (uploadVisible && buf != null && w > 0 && h > 0) {
                buf.clear();
                buf.limit(w * h * 3);
                buf.position(0);

                // Bind through GlStateManager so vanilla's bind-state cache stays
                // in sync (a raw glBindTexture desyncs it and corrupts subsequent
                // vanilla draws), and pin the unpack state: DisplayTexture.uploadRaw
                // (image/JavaCV paths) leaves GL_UNPACK_ROW_LENGTH set to the last
                // stride, which would make these tightly packed rows upload as
                // garbage or fail with GL_INVALID_OPERATION.
                GlStateManager._bindTexture(textureId);
                GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
                GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                // The native side outputs packed BGR (same byte order as JavaCV
                // frames), matching DisplayTexture.uploadRaw's GL_BGR convention.
                if (w == uploadedTexW && h == uploadedTexH) {
                    GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                            GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, buf);
                } else {
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB8, w, h, 0,
                            GL12.GL_BGR, GL11.GL_UNSIGNED_BYTE, buf);
                    uploadedTexW = w;
                    uploadedTexH = h;
                }

                uploadedPtsUs = slotPtsUs[slot];
                uploaded = true;
            }

            freeSlots.addLast(slot);
            handoffLock.notifyAll();
            return uploaded;
        }
    }

    /**
     * PTS (microseconds) of the frame uploaded by the last successful
     * {@link #uploadPending(int, long, boolean)} call.
     */
    public long getUploadedPtsUs() {
        return uploadedPtsUs;
    }

    /**
     * Size of the texture storage written by the last successful
     * {@link #uploadPending(int, long, boolean)}, so the caller can mirror it
     * into {@code DisplayTexture.markReady}.
     */
    public int getUploadedWidth() {
        return uploadedTexW;
    }

    public int getUploadedHeight() {
        return uploadedTexH;
    }

    /**
     * Copy the packed BGR frame (native output, swscale) from {@link #frameBuffer}
     * into the given slot's pre-allocated buffers. Called from the decode worker
     * thread only. Uses pre-allocated buffers to avoid per-frame GC pressure.
     */
    private void copyFrameToSlot(int slot, int w, int h) {
        int rgbSize = w * h * 3;

        // Grow buffers if needed (handles resolution changes)
        if (rgbArrays[slot] == null || allocRgbSizes[slot] < rgbSize) {
            allocRgbSizes[slot] = rgbSize;
            rgbArrays[slot] = new byte[rgbSize];
            rgbBuffers[slot] = ByteBuffer.allocateDirect(rgbSize).order(ByteOrder.nativeOrder());
        }
        byte[] rgbArray = rgbArrays[slot];

        // Bulk read packed BGR from the native buffer
        frameBuffer.position(0);
        frameBuffer.get(rgbArray, 0, Math.min(rgbSize, frameBuffer.capacity()));

        // Bulk copy into the direct buffer the render thread will upload
        ByteBuffer buf = rgbBuffers[slot];
        buf.clear();
        buf.put(rgbArray, 0, rgbSize);
        buf.flip();
    }

    /**
     * Drain one chunk of native-decoded mono S16 PCM. Called from the decode
     * worker thread only. Returns 0 when no audio is pending (or the native
     * library predates audio support, ABI &lt; 7).
     *
     * @return the number of samples written into {@code dst}; {@code info[0]}
     *         receives the pts (µs) of the first sample and {@code info[1]}
     *         the sample rate.
     */
    public int readAudio(short[] dst, long[] info) {
        long h = this.handle;
        ByteBuffer buf = this.audioBuffer;
        if (h <= 0 || buf == null || !this.started) {
            return 0;
        }
        try {
            buf.clear();
            long bytes = NativeLibrary.nReadAudioPcm(h, buf, MAX_AUDIO_CHUNK, info);
            if (bytes < 2) {
                return 0;
            }
            int samples = (int) (bytes / 2);
            if (samples > dst.length) {
                samples = dst.length;
            }
            buf.position(0);
            buf.asShortBuffer().get(dst, 0, samples);
            return samples;
        } catch (Throwable t) {
            // Older native library without nReadAudioPcm (ABI < 7) lands
            // here as UnsatisfiedLinkError — degrade to silent native video.
            return 0;
        }
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
            frameBuffer = null;
            audioBuffer = null;
            for (int i = 0; i < MAX_SLOTS; i++) {
                rgbArrays[i] = null;
                rgbBuffers[i] = null;
                allocRgbSizes[i] = 0;
            }
            slotsTotal = 0;
            readySlots.clear();
            freeSlots.clear();
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
