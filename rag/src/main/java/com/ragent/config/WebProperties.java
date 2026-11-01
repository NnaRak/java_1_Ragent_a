package com.ragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "rag.web")
public record WebProperties(List<String> allowedOrigins) {

    public WebProperties {
        allowedOrigins = allowedOrigins == null || allowedOrigins.isEmpty()
                ? List.of("http://localhost:5173", "http://127.0.0.1:5173")
                : List.copyOf(allowedOrigins);
    }
}
