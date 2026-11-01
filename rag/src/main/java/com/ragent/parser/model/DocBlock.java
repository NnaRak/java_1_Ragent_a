package com.ragent.parser.model;

import java.util.List;

/**
 * 格式无关的文档块模型：解析器只负责把源文件归一化成这些块，
 * 分块器与落库逻辑不感知原始格式，后续新增 PDF/DOCX 只需要新增解析器。
 */
public sealed interface DocBlock {

    String toMarkdown();

    record Heading(int level, String text) implements DocBlock {

        public Heading {
            level = Math.max(1, Math.min(6, level));
            text = text == null ? "" : text.strip();
        }

        @Override
        public String toMarkdown() {
            return "#".repeat(level) + " " + text;
        }
    }

    record Paragraph(String text) implements DocBlock {

        public Paragraph {
            text = text == null ? "" : text.strip();
        }

        @Override
        public String toMarkdown() {
            return text;
        }
    }

    record Code(String language, String text) implements DocBlock {

        public Code {
            language = language == null ? "" : language.strip();
            text = text == null ? "" : text.stripTrailing();
        }

        @Override
        public String toMarkdown() {
            return "```" + language + "\n" + text + "\n```";
        }
    }

    record ListBlock(boolean ordered, List<String> items) implements DocBlock {

        public ListBlock {
            items = items == null ? List.of() : List.copyOf(items);
        }

        @Override
        public String toMarkdown() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(ordered ? (i + 1) + ". " : "- ").append(items.get(i));
            }
            return builder.toString();
        }
    }

    record Table(List<String> header, List<List<String>> rows) implements DocBlock {

        public Table {
            header = header == null ? List.of() : List.copyOf(header);
            rows = rows == null ? List.of() : rows.stream().map(List::copyOf).toList();
        }

        @Override
        public String toMarkdown() {
            if (header.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            appendRow(builder, header);
            builder.append('\n').append('|');
            for (int i = 0; i < header.size(); i++) {
                builder.append(" --- |");
            }
            for (List<String> row : rows) {
                builder.append('\n');
                appendRow(builder, row);
            }
            return builder.toString();
        }

        private static void appendRow(StringBuilder builder, List<String> cells) {
            builder.append('|');
            for (String cell : cells) {
                builder.append(' ').append(escape(cell)).append(" |");
            }
        }

        private static String escape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("|", "\\|").replace('\n', ' ').strip();
        }
    }
}
