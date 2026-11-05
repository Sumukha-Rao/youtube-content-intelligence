package com.yci.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yci.security.CurrentUser;
import com.yci.service.CompetitorDiscoveryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/channels/{id}")
public class CompetitorSuggestionController {
    private final CompetitorDiscoveryService discovery;
    public CompetitorSuggestionController(CompetitorDiscoveryService discovery) { this.discovery = discovery; }

    @GetMapping("/suggestions")
    public JsonNode get(@CurrentUser Long userId, @PathVariable Long id) { return discovery.get(userId, id); }

    @PostMapping("/suggest-competitors")
    public JsonNode suggest(@CurrentUser Long userId, @PathVariable Long id) { return discovery.suggest(userId, id); }
}
