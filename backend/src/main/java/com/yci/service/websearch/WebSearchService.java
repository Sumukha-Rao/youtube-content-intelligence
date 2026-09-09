package com.yci.service.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yci.config.AppProperties;
import com.yci.service.credentials.ApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounds the idea prompt in current information from the open web.
 *
 * A small local model driven through an OpenAI-compatible endpoint cannot be
 * trusted to run a tool-calling loop, so the search happens *here*: the backend
 * queries the strongest demand topics itself and hands the results to the model
 * as plain context.
 *
 * Providers (per user, falling back to {@code app.web-search.provider}):
 *   searxng    — the default, and the one that actually holds up. `docker compose
 *                up` starts a private SearXNG next to the app; it aggregates real
 *                engines, answers JSON, and nothing rate-limits it but the engines
 *                themselves. Point it anywhere with a base URL.
 *   duckduckgo — no API key and no container, but unofficial: DuckDuckGo throttles
 *                repeat callers and answers with a challenge page. Kept as the
 *                automatic fallback when SearXNG returns nothing.
 *   none       — disabled.
 *
 * Search is always best-effort: any failure yields an empty list and the run
 * continues on the user's own data alone.
 */
@Service
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private static final String DDG_ENDPOINT = "https://html.duckduckgo.com/html/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    /** Politeness gap between queries — hammering is what triggers the challenge page. */
    private static final long QUERY_SPACING_MS = 1200;
    private static final long RETRY_BACKOFF_MS = 2500;

    private static final Pattern RESULT_LINK = Pattern.compile(
            "<a[^>]+class=\"[^\"]*result__a[^\"]*\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern SNIPPET = Pattern.compile(
            "<a[^>]+class=\"[^\"]*result__snippet[^\"]*\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final AppProperties props;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebSearchService(AppProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.build();
    }

    public record SearchResult(String title, String snippet, String url) {}

    public record SearchBundle(String query, List<SearchResult> results) {}

    public boolean isAvailable(ApiConfig.WebSearch cfg) {
        if (!cfg.isEnabled()) return false;
        // SearXNG with nowhere to send the query is the same as being switched off.
        return !cfg.isSearxng() || (cfg.baseUrl() != null && !cfg.baseUrl().isBlank());
    }

    /**
     * Run one search per query (deduplicated, capped by {@code maxQueries}).
     * Returns only the bundles that actually produced results.
     */
    public List<SearchBundle> searchAll(ApiConfig.WebSearch cfg, List<String> queries) {
        if (!isAvailable(cfg) || queries == null || queries.isEmpty()) return List.of();

        Set<String> unique = new LinkedHashSet<>();
        for (String q : queries) {
            if (q != null && !q.isBlank()) unique.add(q.trim());
            if (unique.size() >= cfg.maxQueries()) break;
        }

        List<SearchBundle> out = new ArrayList<>();
        boolean first = true;
        for (String q : unique) {
            if (!first) pause(QUERY_SPACING_MS);
            first = false;

            List<SearchResult> results = search(cfg, q);
            if (results.isEmpty()) {
                // One retry: the block is usually transient rate limiting.
                pause(RETRY_BACKOFF_MS);
                results = search(cfg, q);
            }
            if (!results.isEmpty()) out.add(new SearchBundle(q, results));
        }

        if (out.isEmpty() && !unique.isEmpty()) {
            log.warn("Web search returned nothing for {} queries via {}. "
                     + "Ideas will be based on channel data only.", unique.size(), cfg.provider());
        } else {
            log.info("Web search ({}): {}/{} queries returned results",
                    cfg.provider(), out.size(), unique.size());
        }
        return out;
    }

    /** One query, with the DuckDuckGo fallback applied when SearXNG comes back empty. */
    private List<SearchResult> search(ApiConfig.WebSearch cfg, String query) {
        List<SearchResult> results = searchOnce(cfg, query);
        if (results.isEmpty() && cfg.isSearxng() && props.getWebSearch().isFallbackEnabled()) {
            log.debug("SearXNG returned nothing for '{}' — falling back to DuckDuckGo", query);
            results = safely(query, () -> searchDuckDuckGo(cfg, query));
        }
        return results;
    }

    private List<SearchResult> searchOnce(ApiConfig.WebSearch cfg, String query) {
        return cfg.isSearxng()
                ? safely(query, () -> searchSearxng(cfg, query))
                : safely(query, () -> searchDuckDuckGo(cfg, query));
    }

    private interface Search {
        List<SearchResult> run() throws Exception;
    }

    private List<SearchResult> safely(String query, Search search) {
        try {
            return search.run();
        } catch (Exception e) {
            log.warn("Web search for '{}' failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    // ---- SearXNG ----

    /**
     * Queries a SearXNG instance's JSON API.
     *
     * The instance must allow the {@code json} output format — SearXNG ships with
     * only {@code html} enabled, so {@code deploy/searxng/settings.yml} turns it on
     * for the bundled container. A 403 here almost always means that setting, or the
     * bot limiter, is blocking the call, so it is reported as such instead of as a
     * generic HTTP error.
     */
    private List<SearchResult> searchSearxng(ApiConfig.WebSearch cfg, String query) throws Exception {
        String uri = UriComponentsBuilder
                .fromUriString(cfg.baseUrl().replaceAll("/+$", "") + "/search")
                .queryParam("q", query)
                .queryParam("format", "json")
                .queryParam("safesearch", "0")
                .queryParam("language", "en")
                .queryParam("categories", "general")
                .build().toUriString();

        String body;
        try {
            body = client.get().uri(uri)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 403) {
                throw new IllegalStateException("SearXNG refused the request (403). Enable the `json` "
                        + "output format and disable the limiter for this caller in its settings.yml.");
            }
            if (e.getStatusCode().value() == 429) {
                throw new IllegalStateException("SearXNG is rate limiting this caller (429).");
            }
            throw e;
        }
        if (body == null || body.isBlank()) return List.of();

        List<SearchResult> out = new ArrayList<>();
        JsonNode root = mapper.readTree(body);
        for (JsonNode r : root.path("results")) {
            if (out.size() >= cfg.resultsPerQuery()) break;
            String title = r.path("title").asText("");
            if (title.isBlank()) continue;
            out.add(new SearchResult(cleanHtml(title),
                    cleanHtml(r.path("content").asText("")),
                    r.path("url").asText("")));
        }
        return out;
    }

    // ---- DuckDuckGo ----

    private List<SearchResult> searchDuckDuckGo(ApiConfig.WebSearch cfg, String query) {
        String html = client.post()
                .uri(DDG_ENDPOINT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("q", query))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .block();

        if (html == null || html.isBlank()) return List.of();
        if (!html.contains("result__a")) {
            // The challenge/interstitial page: valid HTML, zero results.
            log.debug("DuckDuckGo returned a page with no results (likely rate limited)");
            return List.of();
        }
        return parseDuckDuckGo(html, cfg.resultsPerQuery());
    }

    private List<SearchResult> parseDuckDuckGo(String html, int limit) {
        List<String> snippets = new ArrayList<>();
        Matcher sm = SNIPPET.matcher(html);
        while (sm.find()) snippets.add(cleanHtml(sm.group(1)));

        List<SearchResult> out = new ArrayList<>();
        Matcher m = RESULT_LINK.matcher(html);
        int i = 0;
        while (m.find() && out.size() < limit) {
            String title = cleanHtml(m.group(2));
            String url = decodeUrl(m.group(1));
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            i++;
            if (!title.isBlank()) out.add(new SearchResult(title, snippet, url));
        }
        return out;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** DuckDuckGo wraps result links as /l/?uddg=<percent-encoded target>. */
    private static String decodeUrl(String href) {
        try {
            int idx = href.indexOf("uddg=");
            if (idx < 0) return href.startsWith("//") ? "https:" + href : href;
            String encoded = href.substring(idx + 5);
            int amp = encoded.indexOf('&');
            if (amp > 0) encoded = encoded.substring(0, amp);
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return href;
        }
    }

    private static String cleanHtml(String s) {
        String text = TAG.matcher(s).replaceAll("");
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
