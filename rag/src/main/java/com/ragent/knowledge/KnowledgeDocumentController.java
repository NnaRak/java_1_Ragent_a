package com.ragent.knowledge;

import com.ragent.common.BusinessException;
import com.ragent.knowledge.vo.ChunkResponse;
import com.ragent.knowledge.vo.DocumentResponse;
import com.ragent.knowledge.vo.IngestionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/knowledge/documents")
public class KnowledgeDocumentController {

    private static final int MAX_LIMIT = 200;

    private final IngestionService ingestionService;

    public KnowledgeDocumentController(IngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping
    public IngestionResponse upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "读取上传文件失败", ex);
        }
        IngestionResult result = ingestionService.ingest(file.getOriginalFilename(), file.getContentType(), bytes);
        return IngestionResponse.from(result);
    }

    @GetMapping("/{docId}")
    public DocumentResponse get(@PathVariable String docId) {
        return DocumentResponse.from(ingestionService.getDocument(docId));
    }

    @GetMapping("/{docId}/chunks")
    public List<ChunkResponse> chunks(@PathVariable String docId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return ingestionService.listChunks(docId, normalizeLimit(limit))
                .stream()
                .map(ChunkResponse::from)
                .toList();
    }

    @GetMapping
    public List<DocumentResponse> list(@RequestParam(defaultValue = "20") int limit) {
        return ingestionService.listDocuments(normalizeLimit(limit))
                .stream()
                .map(DocumentResponse::from)
                .toList();
    }

    private static int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
