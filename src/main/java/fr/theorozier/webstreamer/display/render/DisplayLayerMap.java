package fr.theorozier.webstreamer.display.render;

// import fr.theorozier.webstreamer.WebStreamerMod; // Unused
// import fr.theorozier.webstreamer.display.render.DisplayLayer; // Unused
// import fr.theorozier.webstreamer.display.render.DisplayLayerNode; // Unused
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A special, yet abstract, display layer node that contains multiple layers, mapped
 * to a specific key type. Abstract methods must be implemented by subclasses to
 * provide the key and to instantiate a new layer if the key is existing.
 *
 * @param <K> The key to map children nodes to.
 */
public abstract class DisplayLayerMap<K> implements DisplayLayerNode {

    private final HashMap<K, DisplayLayerNode> layers = new HashMap<>();

    /** Incremental total cost of all cached layers (avoids O(n) resummation on hot paths). */
    private int currentCost = 0;

    /** Keys whose layer creation failed due to the cost budget, retried after a short backoff. */
    private final Map<DisplayLayerNode.Key, Long> blockedLayerCreation = new HashMap<>();

    private static final long CREATION_RETRY_INTERVAL_NS = 500_000_000L;

    /** Remaining budget of new layer constructions this render tick. Spreading the
     * construction over several frames avoids a per-frame hitch when the player
     * enters an area with many displays (each construction runs GL calls and
     * spawns a decode thread that allocates a large native buffer pool). */
    private int newLayersThisTick = 0;
    private static final int MAX_NEW_LAYERS_PER_TICK = 4;

    /** Forced cleanups (chunk unloads) that await processing at a bounded rate, to
     * spread the teardown burst (GL deletes, OpenAL free, buffer pool drops) over
     * several frames instead of one frame. The layer is removed from the map as
     * soon as it is queued, so it stops ticking immediately. */
    private final Deque<DisplayLayerNode> pendingCleanups = new ArrayDeque<>();
    private static final int MAX_FORCED_CLEANUPS_PER_TICK = 4;

    @Override
    public void tick() {
        this.newLayersThisTick = 0;

        // Process a bounded number of deferred forced cleanups per frame.
        int cleaned = 0;
        while (cleaned < MAX_FORCED_CLEANUPS_PER_TICK && !this.pendingCleanups.isEmpty()) {
            DisplayLayerNode node = this.pendingCleanups.poll();
            try {
                node.cleanup(0L);
            } catch (Exception ignored) {
            }
            cleaned++;
        }

        // Only tick in-range simple layers. Out-of-range layers should preserve
        // their paused playback state, but not advance or consume resources.
        int frame = DisplayLayerManager.getRenderFrame();
        this.layers.values().forEach(layer -> {
            if (layer instanceof DisplayLayerSimple simpleLayer) {
                simpleLayer.updateVisibility(frame);
                if (simpleLayer.isInRange()) {
                    simpleLayer.tick();
                }
            } else {
                layer.tick();
            }
        });
    }

    @Override
    public boolean cleanup(long now) {
        // Remove layers that return true from cleanup()
        Iterator<Map.Entry<K, DisplayLayerNode>> it = this.layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, DisplayLayerNode> entry = it.next();
            if (entry.getValue().cleanup(now)) {
                this.currentCost -= entry.getValue().cost();
                it.remove();
            }
        }
        // When the whole map is being torn down, flush any deferred forced
        // cleanups so their resources are released too.
        if (now == 0) {
            while (!this.pendingCleanups.isEmpty()) {
                try {
                    this.pendingCleanups.poll().cleanup(0L);
                } catch (Exception ignored) {
                }
            }
        }
        return this.layers.isEmpty();
    }

    public boolean cleanupKey(K key, long now) {
        DisplayLayerNode layer = this.layers.get(key);
        if (layer == null) {
            return false;
        }
        if (now == 0) {
            // Forced teardown (e.g. block removed / chunk unloaded). Stop the
            // layer from ticking right away by removing it from the map, then
            // release its resources at a bounded rate so a burst of unloads
            // doesn't hitch a single frame.
            this.currentCost -= layer.cost();
            this.layers.remove(key);
            this.pendingCleanups.add(layer);
            return true;
        }
        if (layer.cleanup(now)) {
            this.currentCost -= layer.cost();
            this.layers.remove(key);
            return true;
        }
        return false;
    }

    protected void cleanupLayersIf(Predicate<K> predicate, long now) {
        Iterator<Map.Entry<K, DisplayLayerNode>> it = this.layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, DisplayLayerNode> entry = it.next();
            if (predicate.test(entry.getKey()) && entry.getValue().cleanup(now)) {
                this.currentCost -= entry.getValue().cost();
                it.remove();
            }
        }
    }

    @Override
    public int cost() {
        return this.currentCost;
    }

    @Override
    public DisplayLayer getLayer(Key key) throws OutOfLayerException, UnknownFormatException {
        K layerKey = this.getLayerKey(key);
        DisplayLayerNode layer = this.layers.get(layerKey);
        // Remove and do not return destroyed layers
        if (layer instanceof DisplayLayerSimple simpleLayer && simpleLayer.isDestroyed()) {
            this.removeLayer(layerKey, layer);
            return null;
        }
        if (layer == null) {
            if (this.isLayerCreationBlocked(key)) {
                return null;
            }
            // Spread layer construction over frames when many displays enter view
            // at once. Deferral is not a backoff: retried naturally next frame.
            if (this.newLayersThisTick >= MAX_NEW_LAYERS_PER_TICK) {
                return null;
            }
            this.newLayersThisTick++;
            try {
                layer = this.getNewLayer(key);
                this.layers.put(layerKey, layer);
                this.currentCost += layer.cost();
            } catch (OutOfLayerException e) {
                this.blockLayerCreation(key);
                return null;
            }
        }
        // If the new layer is destroyed (shouldn't happen, but guard), do not return it
        if (layer instanceof DisplayLayerSimple simpleLayer2 && simpleLayer2.isDestroyed()) {
            this.removeLayer(layerKey, layer);
            return null;
        }
        return layer.getLayer(key);
    }

    private void removeLayer(K key, DisplayLayerNode layer) {
        this.layers.remove(key);
        this.currentCost -= layer.cost();
    }

    /**
     * Whether layered creation for this key was recently blocked by the cost
     * budget and should be skipped for now (retry-backoff to avoid hammering the
     * budget check and throwing an exception every frame).
     */
    private boolean isLayerCreationBlocked(Key key) {
        Long until = this.blockedLayerCreation.get(key);
        if (until == null) {
            return false;
        }
        if (until <= System.nanoTime()) {
            this.blockedLayerCreation.remove(key);
            return false;
        }
        return true;
    }

    private void blockLayerCreation(Key key) {
        this.blockedLayerCreation.put(key, System.nanoTime() + CREATION_RETRY_INTERVAL_NS);
    }

    /**
     * Evict the least-recently-used simple layer to free cost budget, provided it
     * has been idle for at least {@code minAgeNanos}. This lets more displays
     * play than the theoretical cost minimum instead of failing hard. Returns
     * false if no idle layer was available to evict.
     */
    protected boolean evictLeastRecentlyUsed(long minAgeNanos) {
        long now = System.nanoTime();
        DisplayLayerSimple oldest = null;
        for (DisplayLayerNode node : this.layers.values()) {
            if (node instanceof DisplayLayerSimple s && !s.isDestroyed()) {
                if (oldest == null || s.getLastUse() < oldest.getLastUse()) {
                    oldest = s;
                }
            }
        }
        if (oldest == null || now - oldest.getLastUse() < minAgeNanos) {
            return false;
        }
        Iterator<Map.Entry<K, DisplayLayerNode>> it = this.layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, DisplayLayerNode> entry = it.next();
            if (entry.getValue() == oldest) {
                it.remove();
                this.currentCost -= oldest.cost();
                oldest.cleanup(0L);
                return true;
            }
        }
        return false;
    }

    @NotNull
    protected abstract K getLayerKey(Key key);

    @NotNull
    protected abstract DisplayLayerNode getNewLayer(Key key) throws OutOfLayerException, UnknownFormatException;

}