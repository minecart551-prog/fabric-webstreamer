package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
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

	private static final long CREATION_RETRY_INTERVAL_NS = 100_000_000L;

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

	/**
	 * Forced teardown of every layer keyed to {@code display}, regardless of its
	 * URI. Used when a display block is removed while its URI is unresolved or
	 * blank, cases where a URI-keyed lookup would miss the layer and leave it in
	 * the map (consuming the cost budget) until the 60s idle cleanup. Also drops
	 * any pending creation backoff for this display so a fresh layer can start.
	 */
	public void cleanupLayersForDisplay(DisplayBlockEntity display) {
		Iterator<Map.Entry<K, DisplayLayerNode>> it = this.layers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<K, DisplayLayerNode> entry = it.next();
			if (this.belongsToDisplay(entry.getKey(), display)) {
				this.currentCost -= entry.getValue().cost();
				it.remove();
				this.pendingCleanups.add(entry.getValue());
			}
		}
		this.blockedLayerCreation.entrySet().removeIf(e -> e.getKey().display() == display);
	}

	/**
	 * Remove layers whose display block entity has been removed from the world,
	 * even if they haven't idled long enough for the regular {@link #cleanup(long)}
	 * pass. Guards against orphan layers (e.g. a block broken while its URI was
	 * unresolved) lingering and starving other displays of budget.
	 */
	protected void cleanupOrphanedDisplays() {
		Iterator<Map.Entry<K, DisplayLayerNode>> it = this.layers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<K, DisplayLayerNode> entry = it.next();
			if (this.isDisplayRemoved(entry.getKey())) {
				this.currentCost -= entry.getValue().cost();
				it.remove();
				this.pendingCleanups.add(entry.getValue());
			}
		}
	}

	/**
	 * Whether {@code key} is keyed to {@code display}. Defaults to false; subclasses
	 * whose keys reference a display override this.
	 */
	protected boolean belongsToDisplay(K key, DisplayBlockEntity display) {
		return false;
	}

	/**
	 * Whether the display that owns {@code key} has been removed from the world.
	 * Defaults to false; subclasses whose keys reference a display override this.
	 */
	protected boolean isDisplayRemoved(K key) {
		return false;
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
        // Release permanently failed video layers so a fresh attempt (with a
        // possibly re-resolved URI) can build. Otherwise such a layer is returned
        // every frame and the display stays blank — recovering only when the
        // block is broken and replaced — while the dead layer keeps holding
        // budget that could serve another display.
        if (layer instanceof DisplayLayerVideo videoLayer && videoLayer.isPermanentlyFailed()) {
            WebStreamerMod.LOGGER.info("Rebuilding permanently failed video layer for {}", key.uri());
            key.display().markRenderDataSourceDirty();
            this.removeLayer(layerKey, layer);
            this.pendingCleanups.add(layer);
            layer = null;
        }
        if (layer == null) {
            // A layer for the same display may already be decoding the requested
            // content under a stale key (e.g. a playlist advanced and the layer
            // re-started internally before the renderer noticed the URI change).
            // Re-key it instead of tearing it down and rebuilding a fresh layer,
            // which would reset the stream and drop its audio in the middle of a
            // transition. Subclasses opt in via {@link #tryRekeyLayer}.
            if (this.tryRekeyLayer(key)) {
                layer = this.layers.get(layerKey);
            }
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
     * Opportunity for subclasses to re-key an existing layer that is already
     * decoding the requested content (e.g. a playlist advanced and the layer
     * re-started internally before the renderer noticed the URI change), so the
     * entry can be found under {@code key} instead of being torn down and
     * rebuilt.
     *
     * @param key The key being requested by the renderer.
     * @return true if the layer cache was updated to use {@code key}.
     */
    protected boolean tryRekeyLayer(Key key) {
        return false;
    }

    /**
     * Move an existing layer entry from its current key to {@code newKey} when
     * {@code predicate} matches its current key/node. The layer cost is
     * unchanged (the same node is stored once), so {@code currentCost} is not
     * touched.
     *
     * @param newKey The key the matching layer should be stored under.
     * @param predicate Tests the existing {@code (key, node)} pairs.
     * @return true if an entry was re-keyed.
     */
    protected boolean rekeyLayer(Key newKey, java.util.function.BiPredicate<K, DisplayLayerNode> predicate) {
        Iterator<Map.Entry<K, DisplayLayerNode>> it = this.layers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<K, DisplayLayerNode> entry = it.next();
            if (entry.getKey().equals(this.getLayerKey(newKey))) {
                continue;
            }
            if (predicate.test(entry.getKey(), entry.getValue())) {
                it.remove();
                this.layers.put(this.getLayerKey(newKey), entry.getValue());
                return true;
            }
        }
        return false;
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