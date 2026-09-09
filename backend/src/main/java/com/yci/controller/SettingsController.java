package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.security.CurrentUser;
import com.yci.service.ConnectionTestService;
import com.yci.service.UserSettingsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserSettingsService settingsService;
    private final ConnectionTestService connectionTests;

    public SettingsController(UserSettingsService settingsService, ConnectionTestService connectionTests) {
        this.settingsService = settingsService;
        this.connectionTests = connectionTests;
    }

    @GetMapping
    public Dto.SettingsDto get(@CurrentUser Long userId) {
        return settingsService.get(userId);
    }

    @PutMapping
    public Dto.SettingsDto update(@CurrentUser Long userId, @RequestBody Dto.SettingsDto req) {
        return settingsService.update(userId, req);
    }

    /**
     * Checks one of {@code youtube}, {@code llm} or {@code websearch} against the
     * configuration currently saved for this user. Always answers 200 with an
     * {@code ok} flag: a rejected key is an answer, not a server error.
     */
    @PostMapping("/test/{target}")
    public Dto.ConnectionTestResult test(@CurrentUser Long userId, @PathVariable String target) {
        return connectionTests.test(userId, target);
    }
}
