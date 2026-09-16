package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.display.source.DisplaySource;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * <p>A display render data is an extension added to the {@link DisplayBlockEntity} class
 * only for client side, it's used to asynchronously request the URI from
 * {@link DisplaySource#getUri()} of the block entity (because that method is blocking).
 * This allows non-blocking request of the URL from the display renderer.
 * </p>
 */
@Environment(EnvType.CLIENT)
public class DisplayRenderData {

	/** Backoff between URI resolution retries after a failed/null resolution. A
	 *  transient failure (e.g. an async Twitch/Server lookup still in flight, or a
	 *  YouTube hiccup) must not be cached as a permanent "no URI", or the display
	 *  would stay blank until the block is broken and replaced. */
	private static final long RESOLVE_RETRY_INTERVAL_NS = 1L * 1_000_000_000L;

	private final DisplayBlockEntity display;
	
	private boolean sourceDirty;
	private Future<URI> futureUri;
	private URI uri;

	/** Monotonic time before which a resolution retry must not be submitted. */
	private long nextResolveAttempt = 0;
	
	public DisplayRenderData(DisplayBlockEntity display) {
		this.display = display;
		this.sourceDirty = true;
	}
	
	/**
	 * Mark the render data dirty. This will force the internal URL to be updated.
	 * This is called only from {@link DisplayBlockEntity}.
	 */
	public void markSourceDirty() {
		this.sourceDirty = true;
		this.nextResolveAttempt = 0;
	}
	
	/**
	 * Get the URL of a specific display. This method must be called from 
	 * {@link DisplayBlockEntityRenderer} only in the render thread.
	 * 
	 * @param executor The executor to execute async code on.
	 * @return Return a non-null URL when loaded.
	 */
	public URI getUri(ExecutorService executor) {
		
		if (this.sourceDirty && System.nanoTime() >= this.nextResolveAttempt) {
			this.uri = null;
			this.futureUri = executor.submit(() -> {
				DisplaySource src = this.display.getSource();
				URI result = src.getUri();
				return result;
			});
			this.sourceDirty = false;
		}
		
		if (this.futureUri != null && this.futureUri.isDone()) {
			boolean resolved = true;
			try {
				this.uri = this.futureUri.get();
				resolved = this.uri != null;
			} catch (InterruptedException | CancellationException e) {
				resolved = false;
			} catch (ExecutionException e) {
				resolved = false;
			} finally {
				this.futureUri = null;
			}
			if (!resolved) {
				// Never cache a permanent null/failure: retry after a short backoff
				// so transient network conditions don't leave the display blank.
				this.nextResolveAttempt = System.nanoTime() + RESOLVE_RETRY_INTERVAL_NS;
				this.sourceDirty = true;
			}
		}
		
		return this.uri;
		
	}

}
