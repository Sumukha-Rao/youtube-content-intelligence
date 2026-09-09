package com.yci.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;

/**
 * All public API DTOs as nested records. YouTube / ML / LLM raw responses are
 * never exposed — only these types are.
 */
public final class Dto {
    private Dto() {}

    // ---- auth ----
    public record SignupRequest(String name,
                                @NotBlank(message = "email is required") String email,
                                @NotBlank(message = "password is required") String password) {}

    public record LoginRequest(@NotBlank(message = "email is required") String email,
                               @NotBlank(message = "password is required") String password) {}

    public record UserDto(Long id, String name, String email) {}

    public record AuthResponse(String token, UserDto user) {}

    // ---- channels ----
    public record CreateChannelRequest(
            @NotBlank(message = "channelUrl is required") String channelUrl) {}

    public record ChannelDto(Long id, String youtubeChannelId, String channelName, String channelUrl,
                             String description, Long subscriberCount, Long videoCount,
                             LocalDateTime lastSyncedAt, long storedVideos, long storedComments) {}

    public record CompetitorChannelDto(Long id, String youtubeChannelId, String channelName,
                                       String channelUrl, LocalDateTime lastCheckedAt,
                                       long storedVideos, long storedComments) {}

    // ---- settings ----

    /**
     * Read and write shape of the settings panel.
     *
     * The two key fields are write-only: a request carries the new key, a response
     * always carries {@code null} for them and describes what is stored through the
     * {@code ...Set} / {@code ...Preview} pair instead, so a saved secret can never
     * be read back out of the API.
     *
     * Update semantics for every optional field: {@code null} leaves it unchanged,
     * {@code ""} clears it back to the server default, anything else replaces it.
     */
    public record SettingsDto(String retrievalMode, Integer retrievalValue,
                              Integer ownCommentLimit, Integer competitorCommentLimit,
                              Boolean webSearchEnabled, Boolean notifyEnabled,
                              String notifyFrequency, String notifyEmail,
                              LocalDateTime lastNotifiedAt,
                              // --- your own API access ---
                              String youtubeApiKey,
                              Boolean youtubeApiKeySet, String youtubeApiKeyPreview,
                              String llmBaseUrl, String llmModel, String llmApiKey,
                              Boolean llmApiKeySet, String llmApiKeyPreview,
                              String webSearchProvider, String searxngBaseUrl,
                              ApiDefaults defaults) {}

    /** What the server falls back to when a field above is left empty. */
    public record ApiDefaults(boolean youtubeConfigured, String llmBaseUrl, String llmModel,
                              boolean llmConfigured, String webSearchProvider,
                              String webSearchBaseUrl, boolean secretsEncrypted) {}

    /** Result of the "Test" buttons in Settings. */
    public record ConnectionTestResult(boolean ok, String message, String detail) {}

    // ---- idea generation ----
    public record JobResponse(Long runId, String status) {}

    public record IdeaRunDto(Long id, String status, int progress, String message,
                             String result, String prompt, String model, boolean webSearchUsed,
                             LocalDateTime createdAt, LocalDateTime completedAt, String errorMessage) {}

    // ---- home ----
    public record HomeDto(UserDto user, ChannelDto channel, List<CompetitorChannelDto> competitors,
                          boolean setupComplete, SettingsDto settings, IdeaRunDto latestRun,
                          boolean generating) {}
}
