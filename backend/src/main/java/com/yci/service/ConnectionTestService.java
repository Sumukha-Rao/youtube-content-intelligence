package com.yci.service;

import com.yci.dto.Dto;
import com.yci.exception.BadRequestException;
import com.yci.service.ai.LlmClient;
import com.yci.service.credentials.ApiConfig;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.websearch.WebSearchService;
import com.yci.service.youtube.YouTubeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Backs the "Test" buttons in Settings → API access.
 *
 * A pasted key that turns out to be wrong should be visible in seconds, not after
 * a run has ingested two channels and then failed at the last step. Each test makes
 * the smallest real call the provider offers, using exactly the configuration a run
 * would use for this user.
 */
@Service
public class ConnectionTestService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionTestService.class);

    /** Nobody waits 15 minutes on a test button, whatever the run timeout is. */
    private static final int TEST_TIMEOUT_SECONDS = 45;

    private final ApiConfigService apiConfig;
    private final YouTubeService youTube;
    private final LlmClient llm;
    private final WebSearchService webSearch;

    public ConnectionTestService(ApiConfigService apiConfig, YouTubeService youTube,
                                 LlmClient llm, WebSearchService webSearch) {
        this.apiConfig = apiConfig;
        this.youTube = youTube;
        this.llm = llm;
        this.webSearch = webSearch;
    }

    public Dto.ConnectionTestResult test(Long userId, String target) {
        ApiConfig api = apiConfig.forUser(userId);
        return switch (target == null ? "" : target.toLowerCase()) {
            case "youtube" -> testYouTube(api);
            case "llm" -> testLlm(api);
            case "websearch" -> testWebSearch(api);
            default -> throw new BadRequestException("Unknown test target: " + target);
        };
    }

    private Dto.ConnectionTestResult testYouTube(ApiConfig api) {
        String source = api.youtube().source();
        if (!api.youtube().isConfigured()) {
            return new Dto.ConnectionTestResult(false, "No YouTube API key to test.",
                    "Paste a key above, or set YOUTUBE_API_KEY on the server.");
        }
        try {
            youTube.session(api.youtube().apiKey()).ping();
            return new Dto.ConnectionTestResult(true, "YouTube API is reachable.", "Using " + source + ".");
        } catch (Exception e) {
            log.info("YouTube key test failed: {}", e.getMessage());
            return new Dto.ConnectionTestResult(false, "YouTube rejected the key.", e.getMessage());
        }
    }

    private Dto.ConnectionTestResult testLlm(ApiConfig api) {
        if (!api.llm().isConfigured()) {
            return new Dto.ConnectionTestResult(false, "No model to test.",
                    "A base URL, key and model name are all needed.");
        }
        try {
            String reply = llm.complete(api.llm().withTimeout(TEST_TIMEOUT_SECONDS),
                    "You are a connection test. Answer with exactly: OK",
                    "Reply with OK.");
            String trimmed = reply.length() > 80 ? reply.substring(0, 80) + "…" : reply;
            return new Dto.ConnectionTestResult(true,
                    api.llm().model() + " answered.",
                    "Using " + api.llm().source() + ". It said: " + trimmed);
        } catch (Exception e) {
            log.info("LLM test failed: {}", e.getMessage());
            return new Dto.ConnectionTestResult(false, "The model did not answer.", e.getMessage());
        }
    }

    private Dto.ConnectionTestResult testWebSearch(ApiConfig api) {
        if (!api.webSearch().isEnabled()) {
            return new Dto.ConnectionTestResult(false, "Web search is switched off.",
                    "Pick DuckDuckGo or SearXNG to use it.");
        }
        if (!webSearch.isAvailable(api.webSearch())) {
            return new Dto.ConnectionTestResult(false, "SearXNG has no address.",
                    "Add the URL of your instance, for example http://localhost:8888.");
        }
        List<WebSearchService.SearchBundle> bundles =
                webSearch.searchAll(api.webSearch(), List.of("youtube content strategy 2026"));
        if (bundles.isEmpty()) {
            return new Dto.ConnectionTestResult(false, "The search returned nothing.",
                    api.webSearch().isSearxng()
                            ? "Check that the instance allows the json format and is reachable from the backend."
                            : "DuckDuckGo throttles repeat callers. Run SearXNG for reliable results.");
        }
        int count = bundles.get(0).results().size();
        return new Dto.ConnectionTestResult(true, count + " results came back.",
                "Provider: " + api.webSearch().provider()
                + (api.webSearch().isSearxng() ? " at " + api.webSearch().baseUrl() : "")
                + ". Top hit: " + bundles.get(0).results().get(0).title());
    }
}
