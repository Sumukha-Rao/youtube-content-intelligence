package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.entity.IdeaRun;
import com.yci.security.CurrentUser;
import com.yci.service.IdeaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Idea generation. {@code POST /generate} queues a run and returns immediately;
 * the client polls {@code /runs/{id}} until it reaches COMPLETED or FAILED.
 */
@RestController
@RequestMapping("/api/ideas")
public class IdeaController {

    private final IdeaService ideaService;

    public IdeaController(IdeaService ideaService) {
        this.ideaService = ideaService;
    }

    @PostMapping("/generate")
    public Dto.JobResponse generate(@CurrentUser Long userId) {
        IdeaRun run = ideaService.createRun(userId);
        ideaService.generateAsync(run.getId(), userId);
        return new Dto.JobResponse(run.getId(), run.getStatus().name());
    }

    @GetMapping("/runs/{id}")
    public Dto.IdeaRunDto run(@CurrentUser Long userId, @PathVariable Long id) {
        return ideaService.getRun(userId, id);
    }

    @GetMapping("/latest")
    public Dto.IdeaRunDto latest(@CurrentUser Long userId) {
        return ideaService.latest(userId);
    }

    @GetMapping("/history")
    public List<Dto.IdeaRunDto> history(@CurrentUser Long userId) {
        return ideaService.history(userId);
    }
}
