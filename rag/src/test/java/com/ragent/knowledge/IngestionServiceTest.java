package com.ragent.knowledge;

import com.ragent.chunk.HeadingAwareChunker;
import com.ragent.common.BusinessException;
import com.ragent.common.SnowflakeIdGenerator;
import com.ragent.config.IngestionProperties;
import com.ragent.embedding.EmbeddingClient;
import com.ragent.embedding.MockEmbeddingClient;
import com.ragent.parser.ParserRegistry;
import com.ragent.parser.markdown.MarkdownDocumentParser;
import com.ragent.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    private static final IngestionProperties PROPERTIES = new IngestionProperties(
            "test_collection",
            "测试库",
            "qwen-emb-8b",
            DataSize.ofMegabytes(1),
            new IngestionProperties.Chunk(200, 20, 20));

    @Mock
    private KnowledgeRepository repository;

    @Mock
    private ObjectStorage objectStorage;

    private IngestionService service;

    @BeforeEach
    void setUp() {
        service = newService(new MockEmbeddingClient(8));
    }

    @Test
    void ingestsMarkdownEndToEnd() {
        when(repository.ensureKnowledgeBase()).thenReturn("kb-1");

        IngestionResult result = service.ingest("README.md", "text/markdown",
                "# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.chunkCount()).isGreaterThan(0);
        assertThat(result.fileKey()).startsWith("test_collection/").endsWith(".md");

        verify(objectStorage).put(anyString(), any(byte[].class), eq("text/markdown"));
        verify(repository).insertDocument(anyString(), eq("kb-1"), eq("README.md"), anyString(),
                eq("md"), eq("text/markdown"), anyLong(), anyString());
        verify(repository).replaceDocumentData(anyString(), eq("kb-1"), eq("test_collection"),
                eq("README.md"), anyList(), anyList());
        verify(repository).updateDocumentStatus(anyString(), eq("success"), eq(result.chunkCount()));
        verify(repository).insertChunkLog(anyString(), eq("success"), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong(), eq(result.chunkCount()), isNull(), any(), any());
    }

    @Test
    void rejectsUnsupportedExtensionBeforeTouchingStorageOrDatabase() {
        assertThatThrownBy(() -> service.ingest("manual.pdf", "application/pdf", new byte[]{1}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".md");

        verifyNoInteractions(objectStorage, repository);
    }

    @Test
    void marksDocumentFailedWhenEmbeddingFails() {
        when(repository.ensureKnowledgeBase()).thenReturn("kb-1");
        EmbeddingClient failing = mock(EmbeddingClient.class);
        when(failing.embed(anyList())).thenThrow(new IllegalStateException("boom"));

        IngestionService failingService = newService(failing);

        assertThatThrownBy(() -> failingService.ingest("README.md", "text/markdown",
                "# 标题\n\n正文内容".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("boom");

        verify(repository).updateDocumentStatus(anyString(), eq("failed"), eq(0));
        verify(repository).deleteDocumentData(anyString(), eq("test_collection"));
        verify(repository).insertChunkLog(anyString(), eq("failed"), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong(), eq(0), eq("boom"), any(), any());
    }

    @Test
    void rejectsOversizedFile() {
        byte[] tooLarge = new byte[(int) PROPERTIES.maxFileSize().toBytes() + 1];

        assertThatThrownBy(() -> service.ingest("README.md", "text/markdown", tooLarge))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过限制");

        verifyNoInteractions(objectStorage, repository);
    }

    private IngestionService newService(EmbeddingClient embeddingClient) {
        return new IngestionService(
                new ParserRegistry(List.of(new MarkdownDocumentParser())),
                new HeadingAwareChunker(),
                embeddingClient,
                objectStorage,
                repository,
                new SnowflakeIdGenerator(),
                PROPERTIES);
    }
}
