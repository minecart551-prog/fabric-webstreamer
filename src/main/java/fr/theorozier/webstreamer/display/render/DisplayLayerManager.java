package fr.theorozier.webstreamer.display.render;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jetbrains.annotations.NotNull;

import java.net.URI;

/**
 * This class is responsible for caching and keeping the number of layer to the minimum.
 */
@Environment(EnvType.CLIENT)
public class DisplayLayerManager extends DisplayLayerMap<DisplayLayerNode.Key> {

    /** Max cost for concurrent layers. */
    private static final int MAX_LAYERS_COST = 30 * 30;  // Approx 30 HLS layers.

    /** Interval of cleanups for unused display layers. */
    private static final long CLEANUP_INTERVAL = 1L * 1000000000L;

    /** Common pools for shared and reusable heavy buffers. */
    private final DisplayLayerResources res = new DisplayLayerResources();

    /** Time in nanoseconds (monotonic) of the last cleanup for unused layers. */
    private long lastCleanup = 0;

    /** Rate-limit the cost warning to avoid log spam. */
    private long lastCostWarning = 0;
    private static final long COST_WARNING_INTERVAL = 30L * 1_000_000_000L;

    /** Minimum idle time before a layer may be evicted by LRU to free budget. */
    private static final long EVICT_MIN_AGE_NS = 2L * 1_000_000_000L;

    /** Monotonic counter of render frames, bumped once per frame in {@link #tick()}. */
    private static int renderFrame = 0;

    public static int getRenderFrame() {
        return renderFrame;
    }

    public DisplayLayerResources getResources() {
        return this.res;
    }

    @Override
    public void tick() {

        RenderSystem.assertOnRenderThread();
        renderFrame++;
        super.tick();

        long now = System.nanoTime();
        if (now - this.lastCleanup >= CLEANUP_INTERVAL) {
            super.cleanup(now);
            this.cleanupOrphanedDisplays();
            this.lastCleanup = now;
        }

    }

    @Override
    public boolean cleanup(long now) {
        RenderSystem.assertOnRenderThread();
        return super.cleanup(now);
    }

    @Override
    protected boolean belongsToDisplay(Key key, DisplayBlockEntity display) {
        return key.display() == display;
    }

    @Override
    protected boolean isDisplayRemoved(Key key) {
        DisplayBlockEntity d = key.display();
        return d != null && d.isRemoved();
    }

    @Override
    @NotNull
    protected DisplayLayerNode.Key getLayerKey(Key key) {
        return key;
    }

    @Override
    protected boolean tryRekeyLayer(Key key) {
        if (key.uri() == null) {
            return false;
        }
        // When a playlist advances, the display source's URI changes to the next
        // video. The layer itself may already have re-started on that exact URI
        // (tryRestartNextVideo) before the renderer resolves it; in that case the
        // running layer must be kept and re-keyed, not torn down and rebuilt —
        // a rebuild would reopen FFmpeg from scratch, dropping the buffered
        // audio and leaving the fresh layer unpositioned until it is rendered
        // again (inaudible while the player looks away).
        return this.rekeyLayer(key, (existingKey, node) ->
                existingKey.display() == key.display()
                && !existingKey.uri().equals(key.uri())
                && node instanceof DisplayLayerVideo video
                && key.uri().equals(video.getCurrentUri())
                && !video.isDestroyed());
    }

    @Override
    @NotNull
    protected DisplayLayerNode getNewLayer(Key key) throws OutOfLayerException, UnknownFormatException {

        this.cleanupLayersIf(existingKey -> existingKey.display() == key.display() && !existingKey.uri().equals(key.uri()), 0);

        // Reject URIs without a scheme (e.g. bare local paths like "/x.gif" that
        // slipped through source resolution) so they can't create a layer that
        // fails obscurely later on.
        if (key.uri() == null || key.uri().getScheme() == null) {
            throw new UnknownFormatException();
        }

        // Try to free budget by evicting an idle layer before giving up, so
        // more displays than the theoretical cost minimum can play at once.
        if (this.cost() >= MAX_LAYERS_COST) {
            this.evictLeastRecentlyUsed(EVICT_MIN_AGE_NS);
        }

        if (this.cost() >= MAX_LAYERS_COST) {
            long now = System.nanoTime();
            if (now - this.lastCostWarning >= COST_WARNING_INTERVAL) {
                this.lastCostWarning = now;
                WebStreamerMod.LOGGER.warn("Layer cost limit reached ({}/{}), cannot create new layer for {}",
                        this.cost(), MAX_LAYERS_COST, key.uri());
            }
            throw new OutOfLayerException();
        }

        String path = key.uri().getPath();
        if (path != null) {
            if (path.endsWith(".m3u8")) {
                FFmpegLibrary.initializeFrameGrabber();
                return new DisplayLayerHls(key.uri(), this.res);
            } else if (path.endsWith(".gif")) {
                FFmpegLibrary.initializeFrameGrabber();
                boolean randomStart = false;
                if (key.display() != null) {
                    if (key.display().getSource() instanceof fr.theorozier.webstreamer.display.source.RawDisplaySource rawSrc) {
                        randomStart = rawSrc.isRandomStartFrame();
                    } else if (key.display().getSource() instanceof fr.theorozier.webstreamer.display.source.ServerDisplaySource srvSrc) {
                        randomStart = srvSrc.isRandomStartFrame();
                    }
                }
                return new DisplayLayerGif(key.uri(), this.res, randomStart);
            } else if (path.endsWith(".jpeg") || path.endsWith(".jpg") || path.endsWith(".bmp") || path.endsWith(".png")) {
                return new DisplayLayerImage(key.uri(), this.res);
            } else if (path.endsWith(".svg")) {
                return new DisplayLayerSVGMap(this.res);
            }
        }

        // Fallback for plain HTTP/HTTPS URLs with no recognised extension
        // (e.g. YouTube progressive MP4 stream URLs from YoutubeClient).
        // These are direct video files, not HLS playlists, so we use
        // DisplayLayerVideo which feeds the URL straight into FrameGrabber.
        String scheme = key.uri().getScheme();
        if ("http".equals(scheme) || "https".equals(scheme)) {
            FFmpegLibrary.initializeFrameGrabber();
            return new DisplayLayerVideo(key, this.res);
        }

        throw new UnknownFormatException();

    }

}