package com.ragent.knowledge;

public record IngestionResult(
        String docId,
        String docName,
        String status,
        int chunkCount,
        String fileKey,
        long durationMs
) {
}
