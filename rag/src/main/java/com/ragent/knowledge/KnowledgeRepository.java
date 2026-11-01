package com.ragent.knowledge;

import com.ragent.chunk.model.TextChunk;
import com.ragent.common.JsonSupport;
import com.ragent.common.SnowflakeIdGenerator;
import com.ragent.config.IngestionProperties;
import com.ragent.knowledge.model.ChunkView;
import com.ragent.knowledge.model.DocumentView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class KnowledgeRepository {

    private static final String SYSTEM_USER = "system";

    private final JdbcTemplate jdbcTemplate;
    private final IngestionProperties properties;
    private final SnowflakeIdGenerator idGenerator;

    public KnowledgeRepository(JdbcTemplate jdbcTemplate,
                               IngestionProperties properties,
                               SnowflakeIdGenerator idGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    /**
     * 按 collection_name 复用 ragent 已有的知识库；不存在则创建一个。
     */
    public String ensureKnowledgeBase() {
        List<String> ids = jdbcTemplate.queryForList(
                "select id from t_rag_knowledge_base where collection_name = ? and deleted = 0 limit 1",
                String.class, properties.collectionName());
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        String kbId = idGenerator.nextId();
        try {
            jdbcTemplate.update("""
                    insert into t_rag_knowledge_base
                        (id, name, embedding_model, collection_name, created_by)
                    values (?, ?, ?, ?, ?)
                    """, kbId, properties.kbName(), properties.embeddingModel(),
                    properties.collectionName(), SYSTEM_USER);
            return kbId;
        } catch (DuplicateKeyException ex) {
            return jdbcTemplate.queryForObject(
                    "select id from t_rag_knowledge_base where collection_name = ? and deleted = 0",
                    String.class, properties.collectionName());
        }
    }

    public void insertDocument(String docId, String kbId, String docName, String fileKey,
                               String fileType, String mimeType, long fileSize, String ingestionSpecJson) {
        jdbcTemplate.update("""
                insert into t_rag_knowledge_document
                    (id, kb_id, doc_name, enabled, chunk_count, file_url, file_type, mime_type,
                     file_size, process_mode, status, source_type, ingestion_spec, created_by)
                values (?, ?, ?, 1, 0, ?, ?, ?, ?, 'chunk', 'running', 'file', ?::jsonb, ?)
                """, docId, kbId, docName, fileKey, fileType, mimeType, fileSize, ingestionSpecJson, SYSTEM_USER);
    }

    public void updateDocumentStatus(String docId, String status, int chunkCount) {
        jdbcTemplate.update("""
                update t_rag_knowledge_document
                   set status = ?, chunk_count = ?, update_time = current_timestamp
                 where id = ?
                """, status, chunkCount, docId);
    }

    /**
     * 分块与向量在同一事务内落库；重复入库时先清理旧数据，保证幂等。
     */
    @Transactional
    public void replaceDocumentData(String docId, String kbId, String collectionName, String docName,
                                    List<TextChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("分块数与向量数不一致：" + chunks.size() + " vs " + vectors.size());
        }
        deleteDocumentData(docId, collectionName);
        if (chunks.isEmpty()) {
            return;
        }

        List<PersistChunk> rows = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            rows.add(new PersistChunk(idGenerator.nextId(), chunks.get(i), vectors.get(i)));
        }

        jdbcTemplate.batchUpdate("""
                insert into t_rag_knowledge_chunk
                    (id, kb_id, doc_id, chunk_index, content, content_hash, char_count,
                     token_count, embedding_text, enabled, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
                """, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.id());
            ps.setString(2, kbId);
            ps.setString(3, docId);
            ps.setInt(4, row.chunk().index());
            ps.setString(5, row.chunk().content());
            ps.setString(6, sha256(row.chunk().content()));
            ps.setInt(7, row.chunk().charCount());
            ps.setNull(8, Types.INTEGER);
            ps.setString(9, row.chunk().embeddingText());
            ps.setString(10, SYSTEM_USER);
        });

        jdbcTemplate.batchUpdate("""
                insert into t_rag_knowledge_vector
                    (id, collection_name, content, metadata, embedding)
                values (?, ?, ?, ?::jsonb, ?::vector)
                """, rows, rows.size(), (ps, row) -> {
            ps.setString(1, row.id());
            ps.setString(2, collectionName);
            ps.setString(3, row.chunk().content());
            ps.setString(4, metadataJson(docId, docName, row.chunk()));
            ps.setString(5, vectorLiteral(row.vector()));
        });
    }

    public void deleteDocumentData(String docId, String collectionName) {
        jdbcTemplate.update("delete from t_rag_knowledge_chunk where doc_id = ?", docId);
        jdbcTemplate.update(
                "delete from t_rag_knowledge_vector where collection_name = ? and metadata->>'doc_id' = ?",
                collectionName, docId);
    }

    public void insertChunkLog(String docId, String status, long extractDuration, long chunkDuration,
                               long embedDuration, long persistDuration, long totalDuration,
                               int chunkCount, String errorMessage, Instant startTime, Instant endTime) {
        jdbcTemplate.update("""
                insert into t_rag_knowledge_document_chunk_log
                    (id, doc_id, status, process_mode, parse_profile, extract_duration, chunk_duration,
                     embed_duration, persist_duration, total_duration, chunk_count, error_message,
                     start_time, end_time)
                values (?, ?, ?, 'chunk', 'fast', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, idGenerator.nextId(), docId, status, extractDuration, chunkDuration, embedDuration,
                persistDuration, totalDuration, chunkCount, truncate(errorMessage, 2000),
                Timestamp.from(startTime), Timestamp.from(endTime));
    }

    public Optional<DocumentView> findDocument(String docId) {
        List<DocumentView> rows = jdbcTemplate.query("""
                select id, doc_name, status, coalesce(chunk_count, 0) as chunk_count, file_url,
                       file_type, mime_type, coalesce(file_size, 0) as file_size, create_time
                  from t_rag_knowledge_document
                 where id = ? and deleted = 0
                """, (rs, rowNum) -> toDocumentView(rs), docId);
        return rows.stream().findFirst();
    }

    public List<DocumentView> listDocuments(int limit) {
        return jdbcTemplate.query("""
                select id, doc_name, status, coalesce(chunk_count, 0) as chunk_count, file_url,
                       file_type, mime_type, coalesce(file_size, 0) as file_size, create_time
                  from t_rag_knowledge_document
                 where deleted = 0
                 order by create_time desc
                 limit ?
                """, (rs, rowNum) -> toDocumentView(rs), limit);
    }

    public List<ChunkView> listChunks(String docId, int limit) {
        return jdbcTemplate.query("""
                select id, chunk_index, content, coalesce(char_count, 0) as char_count
                  from t_rag_knowledge_chunk
                 where doc_id = ? and deleted = 0
                 order by chunk_index
                 limit ?
                """, (rs, rowNum) -> new ChunkView(
                rs.getString("id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getInt("char_count")), docId, limit);
    }

    private static DocumentView toDocumentView(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp createTime = rs.getTimestamp("create_time");
        return new DocumentView(
                rs.getString("id"),
                rs.getString("doc_name"),
                rs.getString("status"),
                rs.getInt("chunk_count"),
                rs.getString("file_url"),
                rs.getString("file_type"),
                rs.getString("mime_type"),
                rs.getLong("file_size"),
                createTime == null ? null : createTime.toInstant());
    }

    private static String metadataJson(String docId, String docName, TextChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_id", docId);
        metadata.put("chunk_index", chunk.index());
        metadata.put("doc_name", docName);
        metadata.put("heading_path", chunk.headingPath());
        metadata.put("source_file", docName);
        return JsonSupport.toJson(metadata);
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder(embedding.length * 12);
        builder.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record PersistChunk(String id, TextChunk chunk, float[] vector) {
    }
}
