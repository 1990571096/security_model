package com.securitymodel.application.knowledge;

import com.securitymodel.domain.knowledge.KnowledgeChunk;
import com.securitymodel.infrastructure.persistence.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in real Redis 8 test. It proves ACL filtering happens inside VSIM, before the
 * candidate window is returned, rather than by dropping already-ranked global hits.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
class KnowledgeVectorStoreRedisIT {

    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private KnowledgeVectorStore store;
    private String key;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        key = "security-model:it:vectors:" + UUID.randomUUID();
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        when(mapper.findByDocumentId(anyLong())).thenAnswer(invocation -> chunks(
                invocation.getArgument(0)));
        store = new KnowledgeVectorStore(redis, mapper, new LocalEmbeddingModel(true),
                true, key, 40);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            redis.delete(key);
        }
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void privateChunksFromAnotherUserCannotOccupyTheResultWindow() {
        assertEquals(1, store.indexDocument(1L, "shared", null));
        assertEquals(1, store.indexDocument(2L, "private", 7L));
        assertEquals(50, store.indexDocument(3L, "private", 8L),
                "integer VADD replies must be counted as accepted writes");

        KnowledgeVectorStore.SearchResult user7 = store.search(
                "受控助手如何防止越权访问其他用户的任务？", 5, 7L, false);

        assertTrue(user7.available());
        assertFalse(user7.hits().isEmpty());
        assertTrue(user7.hits().stream().allMatch(hit -> hit.chunkId() == 1L || hit.chunkId() == 2L),
                "only shared or caller-owned chunks may enter user 7's candidate window");

        KnowledgeVectorStore.SearchResult admin = store.search(
                "受控助手如何防止越权访问其他用户的任务？", 5, null, true);
        assertTrue(admin.hits().stream().anyMatch(hit -> hit.chunkId() >= 3L && hit.chunkId() <= 52L),
                "admin may see owner 8's private chunks");
    }

    @Test
    void removeDocumentRemovesItsVectors() {
        store.indexDocument(2L, "private", 7L);
        store.removeDocument(2L);

        KnowledgeVectorStore.SearchResult result = store.search(
                "受控助手如何防止越权访问其他用户的任务？", 5, 7L, false);
        assertTrue(result.hits().stream().noneMatch(hit -> hit.chunkId() == 2L));
    }

    private List<KnowledgeChunk> chunks(Long documentId) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        if (documentId == 1L) {
            chunks.add(chunk(1L, "受控助手如何防止越权访问其他用户的任务？"));
            return chunks;
        }
        if (documentId == 2L) {
            chunks.add(chunk(2L, "受控助手如何防止越权访问其他用户的任务？"));
            return chunks;
        }
        // Many highly similar private chunks from user 8 would fill a global top-5.
        for (long id = 3L; id < 53L; id++) {
            chunks.add(chunk(id, "受控助手如何防止越权访问其他用户的任务？"));
        }
        return chunks;
    }

    private KnowledgeChunk chunk(Long chunkId, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setChunkId(chunkId);
        chunk.setDocumentId(3L);
        chunk.setChunkIndex(0);
        chunk.setContent(content);
        return chunk;
    }
}
