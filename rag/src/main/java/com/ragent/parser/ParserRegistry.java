package com.ragent.parser;

import com.ragent.common.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Component
public class ParserRegistry {

    private final Map<String, DocumentParser> byExtension = new HashMap<>();
    private final Map<String, DocumentParser> byMimeType = new HashMap<>();
    private final Set<String> supportedExtensions;

    public ParserRegistry(List<DocumentParser> parsers) {
        for (DocumentParser parser : parsers) {
            if (parser.supportedExtensions().isEmpty()) {
                throw new IllegalStateException("解析器未声明任何后缀：" + parser.parserType());
            }
            for (String extension : parser.supportedExtensions()) {
                register(byExtension, normalizeExtension(extension), parser);
            }
            for (String mimeType : parser.supportedMimeTypes()) {
                register(byMimeType, normalizeMimeType(mimeType), parser);
            }
        }
        this.supportedExtensions = new TreeSet<>(byExtension.keySet());
    }

    private void register(Map<String, DocumentParser> table, String key, DocumentParser parser) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException("解析器声明了空的注册键：" + parser.parserType());
        }
        DocumentParser previous = table.putIfAbsent(key, parser);
        if (previous != null && previous != parser) {
            throw new IllegalStateException("解析器注册键冲突：" + key
                    + " 同时被 " + previous.parserType() + " 与 " + parser.parserType() + " 认领");
        }
    }

    public Optional<DocumentParser> find(String filename, String contentType) {
        String extension = extensionOf(filename);
        if (extension != null) {
            DocumentParser parser = byExtension.get(extension);
            if (parser != null) {
                return Optional.of(parser);
            }
        }
        String mimeType = normalizeMimeType(contentType);
        if (mimeType != null) {
            return Optional.ofNullable(byMimeType.get(mimeType));
        }
        return Optional.empty();
    }

    public DocumentParser require(String filename, String contentType) {
        return find(filename, contentType).orElseThrow(() -> new BusinessException(
                "暂不支持的文件类型：" + (StringUtils.hasText(filename) ? filename : contentType)
                        + "，当前仅支持 " + supportedExtensionText()));
    }

    public Set<String> supportedExtensions() {
        return supportedExtensions;
    }

    public String supportedExtensionText() {
        return supportedExtensions.stream().map(extension -> "." + extension).collect(Collectors.joining(", "));
    }

    public static String extensionOf(String filename) {
        if (!StringUtils.hasText(filename)) {
            return null;
        }
        String normalized = filename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return null;
        }
        return normalizeExtension(name.substring(dot + 1));
    }

    private static String normalizeExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            return "";
        }
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized.substring(1) : normalized;
    }

    private static String normalizeMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return null;
        }
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        int semicolon = normalized.indexOf(';');
        if (semicolon >= 0) {
            normalized = normalized.substring(0, semicolon).trim();
        }
        return normalized.isEmpty() || "application/octet-stream".equals(normalized) ? null : normalized;
    }
}
