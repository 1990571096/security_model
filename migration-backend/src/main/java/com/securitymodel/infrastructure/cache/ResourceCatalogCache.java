package com.securitymodel.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Optional Redis cache for read-heavy resource metadata.
 *
 * MySQL remains the source of truth. Redis failures are deliberately swallowed
 * so a cache outage cannot block resource reads or writes.
 */
@Component
public class ResourceCatalogCache {
    private static final Logger log = LoggerFactory.getLogger(ResourceCatalogCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    public static final String DATASET_LIST = "security-model:resource:dataset:list";
    public static final String ALGORITHM_LIST = "security-model:resource:algorithm:list";
    public static final String HYPER_LIST = "security-model:resource:hyper:list";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public ResourceCatalogCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public <T> Optional<List<T>> getList(String key, Class<T> itemType) {
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, itemType);
        return read(key, type);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return read(key, objectMapper.getTypeFactory().constructType(type));
    }

    public void put(String key, Object value) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value), TTL);
        } catch (RuntimeException | JsonProcessingException exception) {
            log.debug("Redis resource cache write skipped for key {}", key, exception);
        }
    }

    public void evict(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }
        Runnable eviction = () -> evictNow(keys);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eviction.run();
                }
            });
        } else {
            eviction.run();
        }
    }

    private void evictNow(String... keys) {
        try {
            redis.delete(List.of(keys));
        } catch (RuntimeException exception) {
            log.debug("Redis resource cache eviction skipped", exception);
        }
    }

    private <T> Optional<T> read(String key, JavaType type) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(objectMapper.readValue(value, type));
        } catch (RuntimeException | JsonProcessingException exception) {
            log.debug("Redis resource cache read skipped for key {}", key, exception);
            return Optional.empty();
        }
    }
}
