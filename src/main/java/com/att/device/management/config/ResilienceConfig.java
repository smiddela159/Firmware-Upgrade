package com.att.device.management.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * Centralised Resilience4j configuration.
 *
 * A circuit‑breaker named "adapterCalls" protects calls to external protocol adapters
 * (e.g., REST‑Nudge). The settings are deliberately conservative – 5 failures within a
 * 30‑second sliding window will open the breaker, and it will stay open for 60 seconds
 * before attempting a half‑open trial.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)               // percent failures to open
                .slidingWindowSize(5)                  // number of calls in the window
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(60))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(RuntimeException.class)
                .build();
        return CircuitBreakerRegistry.of(cbConfig);
    }
}

