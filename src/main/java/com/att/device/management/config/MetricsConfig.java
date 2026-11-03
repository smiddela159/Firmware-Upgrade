package com.att.device.management.config;

import io.micrometer.core.instrument.*;
import org.springframework.context.annotation.*;

/**
 * Micrometer counters for idempotency outcomes and scheduler activity.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter idempotentSuccess(MeterRegistry registry) {
        return Counter.builder("idempotent.requests")
                .description("Count of idempotent request outcomes")
                .tag("outcome", "success")
                .register(registry);
    }

    @Bean
    public Counter idempotentFailedValidation(MeterRegistry registry) {
        return Counter.builder("idempotent.requests")
                .description("Count of idempotent request outcomes")
                .tag("outcome", "failed_validation")
                .register(registry);
    }

    @Bean
    public Counter idempotentFailedTransient(MeterRegistry registry) {
        return Counter.builder("idempotent.requests")
                .description("Count of idempotent request outcomes")
                .tag("outcome", "failed_transient")
                .register(registry);
    }

    @Bean
    public Counter idempotentDuplicate(MeterRegistry registry) {
        return Counter.builder("idempotent.requests")
                .description("Count of duplicate idempotent key attempts (conflict)")
                .tag("outcome", "duplicate")
                .register(registry);
    }

    // Scheduler metrics (example)
    @Bean
    public Counter schedulerCycleCounter(MeterRegistry registry) {
        return Counter.builder("scheduler.cycles")
                .description("Number of scheduler cycles executed")
                .register(registry);
    }
}

