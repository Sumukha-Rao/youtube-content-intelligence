package com.yci.service.credentials;

/**
 * The credentials and endpoints one run actually uses.
 *
 * Every field is resolved per user: whatever they saved in Settings, falling back
 * to the server's environment when they saved nothing. {@code userProvided} says
 * which of the two won, so errors can point at the right place to fix them.
 */
public record ApiConfig(Youtube youtube, Llm llm, WebSearch webSearch) {

    public record Youtube(String apiKey, boolean userProvided) {
        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
        /** Where the user should go to fix a missing or rejected key. */
        public String source() {
            return userProvided ? "the key saved in Settings" : "the server's YOUTUBE_API_KEY";
        }
    }

    public record Llm(String baseUrl, String apiKey, String model,
                      int timeoutSeconds, int maxPromptChars, boolean userProvided) {
        public boolean isConfigured() {
            return notBlank(baseUrl) && notBlank(apiKey) && notBlank(model);
        }
        public String source() {
            return userProvided ? "the model saved in Settings" : "the server's LLM_* environment";
        }
        /** Same endpoint, shorter patience — used by the "Test" button in Settings. */
        public Llm withTimeout(int seconds) {
            return new Llm(baseUrl, apiKey, model, seconds, maxPromptChars, userProvided);
        }
    }

    public record WebSearch(String provider, String baseUrl, int maxQueries,
                            int resultsPerQuery, int timeoutSeconds, boolean userProvided) {
        public boolean isEnabled() {
            return provider != null && !"none".equalsIgnoreCase(provider);
        }
        public boolean isSearxng() {
            return "searxng".equalsIgnoreCase(provider);
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
