package com.att.device.management.filter;

import com.att.device.management.service.RateLimitingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * Applies per‑entity rate limiting (by deviceId, userId or accountId) using Resilience4j.
 * If the limit is exceeded the request is rejected with HTTP 429.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitingService rateLimitingService;

    public RateLimitingFilter(RateLimitingService rateLimitingService) {
        this.rateLimitingService = rateLimitingService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = extractKey(request);
        // Fallback to a global limiter if we cannot determine a specific key
        if (key == null) {
            key = "global";
        }
        boolean permitted = rateLimitingService.getLimiter(key).acquirePermission();
        if (!permitted) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded for key: " + key + "\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Determines the appropriate rate‑limit key.
     * • If the URL matches /devices/{deviceId}/... → use deviceId
     * • Else if header X-User-Id is present → use that
     * • Else if header X-Account-Id is present → use that
     */
    private String extractKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Simple pattern matching – enough for this demo
        if (path.startsWith("/devices/")) {
            String[] parts = path.split("/");
            if (parts.length > 2) {
                return "device:" + parts[2];
            }
        }
        String user = request.getHeader("X-User-Id");
        if (user != null && !user.isBlank()) {
            return "user:" + user;
        }
        String account = request.getHeader("X-Account-Id");
        if (account != null && !account.isBlank()) {
            return "account:" + account;
        }
        return null;
    }
}

