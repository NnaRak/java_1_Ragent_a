package com.ragent.config;

import com.ragent.embedding.EmbeddingClient;
import com.ragent.embedding.MockEmbeddingClient;
import com.ragent.embedding.OpenAiCompatibleEmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "mock")
    public EmbeddingClient mockEmbeddingClient(EmbeddingProperties properties) {
        return new MockEmbeddingClient(properties.dimension());
    }

    @Bean
    @ConditionalOnProperty(name = "rag.embedding.provider", havingValue = "bailian", matchIfMissing = true)
    public EmbeddingClient openAiCompatibleEmbeddingClient(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingClient(properties);
    }
}
