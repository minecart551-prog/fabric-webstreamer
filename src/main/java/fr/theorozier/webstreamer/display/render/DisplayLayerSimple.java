package fr.theorozier.webstreamer.display.render;

import com.mojang.blaze3d.systems.RenderSystem;
import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3i;
import org.lwjgl.opengl.GL11;

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
	private final DisplayRenderLayer renderLayer;
	
	// Timing //
	/** Time in nanoseconds (monotonic) of the last use. */
	protected long lastUse = 0;

	/** Whether this layer is currently in range for ticking/rendering. Read
	 *  from background decode threads, so it must be volatile. */
	private volatile boolean inRange = true;

	/**
	 * Whether this layer is currently on screen (upload gate). Decided by the
	 * renderer via {@link #markSeen(int, boolean)} and decayed automatically by
	 * {@link #updateVisibility(int)} when the display stops being rendered, so
	 * frame uploads are skipped while nobody is looking at the display — while
	 * decode and audio keep running in the background.
	 */
	private boolean visible = false;

	/** Visibility last reported by the block-entity renderer, and the frame it was reported in. */
	private boolean seenVisible = false;
	private int seenFrame = Integer.MIN_VALUE;

	/**
	 * How many render frames may pass without a fresh {@code markSeen} before a
	 * layer is treated as invisible (i.e. its chunk left the view frustum).
	 */
	private static final int VISIBILITY_FRAME_TOLERANCE = 2;

	/** Brightness multiplier [0,1] applied via ColorModulator in the render layer setup. */
	private volatile float brightness = 1f;

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

	public void setBrightness(float brightness) {
		this.brightness = brightness;
	}

	public float getBrightness() {
		return this.brightness;
	}

	/**
	 * Report whether this layer was on screen during a render pass. Called from
	 * the block-entity renderer every frame it handles a display.
	 *
	 * @param frame The current render-frame counter.
	 * @param onScreen Whether the display is currently projected into the viewport.
	 */
	public void markSeen(int frame, boolean onScreen) {
		this.seenFrame = frame;
		this.seenVisible = onScreen;
	}

	/**
	 * Recompute the upload-visibility from the last renderer report. Called once
	 * per frame for every layer; a layer whose display stopped being rendered
	 * (e.g. its chunk left the view frustum) decays to invisible automatically.
	 */
	public void updateVisibility(int frame) {
		this.visible = this.seenVisible && (frame - this.seenFrame) <= VISIBILITY_FRAME_TOLERANCE;
	}

	/**
	 * Whether the renderer recently reported this layer as on screen. Guards the
	 * per-frame texture upload only — decode and audio keep running regardless.
	 */
	public boolean isVisible() {
		return this.visible;
	}

	/**
	 * Time in nanoseconds (monotonic) of the last use, used for LRU eviction.
	 */
	public long getLastUse() {
		return this.lastUse;
	}
	
	public DisplayLayerSimple(URI uri, DisplayLayerResources res) {
		this.uri = uri;
		this.res = res;
		this.tex = new DisplayTexture();
		this.renderLayer = new DisplayRenderLayer(this);
		this.lastUse = System.nanoTime();
	}

	@Override
	public boolean cleanup(long now) {
		// Forced cleanup (now == 0): release resources immediately, e.g. when the
		// client world is unloaded or the player disconnects.
		if (now == 0) {
			this.tex.clearGlId();
			return true;
		}
		// Remove layers that have not been rendered for over 60 seconds. This
		// prevents orphan layers (from broken/removed display blocks) from
		// accumulating and exhausting the layer cost budget.
		if (now - this.lastUse > 60L * 1_000_000_000L) {
			this.tex.clearGlId();
			return true;
		}
		return false;
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

	/**
	 * Internal function to prepare a log message for this specific layer.
	 * @param message The log message format to append to the header.
	 * @return The log message ready to format.
	 */
	protected String makeLog(String message) {
		return String.format("[%s:%08X] ", this.getClass().getSimpleName(), this.uri.hashCode()) + message;
	}

	private static class DisplayRenderLayer extends RenderLayer {
		private DisplayRenderLayer(DisplayLayerSimple layer) {
			super("display", VertexFormats.POSITION_TEXTURE, VertexFormat.DrawMode.QUADS,
					256, false, true,
					() -> {
						layer.lastUse = System.nanoTime();
						RenderPhase.POSITION_TEXTURE_PROGRAM.startDrawing();
						float b = layer.brightness;
						RenderSystem.setShaderColor(b, b, b, 1.0f);
						RenderSystem.enableDepthTest();
						RenderSystem.depthFunc(GL11.GL_LEQUAL);
						RenderSystem.setShaderTexture(0, layer.tex.getGlId());
					},
					() -> {
						RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
						RenderSystem.disableDepthTest();
					});
		}
	}
	
}
