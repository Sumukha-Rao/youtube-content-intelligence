package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.entity.IdeaRun;
import com.yci.entity.RunStatus;
import com.yci.exception.BadRequestException;
import com.yci.exception.ResourceNotFoundException;
import com.yci.repository.IdeaRunRepository;
import com.yci.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import com.yci.security.CurrentUser;
import com.yci.service.IdeaService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Idea generation. {@code POST /generate} queues a run and returns immediately;
 * the client polls {@code /runs/{id}} until it reaches COMPLETED or FAILED.
 */
@RestController
@RequestMapping("/api/ideas")
public class IdeaController {

    private final IdeaService ideaService;

    private final IdeaRunRepository runs;
    private final NotificationService notifications;

    public IdeaController(IdeaService ideaService, IdeaRunRepository runs, NotificationService notifications) {
        this.ideaService = ideaService;
        this.runs = runs;
        this.notifications = notifications;
    }

    @PostMapping("/runs/{id}/email")
    public ResponseEntity<Map<String, String>> email(@CurrentUser Long userId, @PathVariable Long id,
                                                    @Valid @RequestBody Dto.SendIdeasEmailRequest request) {
        IdeaRun run = runs.findById(id).filter(r -> r.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Idea run not found: " + id));
        if (run.getStatus() != RunStatus.COMPLETED || run.getResult() == null || run.getResult().isBlank()) {
            throw new BadRequestException("Only completed runs with generated ideas can be emailed.");
        }
        if (!notifications.isConfigured()) {
            return ResponseEntity.status(503).body(Map.of("message",
                    "Email delivery is not configured. Configure SMTP on the server, then try again."));
        }
        if (!notifications.sendDigest(request.email(), run)) {
            return ResponseEntity.status(502).body(Map.of("message",
                    "Could not send the email. Check the server mail configuration and try again."));
        }
        return ResponseEntity.ok(Map.of("message", "Ideas sent to " + request.email() + "."));
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
