package com.ragent.knowledge.vo;

import com.ragent.knowledge.IngestionResult;

public record IngestionResponse(
        String docId,
        String docName,
        String status,
        int chunkCount,
        String fileKey,
        long durationMs
) {

    public static IngestionResponse from(IngestionResult result) {
        return new IngestionResponse(result.docId(), result.docName(), result.status(),
                result.chunkCount(), result.fileKey(), result.durationMs());
    }
}
