package com.ragent.knowledge.model;

public record ChunkView(
        String id,
        int index,
        String content,
        int charCount
) {
}
