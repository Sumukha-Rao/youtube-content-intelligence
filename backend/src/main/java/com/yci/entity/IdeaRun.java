package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One "get ideas" execution: doubles as the async job record the frontend polls
 * and as the stored result. The full prompt is persisted so the user can inspect
 * exactly what the model was given ("View full prompt").
 */
@Entity
@Table(name = "idea_runs")
@Getter
@Setter
public class IdeaRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status = RunStatus.QUEUED;

    @Column(nullable = false)
    private int progress = 0;

    @Column(length = 512)
    private String message;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String prompt;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String result;

    @Column(length = 128)
    private String model;

    @Column(name = "web_search_used", nullable = false)
    private boolean webSearchUsed = false;

    /** True when the digest emailer has already sent this run. */
    @Column(name = "notified", nullable = false)
    private boolean notified = false;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;
}
