package fr.theorozier.webstreamer.display.source;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.youtube.YoutubeClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;

import java.net.URI;
import java.util.Objects;

/**
 * <p>A raw display source can be used to directly set a URI to be returned, the same
 * URI is always returned and a method exists to change it.</p>
 *
 * <p>If the URI is a YouTube watch URL, it is automatically resolved to a direct
 * stream URL via {@link YoutubeClient} so that FFmpeg can open it.</p>
 */
public class RawDisplaySource extends DisplaySource {

    public static final String TYPE = "raw";

    private URI uri;

    /** Cached resolved stream URI for YouTube watch URLs. */
    private URI resolvedUri;

    /** When true, GIF sources start playback from a random frame. */
    private boolean randomStartFrame;

    public RawDisplaySource() { }

    public RawDisplaySource(URI uri) {
        this.setUri(uri);
    }

    /**
     * Set the internal URI to another value, maybe null.
     *
     * @param uri The new URI for this source to return.
     */
    public void setUri(URI uri) {
        this.uri = uri;
        this.resolvedUri = null;
    }

    public boolean isRandomStartFrame() {
        return this.randomStartFrame;
    }

    public void setRandomStartFrame(boolean randomStartFrame) {
        this.randomStartFrame = randomStartFrame;
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public URI getUri() {
        if (this.uri == null) {
            return null;
        }

        // If already resolved, return cached result.
        if (this.resolvedUri != null) {
            return this.resolvedUri;
        }

        // If this looks like a YouTube watch URL, resolve it to a stream URL.
        String uriStr = this.uri.toString();
        if (isYouTubeUrl(uriStr)) {
            this.resolvedUri = resolveYouTubeUrl(uriStr);
            return this.resolvedUri;
        }

        return this.uri;
    }

    @Override
    public void resetUri() {
        if (this.resolvedUri != null) {
            String videoId = YoutubeClient.extractVideoId(this.uri.toString());
            if (videoId != null) {
                WebStreamerClientMod.YOUTUBE_CLIENT.forgetPlaylist(videoId);
            }
            this.resolvedUri = null;
        }
    }

    @Override
    public String getStatus() {
        return Objects.toString(this.uri);
    }

    @Override
    public void writeNbt(NbtCompound nbt) {
        if (this.uri != null) {
            nbt.putString("url", this.uri.toString());
        }
        nbt.putBoolean("randomStartFrame", this.randomStartFrame);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        if (nbt.get("url") instanceof NbtString nbtRaw) {
            try {
                this.uri = URI.create(nbtRaw.asString());
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        this.randomStartFrame = nbt.getBoolean("randomStartFrame");
    }

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
            // Pick the first (best) quality.
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
