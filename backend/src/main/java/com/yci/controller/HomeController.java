package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.security.CurrentUser;
import com.yci.service.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Everything the single page needs in one call: who you are, your channel, your
 * competitors, your settings, and your most recent ideas (spec item 12).
 */
@RestController
@RequestMapping("/api")
public class HomeController {

    private final AuthService authService;
    private final ChannelService channelService;
    private final CompetitorService competitorService;
    private final UserSettingsService settingsService;
    private final IdeaService ideaService;
    private final NotificationService notificationService;

    public HomeController(AuthService authService, ChannelService channelService,
                          CompetitorService competitorService, UserSettingsService settingsService,
                          IdeaService ideaService, NotificationService notificationService) {
        this.authService = authService;
        this.channelService = channelService;
        this.competitorService = competitorService;
        this.settingsService = settingsService;
        this.ideaService = ideaService;
        this.notificationService = notificationService;
    }

    @GetMapping("/home")
    public Dto.HomeDto home(@CurrentUser Long userId) {
        Dto.ChannelDto channel = channelService.getChannelDto(userId);
        List<Dto.CompetitorChannelDto> competitors = competitorService.list(userId);
        return new Dto.HomeDto(
                authService.currentUser(userId),
                channel,
                competitors,
                channel != null || !competitors.isEmpty(),
                settingsService.get(userId),
                ideaService.latest(userId),
                ideaService.isGenerating(userId));
    }

    /** Unauthenticated liveness probe, plus what optional features are wired up. */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "emailConfigured", notificationService.isConfigured());
    }
}
