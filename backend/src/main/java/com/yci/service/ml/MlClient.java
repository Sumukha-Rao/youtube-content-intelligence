package com.yci.service.ml;

import com.yci.config.AppProperties;
import com.yci.exception.MlServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin typed client for the Python FastAPI ML service. All NLP work happens in
 * Python; this class only marshals requests and responses over HTTP.
 */
@Service
public class MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlClient.class);

    private final WebClient client;

    public MlClient(WebClient.Builder builder, AppProperties props) {
        this.client = builder.baseUrl(props.getMl().getBaseUrl()).build();
    }

    // ---- response shapes ----
    public record DemandTopic(String label, double demand_score, int request_count,
                              int mention_count, List<String> examples, String source) {}

    public record MentionedTerm(String term, int mentions, double score) {}

    public record DemandResponse(List<DemandTopic> demand_topics, List<MentionedTerm> mentioned_terms,
                                 int comments_analyzed, int requests_found, String backend) {

        public static DemandResponse empty() {
            return new DemandResponse(List.of(), List.of(), 0, 0, "empty");
        }
    }

    /**
     * Rank what an audience is asking to see next. Explicit requests
     * ("make a video on X") dominate; frequently discussed terms fill in behind.
     */
    public DemandResponse demand(List<String> comments, List<Long> likeCounts, int maxTopics) {
        if (comments == null || comments.isEmpty()) return DemandResponse.empty();
        Map<String, Object> body = Map.of(
                "comments", comments,
                "like_counts", likeCounts == null ? List.of() : likeCounts,
                "max_topics", maxTopics);
        try {
            return client.post().uri("/ml/demand")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(DemandResponse.class)
                    .timeout(Duration.ofSeconds(180))
                    .block();
        } catch (Exception e) {
            throw new MlServiceException("ML demand analysis failed: " + e.getMessage(), e);
        }
    }

    public boolean healthy() {
        try {
            client.get().uri("/health").retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(5)).block();
            return true;
        } catch (Exception e) {
            log.warn("ML service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
