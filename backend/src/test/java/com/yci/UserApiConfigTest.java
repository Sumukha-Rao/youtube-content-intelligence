package com.yci;

import com.yci.dto.Dto;
import com.yci.entity.UserSettings;
import com.yci.exception.BadRequestException;
import com.yci.repository.UserSettingsRepository;
import com.yci.service.AuthService;
import com.yci.service.UserSettingsService;
import com.yci.service.credentials.ApiConfig;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.credentials.SecretCipher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-user API configuration: a user's own keys must win over the server's,
 * an emptied field must fall back, and a saved key must never come back out of
 * the API or sit in the database in the clear.
 *
 * Booted directly rather than with {@code @SpringBootTest} for the reason given
 * in {@link ApplicationContextTest}.
 */
class UserApiConfigTest {

    private static ConfigurableApplicationContext ctx;
    private static UserSettingsService settings;
    private static ApiConfigService apiConfig;
    private static UserSettingsRepository repo;
    private static AuthService auth;

    @BeforeAll
    static void start() {
        ctx = new SpringApplicationBuilder(YciApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .run();
        settings = ctx.getBean(UserSettingsService.class);
        apiConfig = ctx.getBean(ApiConfigService.class);
        repo = ctx.getBean(UserSettingsRepository.class);
        auth = ctx.getBean(AuthService.class);
    }

    @AfterAll
    static void stop() {
        if (ctx != null) ctx.close();
    }

    /** A real account, because settings rows are keyed by user id. */
    private Long newUser() {
        return auth.signup(new Dto.SignupRequest(
                "Test", "api-" + UUID.randomUUID() + "@example.com", "password123")).user().id();
    }

    private Dto.SettingsDto apiUpdate(String youtubeKey, String llmBaseUrl, String llmModel,
                                      String llmKey, String provider, String searxngUrl) {
        return new Dto.SettingsDto(null, null, null, null, null, null, null, null, null,
                youtubeKey, null, null, llmBaseUrl, llmModel, llmKey, null, null,
                provider, searxngUrl, null);
    }

    @Test
    void serverConfigurationIsTheDefault() {
        ApiConfig api = apiConfig.forUser(newUser());

        // From src/test/resources/application.yml.
        assertEquals("http://localhost:9999/v1", api.llm().baseUrl());
        assertEquals("test-model", api.llm().model());
        assertFalse(api.llm().userProvided());
        assertFalse(api.youtube().userProvided());
    }

    @Test
    void userKeysWinOverTheServerAndClearBackToIt() {
        Long userId = newUser();
        settings.update(userId, apiUpdate("AIza-a-users-own-youtube-key", "https://api.openai.com/v1",
                "gpt-4o-mini", "sk-users-own-llm-key", null, null));

        ApiConfig mine = apiConfig.forUser(userId);
        assertEquals("AIza-a-users-own-youtube-key", mine.youtube().apiKey());
        assertTrue(mine.youtube().userProvided());
        assertEquals("https://api.openai.com/v1", mine.llm().baseUrl());
        assertEquals("gpt-4o-mini", mine.llm().model());
        assertEquals("sk-users-own-llm-key", mine.llm().apiKey());
        assertTrue(mine.llm().isConfigured());

        // Another account is unaffected — one user's key is never spent by another.
        assertFalse(apiConfig.forUser(newUser()).youtube().isConfigured());

        // An empty string means "clear it"; null would have meant "leave it alone".
        settings.update(userId, apiUpdate("", "", "", "", null, null));
        ApiConfig back = apiConfig.forUser(userId);
        assertFalse(back.youtube().userProvided());
        assertEquals("test-model", back.llm().model());
    }

    @Test
    void savedKeysAreNeverReadableThroughTheApi() {
        Long userId = newUser();
        settings.update(userId, apiUpdate("AIza-secret-youtube-key-value", null, null,
                "sk-secret-llm-key-value", null, null));

        Dto.SettingsDto dto = settings.get(userId);
        assertNull(dto.youtubeApiKey(), "the key itself must not be serialised back");
        assertNull(dto.llmApiKey());
        assertTrue(dto.youtubeApiKeySet());
        assertTrue(dto.llmApiKeySet());
        assertEquals("AIza…alue", dto.youtubeApiKeyPreview());
        assertFalse(dto.youtubeApiKeyPreview().contains("secret"));
    }

    @Test
    void keysAreEncryptedAtRest() {
        Long userId = newUser();
        settings.update(userId, apiUpdate("AIza-encrypt-me-please-key", null, null, null, null, null));

        UserSettings stored = repo.findByUserId(userId).orElseThrow();
        assertTrue(ctx.getBean(SecretCipher.class).isActive(), "the test profile sets app.secret-key");
        assertTrue(stored.getYoutubeApiKey().startsWith("enc:"));
        assertFalse(stored.getYoutubeApiKey().contains("AIza-encrypt-me-please-key"));
        assertEquals("AIza-encrypt-me-please-key", apiConfig.forUser(userId).youtube().apiKey());
    }

    @Test
    void searxngNeedsAnAddressAndProvidersAreValidated() {
        Long userId = newUser();

        // The server's provider is "none" in tests, so picking SearXNG with no URL
        // would search nowhere at all.
        assertThrows(BadRequestException.class,
                () -> settings.update(userId, apiUpdate(null, null, null, null, "searxng", "")));
        assertThrows(BadRequestException.class,
                () -> settings.update(userId, apiUpdate(null, null, null, null, "google", null)));
        assertThrows(BadRequestException.class,
                () -> settings.update(userId, apiUpdate(null, "not-a-url", null, null, null, null)));

        settings.update(userId, apiUpdate(null, null, null, null, "searxng", "http://searxng:8080/"));
        ApiConfig api = apiConfig.forUser(userId);
        assertTrue(api.webSearch().isSearxng());
        assertEquals("http://searxng:8080", api.webSearch().baseUrl(), "trailing slash trimmed");
    }
}
