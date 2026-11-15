package com.yci;

import com.yci.config.AppProperties;
import com.yci.entity.UserSettings;
import com.yci.service.credentials.ApiConfigService;
import com.yci.service.credentials.SecretCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiConfigCredentialIsolationTest {
    private ApiConfigService config;

    @BeforeEach void setup() {
        var props = new AppProperties();
        props.setSecretKey("test-encryption-key");
        props.getLlm().setBaseUrl("https://default.example/v1/");
        props.getLlm().setApiKey("server-only-api-key");
        props.getLlm().setModel("server-model");
        config = new ApiConfigService(props, null, new SecretCipher(props));
    }

    @Test void customEndpointCannotInheritServerKey() {
        var settings = new UserSettings();
        settings.setLlmBaseUrl("https://other.example/v1");
        var llm = config.forSettings(settings).llm();
        assertEquals("https://other.example/v1", llm.baseUrl());
        assertNull(llm.apiKey());
        assertFalse(llm.isConfigured());
    }

    @Test void customEndpointUsesOnlyItsOwnKeyAndClearingItDisablesTheConfig() {
        var settings = new UserSettings();
        settings.setLlmBaseUrl("https://other.example/v1");
        settings.setLlmApiKey(config.encrypt("users-own-api-key"));
        assertEquals("users-own-api-key", config.forSettings(settings).llm().apiKey());
        assertTrue(config.forSettings(settings).llm().isConfigured());
        settings.setLlmApiKey(null);
        assertFalse(config.forSettings(settings).llm().isConfigured());
    }

    @Test void modelOnlyAndEquivalentEndpointOverridesKeepServerDefaults() {
        var settings = new UserSettings();
        settings.setLlmModel("different-model");
        assertEquals("server-only-api-key", config.forSettings(settings).llm().apiKey());
        settings.setLlmBaseUrl("https://default.example/v1");
        assertEquals("server-only-api-key", config.forSettings(settings).llm().apiKey());
        assertEquals("different-model", config.forSettings(settings).llm().model());
    }

    @Test void sameHostWithDifferentPathIsStillADifferentEndpoint() {
        var settings = new UserSettings();
        settings.setLlmBaseUrl("https://default.example/untrusted");
        assertFalse(config.forSettings(settings).llm().isConfigured());
        assertEquals("server-only-api-key", config.serverDefaults().llm().apiKey());
    }
}
