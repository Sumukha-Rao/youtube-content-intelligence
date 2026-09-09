package com.yci.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Strongly-typed binding of the `app.*` configuration namespace. Every secret is
 * sourced from the environment; nothing is hardcoded.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Encrypts API keys users save in Settings. Blank = store them as typed. */
    private String secretKey = "";

    private final Youtube youtube = new Youtube();
    private final Ml ml = new Ml();
    private final Llm llm = new Llm();
    private final WebSearch webSearch = new WebSearch();
    private final Auth auth = new Auth();
    private final Notifications notifications = new Notifications();

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { this.secretKey = v; }

    public Youtube getYoutube() { return youtube; }
    public Ml getMl() { return ml; }
    public Llm getLlm() { return llm; }
    public WebSearch getWebSearch() { return webSearch; }
    public Auth getAuth() { return auth; }
    public Notifications getNotifications() { return notifications; }

    public static class Youtube {
        private String apiKey = "";
        private String apiBaseUrl = "https://www.googleapis.com/youtube/v3";
        /** Hard ceiling on comments requested per single video, regardless of settings. */
        private int maxCommentsPerVideo = 200;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getApiBaseUrl() { return apiBaseUrl; }
        public void setApiBaseUrl(String v) { this.apiBaseUrl = v; }
        public int getMaxCommentsPerVideo() { return maxCommentsPerVideo; }
        public void setMaxCommentsPerVideo(int v) { this.maxCommentsPerVideo = v; }
        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    public static class Ml {
        private String baseUrl = "http://localhost:8000";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
    }

    public static class Llm {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        /** Generous by default: a local CPU model can take minutes on a long prompt. */
        private int timeoutSeconds = 900;
        /** Prompt budget. Kept well inside a small local model's context window. */
        private int maxPromptChars = 24000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public int getMaxPromptChars() { return maxPromptChars; }
        public void setMaxPromptChars(int v) { this.maxPromptChars = v; }
        public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }
    }

    public static class WebSearch {
        /** "duckduckgo" (keyless), "searxng" (self-hosted) or "none". */
        private String provider = "duckduckgo";
        /** Required for the searxng provider, e.g. http://searxng:8080 */
        private String baseUrl = "";
        private int maxQueries = 4;
        private int resultsPerQuery = 4;
        private int timeoutSeconds = 15;
        /** When SearXNG answers with nothing, try the keyless DuckDuckGo endpoint. */
        private boolean fallbackEnabled = true;

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public int getMaxQueries() { return maxQueries; }
        public void setMaxQueries(int v) { this.maxQueries = v; }
        public int getResultsPerQuery() { return resultsPerQuery; }
        public void setResultsPerQuery(int v) { this.resultsPerQuery = v; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
        public boolean isFallbackEnabled() { return fallbackEnabled; }
        public void setFallbackEnabled(boolean v) { this.fallbackEnabled = v; }
        public boolean isEnabled() { return provider != null && !"none".equalsIgnoreCase(provider); }
    }

    public static class Auth {
        private int tokenTtlDays = 30;
        public int getTokenTtlDays() { return tokenTtlDays; }
        public void setTokenTtlDays(int v) { this.tokenTtlDays = v; }
    }

    public static class Notifications {
        /** From-address for the digest. Sending is skipped when SMTP host is unset. */
        private String from = "yci@localhost";
        private String appUrl = "http://localhost:8081";
        /** Checked hourly; each user is emailed per their own frequency. */
        private String cron = "0 0 * * * *";

        public String getFrom() { return from; }
        public void setFrom(String v) { this.from = v; }
        public String getAppUrl() { return appUrl; }
        public void setAppUrl(String v) { this.appUrl = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { this.cron = v; }
    }
}
