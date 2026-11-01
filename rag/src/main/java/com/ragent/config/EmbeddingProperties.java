package com.ragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "rag.embedding")
public record EmbeddingProperties(
        String provider,
        String baseUrl,
        String path,
        String apiKey,
        String model,
        int dimension,
        int batchSize,
        int timeoutSeconds,
        int maxRetries
) {

    public EmbeddingProperties {
        provider = StringUtils.hasText(provider) ? provider.trim().toLowerCase() : "bailian";
        baseUrl = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "https://dashscope.aliyuncs.com";
        path = StringUtils.hasText(path) ? path.trim() : "/compatible-mode/v1/embeddings";
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = StringUtils.hasText(model) ? model.trim() : "text-embedding-v4";
        dimension = dimension <= 0 ? 1536 : dimension;
        batchSize = batchSize <= 0 ? 10 : batchSize;
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        maxRetries = maxRetries <= 0 ? 3 : maxRetries;
    }
}
