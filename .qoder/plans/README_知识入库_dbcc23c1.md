# README 知识入库模块

## 目标与范围

- 在 `rag/` 下新建独立 Maven 工程（Spring Boot 4.1.0，release 17），不改动 `/home/naraka/project1java/ragent` 仓库。
- 本期只接收 `.md` / `.markdown`（README），跑通：上传 → 原文存 S3 → 解析 → 结构化分块 → 向量化 → 写 PG。
- 复用现有运行中的 `ragent-postgres`（pgvector）和 `ragent-minio`（9000），直接写 `t_knowledge_base / t_knowledge_document / t_knowledge_chunk / t_knowledge_vector`，不新增 DDL。
- 同一套数据可被 ragent 的检索链路读取（collection + `qwen-emb-8b` 对应 text-embedding-v4），便于后续对照验证。

## 数据流

```
POST /api/knowledge/documents (multipart file)
  → 类型门禁 ParserRegistry（不支持直接 400，并列出当前支持的后缀）
  → 原文上传 S3：bucket=ragent-sources, key={collection}/{docId}.md
  → 插入 t_knowledge_document，status=running
  → MarkdownDocumentParser → List<DocBlock>（标题/段落/代码/列表/表格）
  → HeadingAwareChunker → List<TextChunk>（带 headingPath、content、embeddingText）
  → EmbeddingClient 批量向量化（1536 维，维度校验）
  → 事务内写 t_knowledge_chunk + t_knowledge_vector，更新文档 status=success + chunk_count
  → 任一步失败：status=failed，写 t_knowledge_document_chunk_log 记录耗时与错误
```

同步入库（README 体积小）。后续异步化只需把 Controller 到 `IngestionService` 的调用替换为 @Async/MQ，链路内部不变。

## 扩展点设计（多格式能力）

1. `DocumentParser` SPI：声明 `supportedExtensions()` + `supportedMimeTypes()` + `parse(bytes, filename, mime)`。
2. `ParserRegistry`：启动期建立「扩展名/MIME → 解析器」索引；重复认领直接启动失败；未命中抛业务异常。
3. 统一 `DocBlock` 密封接口：`Heading / Paragraph / Code / ListItem / Table`，所有格式先归一化到 Block，分块与落库不感知源格式。
4. 以后加 PDF：新增 `PdfDocumentParser implements DocumentParser`（认领 `pdf`）即可，Chunker、IngestionService、Controller 均不改。
5. `EmbeddingClient`、`ObjectStorage` 均为接口，百炼/mock、S3 实现可替换。

## 新增文件与职责

包根 `com.ragent`，全部位于 `rag/src/main/java/com/ragent/`：

- `RagIngestionApplication.java`：启动类。
- `config/IngestionProperties.java`、`EmbeddingProperties.java`、`StorageProperties.java`：`rag.ingestion.* / rag.embedding.* / rag.storage.*` 配置绑定与校验。
- `config/S3Config.java`、`EmbeddingConfig.java`：构造 `S3Client`、`RestClient`。
- `common/SnowflakeIdGenerator.java`：19 位雪花 ID（符合 VARCHAR(20)）。
- `common/BusinessException.java`、`web/GlobalExceptionHandler.java`：400/413/500 统一 JSON 错误。
- `parser/DocumentParser.java`、`parser/ParserRegistry.java`。
- `parser/model/ParsedDocument.java`（title、kind、blocks）、`parser/model/DocBlock.java`（sealed interface + 5 个 record）。
- `parser/markdown/MarkdownDocumentParser.java`：commonmark 0.22.0 + GFM Tables，抽顶层 Heading/Paragraph/FencedCode/BulletList/OrderedList/TableBlock；图片暂忽略（无多模态）；标题取首个 H1，缺失时用文件名。
- `chunk/HeadingAwareChunker.java`、`chunk/model/TextChunk.java`：
  - 按标题栈切分为逻辑章节，每块记录 `headingPath`；
  - 章节 ≤ maxChars 整块输出；超长按段落/行切分并保留 overlap；
  - 代码块尽量整块，超长才按行切；表格按行切；
  - 不足 minChars 的尾部块并入前一块（合并后不超 maxChars）；
  - `content` = 标题行 + 正文；`embeddingText` = `H1 > H2` 面包屑 + 正文，提升召回。
- `embedding/EmbeddingClient.java`：`List<float[]> embed(List<String> texts)`。
- `embedding/OpenAiCompatibleEmbeddingClient.java`：POST `{base-url}/compatible-mode/v1/embeddings`，model=`text-embedding-v4`，dimensions=1536，batch=10，429/5xx 重试 3 次；校验返回条数与维度。
- `embedding/MockEmbeddingClient.java`：`rag.embedding.provider=mock` 时启用，SHA-256 派生确定性 1536 维归一化向量，仅用于无 key 的本地联调。
- `storage/ObjectStorage.java`、`storage/S3ObjectStorage.java`、`storage/BucketInitializer.java`：AWS SDK v2，path-style，端点可配；启动时确保 `ragent-sources` 桶存在。
- `knowledge/KnowledgeRepository.java`：JdbcTemplate 实现
  - `ensureKnowledgeBase()`：按 `collection_name` 查 `t_knowledge_base`，不存在则插入（name=README 知识库，embedding_model=qwen-emb-8b，created_by=system）；
  - 文档 insert / 状态更新 / chunk 批量 insert / vector 批量 insert（`?::jsonb`、`?::vector`）/ 按 docId 清理 / chunk 日志 insert / 查询。
- `knowledge/IngestionService.java`、`IngestionResult.java`：串起全链路，失败兜底改状态并记日志；`persist()` 加 `@Transactional`。
- `knowledge/KnowledgeDocumentController.java`：
  - `POST /api/knowledge/documents`（multipart `file`）→ `{docId, docName, status, chunkCount, fileUrl, durationMs}`；
  - `GET /api/knowledge/documents/{docId}`；
  - `GET /api/knowledge/documents/{docId}/chunks?limit=50`；
  - `GET /api/knowledge/documents?limit=20`。
- `src/main/resources/application.yaml`：端口 9091，数据源/S3/embedding/分块参数。
- 测试：`MarkdownDocumentParserTest`、`HeadingAwareChunkerTest`、`ParserRegistryTest`、`IngestionServiceTest`（fake 依赖），及 `src/test/resources/readme-sample.md`。
- 更新 `.gitignore`：追加 `target/`、`.idea/`、`*.iml`。

## 表与字段约定

- 文档：`status=running|success|failed`，`process_mode=chunk`，`source_type=file`，`created_by=system`，`file_url` 存 S3 裸 key（沿用 ragent 约定），`ingestion_spec` 写 `{"version":1,"parser":"markdown","chunk":{"maxChars":1024,"overlapChars":128,"minChars":200}}`。
- 分块：`id` 与向量表主键相同；`content_hash`=SHA-256；`char_count`=字符数；`token_count` 留空；`embedding_text` 存面包屑文本。
- 向量：`collection_name` 取 KB 配置；`metadata` 写 `{doc_id, chunk_index, doc_name, heading_path, source_file}`；embedding 必须 1536 维。
- 日志：每次入库写一条 `t_knowledge_document_chunk_log`，记录 extract/chunk/embed/persist/total 耗时与错误信息。

## 配置要点

```yaml
server.port: 9091
spring:
  datasource:
    url: ${POSTGRES_URL:jdbc:postgresql://localhost:5432/ragent}
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
rag:
  ingestion:
    collection-name: readme_ingest
    kb-name: README 知识库
    embedding-model: qwen-emb-8b
    max-file-size: 10MB
    chunk: { max-chars: 1024, overlap-chars: 128, min-chars: 200 }
  embedding:
    provider: ${RAG_EMBEDDING_PROVIDER:bailian}
    base-url: https://dashscope.aliyuncs.com
    api-key: ${BAILIAN_API_KEY:}
    model: text-embedding-v4
    dimension: 1536
    batch-size: 10
  storage:
    endpoint: ${S3_ENDPOINT:http://localhost:9000}
    access-key: ${S3_ACCESS_KEY:rustfsadmin}
    secret-key: ${S3_SECRET_KEY:rustfsadmin}
    bucket: ragent-sources
    path-style: true
```

`provider=bailian` 且 api-key 为空时启动即失败并提示导出 `BAILIAN_API_KEY`；本地无 key 时用 `--rag.embedding.provider=mock` 联调。

## 测试与验收

1. `cd rag && mvn -q test`：解析器、分块器、注册表、服务编排全部通过。
2. mock 模式联调：
   ```
   POSTGRES_PASSWORD=postgres mvn spring-boot:run -Dspring-boot.run.arguments="--rag.embedding.provider=mock --rag.ingestion.collection-name=readme_dev"
   curl -F "file=@/home/naraka/project1java/ragent/README.md" http://localhost:9091/api/knowledge/documents
   curl http://localhost:9091/api/knowledge/documents/{docId}
   curl http://localhost:9091/api/knowledge/documents/{docId}/chunks
   ```
3. 数据核对：
   ```
   docker exec ragent-postgres psql -U postgres -d ragent -c \
     "select id,status,chunk_count from t_knowledge_document where id='{docId}'"
   docker exec ragent-postgres psql -U postgres -d ragent -c \
     "select count(*),min(vector_dims(embedding)) from t_knowledge_vector where metadata->>'doc_id'='{docId}'"
   ```
   另需确认 MinIO `ragent-sources` 下存在对应对象。
4. 真实向量：导出 `BAILIAN_API_KEY` 后以默认 `readme_ingest` 再传一次，确认向量维度 1536 且调用耗时正常。

## 明确不做

- 知识库 CRUD/权限、前端页面、异步 MQ、定时刷新、去重与增量更新、PDF/DOCX 等解析、检索与问答链路。
- 不修改 ragent 仓库、现有 `docker-compose.yaml` 和 `application-local.yaml`。

## 假设

- 使用当前运行中的 `ragent-postgres:5432` 与 `ragent-minio:9000`（与工作区 compose 的 RustFS 同为 S3 协议，配置走环境变量即可切换）。
- 代码包名用 `com.ragent`，端口 9091，默认知识库 collection 为 `readme_ingest`。