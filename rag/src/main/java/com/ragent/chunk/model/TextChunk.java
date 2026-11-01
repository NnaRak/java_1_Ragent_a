package com.ragent.chunk.model;

import java.util.List;

public record TextChunk(
        int index,
        String content,
        String embeddingText,
        List<String> headingPath,
        int charCount
) {

    public TextChunk {
        content = content == null ? "" : content;
        embeddingText = embeddingText == null ? "" : embeddingText;
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
        charCount = content.length();
    }
}
