package fr.theorozier.webstreamer.config;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Configuration for the WebStreamer display block rendering system.
 * This class holds configurable values for display rendering behavior.
 */
@Environment(EnvType.CLIENT)
public class WebStreamerClientConfig {

    /**
     * The maximum distance (in blocks) at which display blocks will render their content.
     * This overrides Minecraft's default ~64 block block entity render distance.
     * 
     * Default: 512 blocks
     * Range: 32 - 2048 blocks
     */
    public static int DISPLAY_BLOCK_RENDER_DISTANCE = 512;

    /**
     * Set the render distance for display blocks.
     * @param distance The distance in blocks (min 32, max 2048)
     */
    public static void setDisplayBlockRenderDistance(int distance) {
        DISPLAY_BLOCK_RENDER_DISTANCE = Math.max(32, Math.min(2048, distance));
    }

    /**
     * Get the current render distance for display blocks.
     * @return The distance in blocks
     */
    public static int getDisplayBlockRenderDistance() {
        return DISPLAY_BLOCK_RENDER_DISTANCE;
    }

}