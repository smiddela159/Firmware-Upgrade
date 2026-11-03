package com.att.device.management.interceptor;

import com.att.device.management.model.IdempotencyAudit;
import com.att.device.management.repository.IdempotencyAuditRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;

/**
 * Interceptor that enforces idempotency for write endpoints.
 * It requires an {@code Idempotency-Key} header and stores a processing flag in Redis.
 * The key is namespaced by the authenticated principal (or "anonymous") to avoid cross‑tenant collisions.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyInterceptor.class);
    private static final String HEADER = "Idempotency-Key";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final IdempotencyAuditRepository auditRepo;

    public IdempotencyInterceptor(StringRedisTemplate redis, IdempotencyAuditRepository auditRepo) {
        this.redis = redis;
        this.auditRepo = auditRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Only protect write methods
        String method = request.getMethod();
        if (!("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method))) {
            return true;
        }

        String idempotencyKey = request.getHeader(HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing Idempotency-Key header – request rejected to ensure idempotent behavior\"}");
            return false;
        }

        // Namespace key by authenticated principal (or anonymous)
        String principal = (request.getUserPrincipal() != null) ? request.getUserPrincipal().getName() : "anonymous";
        String redisKey = "idempotency:" + principal + ":" + idempotencyKey;

        Boolean isFirst;
        try {
            isFirst = redis.opsForValue().setIfAbsent(redisKey, "PROCESSING", CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis unavailable for idempotency lock, falling back to DB audit: {}", e.getMessage());
            isFirst = auditRepo.findByKey(idempotencyKey).isEmpty();
        }

        // Key already exists – return the previously stored response or a conflict error
        if (Boolean.FALSE.equals(isFirst)) {
            IdempotencyAudit audit = auditRepo.findByKey(idempotencyKey).orElse(null);
            if (audit != null) {
                switch (audit.getStatus()) {
                    case "SUCCESS" -> {
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType("application/json");
                        response.getWriter().write(audit.getResponseJson());
                        return false;
                    }
                    case "FAILED_VALIDATION" -> {
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        response.setContentType("application/json");
                        response.getWriter().write(audit.getResponseJson());
                        return false;
                    }
                    case "FAILED_TRANSIENT" -> {
                        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        response.setContentType("application/json");
                        response.getWriter().write(audit.getResponseJson());
                        return false;
                    }
                    case "PROCESSING" -> {
                        response.setStatus(HttpServletResponse.SC_CONFLICT);
                        response.getWriter().write("{\"error\":\"Request with this Idempotency-Key is already being processed\"}");
                        return false;
                    }
                    default -> {
                        response.setStatus(HttpServletResponse.SC_CONFLICT);
                        response.getWriter().write("{\"error\":\"Duplicate Idempotency-Key\"}");
                        return false;
                    }
                }
            }
            // Fallback if audit missing
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            response.getWriter().write("{\"error\":\"Duplicate Idempotency-Key\"}");
            return false;
        }

        // First time – create audit entry in PROCESSING state
        IdempotencyAudit newAudit = new IdempotencyAudit();
        newAudit.setKey(idempotencyKey);
        newAudit.setStatus("PROCESSING");
        newAudit.setPayloadHash(hashRequestBody(request));
        auditRepo.save(newAudit);
        request.setAttribute("idempotencyAuditId", newAudit.getId());
        return true;
    }

    /** Compute a deterministic hash of the request body (first 1 MiB). */
    private String hashRequestBody(HttpServletRequest request) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] body;
            if (request instanceof org.springframework.web.util.ContentCachingRequestWrapper wrapper) {
                body = wrapper.getContentAsByteArray();
            } else {
                body = request.getInputStream().readAllBytes();
            }
            if (body != null && body.length > 0) {
                if (body.length > 1_048_576) {
                    md.update(body, 0, 1_048_576);
                } else {
                    md.update(body);
                }
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (Exception e) {
            log.warn("Unable to hash request body for idempotency", e);
            return "";
        }
    }
}
