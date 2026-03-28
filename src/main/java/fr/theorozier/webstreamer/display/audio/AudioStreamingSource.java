package fr.theorozier.webstreamer.display.audio;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3i;

import java.util.ArrayDeque;
import java.util.Objects;

import static org.lwjgl.openal.AL11.*;

@Environment(EnvType.CLIENT)
public class AudioStreamingSource {

	private final String name;
	private int sourceId;
	
	/** Real system nano timestamp when play started. */
	private long playTimestamp;
	private long playBufferTimestamp;
	private long lastRequestedTimestamp = -1;
	
	private ArrayDeque<AudioStreamingBuffer> queue = new ArrayDeque<>();
	private long lastBufferTimestamp;

	private boolean timestampOffsetInitialized = false;
	private long timestampOffset = 0;
	private static final long TIMESTAMP_REBASE_THRESHOLD = 1000000L;
	
	public AudioStreamingSource() {
		this("unknown");
	}

	public AudioStreamingSource(String name) {
		this.name = name;
		this.sourceId = alGenSources();
		alSourcei(this.sourceId, AL_LOOPING, AL_FALSE);
		alSourcei(this.sourceId, AL_SOURCE_RELATIVE, AL_FALSE);
		this.setVolume(1f);
		this.setAttenuation(50f);
	}
	
	public int getSourceId() {
		return this.sourceId;
	}
	
	public boolean isValid() {
		return this.sourceId != 0;
	}
	
	public void checkValid() {
		if (!this.isValid()) {
			throw new IllegalArgumentException("this audio source has already been freed");
		}
	}
	
	public void free() {
		this.checkValid();
		this.queue.forEach(AudioStreamingBuffer::free);
		this.queue.clear();
		this.timestampOffsetInitialized = false;
		this.timestampOffset = 0;
		this.lastRequestedTimestamp = -1;
		this.lastBufferTimestamp = 0;
		this.playTimestamp = 0;
		this.playBufferTimestamp = 0;
		this.queue = null;
		alSourceStop(this.sourceId);
		alDeleteSources(this.sourceId);
		this.sourceId = 0;
	}

	/** Manually stop the source, when doing that all queued buffers are freed and cleared. */
	public void stop() {
		this.checkValid();
		alSourceStop(this.sourceId);
		this.queue.forEach(AudioStreamingBuffer::free);
		this.queue.clear();
		this.timestampOffsetInitialized = false;
		this.timestampOffset = 0;
		this.lastRequestedTimestamp = -1;
		this.lastBufferTimestamp = 0;
		this.playTimestamp = 0;
		this.playBufferTimestamp = 0;
	}
	
	public void setPosition(Vec3i pos) {
		this.checkValid();
		alSourcefv(this.sourceId, AL_POSITION, new float[] {(float) pos.getX() + 0.5f, (float) pos.getY() + 0.5f, (float) pos.getZ() + 0.5f});
	}
	
	public void setVolume(float volume) {
		this.checkValid();
		alSourcef(this.sourceId, AL_GAIN, volume);
	}
	
	public void setAttenuation(float attenuation) {
		this.checkValid();
		alSourcei(this.sourceId, AL_DISTANCE_MODEL, AL_LINEAR_DISTANCE);
		alSourcef(this.sourceId, AL_MAX_DISTANCE, attenuation);
		alSourcef(this.sourceId, AL_ROLLOFF_FACTOR, 1.0F);
		alSourcef(this.sourceId, AL_REFERENCE_DISTANCE, 0.0F);
	}
	
	public boolean isPlaying() {
		this.checkValid();
		return alGetSourcei(this.sourceId, AL_SOURCE_STATE) == AL_PLAYING;
	}
	
	public void playFrom(long timestamp) {
		
		this.checkValid();
		this.lastRequestedTimestamp = timestamp;
	
		if (timestamp < 0) {
			WebStreamerMod.LOGGER.warn("[{}] Audio playFrom received invalid negative timestamp={}, clamping to 0", this.name, timestamp);
			timestamp = 0;
		}

		boolean playing = this.isPlaying();

		if (!this.queue.isEmpty()) {
			long firstBufferTs = this.queue.peekFirst().timestamp;
			long candidateOffset = firstBufferTs - timestamp;
			long shift = candidateOffset;
			if (!this.timestampOffsetInitialized || (!playing && Math.abs(shift) > TIMESTAMP_REBASE_THRESHOLD)) {
				this.timestampOffset += shift;
				this.timestampOffsetInitialized = true;
				for (AudioStreamingBuffer buffer : this.queue) {
					buffer.shiftTimestamp(shift);
				}
				this.lastBufferTimestamp -= shift;
				WebStreamerMod.LOGGER.info("[{}] Audio timeline normalized by shift={} candidateOffset={} firstBufferTs={} newFirstTs={}", this.name, shift, candidateOffset, firstBufferTs, this.queue.peekFirst().timestamp);
			}
		}
		int queued = alGetSourcei(this.sourceId, AL_BUFFERS_QUEUED);
		int processed = alGetSourcei(this.sourceId, AL_BUFFERS_PROCESSED);
		int state = alGetSourcei(this.sourceId, AL_SOURCE_STATE);
		long lead = this.queue.isEmpty() ? 0 : this.queue.peekFirst().timestamp - timestamp;
		WebStreamerMod.LOGGER.info("[{}] Audio playFrom called timestamp={} playing={} state={} queueSize={} queued={} processed={} lead={}us", this.name, timestamp, playing, getStateName(state), this.queue.size(), queued, processed, lead);
	
		if (!playing) {
			this.removeAndFreeBuffersBefore(timestamp);
		}
		
		this.unqueueAndFree();
		
		if (this.queue.isEmpty()) {
			WebStreamerMod.LOGGER.debug("[{}] Audio source has no queued buffers at timestamp={} (playing={})", this.name, timestamp, playing);
			// If no buffer is queued, just don't play.
			return;
		}
		
		AudioStreamingBuffer firstBuffer = this.queue.peekFirst();
		
		if (!playing && firstBuffer.timestamp > timestamp) {
			WebStreamerMod.LOGGER.info("[{}] Audio source waiting for first buffer timestamp={} currentTimestamp={} queueSize={} state={}", this.name, firstBuffer.timestamp, timestamp, this.queue.size(), getStateName(state));
			// If we are not playing, we should only start playing when the first buffer is reached.
			return;
		}
		
		long firstBufferTimestamp = -1L;
		int buffersCount = this.queue.size();
		
		int[] buffers = new int[buffersCount];
		for (int i = 0; i < buffersCount; ++i) {
			AudioStreamingBuffer buffer = this.queue.removeFirst();
			buffers[i] = buffer.getBufferId();
			if (firstBufferTimestamp == -1L) {
				firstBufferTimestamp = buffer.timestamp;
			}
		}
		
		alSourceQueueBuffers(this.sourceId, buffers);
		int queuedAfter = alGetSourcei(this.sourceId, AL_BUFFERS_QUEUED);
		WebStreamerMod.LOGGER.info("[{}] Queued {} buffers to source, queuedAfter={} firstBufferTs={} state={}", this.name, buffersCount, queuedAfter, firstBufferTimestamp, getStateName(state));
		
		if (!playing) {
			alSourcePlay(this.sourceId);
			this.playTimestamp = System.nanoTime();
			this.playBufferTimestamp = firstBufferTimestamp;
			int stateAfter = alGetSourcei(this.sourceId, AL_SOURCE_STATE);
			WebStreamerMod.LOGGER.info("[{}] Started OpenAL source, playBufferTimestamp={} stateAfter={}", this.name, this.playBufferTimestamp, getStateName(stateAfter));
		}
		
	}
	
	/**
	 * Queue the given streaming buffer on this source.
	 * @param buffer A non-null streaming buffer.
	 */
	public void queueBuffer(AudioStreamingBuffer buffer) {
		
		Objects.requireNonNull(buffer, "given buffer should not be null");
		
		this.checkValid();
		
		if (buffer.timestamp <= this.lastBufferTimestamp) {
			WebStreamerMod.LOGGER.debug("Skipping out-of-order audio buffer ts={} lastTs={}", buffer.timestamp, this.lastBufferTimestamp);
			// Silently skip out-of-order buffers (can happen with streaming)
			return;
		}
		
		boolean shifted = false;
		if (this.queue.isEmpty()) {
			if (this.timestampOffsetInitialized) {
				this.timestampOffsetInitialized = false;
				this.timestampOffset = 0;
				WebStreamerMod.LOGGER.info("[{}] Audio queue emptied, resetting timestamp offset before new refill", this.name);
			}
			if (this.lastRequestedTimestamp >= 0) {
				long delta = buffer.timestamp - this.lastRequestedTimestamp;
				if (delta != 0L) {
					this.timestampOffset = delta;
					this.timestampOffsetInitialized = true;
					buffer.shiftTimestamp(this.timestampOffset);
					shifted = true;
					WebStreamerMod.LOGGER.info("[{}] Audio timeline normalized on first buffer by offset={} delta={} firstBufferTs={} requestedTs={}", this.name, this.timestampOffset, delta, buffer.timestamp, this.lastRequestedTimestamp);
				}
			}
		}

		if (this.timestampOffsetInitialized && !shifted) {
			buffer.shiftTimestamp(this.timestampOffset);
		}
		this.queue.addLast(buffer);
		this.lastBufferTimestamp = buffer.timestamp;
		int queued = alGetSourcei(this.sourceId, AL_BUFFERS_QUEUED);
		int processed = alGetSourcei(this.sourceId, AL_BUFFERS_PROCESSED);
		WebStreamerMod.LOGGER.debug("[{}] Queued audio buffer ts={} queueSize={} sourceQueued={} sourceProcessed={}", this.name, buffer.timestamp, this.queue.size(), queued, processed);
		
	}
	
	/**
	 * Unqueue processed buffers and free them.
	 */
	public void unqueueAndFree() {
		int numProcessed = alGetSourcei(this.sourceId, AL_BUFFERS_PROCESSED);
		if (numProcessed > 0) {
			WebStreamerMod.LOGGER.debug("Unqueuing {} processed buffers", numProcessed);
			int[] buffers = new int[numProcessed];
			alSourceUnqueueBuffers(this.sourceId, buffers);
			if (!checkErrors("audio unqueue buffers")) {
				alDeleteBuffers(buffers);
				checkErrors("audio delete buffers");
			}
		}
	}
	
	private void removeAndFreeBuffersBefore(long timestamp) {
		// System.out.println("removeAndFreeBuffersBefore(" + timestamp + ")");
		while (!this.queue.isEmpty()) {
			AudioStreamingBuffer buffer = this.queue.peekFirst();
			if (buffer.timestamp + buffer.duration > timestamp) {
				break;
			}
			// System.out.println("=> removing buffer at: " + buffer.timestamp);
			this.queue.removeFirst().free();
		}
	}
	
	/**
	 * Check for OpenAL errors.
	 * @param sectionName The section name to use when logging the error.
	 * @return True if there is an OpenAL error.
	 */
	static String getStateName(int state) {
		return switch (state) {
			case AL_INITIAL -> "INITIAL";
			case AL_PLAYING -> "PLAYING";
			case AL_PAUSED -> "PAUSED";
			case AL_STOPPED -> "STOPPED";
			default -> "UNKNOWN(" + state + ")";
		};
	}

	public long getEstimatedPlaybackTimestamp() {
		if (!this.isValid()) {
			return -1;
		}
		if (!this.isPlaying() || this.playTimestamp == 0) {
			return this.playBufferTimestamp;
		}
		long elapsedMicros = (System.nanoTime() - this.playTimestamp) / 1000L;
		return this.playBufferTimestamp + elapsedMicros;
	}

	static boolean checkErrors(String sectionName) {
		int i = alGetError();
		if (i != 0) {
			WebStreamerMod.LOGGER.error("{}: {}", sectionName, getErrorMessage(i));
			return true;
		} else {
			return false;
		}
	}
	
	static String getErrorMessage(int errorCode) {
		return switch (errorCode) {
			case AL_INVALID_NAME -> "invalid name.";
			case AL_INVALID_OPERATION -> "invalid operation.";
			case AL_INVALID_ENUM -> "illegal enum.";
			case AL_INVALID_VALUE -> "invalid value.";
			case AL_OUT_OF_MEMORY -> "unable to allocate memory.";
			default -> "an unrecognized error occurred.";
		};
	}

}
