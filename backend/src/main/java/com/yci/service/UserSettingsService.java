package com.yci.service;

import com.yci.config.AppProperties;
import com.yci.dto.Dto;
import com.yci.entity.NotifyFrequency;
import com.yci.entity.RetrievalMode;
import com.yci.entity.UserSettings;
import com.yci.exception.BadRequestException;
import com.yci.repository.UserSettingsRepository;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.credentials.SecretCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Ingestion, notification and API preferences. Settings are created with defaults
 * on first use and then reused by every subsequent run, so the choices made during
 * setup persist (spec item 6).
 *
 * The API section is optional throughout: a blank field means "use the server's
 * configuration", which is what every account does until it saves its own key.
 */
@Service
public class UserSettingsService {

    private static final Set<String> PROVIDERS = Set.of("duckduckgo", "searxng", "none");

    private final UserSettingsRepository repo;
    private final AppProperties props;
    private final ApiConfigService apiConfig;
    private final SecretCipher cipher;

    public UserSettingsService(UserSettingsRepository repo, AppProperties props,
                               ApiConfigService apiConfig, SecretCipher cipher) {
        this.repo = repo;
        this.props = props;
        this.apiConfig = apiConfig;
        this.cipher = cipher;
    }

    @Transactional
    public UserSettings getOrCreate(Long userId) {
        return repo.findByUserId(userId).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUserId(userId);
            return repo.save(s);
        });
    }

    @Transactional
    public Dto.SettingsDto update(Long userId, Dto.SettingsDto req) {
        UserSettings s = getOrCreate(userId);

        if (req.retrievalMode() != null) {
            try {
                s.setRetrievalMode(RetrievalMode.valueOf(req.retrievalMode()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("retrievalMode must be LAST_N_VIDEOS or LAST_N_DAYS.");
            }
        }
        if (req.retrievalValue() != null) {
            int v = req.retrievalValue();
            int max = s.getRetrievalMode() == RetrievalMode.LAST_N_DAYS ? 365 : 50;
            if (v < 1 || v > max) {
                throw new BadRequestException("retrievalValue must be between 1 and " + max
                        + " for " + s.getRetrievalMode() + ".");
            }
            s.setRetrievalValue(v);
        }
        if (req.ownCommentLimit() != null) {
            s.setOwnCommentLimit(clamp(req.ownCommentLimit(), 50, 5000, "ownCommentLimit"));
        }
        if (req.competitorCommentLimit() != null) {
            s.setCompetitorCommentLimit(clamp(req.competitorCommentLimit(), 20, 2000, "competitorCommentLimit"));
        }
        if (req.webSearchEnabled() != null) s.setWebSearchEnabled(req.webSearchEnabled());

        applyApiConfig(s, req);

        if (req.notifyEnabled() != null) s.setNotifyEnabled(req.notifyEnabled());
        if (req.notifyFrequency() != null) {
            try {
                s.setNotifyFrequency(NotifyFrequency.valueOf(req.notifyFrequency()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("notifyFrequency must be DAILY or WEEKLY.");
            }
        }
        if (req.notifyEmail() != null) {
            String email = req.notifyEmail().trim();
            if (!email.isEmpty() && !email.contains("@")) {
                throw new BadRequestException("notifyEmail must be a valid email address.");
            }
            s.setNotifyEmail(email.isEmpty() ? null : email);
        }
        if (s.isNotifyEnabled() && (s.getNotifyEmail() == null || s.getNotifyEmail().isBlank())) {
            throw new BadRequestException("Add an email address to receive notifications.");
        }
        return toDto(repo.save(s));
    }

    /**
     * The user's own YouTube / LLM / search configuration.
     *
     * Keys are encrypted on the way in and never sent back out, so an empty string
     * has to mean "clear this" — there is no way for the client to echo the current
     * value back unchanged.
     */
    private void applyApiConfig(UserSettings s, Dto.SettingsDto req) {
        if (req.youtubeApiKey() != null) {
            String key = req.youtubeApiKey().trim();
            if (!key.isEmpty() && key.length() < 20) {
                throw new BadRequestException("That does not look like a YouTube Data API key.");
            }
            s.setYoutubeApiKey(key.isEmpty() ? null : cipher.encrypt(key));
        }
        if (req.llmBaseUrl() != null) {
            String url = req.llmBaseUrl().trim().replaceAll("/+$", "");
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw new BadRequestException("The LLM base URL must start with http:// or https://.");
            }
            s.setLlmBaseUrl(url.isEmpty() ? null : url);
        }
        if (req.llmApiKey() != null) {
            String key = req.llmApiKey().trim();
            s.setLlmApiKey(key.isEmpty() ? null : cipher.encrypt(key));
        }
        if (req.llmModel() != null) {
            String model = req.llmModel().trim();
            s.setLlmModel(model.isEmpty() ? null : model);
        }
        if (req.webSearchProvider() != null) {
            String provider = req.webSearchProvider().trim().toLowerCase();
            if (!provider.isEmpty() && !PROVIDERS.contains(provider)) {
                throw new BadRequestException("webSearchProvider must be duckduckgo, searxng or none.");
            }
            s.setWebSearchProvider(provider.isEmpty() ? null : provider);
        }
        if (req.searxngBaseUrl() != null) {
            String url = req.searxngBaseUrl().trim().replaceAll("/+$", "");
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw new BadRequestException("The SearXNG URL must start with http:// or https://.");
            }
            s.setSearxngBaseUrl(url.isEmpty() ? null : url);
        }
        // Choosing SearXNG with nowhere to send the query would silently produce no
        // results, so say it now rather than three minutes into a run.
        boolean searxng = "searxng".equalsIgnoreCase(s.getWebSearchProvider());
        boolean serverHasSearxng = "searxng".equalsIgnoreCase(props.getWebSearch().getProvider())
                && !props.getWebSearch().getBaseUrl().isBlank();
        if (searxng && (s.getSearxngBaseUrl() == null || s.getSearxngBaseUrl().isBlank()) && !serverHasSearxng) {
            throw new BadRequestException(
                    "Add the URL of your SearXNG instance (for example http://localhost:8888) "
                    + "to use it for web search.");
        }
    }

    @Transactional
    public Dto.SettingsDto get(Long userId) {
        return toDto(getOrCreate(userId));
    }

    public Dto.SettingsDto toDto(UserSettings s) {
        String youtubeKey = apiConfig.userYoutubeKey(s);
        String llmKey = apiConfig.userLlmKey(s);
        AppProperties.Llm serverLlm = props.getLlm();
        AppProperties.WebSearch serverSearch = props.getWebSearch();

        Dto.ApiDefaults defaults = new Dto.ApiDefaults(
                props.getYoutube().isConfigured(),
                serverLlm.getBaseUrl(), serverLlm.getModel(), serverLlm.isConfigured(),
                serverSearch.getProvider(), serverSearch.getBaseUrl(),
                cipher.isActive());

        return new Dto.SettingsDto(
                s.getRetrievalMode().name(), s.getRetrievalValue(),
                s.getOwnCommentLimit(), s.getCompetitorCommentLimit(),
                s.isWebSearchEnabled(), s.isNotifyEnabled(),
                s.getNotifyFrequency().name(), s.getNotifyEmail(), s.getLastNotifiedAt(),
                null, youtubeKey != null && !youtubeKey.isBlank(), mask(youtubeKey),
                s.getLlmBaseUrl(), s.getLlmModel(), null,
                llmKey != null && !llmKey.isBlank(), mask(llmKey),
                s.getWebSearchProvider(), s.getSearxngBaseUrl(),
                defaults);
    }

    /** Enough of a key to recognise it, never enough to use it. */
    private static String mask(String key) {
        if (key == null || key.isBlank()) return null;
        String k = key.trim();
        if (k.length() <= 8) return "••••";
        return k.substring(0, 4) + "…" + k.substring(k.length() - 4);
    }

    private static int clamp(int v, int min, int max, String field) {
        if (v < min || v > max) {
            throw new BadRequestException(field + " must be between " + min + " and " + max + ".");
        }
        return v;
    }
}
