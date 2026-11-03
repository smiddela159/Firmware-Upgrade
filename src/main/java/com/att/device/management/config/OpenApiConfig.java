package com.att.device.management.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.*;

/**
 * OpenAPI / Swagger configuration.
 * The UI is available at /swagger-ui.html after the application starts.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Firmware Upgrade Management API")
                        .version("1.0.0")
                        .description("REST endpoints for managing firmware‑upgrade campaigns and device interactions."));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/campaigns/**", "/devices/**")
                .build();
    }
}

