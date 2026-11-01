package com.ragent.parser;

import com.ragent.common.BusinessException;
import com.ragent.parser.markdown.MarkdownDocumentParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserRegistryTest {

    private final ParserRegistry registry = new ParserRegistry(List.of(new MarkdownDocumentParser()));

    @Test
    void resolvesByExtensionAndMimeType() {
        assertThat(registry.find("README.md", null)).isPresent();
        assertThat(registry.find("README.MARKDOWN", "application/octet-stream")).isPresent();
        assertThat(registry.find("README", "text/markdown")).isPresent();
        assertThat(registry.find("manual.pdf", "application/pdf")).isEmpty();
    }

    @Test
    void unsupportedTypeFailsWithSupportedExtensionList() {
        assertThatThrownBy(() -> registry.require("manual.pdf", "application/pdf"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".md")
                .hasMessageContaining(".markdown");
    }

    @Test
    void duplicateExtensionClaimFailsFast() {
        DocumentParser duplicate = mock(DocumentParser.class);
        when(duplicate.parserType()).thenReturn("duplicate");
        when(duplicate.supportedExtensions()).thenReturn(Set.of("md"));
        when(duplicate.supportedMimeTypes()).thenReturn(Set.of());

        assertThatThrownBy(() -> new ParserRegistry(List.of(new MarkdownDocumentParser(), duplicate)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("md");
    }
}
