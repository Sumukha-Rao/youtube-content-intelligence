package com.yci;

import com.yci.dto.Dto;
import com.yci.exception.BadRequestException;
import com.yci.repository.UserRepository;
import com.yci.service.AuthService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authentication behaviour against a real (H2) database. Booted directly rather
 * than with {@code @SpringBootTest} so the run does not depend on Mockito's agent
 * loading — see {@link ApplicationContextTest}.
 */
class AuthServiceTest {

    private static ConfigurableApplicationContext ctx;
    private static AuthService auth;
    private static UserRepository users;

    @BeforeAll
    static void start() {
        ctx = new SpringApplicationBuilder(YciApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .run();
        auth = ctx.getBean(AuthService.class);
        users = ctx.getBean(UserRepository.class);
    }

    @AfterAll
    static void stop() {
        if (ctx != null) ctx.close();
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void signupIssuesAWorkingToken() {
        String email = uniqueEmail();
        Dto.AuthResponse res = auth.signup(new Dto.SignupRequest("Test", email, "supersecret123"));

        assertNotNull(res.token());
        assertEquals(email, res.user().email());
        assertEquals(res.user().id(), auth.resolveUserId(res.token()));
    }

    @Test
    void passwordIsNeverStoredInPlaintext() {
        String email = uniqueEmail();
        auth.signup(new Dto.SignupRequest("Test", email, "supersecret123"));

        String stored = users.findByEmail(email).orElseThrow().getPasswordHash();
        assertNotEquals("supersecret123", stored);
        assertTrue(stored.startsWith("$2"));
    }

    @Test
    void emailIsNormalisedAndCannotBeReused() {
        String email = uniqueEmail();
        auth.signup(new Dto.SignupRequest("Test", "  " + email.toUpperCase() + " ", "supersecret123"));

        // Same address in a different case is the same account.
        assertThrows(BadRequestException.class,
                () -> auth.signup(new Dto.SignupRequest("Other", email, "supersecret123")));
        assertNotNull(auth.login(new Dto.LoginRequest(email, "supersecret123")).token());
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable() {
        String email = uniqueEmail();
        auth.signup(new Dto.SignupRequest("Test", email, "supersecret123"));

        String wrongPassword = assertThrows(BadRequestException.class,
                () -> auth.login(new Dto.LoginRequest(email, "nottherightone"))).getMessage();
        String unknownEmail = assertThrows(BadRequestException.class,
                () -> auth.login(new Dto.LoginRequest(uniqueEmail(), "supersecret123"))).getMessage();

        assertEquals(wrongPassword, unknownEmail, "must not reveal whether the account exists");
    }

    @Test
    void shortPasswordsAreRejected() {
        assertThrows(BadRequestException.class,
                () -> auth.signup(new Dto.SignupRequest("Test", uniqueEmail(), "short")));
    }

    @Test
    void logoutInvalidatesTheToken() {
        Dto.AuthResponse res = auth.signup(new Dto.SignupRequest("Test", uniqueEmail(), "supersecret123"));
        auth.logout(res.token());

        assertNull(auth.resolveUserId(res.token()));
    }

    @Test
    void tokenStopsWorkingOnceTheAccountIsGone() {
        // A token row that outlives its user (missing cascade, manual delete, a
        // restore from backup) must not keep authenticating.
        Dto.AuthResponse res = auth.signup(new Dto.SignupRequest("Test", uniqueEmail(), "supersecret123"));
        assertNotNull(auth.resolveUserId(res.token()));

        users.deleteById(res.user().id());

        assertNull(auth.resolveUserId(res.token()));
    }

    @Test
    void garbageTokensResolveToNobody() {
        assertNull(auth.resolveUserId("not-a-real-token"));
        assertNull(auth.resolveUserId(""));
    }
}
