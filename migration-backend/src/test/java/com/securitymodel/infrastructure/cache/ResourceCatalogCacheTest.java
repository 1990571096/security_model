package com.securitymodel.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResourceCatalogCacheTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ResourceCatalogCache cache = new ResourceCatalogCache(redis, new ObjectMapper());

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void redisReadFailureReturnsEmptySoCallerCanFallbackToMySql() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("resource")).thenThrow(new RuntimeException("redis unavailable"));

        assertEquals(Optional.empty(), cache.get("resource", String.class));
    }

    @Test
    void redisWriteFailureDoesNotEscapeToResourceCommand() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(new RuntimeException("redis unavailable"))
                .when(values).set(anyString(), anyString(), eq(Duration.ofMinutes(5)));

        assertDoesNotThrow(() -> cache.put("resource", "value"));
    }

    @Test
    void redisDeleteFailureDoesNotEscapeToResourceCommand() {
        doThrow(new RuntimeException("redis unavailable"))
                .when(redis).delete(anyCollection());

        assertDoesNotThrow(() -> cache.evict("resource"));
    }

    @Test
    void putUsesFiveMinuteTtl() {
        when(redis.opsForValue()).thenReturn(values);

        cache.put("resource", "value");

        verify(values).set("resource", "\"value\"", Duration.ofMinutes(5));
    }

    @Test
    void evictDeletesImmediatelyWithoutTransaction() {
        cache.evict("resource:list", "resource:1");

        verify(redis).delete(List.of("resource:list", "resource:1"));
    }

    @Test
    void evictWaitsForCommitWhenTransactionSynchronizationIsActive() {
        TransactionSynchronizationManager.initSynchronization();

        cache.evict("resource:list");

        verifyNoInteractions(redis);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().get(0);
        synchronization.afterCommit();

        verify(redis).delete(List.of("resource:list"));
    }

    @Test
    void blankRedisValueIsTreatedAsCacheMiss() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("resource")).thenReturn(" ");

        assertTrue(cache.get("resource", String.class).isEmpty());
    }
}
