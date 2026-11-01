package com.ragent.embedding;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 无外部依赖的确定性 embedding：同一文本永远得到同一向量，
 * 只用于没有 API Key 时跑通入库链路，向量不具备语义检索能力。
 */
public class MockEmbeddingClient implements EmbeddingClient {

    private final int dimension;

    public MockEmbeddingClient(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("embedding dimension 必须大于 0");
        }
        this.dimension = dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(vectorFor(text));
        }
        return vectors;
    }

    private float[] vectorFor(String text) {
        byte[] seed = sha256(text == null ? "" : text);
        float[] vector = new float[dimension];
        int filled = 0;
        int counter = 0;
        while (filled < dimension) {
            byte[] block = sha256(seed, counter++);
            for (int offset = 0; offset + 4 <= block.length && filled < dimension; offset += 4) {
                int value = ((block[offset] & 0xff) << 24)
                        | ((block[offset + 1] & 0xff) << 16)
                        | ((block[offset + 2] & 0xff) << 8)
                        | (block[offset + 3] & 0xff);
                vector[filled++] = value / (float) Integer.MAX_VALUE;
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
        return vector;
    }

    private static byte[] sha256(byte[] seed, int counter) {
        MessageDigest digest = newDigest();
        digest.update(seed);
        digest.update(ByteBuffer.allocate(4).putInt(counter).array());
        return digest.digest();
    }

    private static byte[] sha256(String text) {
        return newDigest().digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
