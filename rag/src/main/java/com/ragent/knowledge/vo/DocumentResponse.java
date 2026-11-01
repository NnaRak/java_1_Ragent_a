package com.ragent.knowledge.vo;

import com.ragent.knowledge.model.DocumentView;

import java.time.Instant;

public record DocumentResponse(
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

    public static DocumentResponse from(DocumentView view) {
        return new DocumentResponse(view.id(), view.docName(), view.status(), view.chunkCount(),
                view.fileKey(), view.fileType(), view.mimeType(), view.fileSize(), view.createTime());
    }
}
