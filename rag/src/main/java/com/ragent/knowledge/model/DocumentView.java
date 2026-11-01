package com.ragent.knowledge.model;

import java.time.Instant;

public record DocumentView(
        String id,
        String docName,
        String status,
        int chunkCount,
        String fileKey,
        String fileType,
        String mimeType,
        long fileSize,
        Instant createTime
) {
}
