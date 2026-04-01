package fr.theorozier.webstreamer.display.source;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import fr.theorozier.webstreamer.youtube.YoutubeClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A display source backed by a YouTube video ID and a quality label.
 * The URI is resolved via {@link YoutubeClient} which fetches it directly
 * from YouTube's watch page — no external tools required.
 *
 * Mirrors the structure of {@link TwitchDisplaySource}.
 */
public class YoutubeDisplaySource extends DisplaySource {

    public static final String TYPE = "youtube";

    private String videoId;
    private List<String> videoIds;
    private int currentVideoIndex = 0;

    /**
     * The original user input used to create this source. For playlist URLs, this
     * allows the UI to display the original URL instead of a huge comma-separated list.
     */
    private String sourceText;

    /**
     * Quality label chosen by the user (e.g. "360p", "480p").
     * Must match a label returned by {@link YoutubeClient#requestPlaylist}.
     */
    private String quality;

    public YoutubeDisplaySource() { }

    public YoutubeDisplaySource(String videoId, String quality) {
        this.videoId = videoId;
        this.sourceText = videoId;
        this.quality = quality;
    }

    public YoutubeDisplaySource(List<String> videoIds, String quality) {
        this(videoIds, quality, null);
    }

    public YoutubeDisplaySource(List<String> videoIds, String quality, String sourceText) {
        this.videoIds = new ArrayList<>(videoIds);
        this.quality = quality;
        this.currentVideoIndex = 0;
        this.sourceText = sourceText;
    }

    public void setVideoIdQuality(String videoId, String quality) {
        this.videoId = videoId;
        this.videoIds = null;
        this.currentVideoIndex = 0;
        this.sourceText = videoId;
        this.quality = quality;
    }

    public void setVideoIdsQuality(List<String> videoIds, String quality) {
        this.videoIds = new ArrayList<>(videoIds);
        this.videoId = null;
        this.currentVideoIndex = 0;
        this.sourceText = null;
        this.quality = quality;
    }

    public void clear() {
        this.videoId = null;
        this.videoIds = null;
        this.currentVideoIndex = 0;
        this.sourceText = null;
        this.quality = null;
    }

    public String getVideoId() {
        return videoId;
    }

    public List<String> getVideoIds() {
        return this.videoIds == null ? Collections.emptyList() : Collections.unmodifiableList(this.videoIds);
    }

    public String getCurrentVideoId() {
        if (this.videoIds != null && !this.videoIds.isEmpty()) {
            return this.videoIds.get(this.currentVideoIndex);
        }
        return this.videoId;
    }

    public String getSourceText() {
        if (this.sourceText != null) {
            return this.sourceText;
        }
        if (this.videoIds != null && !this.videoIds.isEmpty()) {
            return String.join(", ", this.videoIds);
        }
        return this.videoId;
    }

    public String getQuality() {
        return quality;
    }

    public boolean hasPlaylist() {
        return this.videoIds != null && this.videoIds.size() > 1;
    }

    public int getPlaylistSize() {
        return this.videoIds == null ? 0 : this.videoIds.size();
    }

    public int getPlaylistIndex() {
        return this.currentVideoIndex;
    }

    public boolean advanceVideo() {
        if (!hasPlaylist()) {
            return false;
        }
        this.currentVideoIndex = (this.currentVideoIndex + 1) % this.videoIds.size();
        return true;
    }

    public boolean previousVideo() {
        if (!hasPlaylist()) {
            return false;
        }
        this.currentVideoIndex = (this.currentVideoIndex - 1 + this.videoIds.size()) % this.videoIds.size();
        return true;
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
        String id = getCurrentVideoId();
        if (id == null || this.quality == null) return null;
        try {
            Playlist playlist = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylist(id);
            PlaylistQuality q = playlist.getQuality(this.quality);
            if (q == null) {
                if (!playlist.getQualities().isEmpty()) {
                    WebStreamerMod.LOGGER.warn("YouTube quality '{}' not found, falling back to '{}'",
                            this.quality, playlist.getQualities().get(0).name());
                    return playlist.getQualities().get(0).uri();
                }
                return null;
            }
            return q.uri();
        } catch (YoutubeClient.YoutubeException e) {
            WebStreamerMod.LOGGER.error("Failed to get YouTube URI for '{}': {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * Forget the cached playlist so the watch page is re-fetched.
     * YouTube signed URLs embedded in the page expire, so this keeps things fresh.
     */
    @Override
    public void resetUri() {
        if (this.videoIds != null) {
            for (String id : this.videoIds) {
                WebStreamerClientMod.YOUTUBE_CLIENT.forgetPlaylist(id);
            }
            WebStreamerMod.LOGGER.info("Forgot YouTube playlist for {} videos", this.videoIds.size());
        } else if (this.videoId != null) {
            WebStreamerClientMod.YOUTUBE_CLIENT.forgetPlaylist(this.videoId);
            WebStreamerMod.LOGGER.info("Forgot YouTube playlist for video '{}'", this.videoId);
        }
    }

    @Override
    public String getStatus() {
        if (hasPlaylist()) {
            return String.format("youtube:%d/%d %s / %s",
                    this.currentVideoIndex + 1, this.videoIds.size(), getCurrentVideoId(), this.quality);
        }
        return "youtube:" + getCurrentVideoId() + " / " + this.quality;
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (this.videoIds != null && !this.videoIds.isEmpty()) {
            nbt.putString("videoIds", String.join(",", this.videoIds));
        } else if (this.videoId != null) {
            nbt.putString("videoId", this.videoId);
        }
        if (this.sourceText != null) {
            nbt.putString("sourceText", this.sourceText);
        }
        if (this.quality != null) {
            nbt.putString("quality", this.quality);
        }
        nbt.putInt("videoIndex", this.currentVideoIndex);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        this.videoId = null;
        this.videoIds = null;
        this.currentVideoIndex = 0;
        this.sourceText = null;
        this.quality = null;

        if (nbt.get("videoIds") instanceof NbtString videoIdsString) {
            String raw = videoIdsString.asString();
            String[] ids = raw.split(",");
            this.videoIds = new ArrayList<>();
            for (String id : ids) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    this.videoIds.add(trimmed);
                }
            }
            this.videoId = null;
        } else if (nbt.get("videoId") instanceof NbtString v) {
            this.videoId = v.asString();
            this.videoIds = null;
        }
        if (nbt.get("sourceText") instanceof NbtString s) {
            this.sourceText = s.asString();
        } else if (this.videoIds != null) {
            this.sourceText = String.join(", ", this.videoIds);
        } else {
            this.sourceText = this.videoId;
        }
        if (nbt.get("quality") instanceof NbtString q) {
            this.quality = q.asString();
        }
        if (nbt.contains("videoIndex")) {
            this.currentVideoIndex = nbt.getInt("videoIndex");
        }
    }
}