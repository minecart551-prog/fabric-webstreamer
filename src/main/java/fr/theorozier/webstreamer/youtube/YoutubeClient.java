package fr.theorozier.webstreamer.youtube;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube client using the internal Android player API.
 *
 * Unlike scraping the watch page, the Android API returns stream URLs that are
 * NOT IP-locked — they can be fetched from any machine. This is the same
 * approach used by yt-dlp's "android" client extractor.
 *
 * The API endpoint is:
 *   POST https://www.youtube.com/youtubei/v1/player?key=AIzaSyA8eiZmM1fanX44...
 * with a JSON body identifying the client as an Android YouTube app.
 *
 * Mirrors the structure of {@link fr.theorozier.webstreamer.twitch.TwitchClient}.
 */
@Environment(EnvType.CLIENT)
public class YoutubeClient {

    // Android VR (Oculus Quest) client context — same values yt-dlp uses.
    // This client returns direct signed URLs without cipher, works without poToken.
    // Since 2026.07, intermittent POT enforcement has been observed, so we also
    // define ANDROID and IOS as fallbacks.
    private static final String API_KEY =
            "AIzaSyA8eiZmM1fanX44NAnt95JaAMuqFROH_AI";

    private static final String PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?key=" + API_KEY +
                    "&prettyPrint=false";

    private record InnertubeClient(
            String clientName, int clientNameInt, String clientVersion,
            String userAgent, JsonObject contextClient) {}

    private static final InnertubeClient[] INNERTUBE_CLIENTS = {
        // ANDROID_VR — primary, best geo-bypass, no cipher
        new InnertubeClient(
            "ANDROID_VR", 28, "1.65.10",
            "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            buildClientContext("ANDROID_VR", "1.65.10", 32, "12L", "Oculus", "Quest 3")),
        // ANDROID — fallback, also no cipher
        new InnertubeClient(
            "ANDROID", 3, "21.26.364",
            "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            buildClientContext("ANDROID", "21.26.364", 30, "11", null, null)),
        // IOS — second fallback, direct URLs
        new InnertubeClient(
            "IOS", 5, "21.26.4",
            "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            buildClientContext("IOS", "21.26.4", -1, "18.3.2.22D82", "Apple", "iPhone16,2")),
    };

    private static JsonObject buildClientContext(String name, String version, int sdk, String os,
                                                  String deviceMake, String deviceModel) {
        JsonObject c = new JsonObject();
        c.addProperty("clientName", name);
        c.addProperty("clientVersion", version);
        if (sdk >= 0) c.addProperty("androidSdkVersion", sdk);
        c.addProperty("osName", name.startsWith("IOS") ? "iPhone" : "Android");
        c.addProperty("osVersion", os);
        if (deviceMake != null) c.addProperty("deviceMake", deviceMake);
        if (deviceModel != null) c.addProperty("deviceModel", deviceModel);
        return c;
    }

    private static final String WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36";

    // Known progressive (video+audio combined) itags.
    private static final HashMap<Integer, String> KNOWN_ITAGS = new HashMap<>();
    static {
        KNOWN_ITAGS.put(18,  "360p");  // MP4 H.264 + AAC — most reliable
        KNOWN_ITAGS.put(22,  "720p");  // MP4 H.264 + AAC
        KNOWN_ITAGS.put(37,  "1080p"); // MP4 H.264 + AAC
        KNOWN_ITAGS.put(59,  "480p");  // MP4 H.264 + AAC
        KNOWN_ITAGS.put(78,  "480p");  // MP4 H.264 + AAC
        KNOWN_ITAGS.put(43,  "360p");  // WebM VP8 + Vorbis
        KNOWN_ITAGS.put(34,  "360p");  // FLV H.264 + AAC
        KNOWN_ITAGS.put(35,  "480p");  // FLV H.264 + AAC
        KNOWN_ITAGS.put(17,  "144p");  // 3GP mobile + audio
    }

    private final Gson gson = new GsonBuilder().create();
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(cookieManager)
            .build();

    /** Cache: video ID → Playlist. Evicted manually via {@link #forgetPlaylist}. */
    private final HashMap<String, Playlist> cache = new HashMap<>();

    /**
     * Set of video IDs that are permanently unavailable (VIDEO_UNAVAILABLE, NOT_FOUND, etc.).
     * Prevents repeated failed HTTP requests every render frame when a video is blocked or removed.
     * Evicted manually via {@link #forgetPlaylist}.
     */
    private final java.util.HashSet<String> failedVideoIds = new java.util.HashSet<>();

    /** Cache: playlist ID → video IDs for playlist entries. */
    private final HashMap<String, List<String>> playlistCache = new HashMap<>();

    private static final Pattern PLAYLIST_VIDEO_ID_PATTERN = Pattern.compile("\\\"playlistVideoRenderer\\\".*?\\\"videoId\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{11})\\\"", Pattern.DOTALL);
    private static final Pattern FALLBACK_VIDEO_ID_PATTERN = Pattern.compile("\\\"videoId\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{11})\\\"");

    /**
     * Expose the shared HttpClient so DisplayLayerVideo can use the same
     * session for the video stream download.
     */
    public HttpClient getHttpClient() {
        return this.httpClient;
    }

    // -------------------------------------------------------------------------
    // Public API (mirrors TwitchClient)
    // -------------------------------------------------------------------------

    /**
     * Return the playlist of stream qualities for a given video ID or YouTube URL.
     * Results are cached until {@link #forgetPlaylist} is called.
     *
     * @param videoIdOrUrl Either a 11-character YouTube video ID (e.g. {@code dQw4w9WgXcQ})
     *                     or a full YouTube URL (e.g. {@code https://www.youtube.com/watch?v=dQw4w9WgXcQ}).
     * @return Non-empty {@link Playlist} of available qualities.
     * @throws YoutubeException If the video cannot be fetched or has no streams.
     */
    public Playlist requestPlaylist(String videoIdOrUrl) throws YoutubeException {
        String playlistId = extractPlaylistId(videoIdOrUrl);
        if (playlistId != null) {
            List<String> ids = requestPlaylistVideos(playlistId);
            if (ids.isEmpty()) {
                throw new YoutubeException(YoutubeExceptionType.VIDEO_NOT_FOUND);
            }
            videoIdOrUrl = ids.get(0);
        }

        // Extract video ID from URL if necessary
        String videoId = extractVideoId(videoIdOrUrl);
        
        synchronized (this.cache) {
            // Check success cache first
            Playlist cached = this.cache.get(videoId);
            if (cached != null) {
                return cached;
            }
            // Check failed cache — prevents per-frame HTTP requests for permanently unavailable videos
            if (this.failedVideoIds.contains(videoId)) {
                WebStreamerMod.LOGGER.debug("Video {} is cached as failed, skipping re-fetch", videoId);
                throw new YoutubeException(YoutubeExceptionType.VIDEO_UNAVAILABLE);
            }
            try {
                Playlist playlist = this.fetchPlaylist(videoId);
                this.cache.put(videoId, playlist);
                return playlist;
            } catch (YoutubeException e) {
                // Cache permanent failures (VIDEO_UNAVAILABLE, VIDEO_NOT_FOUND, INVALID_VIDEO_ID)
                // so we don't hammer YouTube on every render frame.
                if (e.getExceptionType() == YoutubeExceptionType.VIDEO_UNAVAILABLE ||
                    e.getExceptionType() == YoutubeExceptionType.VIDEO_NOT_FOUND ||
                    e.getExceptionType() == YoutubeExceptionType.INVALID_VIDEO_ID) {
                    this.failedVideoIds.add(videoId);
                }
                throw e;
            } catch (Exception e) {
                throw new YoutubeException(YoutubeExceptionType.FETCH_FAILED, e);
            }
        }
    }

    public void forgetPlaylist(String videoIdOrUrl) {
        String videoId = extractVideoId(videoIdOrUrl);
        synchronized (this.cache) {
            this.cache.remove(videoId);
            this.failedVideoIds.remove(videoId);
        }
    }

    public List<String> requestPlaylistVideos(String playlistIdOrUrl) throws YoutubeException {
        String playlistId = extractPlaylistId(playlistIdOrUrl);
        if (playlistId == null) {
            throw new YoutubeException(YoutubeExceptionType.INVALID_VIDEO_ID);
        }

        synchronized (this.playlistCache) {
            List<String> cached = this.playlistCache.get(playlistId);
            if (cached != null) {
                return cached;
            }
        }

        try {
            URI playlistUri = new URI("https://www.youtube.com/playlist?list=" + playlistId);
            HttpRequest request = HttpRequest.newBuilder(playlistUri)
                    .GET()
                    .header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new YoutubeException(YoutubeExceptionType.FETCH_FAILED);
            }

            String body = response.body();
            int startIndex = body.indexOf("\"playlistVideoRenderer\"");
            String snippet = startIndex >= 0 ? body.substring(startIndex) : body;

            Matcher matcher = PLAYLIST_VIDEO_ID_PATTERN.matcher(snippet);
            List<String> ids = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            while (matcher.find()) {
                String id = matcher.group(1);
                if (seen.add(id)) {
                    ids.add(id);
                }
            }

            if (ids.isEmpty()) {
                matcher = FALLBACK_VIDEO_ID_PATTERN.matcher(body);
                while (matcher.find()) {
                    String id = matcher.group(1);
                    if (seen.add(id)) {
                        ids.add(id);
                    }
                }
            }

            if (ids.isEmpty()) {
                throw new YoutubeException(YoutubeExceptionType.PARSE_FAILED);
            }

            synchronized (this.playlistCache) {
                this.playlistCache.put(playlistId, ids);
            }
            return ids;
        } catch (YoutubeException e) {
            throw e;
        } catch (Exception e) {
            throw new YoutubeException(YoutubeExceptionType.FETCH_FAILED, e);
        }
    }

    public void forgetPlaylistVideos(String playlistIdOrUrl) {
        String playlistId = extractPlaylistId(playlistIdOrUrl);
        if (playlistId == null) {
            return;
        }
        synchronized (this.playlistCache) {
            this.playlistCache.remove(playlistId);
        }
    }

    public static String extractPlaylistId(String playlistIdOrUrl) {
        if (playlistIdOrUrl == null || playlistIdOrUrl.isBlank()) {
            return null;
        }

        if (playlistIdOrUrl.contains("youtube.com") || playlistIdOrUrl.contains("youtu.be")) {
            try {
                java.net.URL url = new java.net.URL(playlistIdOrUrl);
                String query = url.getQuery();

                if (query != null && query.contains("list=")) {
                    String[] parts = query.split("&");
                    for (String part : parts) {
                        if (part.startsWith("list=")) {
                            return part.substring(5);
                        }
                    }
                }
            } catch (java.net.MalformedURLException e) {
                // fall through
            }
        }

        if (playlistIdOrUrl.matches("^(PL|LL|UU|WL|RD|OL|FL)[A-Za-z0-9_-]+$")) {
            return playlistIdOrUrl;
        }

        return null;
    }

    /**
     * Return the playlist of stream qualities for a given video ID or YouTube URL.
     * Supports formats:
     * - https://www.youtube.com/watch?v=VIDEO_ID
     * - https://youtu.be/VIDEO_ID
     * - or just VIDEO_ID directly
     */
    public static String extractVideoId(String videoIdOrUrl) {
        if (videoIdOrUrl == null || videoIdOrUrl.isBlank()) {
            return videoIdOrUrl;
        }

        // Check if it's a URL
        if (videoIdOrUrl.contains("youtube.com") || videoIdOrUrl.contains("youtu.be")) {
            try {
                java.net.URL url = new java.net.URL(videoIdOrUrl);
                String host = url.getHost();
                String query = url.getQuery();

                if (host.contains("youtube.com")) {
                    // Format: https://www.youtube.com/watch?v=VIDEO_ID
                    if (query != null && query.contains("v=")) {
                        String[] parts = query.split("&");
                        for (String part : parts) {
                            if (part.startsWith("v=")) {
                                return part.substring(2);
                            }
                        }
                    }
                } else if (host.contains("youtu.be")) {
                    // Format: https://youtu.be/VIDEO_ID
                    String path = url.getPath();
                    if (path.length() > 1) {
                        return path.substring(1);
                    }
                }
            } catch (java.net.MalformedURLException e) {
                // Fall through to return original string
            }
        }

        // Return as-is if it's already a video ID or couldn't parse
        return videoIdOrUrl;
    }

    // -------------------------------------------------------------------------
    // Internal — Android API fetch
    // -------------------------------------------------------------------------

    private Playlist fetchPlaylist(String videoId)
            throws YoutubeException, IOException, InterruptedException, URISyntaxException {

        if (videoId == null || videoId.isBlank() || videoId.length() > 16) {
            throw new YoutubeException(YoutubeExceptionType.INVALID_VIDEO_ID);
        }

        // Try each innertube client in order until one succeeds.
        YoutubeException lastException = null;
        for (InnertubeClient ic : INNERTUBE_CLIENTS) {
            try {
                Playlist result = fetchPlaylistWithClient(videoId, ic);
                return result;
            } catch (YoutubeException e) {
                lastException = e;
                if (e.getExceptionType() == YoutubeExceptionType.VIDEO_NOT_FOUND ||
                    e.getExceptionType() == YoutubeExceptionType.INVALID_VIDEO_ID ||
                    e.getExceptionType() == YoutubeExceptionType.NO_STREAMS) {
                    // These are definitive failures, don't try other clients.
                    throw e;
                }
                WebStreamerMod.LOGGER.warn("YouTube client {} failed for {}: {}",
                        ic.clientName, videoId, e.getMessage());
            }
        }
        throw lastException;
    }

    private Playlist fetchPlaylistWithClient(String videoId, InnertubeClient ic)
            throws YoutubeException, IOException, InterruptedException, URISyntaxException {

        JsonObject context = new JsonObject();
        context.add("client", ic.contextClient);

        JsonObject body = new JsonObject();
        body.add("context", context);
        body.addProperty("videoId", videoId);
        body.addProperty("contentCheckOk", true);
        body.addProperty("racyCheckOk", true);

        String bodyJson = gson.toJson(body);

        HttpRequest request = HttpRequest.newBuilder(URI.create(PLAYER_URL))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .header("Content-Type",  "application/json")
                .header("User-Agent",    ic.userAgent)
                .header("X-YouTube-Client-Name",    String.valueOf(ic.clientNameInt))
                .header("X-YouTube-Client-Version", ic.clientVersion)
                .timeout(Duration.ofSeconds(15))
                .build();

        HttpResponse<String> response = this.httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            WebStreamerMod.LOGGER.warn("YouTube Android API returned HTTP {} for {}",
                    response.statusCode(), videoId);
            throw new YoutubeException(YoutubeExceptionType.FETCH_FAILED);
        }

        JsonObject playerResponse;
        try {
            playerResponse = gson.fromJson(response.body(), JsonObject.class);
        } catch (Exception e) {
            throw new YoutubeException(YoutubeExceptionType.PARSE_FAILED);
        }

        // Check playability.
        JsonObject playabilityStatus = getObj(playerResponse, "playabilityStatus");
        if (playabilityStatus != null) {
            String status = getString(playabilityStatus, "status");
            if ("ERROR".equals(status)) {
                throw new YoutubeException(YoutubeExceptionType.VIDEO_NOT_FOUND);
            }
            if ("LOGIN_REQUIRED".equals(status) || "UNPLAYABLE".equals(status)) {
                String reason = getString(playabilityStatus, "reason");
                WebStreamerMod.LOGGER.warn("Video {} unplayable: {}", videoId, reason);
                throw new YoutubeException(YoutubeExceptionType.VIDEO_UNAVAILABLE);
            }
        }

        // Extract streamingData.
        JsonObject streamingData = getObj(playerResponse, "streamingData");
        if (streamingData == null) {
            throw new YoutubeException(YoutubeExceptionType.NO_STREAMS);
        }

        // Collect formats — Android API returns them with plain unsigned URLs.
        List<StreamFormat> formats = new ArrayList<>();
        collectFormats(streamingData, "formats",         formats);
        collectFormats(streamingData, "adaptiveFormats", formats);

        // Keep only formats that have a plain URL and video.
        // Accept formats with both video+audio, or formats that are known progressive itags.
        List<StreamFormat> usable = formats.stream()
                .filter(f -> f.url != null)
                .filter(f -> f.hasVideo && (f.hasAudio || KNOWN_ITAGS.containsKey(f.itag)))
                .sorted(Comparator.comparingInt((StreamFormat f) -> f.height).reversed())
                .toList();

        if (usable.isEmpty()) {
            WebStreamerMod.LOGGER.warn("No usable progressive streams from Android API for {}", videoId);
            throw new YoutubeException(YoutubeExceptionType.NO_STREAMS);
        }

        Playlist playlist = new Playlist(videoId);
        Set<String> seenLabels = new LinkedHashSet<>();
        for (StreamFormat fmt : usable) {
            String label = KNOWN_ITAGS.getOrDefault(fmt.itag,
                    fmt.height > 0 ? fmt.height + "p" : "itag" + fmt.itag);
            if (seenLabels.add(label)) {
                try {
                    playlist.addQuality(new PlaylistQuality(label, new URI(fmt.url)));
                } catch (URISyntaxException e) {
                    WebStreamerMod.LOGGER.warn("Skipping malformed URL for itag {}", fmt.itag);
                }
            }
        }

        if (playlist.getQualities().isEmpty()) {
            throw new YoutubeException(YoutubeExceptionType.NO_STREAMS);
        }

        return playlist;
    }

    private void collectFormats(JsonObject streamingData, String key, List<StreamFormat> out) {
        JsonArray arr = getArr(streamingData, key);
        if (arr == null) return;
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();

            // Android API gives plain urls — skip anything still cipher-signed.
            if (obj.has("signatureCipher") || obj.has("cipher")) continue;

            String url = getString(obj, "url");
            if (url == null) continue;

            StreamFormat fmt = new StreamFormat();
            fmt.itag   = getInt(obj, "itag");
            fmt.url    = url;
            fmt.height = getInt(obj, "height");

            String mimeType = getString(obj, "mimeType");
            if (mimeType != null) {
                fmt.hasVideo = mimeType.startsWith("video/");
                fmt.hasAudio = obj.has("audioQuality") || obj.has("audioSampleRate");
            }

            out.add(fmt);
        }
    }

    // -------------------------------------------------------------------------
    // JSON helpers
    // -------------------------------------------------------------------------

    private static JsonObject getObj(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return (el != null && el.isJsonObject()) ? el.getAsJsonObject() : null;
    }

    private static JsonArray getArr(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return (el != null && el.isJsonArray()) ? el.getAsJsonArray() : null;
    }

    private static String getString(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static int getInt(JsonObject o, String key) {
        JsonElement el = o.get(key);
        try { return (el != null && el.isJsonPrimitive()) ? el.getAsInt() : 0; }
        catch (NumberFormatException e) { return 0; }
    }

    // -------------------------------------------------------------------------
    // Internal data structure
    // -------------------------------------------------------------------------

    private static class StreamFormat {
        int    itag;
        String url;
        boolean hasVideo;
        boolean hasAudio;
        int    height;
    }

    // -------------------------------------------------------------------------
    // Exception types
    // -------------------------------------------------------------------------

    public enum YoutubeExceptionType {
        INVALID_VIDEO_ID,
        VIDEO_NOT_FOUND,
        VIDEO_UNAVAILABLE,
        FETCH_FAILED,
        PARSE_FAILED,
        NO_STREAMS,
    }

    public static class YoutubeException extends Exception {

        private final YoutubeExceptionType exceptionType;

        public YoutubeException(YoutubeExceptionType type) {
            super(type.name());
            this.exceptionType = type;
        }

        public YoutubeException(YoutubeExceptionType type, Throwable cause) {
            super(type.name(), cause);
            this.exceptionType = type;
        }

        public YoutubeExceptionType getExceptionType() {
            return exceptionType;
        }
    }
}