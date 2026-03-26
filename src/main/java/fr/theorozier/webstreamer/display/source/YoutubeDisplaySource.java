package fr.theorozier.webstreamer.display.source;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import fr.theorozier.webstreamer.youtube.YoutubeClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;

import java.net.URI;

/**
 * A display source backed by a YouTube video ID and a quality label.
 * The URI is resolved via {@link YoutubeClient} which fetches it directly
 * from YouTube's watch page — no external tools required.
 *
 * Mirrors the structure of {@link TwitchDisplaySource}.
 */
public class YoutubeDisplaySource extends DisplaySource {

    public static final String TYPE = "youtube";

    /** 11-character YouTube video ID (e.g. {@code dQw4w9WgXcQ}). */
    private String videoId;

    /**
     * Quality label chosen by the user (e.g. "360p", "480p").
     * Must match a label returned by {@link YoutubeClient#requestPlaylist}.
     */
    private String quality;

    public YoutubeDisplaySource() { }

    public YoutubeDisplaySource(String videoId, String quality) {
        this.videoId = videoId;
        this.quality = quality;
    }

    public void setVideoIdQuality(String videoId, String quality) {
        this.videoId = videoId;
        this.quality = quality;
    }

    public void clear() {
        this.videoId = null;
        this.quality = null;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getQuality() {
        return quality;
    }

    // -------------------------------------------------------------------------
    // DisplaySource
    // -------------------------------------------------------------------------

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public URI getUri() {
        if (this.videoId == null || this.quality == null) return null;
        try {
            Playlist playlist = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylist(this.videoId);
            PlaylistQuality q = playlist.getQuality(this.quality);
            if (q == null) {
                // Chosen quality disappeared — fall back to first available
                if (!playlist.getQualities().isEmpty()) {
                    WebStreamerMod.LOGGER.warn("YouTube quality '{}' not found, falling back to '{}'",
                            this.quality, playlist.getQualities().get(0).name());
                    return playlist.getQualities().get(0).uri();
                }
                return null;
            }
            return q.uri();
        } catch (YoutubeClient.YoutubeException e) {
            WebStreamerMod.LOGGER.error("Failed to get YouTube URI for '{}': {}", this.videoId, e.getMessage());
            return null;
        }
    }

    /**
     * Forget the cached playlist so the watch page is re-fetched.
     * YouTube signed URLs embedded in the page expire, so this keeps things fresh.
     */
    @Override
    public void resetUri() {
        if (this.videoId != null) {
            WebStreamerClientMod.YOUTUBE_CLIENT.forgetPlaylist(this.videoId);
            WebStreamerMod.LOGGER.info("Forgot YouTube playlist for video '{}'", this.videoId);
        }
    }

    @Override
    public String getStatus() {
        return "youtube:" + this.videoId + " / " + this.quality;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (this.videoId != null) nbt.putString("videoId", this.videoId);
        if (this.quality  != null) nbt.putString("quality",  this.quality);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (nbt.get("videoId") instanceof NbtString v) this.videoId = v.asString();
        if (nbt.get("quality")  instanceof NbtString q) this.quality  = q.asString();
    }
}