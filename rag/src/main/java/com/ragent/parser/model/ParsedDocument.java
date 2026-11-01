package com.ragent.parser.model;

import java.util.List;
import java.util.Map;

public record ParsedDocument(
        String title,
        DocumentKind kind,
        List<DocBlock> blocks,
        Map<String, Object> metadata
) {

    public ParsedDocument {
        title = title == null || title.isBlank() ? "untitled" : title.strip();
        kind = kind == null ? DocumentKind.MARKDOWN : kind;
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum DocumentKind {
        MARKDOWN,
        PLAIN_TEXT
    }

    public static ParsedDocument empty(String title, DocumentKind kind) {
        return new ParsedDocument(title, kind, List.of(), Map.of());
    }
}
