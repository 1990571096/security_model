package com.securitymodel.infrastructure.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Optional Redis cache for read-heavy resource metadata.
 *
 * <p>MySQL remains the source of truth. Redis failures are deliberately swallowed so a
 * cache outage cannot block resource reads or writes. A per-key generation is incremented
 * on commit-time eviction; a reader that missed before the eviction may only refill when
 * the generation it observed is still current. That prevents a slow reader from putting a
 * pre-update value back into the cache.</p>
 */
@Component
public class ResourceCatalogCache {
    private static final Logger log = LoggerFactory.getLogger(ResourceCatalogCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration GENERATION_TTL = Duration.ofDays(1);
    private static final String GENERATION_SUFFIX = ":generation";

    public static final String DATASET_LIST = "security-model:resource:dataset:list";
    public static final String ALGORITHM_LIST = "security-model:resource:algorithm:list";
    public static final String HYPER_LIST = "security-model:resource:hyper:list";

    private static final DefaultRedisScript<Long> PUT_IF_GENERATION = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]); "
                    + "if current == false then current = '0' end; "
                    + "if current == ARGV[2] then "
                    + "redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[3]); return 1 "
                    + "else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Counter hits;
    private final Counter misses;
    private final Counter degraded;
    private final Counter staleFillSkipped;

    public ResourceCatalogCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry registry) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.hits = Counter.builder("security_model_cache_requests")
                .description("Resource cache reads by bounded result")
                .tag("result", "hit")
                .register(registry);
        this.misses = Counter.builder("security_model_cache_requests")
                .description("Resource cache reads by bounded result")
                .tag("result", "miss")
                .register(registry);
        this.degraded = Counter.builder("security_model_cache_requests")
                .description("Resource cache reads by bounded result")
                .tag("result", "degraded")
                .register(registry);
        this.staleFillSkipped = Counter.builder("security_model_cache_stale_fill_skipped_total")
                .description("Refills rejected because the key generation changed")
                .register(registry);
    }

    public <T> Optional<List<T>> getList(String key, Class<T> itemType) {
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, itemType);
        return read(key, type);
    }

    public <T> Optional<T> get(String key, Class<T> type) {
        return read(key, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * Read-through with generation-safe refill. A Redis miss or failure always falls back to
     * the supplied MySQL loader; a refill is skipped when the eviction generation moved while
     * the loader was running.
     */
    public <T> Optional<T> getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        return loadThrough(key, objectMapper.getTypeFactory().constructType(type), loader);
    }

    public <T> Optional<List<T>> getListOrLoad(String key, Class<T> itemType, Supplier<List<T>> loader) {
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, itemType);
        return loadThrough(key, type, loader);
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
        for (String key : keys) {
            bumpGeneration(key);
        }
        try {
            redis.delete(List.of(keys));
        } catch (RuntimeException exception) {
            log.debug("Redis resource cache eviction skipped", exception);
        }
    }

    private void bumpGeneration(String key) {
        try {
            Long generation = redis.opsForValue().increment(generationKey(key));
            if (generation != null) {
                redis.expire(generationKey(key), GENERATION_TTL);
            }
        } catch (RuntimeException exception) {
            // A failed bump is a cache failure, not a business failure. The cached value may
            // survive until TTL; the next successful eviction or TTL expiry clears it.
            log.debug("Redis resource cache generation bump skipped for key {}", key, exception);
        }
    }

    private <T> Optional<T> loadThrough(String key, JavaType type, Supplier<T> loader) {
        long generation = generation(key);
        Optional<T> cached = readInternal(key, type, false);
        if (cached.isPresent()) {
            long afterRead = generation(key);
            if (afterRead < 0 || afterRead == generation) {
                hits.increment();
                return cached;
            }
            // The value was evicted while it was being read; treat it as a miss and
            // refill only under the new generation.
            generation = afterRead;
        }
        misses.increment();
        T loaded = loader.get();
        if (loaded != null && generation >= 0 && putIfGeneration(key, loaded, generation)) {
            return Optional.of(loaded);
        }
        if (loaded != null && generation >= 0) {
            staleFillSkipped.increment();
        }
        return Optional.ofNullable(loaded);
    }

    private long generation(String key) {
        try {
            String raw = redis.opsForValue().get(generationKey(key));
            return raw == null || raw.isBlank() ? 0 : Long.parseLong(raw);
        } catch (RuntimeException exception) {
            degraded.increment();
            log.debug("Redis resource cache generation read skipped for key {}", key, exception);
            return -1;
        }
    }

    private boolean putIfGeneration(String key, Object value, long generation) {
        try {
            Long accepted = redis.execute(PUT_IF_GENERATION, List.of(generationKey(key), key),
                    objectMapper.writeValueAsString(value), String.valueOf(generation),
                    String.valueOf(TTL.toMillis()));
            return accepted != null && accepted == 1L;
        } catch (RuntimeException | JsonProcessingException exception) {
            degraded.increment();
            log.debug("Redis resource cache conditional write skipped for key {}", key, exception);
            return false;
        }
    }

    private <T> Optional<T> read(String key, JavaType type) {
        return readInternal(key, type, true);
    }

    private <T> Optional<T> readInternal(String key, JavaType type, boolean countMetrics) {
        try {
            String value = redis.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                if (countMetrics) {
                    misses.increment();
                }
                return Optional.empty();
            }
            if (countMetrics) {
                hits.increment();
            }
            return Optional.ofNullable(objectMapper.readValue(value, type));
        } catch (RuntimeException | JsonProcessingException exception) {
            if (countMetrics) {
                degraded.increment();
            }
            log.debug("Redis resource cache read skipped for key {}", key, exception);
            return Optional.empty();
        }
    }

    private String generationKey(String key) {
        return key + GENERATION_SUFFIX;
    }
}
