package fr.theorozier.webstreamer.display.source;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import fr.theorozier.webstreamer.youtube.YoutubeClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A display source that references a named entry from the server's
 * {@code config/webstreamer/sources.txt}. The URL is resolved at render time
 * from a client-side cache that is kept up to date via server broadcasts.
 *
 * <p>If the resolved URL is a YouTube watch URL, it is automatically resolved
 * to a direct stream URL via {@link YoutubeClient} so that FFmpeg can open it.</p>
 *
 * <p>If the resolved URL is a YouTube playlist, the source supports playlist
 * navigation (prev/next/shuffle) just like {@link YoutubeDisplaySource}.</p>
 */
public class ServerDisplaySource extends DisplaySource {

    public static final String TYPE = "server";

    private String sourceName;

    /** Cached resolved stream URI for YouTube watch URLs. */
    private URI resolvedUri;
    private String lastResolvedUrl;

    // Playlist support — populated when the resolved URL is a YouTube playlist.
    private List<String> videoIds;
    private int currentVideoIndex = 0;
    private boolean shuffle = false;
    private final Random random = new Random();

    /**
     * When shuffle is on, {@link #prepareNextVideo()} picks a random next video
     * and pre-fetches its playlist. This field stores the picked index so that
     * {@link #advanceVideo()} reuses it instead of picking again.
     */
    private int preparedNextIndex = -1;

    /**
     * The fully resolved URI for the next video, resolved on a background thread
     * by {@link #prepareNextVideo()}. Consumed by {@link #consumePreparedNextUri()}.
     */
    private volatile URI preparedNextUri = null;

    public ServerDisplaySource() { }

    public ServerDisplaySource(String sourceName) {
        this.sourceName = sourceName;
    }

    public String getSourceName() {
        return this.sourceName;
    }

    public void setSourceName(String sourceName) {
        this.sourceName = sourceName;
    }

    // -------------------------------------------------------------------------
    // Playlist navigation
    // -------------------------------------------------------------------------

    public boolean hasPlaylist() {
        return this.videoIds != null && this.videoIds.size() > 1;
    }

    public boolean isShuffle() {
        return this.shuffle;
    }

    public void setShuffle(boolean shuffle) {
        this.shuffle = shuffle;
    }

    public int getPlaylistSize() {
        return this.videoIds == null ? 0 : this.videoIds.size();
    }

    public int getPlaylistIndex() {
        return this.currentVideoIndex;
    }

    public void setPlaylistIndex(int index) {
        this.currentVideoIndex = index;
    }

    public String getCurrentVideoId() {
        if (this.videoIds != null && !this.videoIds.isEmpty()) {
            return this.videoIds.get(this.currentVideoIndex);
        }
        return null;
    }

    public boolean advanceVideo() {
        if (!hasPlaylist()) {
            return false;
        }
        if (this.shuffle) {
            if (this.preparedNextIndex >= 0) {
                this.currentVideoIndex = this.preparedNextIndex;
                this.preparedNextIndex = -1;
            } else if (this.videoIds.size() == 1) {
                this.currentVideoIndex = 0;
            } else {
                int newIndex;
                do {
                    newIndex = this.random.nextInt(this.videoIds.size());
                } while (newIndex == this.currentVideoIndex);
                this.currentVideoIndex = newIndex;
            }
        } else {
            this.currentVideoIndex = (this.currentVideoIndex + 1) % this.videoIds.size();
        }
        this.resolvedUri = null;
        return true;
    }

    public boolean previousVideo() {
        if (!hasPlaylist()) {
            return false;
        }
        this.currentVideoIndex = (this.currentVideoIndex - 1 + this.videoIds.size()) % this.videoIds.size();
        this.resolvedUri = null;
        return true;
    }

    /**
     * Pick the next video (random if shuffle, sequential otherwise) and
     * pre-fetch its full URI in a background thread.
     */
    public void prepareNextVideo() {
        if (!hasPlaylist()) {
            return;
        }
        int nextIndex;
        if (this.shuffle) {
            if (this.videoIds.size() == 1) {
                nextIndex = 0;
            } else {
                do {
                    nextIndex = this.random.nextInt(this.videoIds.size());
                } while (nextIndex == this.currentVideoIndex);
            }
            this.preparedNextIndex = nextIndex;
        } else {
            nextIndex = (this.currentVideoIndex + 1) % this.videoIds.size();
        }
        String nextId = this.videoIds.get(nextIndex);
        WebStreamerMod.LOGGER.info("Server source pre-fetching URI for next video: {} (index {})", nextId, nextIndex);
        Thread prefetch = new Thread(() -> {
            try {
                Playlist playlist = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylist(nextId);
                PlaylistQuality q = playlist.getQualities().isEmpty() ? null : playlist.getQualities().get(0);
                if (q != null) {
                    this.preparedNextUri = q.uri();
                }
            } catch (Exception e) {
                WebStreamerMod.LOGGER.warn("Server source pre-fetch failed for '{}': {}", nextId, e.getMessage());
            }
        }, "srv-prefetch-" + nextId);
        prefetch.setDaemon(true);
        prefetch.start();
    }

    /**
     * Consume the pre-resolved URI for the next video. Returns and clears it.
     */
    public URI consumePreparedNextUri() {
        URI uri = this.preparedNextUri;
        this.preparedNextUri = null;
        return uri;
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
        if (this.sourceName == null || this.sourceName.isEmpty()) {
            return null;
        }
        String url = DisplayNetworking.resolveServerSource(this.sourceName);
        if (url == null) {
            return null;
        }

        // If the URL changed, invalidate cached resolution.
        if (!url.equals(this.lastResolvedUrl)) {
            this.resolvedUri = null;
            this.videoIds = null;
            this.lastResolvedUrl = url;
        }

        // If already resolved, return cached result.
        if (this.resolvedUri != null) {
            return this.resolvedUri;
        }

        // If this looks like a YouTube URL, resolve it.
        if (isYouTubeUrl(url)) {
            // Check if it's a playlist URL.
            String playlistId = YoutubeClient.extractPlaylistId(url);
            if (playlistId != null) {
                return resolveYouTubePlaylist(url, playlistId);
            }
            this.resolvedUri = resolveYouTubeUrl(url);
            return this.resolvedUri;
        }

        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private URI resolveYouTubePlaylist(String url, String playlistId) {
        try {
            List<String> ids = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylistVideos(playlistId);
            if (ids.isEmpty()) {
                WebStreamerMod.LOGGER.warn("No videos found in YouTube playlist '{}'", url);
                return null;
            }
            this.videoIds = ids;

            // Clamp currentVideoIndex in case the playlist shrunk.
            if (this.currentVideoIndex >= this.videoIds.size()) {
                this.currentVideoIndex = 0;
            }

            String videoId = this.videoIds.get(this.currentVideoIndex);
            Playlist playlist = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylist(videoId);
            if (playlist.getQualities().isEmpty()) {
                WebStreamerMod.LOGGER.warn("No qualities available for YouTube video '{}'", videoId);
                return null;
            }
            this.resolvedUri = playlist.getQualities().get(0).uri();
            WebStreamerMod.LOGGER.info("Resolved server playlist '{}' → video '{}' quality '{}'",
                    url, videoId, playlist.getQualities().get(0).name());
            return this.resolvedUri;
        } catch (YoutubeClient.YoutubeException e) {
            WebStreamerMod.LOGGER.warn("Failed to resolve server YouTube playlist '{}': {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            WebStreamerMod.LOGGER.error("Unexpected error resolving server YouTube playlist '{}'", url, e);
            return null;
        }
    }

    @Override
    public void resetUri() {
        if (this.resolvedUri != null && this.lastResolvedUrl != null) {
            String videoId = YoutubeClient.extractVideoId(this.lastResolvedUrl);
            if (videoId != null) {
                WebStreamerClientMod.YOUTUBE_CLIENT.forgetPlaylist(videoId);
            }
            this.resolvedUri = null;
        }
        // Also forget cached playlists for all videos in a playlist.
        if (this.videoIds != null) {
            for (String id : this.videoIds) {
                WebStreamerClientMod.YOUTUBE_CLIENT.forgetPlaylist(id);
            }
        }
    }

    @Override
    public String getStatus() {
        if (hasPlaylist()) {
            return String.format("server:%s %d/%d %s",
                    this.sourceName, this.currentVideoIndex + 1, this.videoIds.size(), getCurrentVideoId());
        }
        URI uri = getUri();
        if (uri != null) {
            return "server:" + this.sourceName + " → " + uri;
        }
        return "server:" + this.sourceName + " (not resolved)";
    }

    // -------------------------------------------------------------------------
    // NBT
    // -------------------------------------------------------------------------

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (this.sourceName != null) {
            nbt.putString("serverSourceName", this.sourceName);
        }
        nbt.putInt("serverVideoIndex", this.currentVideoIndex);
        nbt.putBoolean("serverShuffle", this.shuffle);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (nbt.get("serverSourceName") instanceof NbtString name) {
            this.sourceName = name.asString();
        }
        if (nbt.contains("serverVideoIndex")) {
            this.currentVideoIndex = nbt.getInt("serverVideoIndex");
        }
        this.shuffle = nbt.getBoolean("serverShuffle");
    }

    // -------------------------------------------------------------------------
    // Static helpers
    // -------------------------------------------------------------------------

    private static boolean isYouTubeUrl(String url) {
        return url.contains("youtube.com/watch") || url.contains("youtu.be/");
    }

    private static URI resolveYouTubeUrl(String url) {
        try {
            String videoId = YoutubeClient.extractVideoId(url);
            if (videoId == null || videoId.isBlank()) {
                WebStreamerMod.LOGGER.warn("Could not extract YouTube video ID from '{}'", url);
                return null;
            }
            Playlist playlist = WebStreamerClientMod.YOUTUBE_CLIENT.requestPlaylist(videoId);
            if (playlist.getQualities().isEmpty()) {
                WebStreamerMod.LOGGER.warn("No qualities available for YouTube video '{}'", videoId);
                return null;
            }
            URI streamUri = playlist.getQualities().get(0).uri();
            WebStreamerMod.LOGGER.info("Resolved YouTube URL for '{}' → quality '{}'", videoId, playlist.getQualities().get(0).name());
            return streamUri;
        } catch (YoutubeClient.YoutubeException e) {
            WebStreamerMod.LOGGER.warn("Failed to resolve YouTube URL '{}': {}", url, e.getMessage());
            return null;
        } catch (Exception e) {
            WebStreamerMod.LOGGER.error("Unexpected error resolving YouTube URL '{}'", url, e);
            return null;
        }
    }

}
