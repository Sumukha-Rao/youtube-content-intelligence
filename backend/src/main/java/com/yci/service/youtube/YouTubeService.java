package com.yci.service.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yci.config.AppProperties;
import com.yci.exception.YouTubeApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encapsulates all access to the YouTube Data API v3. Raw API responses are never
 * exposed outside this class; callers receive the lightweight records below.
 *
 * Every request is made with one specific API key. Callers open a {@link Api}
 * session for the key that belongs to the user they are acting for — their own if
 * they saved one in Settings, the server's otherwise — so quota is spent against
 * the right project and one account cannot exhaust another's.
 *
 * Handles: channel URL resolution, channel details, video listing (via the uploads
 * playlist), video metadata, comment threads (with pagination), and best-effort
 * transcript retrieval. Quota / not-found / comments-disabled errors are translated
 * into {@link YouTubeApiException} with actionable messages.
 */
@Service
public class YouTubeService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeService.class);

    /** A well-known channel (Google Developers) used only to prove a key works. */
    private static final String PROBE_CHANNEL_ID = "UC_x5XG1OV2P6uZZ5FSM9Ttw";

    private final AppProperties props;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TranscriptService transcriptService;

    public YouTubeService(AppProperties props, WebClient.Builder builder, TranscriptService transcriptService) {
        this.props = props;
        this.client = builder.baseUrl(props.getYoutube().getApiBaseUrl()).build();
        this.transcriptService = transcriptService;
    }

    // ---- lightweight DTOs returned to callers ----
    public record YtChannel(String channelId, String title, String description,
                            long subscriberCount, long videoCount, String uploadsPlaylistId, String language) {}
    public record YtVideo(String videoId, String title, String description, LocalDateTime publishedAt,
                          String duration, long viewCount, long likeCount, long commentCount, String thumbnailUrl,
                          List<String> tags, String language) {}
    public record YtComment(String commentId, String parentId, String text, LocalDateTime publishedAt, long likeCount) {}

    /**
     * Open a session bound to {@code apiKey}, falling back to the server's key when
     * the user has not saved one of their own.
     */
    public Api session(String apiKey) {
        boolean own = apiKey != null && !apiKey.isBlank();
        return new Api(own ? apiKey.trim() : props.getYoutube().getApiKey(), own);
    }

    /** Session using the server's key — for callers with no user context. */
    public Api session() {
        return session(null);
    }

    /** Every call in here spends quota against one key. */
    public class Api {

        /** Hard ceiling so a "last 365 days" setting on a prolific channel cannot run away. */
        private static final int MAX_VIDEOS = 200;

        private final String apiKey;
        private final boolean userKey;

        private Api(String apiKey, boolean userKey) {
            this.apiKey = apiKey;
            this.userKey = userKey;
        }

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }

        private void requireKey() {
            if (!isConfigured()) {
                throw new YouTubeApiException(
                        "No YouTube API key is available. Add your own key under Settings → API access, "
                        + "or set YOUTUBE_API_KEY on the server.");
            }
        }

        private JsonNode get(String path, Map<String, String> params) {
            requireKey();
            // Expand values after encoding: literal +, &, Unicode, and percent
            // characters must remain part of the parameter, never query syntax.
            UriComponentsBuilder b = UriComponentsBuilder.fromUriString(
                    props.getYoutube().getApiBaseUrl().replaceAll("/+$", ""))
                    .path(path).queryParam("key", "{apiKey}");
            Map<String, Object> values = new HashMap<>(); values.put("apiKey", apiKey);
            params.forEach((name, value) -> {
                b.queryParam(name, "{" + name + "}"); values.put(name, value);
            });
            URI uri = b.encode().buildAndExpand(values).toUri();
            try {
                String raw = client.get().uri(uri).retrieve()
                        .bodyToMono(String.class).timeout(Duration.ofSeconds(30)).block();
                return mapper.readTree(raw);
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                String bodyText = e.getResponseBodyAsString();
                if (e.getStatusCode().value() == 403 && bodyText.contains("quota")) {
                    throw new YouTubeApiException("YouTube API quota exceeded for " + whichKey()
                            + ". Try again after the daily reset, or use a different key.", e);
                }
                if (e.getStatusCode().value() == 400 && bodyText.contains("API key not valid")) {
                    throw new YouTubeApiException("The YouTube API key is not valid (" + whichKey() + ").", e);
                }
                if (e.getStatusCode().value() == 404) {
                    throw new YouTubeApiException("YouTube resource not found.", e);
                }
                throw new YouTubeApiException("YouTube API error (" + e.getStatusCode() + "): " + bodyText, e);
            } catch (YouTubeApiException e) {
                throw e;
            } catch (Exception e) {
                throw new YouTubeApiException("YouTube API request failed. Please try again later.", e);
            }
        }

        private String whichKey() {
            return userKey ? "the key saved in your Settings" : "the server's key";
        }

        /** One cheap call, used by the Test button to prove a key works. */
        public void ping() {
            JsonNode r = get("/channels", Map.of("part", "id", "id", PROBE_CHANNEL_ID));
            if (!r.path("items").isArray()) {
                throw new YouTubeApiException("The YouTube API answered, but not with channel data.");
            }
        }

        // ---- channel URL resolution ----

        public String resolveChannelId(String urlOrId) {
            if (urlOrId == null || urlOrId.isBlank()) throw new YouTubeApiException("Empty channel URL.");
            String value = urlOrId.trim();
            if (value.matches("UC[\\w-]{22}")) return value;
            if (value.startsWith("@")) return resolveByHandle(value.substring(1));
            if (value.matches("(?i)(www\\.|m\\.)?youtube\\.com/.*")) value = "https://" + value;
            if (value.contains("://")) {
                URI uri;
                try { uri = URI.create(value); }
                catch (IllegalArgumentException e) { throw new YouTubeApiException("Invalid YouTube channel URL."); }
                String host = uri.getHost();
                if (host == null || !java.util.Set.of("youtube.com", "www.youtube.com", "m.youtube.com")
                        .contains(host.toLowerCase(java.util.Locale.ROOT))
                        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    throw new YouTubeApiException("Enter a YouTube channel URL, @handle or channel ID.");
                }
                String[] parts = uri.getPath().split("/");
                if (parts.length >= 2 && parts[1].startsWith("@")) return resolveByHandle(parts[1].substring(1));
                if (parts.length >= 3 && parts[1].equals("channel") && parts[2].matches("UC[\\w-]{22}")) return parts[2];
                if (parts.length >= 3 && parts[1].equals("user")) {
                    JsonNode items = get("/channels", Map.of("part", "id", "forUsername", parts[2])).path("items");
                    if (items.isArray() && !items.isEmpty()) return items.get(0).path("id").asText();
                    throw new YouTubeApiException("Could not resolve a channel from: " + urlOrId);
                }
                if (parts.length >= 3 && parts[1].equals("c")) return resolveBySearch(parts[2]);
                throw new YouTubeApiException("Enter a YouTube channel URL, @handle or channel ID.");
            }
            if (value.matches("UC[\\w-]+")) throw new YouTubeApiException("Invalid YouTube channel ID.");
            return resolveBySearch(value);
        }

        private String resolveByHandle(String handle) {
            if (handle.isBlank() || handle.matches(".*[\\s/?#].*")) throw new YouTubeApiException("Invalid YouTube handle.");
            JsonNode items = get("/channels", Map.of("part", "id", "forHandle", "@" + handle)).path("items");
            if (items.isArray() && !items.isEmpty()) return items.get(0).path("id").asText();
            // A missing explicit handle must not silently resolve to a namesake.
            throw new YouTubeApiException("Could not resolve a channel from: @" + handle);
        }

        /** Bounded channel-name search for verifying LLM suggestions. */
        public List<String> searchChannels(String query) {
            JsonNode r = get("/search", Map.of("part", "snippet", "type", "channel",
                    "maxResults", "3", "q", query));
            List<String> ids = new ArrayList<>();
            for (JsonNode item : r.path("items")) {
                String id = item.path("snippet").path("channelId").asText();
                if (id.matches("UC[\\w-]{22}")) ids.add(id);
            }
            return ids;
        }

        private String resolveBySearch(String query) {
            JsonNode r = get("/search", Map.of(
                    "part", "snippet", "type", "channel", "maxResults", "1", "q", query));
            JsonNode items = r.path("items");
            if (items.isArray() && items.size() > 0)
                return items.get(0).path("snippet").path("channelId").asText();
            throw new YouTubeApiException("Could not resolve a channel from: " + query);
        }

        // ---- channel details ----
        public YtChannel getChannelDetails(String channelId) {
            JsonNode r = get("/channels", Map.of(
                    "part", "snippet,statistics,contentDetails", "id", channelId));
            JsonNode items = r.path("items");
            if (!items.isArray() || items.size() == 0)
                throw new YouTubeApiException("Channel not found: " + channelId);
            JsonNode c = items.get(0);
            return new YtChannel(
                    channelId,
                    c.path("snippet").path("title").asText(),
                    c.path("snippet").path("description").asText(),
                    c.path("statistics").path("subscriberCount").asLong(0),
                    c.path("statistics").path("videoCount").asLong(0),
                    c.path("contentDetails").path("relatedPlaylists").path("uploads").asText(),
                    c.path("snippet").path("defaultLanguage").asText(""));
        }

        // ---- videos via uploads playlist ----

        public List<YtVideo> getChannelVideos(String channelId, int max) {
            YtChannel ch = getChannelDetails(channelId);
            return getPlaylistVideos(ch.uploadsPlaylistId(), max, null);
        }

        /**
         * Videos published on or after {@code since}. The uploads playlist is ordered
         * newest-first, so paging stops as soon as an older item appears.
         */
        public List<YtVideo> getChannelVideosSince(String channelId, LocalDateTime since) {
            YtChannel ch = getChannelDetails(channelId);
            return getPlaylistVideos(ch.uploadsPlaylistId(), MAX_VIDEOS, since);
        }

        private List<YtVideo> getPlaylistVideos(String uploadsPlaylistId, int max, LocalDateTime since) {
            if (uploadsPlaylistId == null || uploadsPlaylistId.isBlank()) return List.of();
            int limit = Math.min(max, MAX_VIDEOS);
            List<String> videoIds = new ArrayList<>();
            String pageToken = null;
            boolean reachedCutoff = false;

            while (videoIds.size() < limit && !reachedCutoff) {
                var params = new HashMap<String, String>();
                params.put("part", "snippet,contentDetails");
                params.put("maxResults", String.valueOf(Math.min(50, limit - videoIds.size())));
                params.put("playlistId", uploadsPlaylistId);
                if (pageToken != null) params.put("pageToken", pageToken);
                JsonNode r = get("/playlistItems", params);

                for (JsonNode item : r.path("items")) {
                    if (since != null) {
                        LocalDateTime published = parseTime(item.path("contentDetails")
                                .path("videoPublishedAt").asText(null));
                        if (published == null) {
                            published = parseTime(item.path("snippet").path("publishedAt").asText(null));
                        }
                        if (published != null && published.isBefore(since)) {
                            reachedCutoff = true;
                            break;
                        }
                    }
                    String id = item.path("contentDetails").path("videoId").asText(null);
                    if (id != null && !id.isBlank()) videoIds.add(id);
                }
                pageToken = r.path("nextPageToken").asText(null);
                if (pageToken == null) break;
            }
            return getVideoDetails(videoIds);
        }

        /** Fetch full metadata for the given video ids (batched in groups of 50). */
        public List<YtVideo> getVideoDetails(List<String> videoIds) {
            List<YtVideo> out = new ArrayList<>();
            for (int i = 0; i < videoIds.size(); i += 50) {
                List<String> batch = videoIds.subList(i, Math.min(i + 50, videoIds.size()));
                JsonNode r = get("/videos", Map.of(
                        "part", "snippet,statistics,contentDetails", "id", String.join(",", batch)));
                for (JsonNode v : r.path("items")) {
                    out.add(new YtVideo(
                            v.path("id").asText(),
                            v.path("snippet").path("title").asText(),
                            v.path("snippet").path("description").asText(),
                            parseTime(v.path("snippet").path("publishedAt").asText(null)),
                            v.path("contentDetails").path("duration").asText(),
                            v.path("statistics").path("viewCount").asLong(0),
                            v.path("statistics").path("likeCount").asLong(0),
                            v.path("statistics").path("commentCount").asLong(0),
                            v.path("snippet").path("thumbnails").path("medium").path("url").asText(null),
                            mapper.convertValue(v.path("snippet").path("tags").isArray()
                                    ? v.path("snippet").path("tags") : mapper.createArrayNode(),
                                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}),
                            v.path("snippet").path("defaultAudioLanguage").asText(
                                    v.path("snippet").path("defaultLanguage").asText(""))));
                }
            }
            return out;
        }

        /** Latest N videos for change detection (cheaper than full uploads scan). */
        public List<YtVideo> getLatestVideos(String channelId, int max) {
            JsonNode r = get("/search", Map.of(
                    "part", "id", "channelId", channelId, "order", "date",
                    "type", "video", "maxResults", String.valueOf(Math.min(50, max))));
            List<String> ids = new ArrayList<>();
            for (JsonNode item : r.path("items")) ids.add(item.path("id").path("videoId").asText());
            return getVideoDetails(ids);
        }

        // ---- comments (paginated) ----
        public List<YtComment> getComments(String videoId, int limit) {
            List<YtComment> out = new ArrayList<>();
            String pageToken = null;
            try {
                while (out.size() < limit) {
                    var params = new HashMap<String, String>();
                    params.put("part", "snippet");
                    params.put("videoId", videoId);
                    params.put("maxResults", String.valueOf(Math.min(100, limit - out.size())));
                    params.put("order", "relevance");
                    params.put("textFormat", "plainText");
                    if (pageToken != null) params.put("pageToken", pageToken);
                    JsonNode r = get("/commentThreads", params);
                    for (JsonNode item : r.path("items")) {
                        JsonNode top = item.path("snippet").path("topLevelComment");
                        JsonNode s = top.path("snippet");
                        out.add(new YtComment(
                                top.path("id").asText(),
                                null,
                                s.path("textDisplay").asText(),
                                parseTime(s.path("publishedAt").asText(null)),
                                s.path("likeCount").asLong(0)));
                    }
                    pageToken = r.path("nextPageToken").asText(null);
                    if (pageToken == null) break;
                }
            } catch (YouTubeApiException e) {
                // Comments disabled -> 403 with commentsDisabled. Treat as empty, not fatal.
                if (e.getMessage() != null && e.getMessage().contains("commentsDisabled")) {
                    log.info("Comments disabled for video {}", videoId);
                    return List.of();
                }
                throw e;
            }
            return out;
        }

        /** Best-effort transcript. Returns null when unavailable (very common). */
        public String getTranscript(String videoId) {
            return transcriptService.fetch(videoId);
        }
    }

    private static LocalDateTime parseTime(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return OffsetDateTime.parse(iso).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}
