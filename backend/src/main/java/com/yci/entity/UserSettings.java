package com.yci.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Per-user ingestion and notification preferences. Created with defaults the
 * first time a user is seen, then edited from the settings panel and reused by
 * every later run (including the scheduled digest).
 */
@Entity
@Table(name = "user_settings")
@Getter
@Setter
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    // ---- how much history to pull ----
    @Enumerated(EnumType.STRING)
    @Column(name = "retrieval_mode", nullable = false, length = 24)
    private RetrievalMode retrievalMode = RetrievalMode.LAST_N_VIDEOS;

    /** Interpreted as a video count or a day count depending on {@link #retrievalMode}. */
    @Column(name = "retrieval_value", nullable = false)
    private Integer retrievalValue = 15;

    /** Total comments pulled from the creator's own channel (spec: top 1000). */
    @Column(name = "own_comment_limit", nullable = false)
    private Integer ownCommentLimit = 1000;

    /** Comments pulled per competitor channel (spec: top 200). */
    @Column(name = "competitor_comment_limit", nullable = false)
    private Integer competitorCommentLimit = 200;

    /**
     * Off by default. Search results are the one input the creator did not choose,
     * and a weak model tends to follow them away from the audience evidence — which
     * is the whole point of the run. Opt in per account.
     */
    @Column(name = "web_search_enabled", nullable = false)
    private boolean webSearchEnabled = false;

    // ---- the user's own API credentials ----
    // All of these are optional: blank means "use whatever the server is
    // configured with". The two key columns hold the output of SecretCipher, so
    // they are ciphertext whenever APP_SECRET_KEY is set.

    @Column(name = "youtube_api_key", length = 512)
    private String youtubeApiKey;

    @Column(name = "llm_base_url", length = 512)
    private String llmBaseUrl;

    @Column(name = "llm_api_key", length = 512)
    private String llmApiKey;

    @Column(name = "llm_model", length = 128)
    private String llmModel;

    /** duckduckgo | searxng | none. Blank falls back to the server's provider. */
    @Column(name = "web_search_provider", length = 24)
    private String webSearchProvider;

    @Column(name = "searxng_base_url", length = 512)
    private String searxngBaseUrl;

    // ---- email digest ----
    @Column(name = "notify_enabled", nullable = false)
    private boolean notifyEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_frequency", nullable = false, length = 16)
    private NotifyFrequency notifyFrequency = NotifyFrequency.WEEKLY;

    @Column(name = "notify_email", length = 255)
    private String notifyEmail;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
