package com.att.device.management.service;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provides a per‑key RateLimiter (account, user, or device) using Resilience4j.
 * Each distinct key gets its own limiter with the same configuration, allowing
 * independent burst control per tenant/device.
 */
@Component
public class RateLimitingService {

    private final RateLimiterRegistry registry;
    private final ConcurrentMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public RateLimitingService() {
        // Global config: 20 requests per second, max wait 100 ms before refusing.
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(20)
                .timeoutDuration(Duration.ofMillis(100))
                .build();
        this.registry = RateLimiterRegistry.of(config);
    }

    /**
     * Obtain (or create) a RateLimiter for the supplied key.
     */
    public RateLimiter getLimiter(String key) {
        return limiters.computeIfAbsent(key, k -> registry.rateLimiter(k));
    }
}

