package com.ragent.parser.markdown;

import com.ragent.parser.model.DocBlock;
import com.ragent.parser.model.ParsedDocument;
import com.ragent.parser.model.RawDocument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownDocumentParserTest {

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();

    @Test
    void parsesHeadingsParagraphsListCodeAndTable() throws IOException {
        ParsedDocument document = parser.parse(new RawDocument(readSample(), "README.md", "text/markdown"));

        assertThat(document.title()).isEqualTo("RAG 示例");

        List<DocBlock.Heading> headings = document.blocks().stream()
                .filter(DocBlock.Heading.class::isInstance)
                .map(DocBlock.Heading.class::cast)
                .toList();
        assertThat(headings).extracting(DocBlock.Heading::text)
                .containsExactly("RAG 示例", "安装", "配置");

        DocBlock.Paragraph paragraph = document.blocks().stream()
                .filter(DocBlock.Paragraph.class::isInstance)
                .map(DocBlock.Paragraph.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(paragraph.text()).contains("README");

        DocBlock.ListBlock list = document.blocks().stream()
                .filter(DocBlock.ListBlock.class::isInstance)
                .map(DocBlock.ListBlock.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(list.items()).containsExactly("安装 JDK", "启动数据库");

        DocBlock.Code code = document.blocks().stream()
                .filter(DocBlock.Code.class::isInstance)
                .map(DocBlock.Code.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(code.language()).isEqualTo("java");
        assertThat(code.text()).contains("System.out.println");

        DocBlock.Table table = document.blocks().stream()
                .filter(DocBlock.Table.class::isInstance)
                .map(DocBlock.Table.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(table.header()).containsExactly("参数", "说明");
        assertThat(table.rows()).containsExactly(
                List.of("url", "数据库地址"),
                List.of("user", "用户名"));
    }

    @Test
    void stripsHtmlTagsFromReadmeBanner() {
        String markdown = """
                <p align="center">
                  <strong>后端程序员转型 AI 工程师的第一站</strong><br/>
                </p>

                # 标题

                正文
                """;

        ParsedDocument document = parser.parse(new RawDocument(
                markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8), "README.md", "text/markdown"));

        assertThat(document.blocks()).first().isInstanceOfSatisfying(DocBlock.Paragraph.class,
                paragraph -> assertThat(paragraph.text()).isEqualTo("后端程序员转型 AI 工程师的第一站"));
    }

    @Test
    void returnsEmptyDocumentForBlankContent() {
        ParsedDocument document = parser.parse(new RawDocument(new byte[0], "README.md", null));

        assertThat(document.blocks()).isEmpty();
        assertThat(document.title()).isEqualTo("README");
    }

    private static byte[] readSample() throws IOException {
        try (InputStream input = MarkdownDocumentParserTest.class.getResourceAsStream("/readme-sample.md")) {
            assertThat(input).isNotNull();
            return input.readAllBytes();
        }
    }
}
