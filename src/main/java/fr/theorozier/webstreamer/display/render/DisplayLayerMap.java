package fr.theorozier.webstreamer.display.render;

// import fr.theorozier.webstreamer.WebStreamerMod; // Unused
// import fr.theorozier.webstreamer.display.render.DisplayLayer; // Unused
// import fr.theorozier.webstreamer.display.render.DisplayLayerNode; // Unused
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
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

    @Override
    public void tick() {
        // Only tick in-range simple layers. Out-of-range layers should preserve
        // their paused playback state, but not advance or consume resources.
        this.layers.values().forEach(layer -> {
            if (layer instanceof DisplayLayerSimple simpleLayer) {
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
        this.layers.entrySet().removeIf(entry -> entry.getValue().cleanup(now));
        return this.layers.isEmpty();
    }

    public boolean cleanupKey(K key, long now) {
        DisplayLayerNode layer = this.layers.get(key);
        if (layer != null && layer.cleanup(now)) {
            this.layers.remove(key);
            return true;
        }
        return false;
    }

    protected void cleanupLayersIf(java.util.function.Predicate<K> predicate, long now) {
        this.layers.entrySet().removeIf(entry -> predicate.test(entry.getKey()) && entry.getValue().cleanup(now));
    }

    @Override
    public int cost() {
        return this.layers.values().stream().mapToInt(DisplayLayerNode::cost).sum();
    }

    @Override
    public DisplayLayer getLayer(Key key) throws OutOfLayerException, UnknownFormatException {
        K layerKey = this.getLayerKey(key);
        DisplayLayerNode layer = this.layers.get(layerKey);
        // Remove and do not return destroyed layers
        if (layer instanceof DisplayLayerSimple simpleLayer && simpleLayer.isDestroyed()) {
            this.layers.remove(layerKey);
            return null;
        }
        if (layer == null) {
            layer = this.getNewLayer(key);
            this.layers.put(layerKey, layer);
        }
        // If the new layer is destroyed (shouldn't happen, but guard), do not return it
        if (layer instanceof DisplayLayerSimple simpleLayer2 && simpleLayer2.isDestroyed()) {
            this.layers.remove(layerKey);
            return null;
        }
        return layer.getLayer(key);
    }

    @NotNull
    protected abstract K getLayerKey(Key key);

    @NotNull
    protected abstract DisplayLayerNode getNewLayer(Key key) throws OutOfLayerException, UnknownFormatException;

}
