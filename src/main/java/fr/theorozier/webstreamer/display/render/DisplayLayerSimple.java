package fr.theorozier.webstreamer.display.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3i;

import java.net.URI;

/**
 * Concrete implementation of a display layer that actually provides a render layer
 * and method to push audio sources.
 */
@Environment(EnvType.CLIENT)
public abstract class DisplayLayerSimple implements DisplayLayerNode, DisplayLayer {
	
	// Common //
	protected URI uri;
	protected final DisplayLayerResources res;
	protected final DisplayTexture tex;
	private final Identifier textureId;
	private final RenderLayer renderLayer;
	
	// Timing //
	/** Time in nanoseconds (monotonic) of the last use. */
	protected long lastUse = 0;

	/** Whether this layer is currently in range for ticking/rendering. */
	private boolean inRange = true;

	// Allow subclasses and this class to check destroyed state
	protected boolean destroyed = false;


	public void setInRange(boolean inRange) {
		this.inRange = inRange;
	}

	public boolean isDestroyed() {
		return destroyed;
	}

	public boolean isInRange() {
		return this.inRange;
	}
	
	public DisplayLayerSimple(URI uri, DisplayLayerResources res) {
		this.uri = uri;
		this.res = res;
		this.tex = new DisplayTexture();
		this.textureId = new Identifier("webstreamer", Integer.toHexString(System.identityHashCode(this)));
		MinecraftClient.getInstance().getTextureManager().registerTexture(this.textureId, this.tex);
		this.renderLayer = RenderLayer.getEntityCutoutNoCull(this.textureId);
		this.lastUse = System.nanoTime();
	}

	@Override
	public boolean cleanup(long now) {
		if (now == 0) {
			MinecraftClient client = MinecraftClient.getInstance();
			if (client != null) {
				client.getTextureManager().destroyTexture(this.textureId);
			}
			this.tex.clearGlId();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public abstract int cost();

	@Override
	public DisplayLayer getLayer(Key key) {
		return this;
	}

	@Override
	public void pushAudioSource(Vec3i pos, float dist, float audioDistance, float audioVolume) { }

	@Override
	public boolean isLost() {
		return false;
	}

	@Override
	public RenderLayer getRenderLayer() {
		return this.renderLayer;
	}

	public boolean isReady() {
		return this.tex.isReady();
	}

	protected String makeLog(String message) {
		return String.format("[%s:%08X] ", this.getClass().getSimpleName(), this.uri.hashCode()) + message;
	}
	
}
