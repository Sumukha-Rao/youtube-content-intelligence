package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.security.CurrentUser;
import com.yci.service.CompetitorService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/competitors")
public class CompetitorController {

    private final CompetitorService competitorService;

    public CompetitorController(CompetitorService competitorService) {
        this.competitorService = competitorService;
    }

    @GetMapping
    public List<Dto.CompetitorChannelDto> list(@CurrentUser Long userId) {
        return competitorService.list(userId);
    }

    @PostMapping
    public Dto.CompetitorChannelDto add(@CurrentUser Long userId,
                                        @Valid @RequestBody Dto.CreateChannelRequest req) {
        return competitorService.add(userId, req.channelUrl());
    }

    @DeleteMapping("/{id}")
    public void remove(@CurrentUser Long userId, @PathVariable Long id) {
        competitorService.delete(userId, id);
    }
}
