package fr.theorozier.webstreamer.twitch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.playlist.Playlist;
import fr.theorozier.webstreamer.playlist.PlaylistQuality;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <p>Twitch client that fetches HLS playlist URIs for Twitch channels.
 * All HTTP requests are performed asynchronously via {@link CompletableFuture}
 * so that callers on the render thread never block.</p>
 */
@Environment(EnvType.CLIENT)
public class TwitchClient {

	private final Gson gson = new GsonBuilder().create();
	private final String clientId;
	
	private final ConcurrentHashMap<String, CompletableFuture<Playlist>> cache = new ConcurrentHashMap<>();

	public TwitchClient(String clientId) {
		this.clientId = clientId;
	}

	public TwitchClient() {
		this("kimne78kx3ncx6brgo4mv6wki5h1ko");
	}

	/**
	 * Asynchronously fetch the playlist for a channel. The returned future is
	 * cached so that concurrent callers share the same request. The caller
	 * should check {@link CompletableFuture#isDone()} before blocking.
	 *
	 * @param channel The Twitch channel name.
	 * @return A future that completes with the playlist, or completes exceptionally.
	 */
	public CompletableFuture<Playlist> requestPlaylist(String channel) {
		return cache.computeIfAbsent(channel, ch -> CompletableFuture.supplyAsync(() -> {
			try {
				return requestPlaylistInternal(ch);
			} catch (PlaylistException e) {
				throw new RuntimeException(e);
			} catch (Exception e) {
				WebStreamerMod.LOGGER.error("Twitch playlist request failed for channel {}", ch, e);
				throw new RuntimeException(new PlaylistException(PlaylistExceptionType.UNKNOWN));
			}
		}));
	}

	/**
	 * Internal blocking implementation. Runs on an async thread via
	 * {@link #requestPlaylist(String)}.
	 */
	private Playlist requestPlaylistInternal(String channel) throws PlaylistException, IOException, URISyntaxException, InterruptedException {
		if (channel.isEmpty()) {
			WebStreamerMod.LOGGER.warn("Twitch playlist request failed: empty channel");
			throw new PlaylistException(PlaylistExceptionType.CHANNEL_NOT_FOUND);
		}

		URI gqlUri = new URI("https://gql.twitch.tv/gql");
		HttpClient client = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(15))
				.build();

		JsonObject body = new JsonObject();
		body.addProperty("operationName", "PlaybackAccessToken");
		JsonObject extensions = new JsonObject();
		JsonObject persistedQuery = new JsonObject();
		persistedQuery.addProperty("version", 1);
		persistedQuery.addProperty("sha256Hash", "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712");
		extensions.add("persistedQuery", persistedQuery);
		body.add("extensions", extensions);
		JsonObject variables = new JsonObject();
		variables.addProperty("isLive", true);
		variables.addProperty("login", channel);
		variables.addProperty("isVod", false);
		variables.addProperty("vodID", "");
		variables.addProperty("playerType", "embed");
		body.add("variables", variables);

		HttpRequest tokenReq = HttpRequest.newBuilder(gqlUri)
				.POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body)))
				.header("Accept", "application/json")
				.header("Client-ID", this.clientId)
				.header("User-Agent", "WebStreamer/1.0")
				.timeout(Duration.ofSeconds(20))
				.build();

		HttpResponse<String> tokenRes = client.send(tokenReq, HttpResponse.BodyHandlers.ofString());

		if (tokenRes.statusCode() != 200) {
			WebStreamerMod.LOGGER.warn("Twitch PlaybackAccessToken request failed status={} body={}", tokenRes.statusCode(), tokenRes.body());
			throw new PlaylistException(PlaylistExceptionType.NO_TOKEN);
		}

		JsonObject tokenResJson = this.gson.fromJson(tokenRes.body(), JsonObject.class);
		JsonObject tokenResData = tokenResJson != null && tokenResJson.has("data") && tokenResJson.get("data").isJsonObject()
				? tokenResJson.getAsJsonObject("data")
				: null;
		JsonElement tokenRaw = tokenResData == null ? null : tokenResData.get("streamPlaybackAccessToken");
		if (tokenRaw == null || !tokenRaw.isJsonObject()) {
			WebStreamerMod.LOGGER.warn("Twitch PlaybackAccessToken response missing token object for channel={} body={}", channel, tokenRes.body());
			throw new PlaylistException(PlaylistExceptionType.CHANNEL_NOT_FOUND);
		}

		JsonObject token = tokenRaw.getAsJsonObject();
		String tokenValue = URLEncoder.encode(token.get("value").getAsString(), StandardCharsets.UTF_8);
		String tokenSignature = token.get("signature").getAsString();
		URI urlsUri = new URL("https://usher.ttvnw.net/api/channel/hls/" + channel + ".m3u8?client_id=" + this.clientId + "&token=" + tokenValue + "&sig=" + tokenSignature + "&allow_source=true&allow_audio_only=false").toURI();

		HttpRequest urlsReq = HttpRequest.newBuilder(urlsUri)
				.GET()
				.header("Accept", "application/json")
				.header("User-Agent", "WebStreamer/1.0")
				.timeout(Duration.ofSeconds(20))
				.build();

		HttpResponse<String> urlsRes = client.send(urlsReq, HttpResponse.BodyHandlers.ofString());

		if (urlsRes.statusCode() == 404) {
			if (urlsRes.body().contains("Can not find channel")) {
				throw new PlaylistException(PlaylistExceptionType.CHANNEL_NOT_FOUND);
			} else {
				throw new PlaylistException(PlaylistExceptionType.CHANNEL_OFFLINE);
			}
		} else if (urlsRes.statusCode() != 200) {
			throw new PlaylistException(PlaylistExceptionType.UNKNOWN);
		}

		String raw = urlsRes.body();
		List<String> rawLines = raw.lines().toList();

		Playlist playlist = new Playlist(channel);
		for (int i = 4; i < rawLines.size(); i += 3) {
			String line0 = rawLines.get(i);
			String line2 = rawLines.get(i - 2);
			int qualityNameStartIdx = line2.indexOf("NAME=\"");
			int qualityNameStopIdx = line2.indexOf('"', qualityNameStartIdx + 6);
			if (qualityNameStartIdx < 0 || qualityNameStopIdx < 0) {
				WebStreamerMod.LOGGER.warn("Twitch playlist parse failed for channel={} line={} content={}", channel, i - 2, line2);
				continue;
			}
			String qualityName = line2.substring(qualityNameStartIdx + 6, qualityNameStopIdx);
			playlist.addQuality(new PlaylistQuality(qualityName, new URI(line0)));
		}

		return playlist;
	}

	/**
	 * Remove a channel's cached future. Subsequent calls to
	 * {@link #requestPlaylist(String)} will trigger a new async request.
	 */
	public void forgetPlaylist(String channel) {
		this.cache.remove(channel);
	}

	public enum PlaylistExceptionType {
		UNKNOWN,
		NO_TOKEN,
		CHANNEL_NOT_FOUND,
		CHANNEL_OFFLINE,
	}

	public static class PlaylistException extends Exception {

		private final PlaylistExceptionType exceptionType;

		public PlaylistException(PlaylistExceptionType exceptionType) {
			super(exceptionType.name());
			this.exceptionType = exceptionType;
		}

		public PlaylistExceptionType getExceptionType() {
			return exceptionType;
		}
	}

}