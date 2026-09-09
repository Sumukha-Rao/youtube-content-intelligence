package com.yci;

import com.yci.controller.IdeaController;
import com.yci.security.AuthInterceptor;
import com.yci.service.AuthService;
import com.yci.service.IdeaService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wiring smoke test. Every other test here is context-free, so a bean cycle —
 * such as WebConfig -> AuthInterceptor -> AuthService -> PasswordEncoder(WebConfig)
 * — would otherwise only surface at runtime.
 *
 * The context is booted directly rather than with {@code @SpringBootTest} because
 * Spring Boot's test listeners initialise Mockito, whose agent cannot load in
 * every JDK/container combination; that is an infrastructure detail and should
 * not decide whether this check can run.
 */
class ApplicationContextTest {

    private ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(YciApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .run();
    }

    @Test
    void contextLoadsWithEveryBeanWired() {
        try (ConfigurableApplicationContext ctx = boot()) {
            assertNotNull(ctx.getBean(AuthService.class));
            assertNotNull(ctx.getBean(AuthInterceptor.class));
            assertNotNull(ctx.getBean(IdeaService.class));
            assertNotNull(ctx.getBean(IdeaController.class));
            assertNotNull(ctx.getBean(PasswordEncoder.class));
        }
    }

    @Test
    void passwordsAreHashedNotStored() {
        try (ConfigurableApplicationContext ctx = boot()) {
            PasswordEncoder encoder = ctx.getBean(PasswordEncoder.class);
            String hash = encoder.encode("correct horse battery staple");

            assertTrue(hash.startsWith("$2"), "expected a BCrypt hash");
            assertNotEquals("correct horse battery staple", hash);
            assertTrue(encoder.matches("correct horse battery staple", hash));
            assertFalse(encoder.matches("wrong password", hash));
        }
    }
}
