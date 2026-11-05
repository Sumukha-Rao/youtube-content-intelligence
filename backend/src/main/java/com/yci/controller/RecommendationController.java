package com.yci.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yci.security.CurrentUser;
import com.yci.service.RecommendationService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ideas/runs/{runId}/ideas/{ideaId}")
public class RecommendationController {
    private final RecommendationService recommendations;
    public RecommendationController(RecommendationService recommendations) { this.recommendations = recommendations; }

    @GetMapping("/related-videos")
    public JsonNode related(@CurrentUser Long userId, @PathVariable Long runId, @PathVariable int ideaId) {
        return recommendations.related(userId, runId, ideaId);
    }
    @GetMapping("/transcript-summary")
    public JsonNode get(@CurrentUser Long userId, @PathVariable Long runId, @PathVariable int ideaId) {
        return recommendations.getSummary(userId, runId, ideaId);
    }
    @PostMapping("/transcript-summary")
    public JsonNode summarize(@CurrentUser Long userId, @PathVariable Long runId, @PathVariable int ideaId) {
        return recommendations.summarize(userId, runId, ideaId);
    }
}
