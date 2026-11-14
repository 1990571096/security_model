package com.securitymodel.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Opt-in real Redis test. Run with REDIS_IT=true; the normal/CI suite skips it because
 * GitHub Actions does not start Redis in the current workflow.
 */
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
class ResourceCatalogCacheRedisIT {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private ResourceCatalogCache cache;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        cache = new ResourceCatalogCache(redis, new ObjectMapper(), new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void evictionPreventsAnOlderReadFromRefillingTheCache() throws Exception {
        String key = "security-model:it:cache:" + UUID.randomUUID();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> oldRead = pool.submit(() -> cache.getOrLoad(key, String.class, () -> {
                loaderStarted.countDown();
                try {
                    releaseLoader.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "old";
            }).orElseThrow());

            loaderStarted.await(5, TimeUnit.SECONDS);
            cache.evict(key);
            releaseLoader.countDown();
            assertEquals("old", oldRead.get(5, TimeUnit.SECONDS));

            // The stale conditional write must have lost to the eviction generation.
            assertEquals(Optional.empty(), cache.get(key, String.class));

            assertEquals("new", cache.getOrLoad(key, String.class, () -> "new").orElseThrow());
            assertEquals("new", cache.get(key, String.class).orElseThrow());
        } finally {
            pool.shutdownNow();
            redis.delete(List.of(key, key + ":generation"));
        }
    }
}
