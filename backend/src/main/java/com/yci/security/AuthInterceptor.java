package com.yci.security;

import com.yci.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates the {@code Authorization: Bearer <token>} header on protected routes
 * and stashes the resolved user id as a request attribute.
 *
 * The previous version of this app trusted a client-supplied {@code X-User-Id}
 * header, which meant any caller could act as any user. That header is gone.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID_ATTRIBUTE = "yci.userId";

    private final AuthService authService;

    public AuthInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        String header = request.getHeader("Authorization");
        String token = (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7))
                ? header.substring(7).trim()
                : null;

        Long userId = token == null ? null : authService.resolveUserId(token);
        if (userId == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            try {
                response.getWriter().write("{\"message\":\"Not signed in. Please log in again.\"}");
            } catch (Exception ignored) {
                // Response already committed; the 401 status is what matters.
            }
            return false;
        }
        request.setAttribute(USER_ID_ATTRIBUTE, userId);
        return true;
    }
}
