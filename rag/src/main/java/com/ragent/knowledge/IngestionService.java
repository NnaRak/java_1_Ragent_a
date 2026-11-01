package com.ragent.knowledge;

import com.ragent.chunk.HeadingAwareChunker;
import com.ragent.chunk.model.TextChunk;
import com.ragent.common.BusinessException;
import com.ragent.common.JsonSupport;
import com.ragent.common.SnowflakeIdGenerator;
import com.ragent.config.IngestionProperties;
import com.ragent.embedding.EmbeddingClient;
import com.ragent.knowledge.model.ChunkView;
import com.ragent.knowledge.model.DocumentView;
import com.ragent.parser.DocumentParser;
import com.ragent.parser.ParserRegistry;
import com.ragent.parser.model.ParsedDocument;
import com.ragent.parser.model.RawDocument;
import com.ragent.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final ParserRegistry parserRegistry;
    private final HeadingAwareChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final ObjectStorage objectStorage;
    private final KnowledgeRepository repository;
    private final SnowflakeIdGenerator idGenerator;
    private final IngestionProperties properties;

    public IngestionService(ParserRegistry parserRegistry,
                            HeadingAwareChunker chunker,
                            EmbeddingClient embeddingClient,
                            ObjectStorage objectStorage,
                            KnowledgeRepository repository,
                            SnowflakeIdGenerator idGenerator,
                            IngestionProperties properties) {
        this.parserRegistry = parserRegistry;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.objectStorage = objectStorage;
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.properties = properties;
    }

    public IngestionResult ingest(String originalFilename, String contentType, byte[] bytes) {
        String filename = sanitizeFilename(originalFilename);
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException("上传文件不能为空");
        }
        if (bytes.length > properties.maxFileSize().toBytes()) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "文件大小超过限制：" + properties.maxFileSize());
        }

        DocumentParser parser = parserRegistry.require(filename, contentType);
        String extension = resolveExtension(filename, parser);
        String mimeType = resolveMimeType(contentType, parser);
        String kbId = repository.ensureKnowledgeBase();
        String docId = idGenerator.nextId();
        String fileKey = properties.collectionName() + "/" + docId + "." + extension;
        String ingestionSpec = ingestionSpec(parser);

        Instant startTime = Instant.now();
        long uploadStart = System.nanoTime();
        objectStorage.put(fileKey, bytes, mimeType);
        long uploadDuration = elapsedMillis(uploadStart);

        repository.insertDocument(docId, kbId, filename, fileKey, extension, mimeType,
                bytes.length, ingestionSpec);

        long extractDuration = 0;
        long chunkDuration = 0;
        long embedDuration = 0;
        long persistDuration = 0;
        try {
            long start = System.nanoTime();
            ParsedDocument parsed = parser.parse(new RawDocument(bytes, filename, contentType));
            extractDuration = elapsedMillis(start);

            start = System.nanoTime();
            List<TextChunk> chunks = chunker.chunk(parsed, properties.chunk());
            chunkDuration = elapsedMillis(start);
            if (chunks.isEmpty()) {
                throw new BusinessException("文件未解析出有效文本，未入库");
            }

            start = System.nanoTime();
            List<float[]> vectors = embeddingClient.embed(
                    chunks.stream().map(TextChunk::embeddingText).toList());
            embedDuration = elapsedMillis(start);
            validateVectors(vectors, chunks.size());

            start = System.nanoTime();
            repository.replaceDocumentData(docId, kbId, properties.collectionName(), filename, chunks, vectors);
            persistDuration = elapsedMillis(start);

            repository.updateDocumentStatus(docId, "success", chunks.size());
            long totalDuration = uploadDuration + extractDuration + chunkDuration + embedDuration + persistDuration;
            repository.insertChunkLog(docId, "success", extractDuration, chunkDuration, embedDuration,
                    persistDuration, totalDuration, chunks.size(), null, startTime, Instant.now());

            log.info("入库成功 docId={} docName={} chunks={} totalMs={}",
                    docId, filename, chunks.size(), totalDuration);
            return new IngestionResult(docId, filename, "success", chunks.size(), fileKey, totalDuration);
        } catch (RuntimeException ex) {
            long totalDuration = uploadDuration + extractDuration + chunkDuration + embedDuration + persistDuration;
            log.error("入库失败 docId={} docName={}", docId, filename, ex);
            markFailed(docId, extractDuration, chunkDuration, embedDuration, persistDuration,
                    totalDuration, startTime, ex);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "入库失败：" + ex.getMessage(), ex);
        }
    }

    public DocumentView getDocument(String docId) {
        return repository.findDocument(docId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "文档不存在：" + docId));
    }

    public List<DocumentView> listDocuments(int limit) {
        return repository.listDocuments(limit);
    }

    public List<ChunkView> listChunks(String docId, int limit) {
        getDocument(docId);
        return repository.listChunks(docId, limit);
    }

    private void markFailed(String docId, long extractDuration, long chunkDuration, long embedDuration,
                            long persistDuration, long totalDuration, Instant startTime, RuntimeException ex) {
        try {
            repository.updateDocumentStatus(docId, "failed", 0);
        } catch (RuntimeException updateError) {
            log.warn("更新文档失败状态时出错 docId={}", docId, updateError);
        }
        try {
            repository.deleteDocumentData(docId, properties.collectionName());
        } catch (RuntimeException deleteError) {
            log.warn("清理失败文档数据时出错 docId={}", docId, deleteError);
        }
        try {
            repository.insertChunkLog(docId, "failed", extractDuration, chunkDuration, embedDuration,
                    persistDuration, totalDuration, 0, ex.getMessage(), startTime, Instant.now());
        } catch (RuntimeException logError) {
            log.warn("写入入库日志时出错 docId={}", docId, logError);
        }
    }

    private void validateVectors(List<float[]> vectors, int expectedCount) {
        if (vectors == null || vectors.size() != expectedCount) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY,
                    "向量条数与分块数不一致，期望 " + expectedCount
                            + "，实际 " + (vectors == null ? 0 : vectors.size()));
        }
        int dimension = embeddingClient.dimension();
        for (int i = 0; i < vectors.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector == null || vector.length != dimension) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "第 " + i + " 个向量维度异常，期望 " + dimension
                                + "，实际 " + (vector == null ? 0 : vector.length));
            }
        }
    }

    private String ingestionSpec(DocumentParser parser) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("maxChars", properties.chunk().maxChars());
        chunk.put("overlapChars", properties.chunk().overlapChars());
        chunk.put("minChars", properties.chunk().minChars());

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("version", 1);
        spec.put("parser", parser.parserType());
        spec.put("chunk", chunk);
        return JsonSupport.toJson(spec);
    }

    private static String resolveExtension(String filename, DocumentParser parser) {
        String extension = ParserRegistry.extensionOf(filename);
        if (extension != null && parser.supportedExtensions().contains(extension)) {
            return extension;
        }
        return parser.supportedExtensions().stream().sorted().findFirst().orElse("md");
    }

    private static String resolveMimeType(String contentType, DocumentParser parser) {
        if (StringUtils.hasText(contentType)) {
            String normalized = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("text/")) {
                return normalized;
            }
        }
        return parser.supportedMimeTypes().stream().sorted().findFirst().orElse("text/markdown");
    }

    private static String sanitizeFilename(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.strip();
        return name.isEmpty() ? "README.md" : name;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
