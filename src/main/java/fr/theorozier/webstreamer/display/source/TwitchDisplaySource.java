package fr.theorozier.webstreamer.display.source;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import fr.theorozier.webstreamer.twitch.TwitchClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;

import java.net.*;
import java.util.concurrent.CompletableFuture;

/**
 * <p>A Twitch display source is defined by a Twitch channel and quality, the URI is
 * computed with the Twitch GraphQL API.</p>
 *
 * <p>This source is fully async-safe: all HTTP requests are dispatched on a
 * background thread via {@link TwitchClient#requestPlaylist(String)}.
 * {@link #getUri()} never blocks the caller — if the async request hasn't
 * completed yet, it returns null and the caller should retry on the next tick.</p>
 */
public class TwitchDisplaySource extends DisplaySource {
    
    public static final String TYPE = "twitch";

    private String channel;
    private String quality;

    /** Resolved URI cache, set once the async future completes. */
    private URI resolvedUri;

    public TwitchDisplaySource() { }

    public TwitchDisplaySource(String channel, String quality) {
        this.setChannelQuality(channel, quality);
    }
    
    public void setChannelQuality(String channel, String quality) {
        this.channel = channel;
        this.quality = quality;
        this.resolvedUri = null;
    }

    public void clearChannelQuality() {
        this.channel = null;
        this.quality = null;
        this.resolvedUri = null;
    }

    public String getChannel() {
        return channel;
    }
    
    public String getQuality() {
        return quality;
    }
    
    @Override
    public String getType() {
        return TYPE;
    }
    
    @Override
    public URI getUri() {
        if (this.channel == null || this.quality == null) {
            return null;
        }

        // If we already resolved the URI, return it immediately (non-blocking).
        if (this.resolvedUri != null) {
            return this.resolvedUri;
        }

        // Kick off (or retrieve cached) async request.
        CompletableFuture<Playlist> future = WebStreamerClientMod.TWITCH_CLIENT.requestPlaylist(this.channel);

        // If the future is already done, resolve synchronously (still non-blocking).
        if (future.isDone()) {
            try {
                Playlist playlist = future.get();
                PlaylistQuality quality = playlist.getQuality(this.quality);
                if (quality != null) {
                    this.resolvedUri = quality.uri();
                }
            } catch (Exception e) {
                // Ignore — mark source dirty so it retries next tick.
                WebStreamerMod.LOGGER.warn("Twitch playlist future failed for channel={}", this.channel, e);
            }
        }

        // Return null if not yet resolved. The render framework calls getUri()
        // every tick and will pick up the URI once the future completes.
        return this.resolvedUri;
    }
    
    @Override
    public void resetUri() {
        this.resolvedUri = null;
        if (this.channel != null) {
            WebStreamerClientMod.TWITCH_CLIENT.forgetPlaylist(this.channel);
        }
    }
    
    @Override
    public String getStatus() {
        return this.channel + " / " + this.quality;
    }
    
    @Override
    public void writeNbt(NbtCompound nbt) {
        if (this.channel != null && this.quality != null) {
            nbt.putString("channel", this.channel);
            nbt.putString("quality", this.quality);
            //nbt.putString("url", this.quality.uri().toString());
        }
    }
    
    @Override
    public void readNbt(NbtCompound nbt) {
        if (
            nbt.get("channel") instanceof NbtString channel &&
            nbt.get("quality") instanceof NbtString quality
        ) {
            try {
                // URI uri = URI.create(urlRaw.asString());
                this.channel = channel.asString();
                this.quality = quality.asString();
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

}
