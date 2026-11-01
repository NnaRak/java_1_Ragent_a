package com.ragent.embedding;

import com.ragent.common.BusinessException;
import com.ragent.config.EmbeddingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼 compatible-mode /v1/embeddings；任何 OpenAI 兼容端点都可以通过配置切换。
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final EmbeddingProperties properties;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException("rag.embedding.provider=bailian 但未设置 BAILIAN_API_KEY，"
                    + "请导出环境变量，或本地联调时加 --rag.embedding.provider=mock");
        }
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public int dimension() {
        return properties.dimension();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += properties.batchSize()) {
            int end = Math.min(texts.size(), start + properties.batchSize());
            vectors.addAll(embedBatch(texts.subList(start, end)));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= properties.maxRetries(); attempt++) {
            try {
                return call(batch);
            } catch (RestClientResponseException ex) {
                lastError = ex;
                int status = ex.getStatusCode().value();
                if (!retryable(status) || attempt == properties.maxRetries()) {
                    break;
                }
                log.warn("Embedding 调用返回 {}，第 {} 次重试，body={}", status, attempt, ex.getResponseBodyAsString());
            } catch (ResourceAccessException ex) {
                lastError = ex;
                if (attempt == properties.maxRetries()) {
                    break;
                }
                log.warn("Embedding 调用网络异常，第 {} 次重试：{}", attempt, ex.getMessage());
            }
            sleep(attempt);
        }
        throw new BusinessException(HttpStatus.BAD_GATEWAY,
                "Embedding 调用失败：" + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    private List<float[]> call(List<String> batch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.model());
        payload.put("input", batch);
        payload.put("dimensions", properties.dimension());
        payload.put("encoding_format", "float");

        Map<String, Object> response = restClient.post()
                .uri(properties.path())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return parse(response, batch.size());
    }

    private List<float[]> parse(Map<String, Object> response, int expected) {
        if (response == null || !(response.get("data") instanceof List<?> data)) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "Embedding 响应缺少 data 字段");
        }
        float[][] vectors = new float[expected][];
        int cursor = 0;
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> itemMap) || !(itemMap.get("embedding") instanceof List<?> values)) {
                continue;
            }
            int index = itemMap.get("index") instanceof Number number ? number.intValue() : cursor;
            if (index < 0 || index >= expected || vectors[index] != null) {
                index = nextFreeSlot(vectors);
            }
            if (index < 0) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "Embedding 返回条数超过请求条数");
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = ((Number) values.get(i)).floatValue();
            }
            if (vector.length != properties.dimension()) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY, "Embedding 维度不符，期望 "
                        + properties.dimension() + "，实际 " + vector.length);
            }
            vectors[index] = vector;
            cursor = index + 1;
        }
        List<float[]> result = new ArrayList<>(expected);
        for (float[] vector : vectors) {
            if (vector == null) {
                throw new BusinessException(HttpStatus.BAD_GATEWAY,
                        "Embedding 返回条数不足，期望 " + expected + "，实际 " + data.size());
            }
            result.add(vector);
        }
        return result;
    }

    private static int nextFreeSlot(float[][] vectors) {
        for (int i = 0; i < vectors.length; i++) {
            if (vectors[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "Embedding 重试被中断", ex);
        }
    }
}
