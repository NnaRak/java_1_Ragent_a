package com.ragent.chunk;

import com.ragent.chunk.model.TextChunk;
import com.ragent.config.IngestionProperties;
import com.ragent.parser.model.DocBlock;
import com.ragent.parser.model.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeadingAwareChunkerTest {

    private static final IngestionProperties.Chunk BUDGET = new IngestionProperties.Chunk(300, 40, 50);

    private final HeadingAwareChunker chunker = new HeadingAwareChunker();

    @Test
    void keepsHeadingPathAndNeverExceedsBudget() {
        String longParagraph = "这是一个很长的段落。".repeat(80);
        ParsedDocument document = new ParsedDocument("测试", ParsedDocument.DocumentKind.MARKDOWN, List.of(
                new DocBlock.Heading(1, "项目"),
                new DocBlock.Paragraph("简介"),
                new DocBlock.Heading(2, "安装"),
                new DocBlock.Paragraph(longParagraph),
                new DocBlock.Heading(2, "配置"),
                new DocBlock.Code("java", "class A { }"),
                new DocBlock.Table(List.of("参数", "说明"), List.of(List.of("a", "b")))
        ), Map.of());

        List<TextChunk> chunks = chunker.chunk(document, BUDGET);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.charCount()).isLessThanOrEqualTo(300));
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).index()).isEqualTo(i);
        }
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.headingPath()).containsExactly("项目", "安装");
            assertThat(chunk.embeddingText()).contains("项目 > 安装");
        });
        assertThat(chunks.stream().anyMatch(chunk -> chunk.content().contains("```java"))).isTrue();
    }

    @Test
    void mergesTinyTrailingSectionIntoPreviousChunk() {
        ParsedDocument document = new ParsedDocument("测试", ParsedDocument.DocumentKind.MARKDOWN, List.of(
                new DocBlock.Heading(1, "A"),
                new DocBlock.Paragraph("x".repeat(200)),
                new DocBlock.Heading(2, "B"),
                new DocBlock.Paragraph("tail")
        ), Map.of());

        List<TextChunk> chunks = chunker.chunk(document, BUDGET);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("tail");
        assertThat(chunks.get(0).headingPath()).containsExactly("A");
    }

    @Test
    void mergesTinyLeadingSectionIntoNextChunk() {
        ParsedDocument document = new ParsedDocument("测试", ParsedDocument.DocumentKind.MARKDOWN, List.of(
                new DocBlock.Paragraph("banner"),
                new DocBlock.Heading(1, "A"),
                new DocBlock.Paragraph("x".repeat(200))
        ), Map.of());

        List<TextChunk> chunks = chunker.chunk(document, BUDGET);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).startsWith("banner").contains("x".repeat(50));
        assertThat(chunks.get(0).headingPath()).containsExactly("A");
    }

    @Test
    void returnsEmptyListForEmptyDocument() {
        ParsedDocument document = ParsedDocument.empty("空", ParsedDocument.DocumentKind.MARKDOWN);

        assertThat(chunker.chunk(document, BUDGET)).isEmpty();
    }
}
