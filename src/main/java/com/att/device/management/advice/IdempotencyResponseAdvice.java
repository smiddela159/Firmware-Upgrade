package com.att.device.management.advice;

import com.att.device.management.model.IdempotencyAudit;
import com.att.device.management.repository.IdempotencyAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.Instant;

@Component
public class IdempotencyResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyResponseAdvice.class);
    private final IdempotencyAuditRepository auditRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public IdempotencyResponseAdvice(IdempotencyAuditRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Apply to all controller responses – we will filter inside based on request attribute
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        Long auditId = (Long) servletRequest.getAttribute("idempotencyAuditId");
        if (auditId == null) {
            return body; // not an idempotent request
        }

        IdempotencyAudit audit = auditRepo.findById(auditId).orElse(null);
        if (audit == null) {
            log.warn("Idempotency audit entry missing for id {}", auditId);
            return body;
        }

        int status = ((ServletServerHttpResponse) response).getServletResponse().getStatus();
        try {
            String json = mapper.writeValueAsString(body);
            if (status >= 200 && status < 300) {
                audit.setStatus("SUCCESS");
                audit.setResponseJson(json);
            } else if (status >= 400 && status < 500) {
                audit.setStatus("FAILED_VALIDATION");
                audit.setResponseJson(json);
            } else {
                audit.setStatus("FAILED_TRANSIENT");
                // keep original payload if any, otherwise a generic message
                audit.setResponseJson(json != null ? json : "{\"error\":\"internal error\"}");
            }
        } catch (Exception e) {
            log.warn("Failed to serialize response for idempotency audit", e);
            audit.setStatus(status >= 200 && status < 300 ? "SUCCESS" : "FAILED_TRANSIENT");
            audit.setResponseJson("{}");
        }
        audit.setUpdatedAt(Instant.now());
        auditRepo.save(audit);
        return body;
    }
}

