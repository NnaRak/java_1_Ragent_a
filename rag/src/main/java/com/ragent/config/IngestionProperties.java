package com.ragent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "rag.ingestion")
public record IngestionProperties(
        String collectionName,
        String kbName,
        String embeddingModel,
        DataSize maxFileSize,
        Chunk chunk
) {

    public IngestionProperties {
        collectionName = StringUtils.hasText(collectionName) ? collectionName.trim() : "readme_ingest";
        kbName = StringUtils.hasText(kbName) ? kbName.trim() : "README 知识库";
        embeddingModel = StringUtils.hasText(embeddingModel) ? embeddingModel.trim() : "qwen-emb-8b";
        maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(10) : maxFileSize;
        chunk = chunk == null ? new Chunk(1024, 128, 200) : chunk;
        if (maxFileSize.toBytes() <= 0) {
            throw new IllegalArgumentException("rag.ingestion.max-file-size 必须大于 0");
        }
    }

    public record Chunk(int maxChars, int overlapChars, int minChars) {

        public Chunk {
            if (maxChars <= 0 && overlapChars == 0 && minChars == 0) {
                maxChars = 1024;
                overlapChars = 128;
                minChars = 200;
            }
            if (maxChars <= 0) {
                throw new IllegalArgumentException("rag.ingestion.chunk.max-chars 必须大于 0");
            }
            if (overlapChars < 0 || overlapChars >= maxChars) {
                throw new IllegalArgumentException("rag.ingestion.chunk.overlap-chars 必须落在 [0, max-chars) 区间");
            }
            if (minChars < 0 || minChars > maxChars) {
                throw new IllegalArgumentException("rag.ingestion.chunk.min-chars 必须落在 [0, max-chars] 区间");
            }
        }
    }
}
