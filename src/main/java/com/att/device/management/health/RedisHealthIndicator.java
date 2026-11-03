package com.att.device.management.health;

import org.springframework.boot.actuate.health.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.*;
import org.slf4j.*;

/**
 * Simple health indicator that checks connectivity to the Redis cache used for idempotency.
 * If Redis is unavailable the endpoint will report DOWN, which is critical for production.
 */
@Component
@ConditionalOnProperty(name = "management.health.redis.enabled", havingValue = "true", matchIfMissing = true)
public class RedisHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthIndicator.class);
    private final RedisConnectionFactory connectionFactory;

    public RedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try (var connection = connectionFactory.getConnection()) {
            // PING returns "PONG" if the server is alive
            String pong = connection.ping();
            return Health.up().withDetail("redis", pong).build();
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return Health.down(e).withDetail("redis", "unreachable").build();
        }
    }
}

