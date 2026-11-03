package com.att.device.management.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static String getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return "system";
        }
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            String sub = jwtAuth.getToken().getSubject();
            if (sub != null && !sub.isBlank()) {
                return sub;
            }
            String clientId = jwtAuth.getToken().getClaimAsString("client_id");
            if (clientId != null && !clientId.isBlank()) {
                return clientId;
            }
            return jwtAuth.getName();
        }
        String name = auth.getName();
        return (name != null && !name.isBlank()) ? name : "system";
    }
}

