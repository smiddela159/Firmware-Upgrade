package com.att.device.management.config;

import com.att.device.management.interceptor.IdempotencyInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers MVC components such as interceptors.
 * Adds the {@link IdempotencyInterceptor} to protect campaign write endpoints.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final IdempotencyInterceptor idempotencyInterceptor;

    public WebMvcConfig(IdempotencyInterceptor idempotencyInterceptor) {
        this.idempotencyInterceptor = idempotencyInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Enforce idempotency on campaign write operations; device callbacks handle idempotency internally.
        registry.addInterceptor(idempotencyInterceptor)
                .addPathPatterns("/campaigns/**");
    }
}
