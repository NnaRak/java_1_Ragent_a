package com.ragent.chunk;

import com.ragent.chunk.model.TextChunk;
import com.ragent.config.IngestionProperties;
import com.ragent.parser.model.DocBlock;
import com.ragent.parser.model.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 标题感知分块：先按标题栈切成逻辑章节，再在章节内部按字符预算打包。
 * 输出的 content 保留标题行，embeddingText 额外带上「H1 > H2」面包屑，便于召回时区分同名段落。
 */
@Component
public class HeadingAwareChunker {

    private static final String BLOCK_SEPARATOR = "\n\n";

    public List<TextChunk> chunk(ParsedDocument document, IngestionProperties.Chunk budget) {
        if (document == null || document.blocks().isEmpty()) {
            return List.of();
        }
        List<Draft> drafts = new ArrayList<>();
        for (Section section : splitSections(document.blocks())) {
            drafts.addAll(chunkSection(section, budget));
        }
        return mergeAndIndex(drafts, budget);
    }

    private List<Section> splitSections(List<DocBlock> blocks) {
        List<Section> sections = new ArrayList<>();
        Deque<DocBlock.Heading> stack = new ArrayDeque<>();
        DocBlock.Heading currentHeading = null;
        List<DocBlock> body = new ArrayList<>();

        for (DocBlock block : blocks) {
            if (block instanceof DocBlock.Heading heading) {
                if (currentHeading != null || !body.isEmpty()) {
                    sections.add(new Section(pathOf(stack), currentHeading, body));
                    body = new ArrayList<>();
                }
                while (!stack.isEmpty() && stack.peek().level() >= heading.level()) {
                    stack.pop();
                }
                stack.push(heading);
                currentHeading = heading;
            } else {
                body.add(block);
            }
        }
        if (currentHeading != null || !body.isEmpty()) {
            sections.add(new Section(pathOf(stack), currentHeading, body));
        }
        return sections;
    }

    private List<String> pathOf(Deque<DocBlock.Heading> stack) {
        List<String> path = new ArrayList<>(stack.size());
        Iterator<DocBlock.Heading> iterator = stack.descendingIterator();
        while (iterator.hasNext()) {
            path.add(iterator.next().text());
        }
        return path;
    }

    private List<Draft> chunkSection(Section section, IngestionProperties.Chunk budget) {
        String headingLine = section.heading() == null ? null : section.heading().toMarkdown();
        int prefixLength = headingLine == null ? 0 : headingLine.length() + BLOCK_SEPARATOR.length();
        int bodyBudget = Math.max(1, budget.maxChars() - prefixLength);
        List<String> units = buildUnits(section.blocks(), bodyBudget, budget.overlapChars());
        if (units.isEmpty()) {
            return List.of();
        }
        return packUnits(units, bodyBudget, budget.overlapChars(), headingLine, section.headingPath());
    }

    private List<String> buildUnits(List<DocBlock> blocks, int budget, int overlap) {
        List<String> units = new ArrayList<>();
        for (DocBlock block : blocks) {
            String rendered = block.toMarkdown().strip();
            if (rendered.isEmpty()) {
                continue;
            }
            if (rendered.length() <= budget) {
                units.add(rendered);
            } else if (block instanceof DocBlock.Code code) {
                units.addAll(splitCode(code, budget));
            } else if (block instanceof DocBlock.Table table) {
                units.addAll(splitTable(table, budget));
            } else {
                units.addAll(splitText(rendered, budget, overlap));
            }
        }
        return units;
    }

    private List<Draft> packUnits(List<String> units, int bodyBudget, int overlap,
                                  String headingLine, List<String> headingPath) {
        List<Draft> drafts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.isEmpty()) {
                current.append(unit);
                continue;
            }
            if (current.length() + BLOCK_SEPARATOR.length() + unit.length() <= bodyBudget) {
                current.append(BLOCK_SEPARATOR).append(unit);
                continue;
            }
            drafts.add(new Draft(headingLine, current.toString(), headingPath));
            String tail = tailForOverlap(current.toString(), overlap);
            current.setLength(0);
            if (!tail.isEmpty() && tail.length() + BLOCK_SEPARATOR.length() + unit.length() <= bodyBudget) {
                current.append(tail).append(BLOCK_SEPARATOR);
            }
            current.append(unit);
        }
        if (!current.isEmpty()) {
            drafts.add(new Draft(headingLine, current.toString(), headingPath));
        }
        return drafts;
    }

    private List<TextChunk> mergeAndIndex(List<Draft> drafts, IngestionProperties.Chunk budget) {
        List<TextChunk> chunks = new ArrayList<>(drafts.size());
        TextChunk pending = null;
        for (Draft draft : drafts) {
            TextChunk candidate = draft.toChunk(0);
            if (pending != null) {
                String combined = pending.content() + BLOCK_SEPARATOR + candidate.content();
                if (combined.length() <= budget.maxChars()) {
                    candidate = new TextChunk(0, combined,
                            pending.embeddingText() + "\n" + candidate.embeddingText(),
                            candidate.headingPath(), combined.length());
                    pending = null;
                } else {
                    chunks.add(pending);
                    pending = null;
                }
            }
            if (!chunks.isEmpty() && candidate.charCount() < budget.minChars()) {
                TextChunk previous = chunks.get(chunks.size() - 1);
                String merged = previous.content() + BLOCK_SEPARATOR + candidate.content();
                if (merged.length() <= budget.maxChars()) {
                    chunks.set(chunks.size() - 1, new TextChunk(
                            0,
                            merged,
                            previous.embeddingText() + "\n" + candidate.embeddingText(),
                            previous.headingPath(),
                            merged.length()));
                    continue;
                }
            }
            if (chunks.isEmpty() && candidate.charCount() < budget.minChars()) {
                // 文档开头的小块（banner 文字等）先挂起，下一块能容纳时前向合并
                pending = candidate;
                continue;
            }
            chunks.add(candidate);
        }
        if (pending != null) {
            chunks.add(pending);
        }
        List<TextChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            result.add(new TextChunk(i, chunk.content(), chunk.embeddingText(), chunk.headingPath(), chunk.charCount()));
        }
        return result;
    }

    private static List<String> splitText(String text, int budget, int overlap) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + budget);
            if (end < text.length()) {
                int minBreak = start + Math.max(1, budget / 2);
                end = findBreak(text, minBreak, end);
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - Math.max(0, overlap));
        }
        return pieces;
    }

    private static int findBreak(String text, int min, int max) {
        for (int i = max - 1; i >= min; i--) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '.' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '！' || c == '？') {
                return i + 1;
            }
        }
        return max;
    }

    private static List<String> splitCode(DocBlock.Code code, int budget) {
        String prefix = "```" + code.language() + "\n";
        String suffix = "\n```";
        int contentBudget = budget - prefix.length() - suffix.length();
        if (contentBudget < 32) {
            return splitText(code.toMarkdown(), budget, 0);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : code.text().split("\n", -1)) {
            if (line.length() > contentBudget) {
                if (!current.isEmpty()) {
                    pieces.add(prefix + current + suffix);
                    current.setLength(0);
                }
                for (String part : splitText(line, contentBudget, 0)) {
                    pieces.add(prefix + part + suffix);
                }
                continue;
            }
            int needed = current.isEmpty() ? line.length() : current.length() + 1 + line.length();
            if (needed > contentBudget) {
                pieces.add(prefix + current + suffix);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line);
        }
        if (!current.isEmpty()) {
            pieces.add(prefix + current + suffix);
        }
        return pieces;
    }

    private static List<String> splitTable(DocBlock.Table table, int budget) {
        String headerBlock = renderRow(table.header()) + "\n" + separator(table.header().size());
        if (headerBlock.length() > budget) {
            return splitText(table.toMarkdown(), budget, 0);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder(headerBlock);
        for (List<String> row : table.rows()) {
            String line = renderRow(row);
            if (headerBlock.length() + 1 + line.length() > budget) {
                if (current.length() > headerBlock.length()) {
                    pieces.add(current.toString());
                }
                return splitText(table.toMarkdown(), budget, 0);
            }
            if (current.length() + 1 + line.length() > budget) {
                pieces.add(current.toString());
                current.setLength(0);
                current.append(headerBlock);
            }
            current.append('\n').append(line);
        }
        if (current.length() > 0) {
            pieces.add(current.toString());
        }
        return pieces;
    }

    private static String renderRow(List<String> cells) {
        StringBuilder builder = new StringBuilder();
        builder.append('|');
        for (String cell : cells) {
            builder.append(' ').append(cell == null ? "" : cell.replace("|", "\\|").replace('\n', ' ')).append(" |");
        }
        return builder.toString();
    }

    private static String separator(int columns) {
        return "|" + " --- |".repeat(Math.max(1, columns));
    }

    private static String tailForOverlap(String text, int overlap) {
        if (overlap <= 0 || text.isEmpty()) {
            return "";
        }
        int start = Math.max(0, text.length() - overlap);
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '。' || c == '.' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '！' || c == '？') {
                String tail = text.substring(i + 1).strip();
                if (!tail.isEmpty()) {
                    return tail;
                }
            }
        }
        // 找不到自然边界就不重叠，避免下一块从半个词/半个标签开始
        return "";
    }

    private record Section(List<String> headingPath, DocBlock.Heading heading, List<DocBlock> blocks) {
    }

    private record Draft(String headingLine, String body, List<String> headingPath) {

        private TextChunk toChunk(int index) {
            String content = headingLine == null ? body : headingLine + BLOCK_SEPARATOR + body;
            String breadcrumb = String.join(" > ", headingPath);
            String embeddingText = breadcrumb.isBlank() ? body : breadcrumb + "\n" + body;
            return new TextChunk(index, content, embeddingText, headingPath, content.length());
        }
    }
}
