package com.securitymodel.application.knowledge;

import com.securitymodel.domain.knowledge.KnowledgeChunk;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis 8 vector-set store. Redis is only a similarity index: ACL filtering still
 * happens in MySQL/Java before any chunk can become a citation. If Redis is absent or
 * the vector command fails, callers fall back to the existing lexical ranker.
 */
@Component
public class KnowledgeVectorStore {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStore.class);
    private static final String DEFAULT_KEY = "security_model:knowledge:vectors:v1";

    private final StringRedisTemplate redis;
    private final KnowledgeChunkMapper chunkMapper;
    private final LocalEmbeddingModel embedding;
    private final String key;
    private final int limit;
    private final boolean enabled;

    public KnowledgeVectorStore(
            StringRedisTemplate redis,
            KnowledgeChunkMapper chunkMapper,
            LocalEmbeddingModel embedding,
            @Value("${security-model.knowledge.vector.enabled:true}") boolean enabled,
            @Value("${security-model.knowledge.vector.key:" + DEFAULT_KEY + "}") String key,
            @Value("${security-model.knowledge.vector.candidate-limit:40}") int limit) {
        this.redis = redis;
        this.chunkMapper = chunkMapper;
        this.embedding = embedding;
        this.key = key == null || key.isBlank() ? DEFAULT_KEY : key;
        this.limit = Math.max(1, Math.min(200, limit));
        this.enabled = enabled && embedding.enabled();
    }

    public boolean enabled() {
        return enabled;
    }

    /** Index one document's chunks. Returns how many vectors were written. */
    public int indexDocument(Long documentId) {
        if (!enabled || documentId == null) {
            return 0;
        }
        List<KnowledgeChunk> chunks = chunkMapper.findByDocumentId(documentId);
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (KnowledgeChunk chunk : chunks) {
            float[] vector = embedding.embed(chunk.getContent());
            if (vector.length == 0) {
                continue;
            }
            List<String> args = new ArrayList<>(vector.length + 5);
            args.add(key);
            args.add("VALUES");
            args.add(Integer.toString(embedding.dimension()));
            for (float value : vector) {
                args.add(Float.toString(value));
            }
            args.add("c:" + chunk.getChunkId());
            if (execute("VADD", args) != null) {
                written++;
            }
        }
        log.info("知识向量索引完成: documentId={}, chunks={}, written={}", documentId,
                chunks.size(), written);
        return written;
    }

    /** Remove vectors for a retired document version; stale hits are also filtered by ACL. */
    public void removeDocument(Long documentId) {
        if (!enabled || documentId == null) {
            return;
        }
        List<KnowledgeChunk> chunks = chunkMapper.findByDocumentId(documentId);
        if (chunks == null) {
            return;
        }
        for (KnowledgeChunk chunk : chunks) {
            execute("VREM", List.of(key, "c:" + chunk.getChunkId()));
        }
        log.info("知识向量清理完成: documentId={}, chunks={}", documentId, chunks.size());
    }

    public List<VectorHit> search(String question, int count) {
        if (!enabled || question == null || question.isBlank()) {
            return List.of();
        }
        float[] vector = embedding.embed(question);
        List<String> args = new ArrayList<>(vector.length + 6);
        args.add(key);
        args.add("VALUES");
        args.add(Integer.toString(embedding.dimension()));
        for (float value : vector) {
            args.add(Float.toString(value));
        }
        args.add("WITHSCORES");
        args.add("COUNT");
        args.add(Integer.toString(Math.max(1, Math.min(limit, count))));
        Object result = executeList("VSIM", args);
        return parse(result);
    }

    public boolean ping() {
        if (!enabled) {
            return false;
        }
        RedisConnection connection = null;
        try {
            connection = redis.getConnectionFactory().getConnection();
            String pong = connection.ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // already closed
                }
            }
        }
    }

    private Object executeList(String command, List<String> args) {
        RedisConnection connection = null;
        try {
            connection = redis.getConnectionFactory().getConnection();
            if (!(connection instanceof LettuceConnection lettuce)) {
                return null;
            }
            byte[][] encoded = new byte[args.size()][];
            for (int i = 0; i < args.size(); i++) {
                encoded[i] = args.get(i).getBytes(StandardCharsets.UTF_8);
            }
            return lettuce.execute(command, new NestedMultiOutput<>(ByteArrayCodec.INSTANCE), encoded);
        } catch (RuntimeException exception) {
            log.warn("Redis 向量查询命令失败: command={}, reason={}", command,
                    exception.getClass().getSimpleName());
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // connection may already be closed by the factory
                }
            }
        }
    }

    private Object execute(String command, List<String> args) {
        RedisConnection connection = null;
        try {
            connection = redis.getConnectionFactory().getConnection();
            if (!(connection instanceof LettuceConnection lettuce)) {
                return null;
            }
            byte[][] encoded = new byte[args.size()][];
            for (int i = 0; i < args.size(); i++) {
                encoded[i] = args.get(i).getBytes(StandardCharsets.UTF_8);
            }
            return lettuce.execute(command, encoded);
        } catch (RuntimeException exception) {
            log.debug("Redis 向量命令失败: command={}, reason={}", command,
                    exception.getClass().getSimpleName());
            return null;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (RuntimeException ignored) {
                    // connection may already be closed by the factory
                }
            }
        }
    }

    private List<VectorHit> parse(Object result) {
        if (result == null) {
            log.debug("Redis VSIM 返回 null");
            return List.of();
        }
        if (!(result instanceof List<?> list)) {
            log.debug("Redis VSIM 返回类型异常: {}", result.getClass().getName());
            return List.of();
        }
        if (list.isEmpty()) {
            log.debug("Redis VSIM 返回空列表");
            return List.of();
        }
        List<VectorHit> hits = new ArrayList<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            String element = asText(list.get(i));
            String scoreText = asText(list.get(i + 1));
            if (!element.startsWith("c:") || scoreText == null) {
                log.debug("Redis VSIM 元素格式异常: element={}, score={}", element, scoreText);
                continue;
            }
            try {
                hits.add(new VectorHit(Long.parseLong(element.substring(2)),
                        Double.parseDouble(scoreText)));
            } catch (NumberFormatException ignored) {
                log.debug("Redis VSIM 元素解析失败: element={}, score={}", element, scoreText);
            }
        }
        return hits;
    }

    private String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    public record VectorHit(Long chunkId, double score) {
    }
}
