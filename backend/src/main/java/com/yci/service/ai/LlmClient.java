package com.yci.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yci.exception.LlmServiceException;
import com.yci.service.credentials.ApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls any OpenAI-compatible {@code /chat/completions} endpoint (OpenAI,
 * OpenRouter, Together, a local Ollama/vLLM/llama.cpp server, ...).
 *
 * The endpoint is passed in per call rather than fixed at startup, because each
 * user may point at their own provider and model from the Settings page; the
 * server's {@code LLM_*} environment is simply the default that resolution falls
 * back to.
 *
 * No {@code max_tokens} is sent: a truncated answer is worse than a slow one, and
 * a local CPU model needs room to finish. The timeout is correspondingly generous
 * and configurable, because generation here runs inside an async job that the
 * frontend polls rather than in a request thread.
 */
@Service
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(WebClient.Builder builder) {
        // No base URL: every request targets whichever provider the caller resolved.
        this.client = builder.build();
    }

    /** Send a system + user message pair and return the assistant's text. */
    public String complete(ApiConfig.Llm cfg, String system, String user) {
        if (!cfg.isConfigured()) {
            throw new LlmServiceException(
                    "No language model is configured. Add your provider URL, key and model under "
                    + "Settings → API access, or set LLM_BASE_URL, LLM_API_KEY and LLM_MODEL on the server.");
        }
        Map<String, Object> body = Map.of(
                "model", cfg.model(),
                "temperature", 0.8,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));

        long started = System.currentTimeMillis();
        String raw;
        try {
            raw = client.post().uri(endpoint(cfg))
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                    .block();
        } catch (Exception e) {
            throw new LlmServiceException("The model did not respond: " + e.getMessage()
                    + " (model=" + cfg.model()
                    + ", endpoint=" + cfg.baseUrl()
                    + ", timeout=" + cfg.timeoutSeconds() + "s)", e);
        }

        try {
            JsonNode node = mapper.readTree(raw);
            JsonNode message = node.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            if (content.isBlank()) {
                // Reasoning models put their answer in "reasoning" when they run out
                // of room for the final message. Better to show that than nothing.
                content = message.path("reasoning").asText("");
            }
            if (content.isBlank()) {
                String error = node.path("error").path("message").asText("");
                throw new LlmServiceException(error.isBlank()
                        ? "The model returned an empty response."
                        : "The provider rejected the request: " + error);
            }
            log.info("LLM completion in {} ms ({} chars) using {} at {}",
                    System.currentTimeMillis() - started, content.length(), cfg.model(), cfg.baseUrl());
            return content.trim();
        } catch (LlmServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmServiceException("Could not read the model response: " + e.getMessage(), e);
        }
    }

    /** Tolerates a base URL given with or without a trailing slash. */
    private static String endpoint(ApiConfig.Llm cfg) {
        return cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";
    }
}
