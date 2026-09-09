package com.yci.service;

import com.yci.config.AppProperties;
import com.yci.dto.Dto;
import com.yci.entity.AuthToken;
import com.yci.entity.User;
import com.yci.exception.BadRequestException;
import com.yci.exception.ResourceNotFoundException;
import com.yci.repository.AuthTokenRepository;
import com.yci.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;

/**
 * Email + password authentication with server-side opaque bearer tokens.
 *
 * Passwords are stored only as BCrypt hashes and never logged. A login failure
 * returns the same message whether the email is unknown or the password is
 * wrong, so the endpoint cannot be used to enumerate accounts.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepo;
    private final AuthTokenRepository tokenRepo;
    private final UserSettingsService settingsService;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public AuthService(UserRepository userRepo, AuthTokenRepository tokenRepo,
                       UserSettingsService settingsService, PasswordEncoder encoder,
                       AppProperties props) {
        this.userRepo = userRepo;
        this.tokenRepo = tokenRepo;
        this.settingsService = settingsService;
        this.encoder = encoder;
        this.props = props;
    }

    @Transactional
    public Dto.AuthResponse signup(Dto.SignupRequest req) {
        String email = normalizeEmail(req.email());
        if (req.password() == null || req.password().length() < 8) {
            throw new BadRequestException("Password must be at least 8 characters.");
        }
        if (userRepo.findByEmail(email).isPresent()) {
            throw new BadRequestException("An account with that email already exists.");
        }
        User u = new User();
        u.setName(req.name() == null || req.name().isBlank() ? email.split("@")[0] : req.name().trim());
        u.setEmail(email);
        u.setPasswordHash(encoder.encode(req.password()));
        u = userRepo.save(u);

        // Give every new account its default ingestion + notification settings.
        settingsService.getOrCreate(u.getId());
        log.info("New account created: user {}", u.getId());
        return issue(u);
    }

    @Transactional
    public Dto.AuthResponse login(Dto.LoginRequest req) {
        String email = normalizeEmail(req.email());
        User u = userRepo.findByEmail(email)
                .filter(x -> req.password() != null && encoder.matches(req.password(), x.getPasswordHash()))
                .orElseThrow(() -> new BadRequestException("Incorrect email or password."));
        settingsService.getOrCreate(u.getId());
        return issue(u);
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) tokenRepo.deleteByToken(token);
    }

    /**
     * Returns the user id for a valid, unexpired token, else null.
     *
     * The account is re-checked on every request rather than trusted from the
     * token row alone: a token whose user has been deleted must stop working even
     * if the row outlived them (a missing cascade, a manual DELETE, a restore).
     */
    @Transactional(readOnly = true)
    public Long resolveUserId(String token) {
        return tokenRepo.findByToken(token)
                .filter(t -> t.getExpiresAt().isAfter(LocalDateTime.now()))
                .map(AuthToken::getUserId)
                .filter(userRepo::existsById)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Dto.UserDto currentUser(Long userId) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return new Dto.UserDto(u.getId(), u.getName(), u.getEmail());
    }

    private Dto.AuthResponse issue(User u) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        AuthToken t = new AuthToken();
        t.setUserId(u.getId());
        t.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
        t.setExpiresAt(LocalDateTime.now().plusDays(props.getAuth().getTokenTtlDays()));
        tokenRepo.save(t);
        return new Dto.AuthResponse(t.getToken(), new Dto.UserDto(u.getId(), u.getName(), u.getEmail()));
    }

    private static String normalizeEmail(String email) {
        if (email == null || !email.contains("@") || email.length() < 5) {
            throw new BadRequestException("A valid email address is required.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
