package com.yci.controller;

import com.yci.dto.Dto;
import com.yci.security.CurrentUser;
import com.yci.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public Dto.AuthResponse signup(@Valid @RequestBody Dto.SignupRequest req) {
        return authService.signup(req);
    }

    @PostMapping("/login")
    public Dto.AuthResponse login(@Valid @RequestBody Dto.LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            authService.logout(header.substring(7).trim());
        }
    }

    @GetMapping("/me")
    public Dto.UserDto me(@CurrentUser Long userId) {
        return authService.currentUser(userId);
    }
}
