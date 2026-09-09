package com.yci.service.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Best-effort transcript retrieval via YouTube's public timedtext endpoint.
 *
 * LIMITATION: Most videos do NOT expose a public transcript through this endpoint —
 * captions typically require OAuth via the Captions API, which is out of scope for a
 * public-API-key project. This service therefore returns {@code null} whenever a
 * transcript cannot be fetched, and callers must treat transcripts as optional.
 */
@Service
public class TranscriptService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public TranscriptService(WebClient.Builder builder) {
        this.client = builder.build();
    }

    public String fetch(String videoId) {
        try {
            String url = "https://video.google.com/timedtext?lang=en&v=" + videoId + "&fmt=json3";
            String raw = client.get().uri(url).retrieve()
                    .bodyToMono(String.class).timeout(Duration.ofSeconds(10)).block();
            if (raw == null || raw.isBlank()) return null;
            JsonNode root = mapper.readTree(raw);
            StringBuilder sb = new StringBuilder();
            for (JsonNode event : root.path("events")) {
                for (JsonNode seg : event.path("segs")) {
                    sb.append(seg.path("utf8").asText(""));
                }
            }
            String text = sb.toString().replaceAll("\\s+", " ").trim();
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.debug("No transcript for {}: {}", videoId, e.getMessage());
            return null;
        }
    }
}
