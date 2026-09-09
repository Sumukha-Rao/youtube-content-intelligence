package com.yci.service.credentials;

import com.yci.config.AppProperties;
import com.yci.entity.UserSettings;
import com.yci.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;

/**
 * Merges a user's own API configuration over the server's.
 *
 * The server environment stays the default so the app works out of the box, and
 * anything a user fills in on the Settings page wins for their runs only — one
 * account's OpenAI bill or YouTube quota is never spent by another.
 */
@Service
public class ApiConfigService {

    private final AppProperties props;
    private final UserSettingsRepository settingsRepo;
    private final SecretCipher cipher;

    public ApiConfigService(AppProperties props, UserSettingsRepository settingsRepo, SecretCipher cipher) {
        this.props = props;
        this.settingsRepo = settingsRepo;
        this.cipher = cipher;
    }

    public ApiConfig forUser(Long userId) {
        return forSettings(settingsRepo.findByUserId(userId).orElse(null));
    }

    public ApiConfig forSettings(UserSettings s) {
        return new ApiConfig(youtube(s), llm(s), webSearch(s));
    }

    /** What the server itself is configured with — shown in Settings as the fallback. */
    public ApiConfig serverDefaults() {
        return forSettings(null);
    }

    /** Decrypted YouTube key exactly as the user typed it (for the masked preview). */
    public String userYoutubeKey(UserSettings s) {
        return s == null ? null : cipher.decrypt(s.getYoutubeApiKey());
    }

    public String userLlmKey(UserSettings s) {
        return s == null ? null : cipher.decrypt(s.getLlmApiKey());
    }

    public String encrypt(String plain) {
        return cipher.encrypt(plain);
    }

    // ---- per-section merge ----

    private ApiConfig.Youtube youtube(UserSettings s) {
        String own = userYoutubeKey(s);
        return notBlank(own)
                ? new ApiConfig.Youtube(own.trim(), true)
                : new ApiConfig.Youtube(props.getYoutube().getApiKey(), false);
    }

    private ApiConfig.Llm llm(UserSettings s) {
        AppProperties.Llm server = props.getLlm();
        String ownKey = userLlmKey(s);
        String ownBase = s == null ? null : s.getLlmBaseUrl();
        String ownModel = s == null ? null : s.getLlmModel();

        // Any one of the three being set marks the LLM as the user's own: a user may
        // point at a local Ollama (no real key) or just override the model name.
        boolean userProvided = notBlank(ownKey) || notBlank(ownBase) || notBlank(ownModel);

        return new ApiConfig.Llm(
                notBlank(ownBase) ? ownBase.trim() : server.getBaseUrl(),
                notBlank(ownKey) ? ownKey.trim() : server.getApiKey(),
                notBlank(ownModel) ? ownModel.trim() : server.getModel(),
                server.getTimeoutSeconds(),
                server.getMaxPromptChars(),
                userProvided);
    }

    private ApiConfig.WebSearch webSearch(UserSettings s) {
        AppProperties.WebSearch server = props.getWebSearch();
        String ownProvider = s == null ? null : s.getWebSearchProvider();
        String ownBase = s == null ? null : s.getSearxngBaseUrl();

        String provider = notBlank(ownProvider) ? ownProvider.trim() : server.getProvider();
        String baseUrl = notBlank(ownBase) ? ownBase.trim() : server.getBaseUrl();

        return new ApiConfig.WebSearch(provider, baseUrl, server.getMaxQueries(),
                server.getResultsPerQuery(), server.getTimeoutSeconds(),
                notBlank(ownProvider) || notBlank(ownBase));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
