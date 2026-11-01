package com.ragent.knowledge.vo;

import com.ragent.knowledge.model.ChunkView;

public record ChunkResponse(
        String id,
        int index,
        String content,
        int charCount
) {

    public static ChunkResponse from(ChunkView view) {
        return new ChunkResponse(view.id(), view.index(), view.content(), view.charCount());
    }
}
