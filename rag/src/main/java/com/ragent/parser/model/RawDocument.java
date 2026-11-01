package com.ragent.parser.model;

public record RawDocument(byte[] bytes, String filename, String contentType) {

    public RawDocument {
        bytes = bytes == null ? new byte[0] : bytes;
        filename = filename == null || filename.isBlank() ? "README.md" : filename.strip();
        contentType = contentType == null ? "" : contentType.strip();
    }
}
