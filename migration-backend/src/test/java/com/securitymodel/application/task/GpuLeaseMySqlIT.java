package com.securitymodel.application.task;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.persistence.mapper.GpuLeaseMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MySQL proof for R2's single-GPU lease: one conditional UPDATE wins, an expired
 * lease is reclaimed, and a stale token can neither renew nor release the new owner.
 */
@SpringJUnitConfig(GpuLeaseMySqlIT.LeaseTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GpuLeaseMySqlIT {

    private static final String SCHEMA = "security_model_gpu_it_" + UUID.randomUUID().toString().replace("-", "");
    private static final String HOST = env("MYSQL_IT_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    private static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");
    private static final String SLOT = "default-gpu-0";

    static {
        createSchema();
    }

    @Autowired
    private GpuLeaseService leaseService;

    @Autowired
    private GpuLeaseMapper leaseMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GpuLeaseMySqlIT::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void resetSlot() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_gpu_lease (
                  slot_id VARCHAR(32) NOT NULL,
                  lease_token CHAR(36) NULL,
                  task_id BIGINT NULL,
                  run_id VARCHAR(64) NULL,
                  attempt INT NULL,
                  fencing_token BIGINT NOT NULL DEFAULT 0,
                  acquired_at DATETIME(3) NULL,
                  heartbeat_at DATETIME(3) NULL,
                  expires_at DATETIME(3) NULL,
                  released_at DATETIME(3) NULL,
                  PRIMARY KEY (slot_id),
                  KEY idx_gpu_lease_active (lease_token, expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM sys_gpu_lease");
        jdbc.update("INSERT INTO sys_gpu_lease (slot_id, fencing_token) VALUES (?, 0)", SLOT);
    }

    @AfterAll
    static void dropSchema() throws Exception {
        if (!SCHEMA.matches("security_model_gpu_it_[a-f0-9]{32}")) {
            return;
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + SCHEMA + "`");
        }
    }

    @Test
    void onlyOneConcurrentStartWinsTheSingleSlot() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> tryAcquire(start, 11L, "run-11"));
            Future<Boolean> second = pool.submit(() -> tryAcquire(start, 12L, "run-12"));
            start.countDown();
            boolean a = first.get(10, TimeUnit.SECONDS);
            boolean b = second.get(10, TimeUnit.SECONDS);

            assertNotEquals(a, b, "exactly one concurrent start may own the GPU");
            assertTrue(a || b);
            GpuLease current = leaseMapper.findBySlot(SLOT);
            assertEquals(1L, current.getFencingToken());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndFencingTokenIncreases() {
        GpuLease first = leaseService.acquire(21L, "run-21", 1);
        expireNow();

        GpuLease second = leaseService.acquire(22L, "run-22", 1);

        assertNotEquals(first.getLeaseToken(), second.getLeaseToken());
        assertEquals(2L, second.getFencingToken());
        assertEquals(22L, second.getTaskId());
        assertEquals("run-22", second.getRunId());
    }

    @Test
    void staleLeaseCannotRenewOrReleaseTheNewOwner() {
        GpuLease stale = leaseService.acquire(31L, "run-31", 1);
        expireNow();
        GpuLease current = leaseService.acquire(32L, "run-32", 1);

        assertFalse(leaseService.renew(stale), "an expired token must not extend the lease");
        leaseService.release(stale);

        GpuLease afterStaleRelease = leaseMapper.findBySlot(SLOT);
        assertEquals(current.getLeaseToken(), afterStaleRelease.getLeaseToken(),
                "a stale release must not clear the new owner");
        assertTrue(leaseService.renew(current));
        leaseService.release(current);
        assertEquals(null, leaseMapper.findBySlot(SLOT).getLeaseToken());
    }

    @Test
    void releaseByRunOnlyClearsTheMatchingRun() {
        leaseService.acquire(41L, "run-41", 1);

        leaseService.releaseByRun(41L, "another-run");
        assertEquals("run-41", leaseMapper.findBySlot(SLOT).getRunId());

        leaseService.releaseByRun(41L, "run-41");
        assertEquals(null, leaseMapper.findBySlot(SLOT).getLeaseToken());
    }

    @Test
    void releasedSlotCanBeAcquiredAgainWithAHigherFence() {
        GpuLease first = leaseService.acquire(51L, "run-51", 1);
        leaseService.release(first);

        GpuLease second = leaseService.acquire(52L, "run-52", 1);

        assertEquals(2L, second.getFencingToken());
        assertEquals(52L, second.getTaskId());
    }

    private boolean tryAcquire(CountDownLatch start, Long taskId, String runId) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            leaseService.acquire(taskId, runId, 1);
            return true;
        } catch (TaskCommandException exception) {
            assertTrue(exception.getMessage().contains("GPU_BUSY"));
            return false;
        }
    }

    private void expireNow() {
        jdbc.update("UPDATE sys_gpu_lease SET expires_at = ? WHERE slot_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), SLOT);
    }

    private static void createSchema() {
        if (!SCHEMA.matches("security_model_gpu_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to create a non-isolated MySQL test schema");
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String schemaUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";
    }

    private static String adminUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/?useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null ? defaultValue : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    @MapperScan("com.securitymodel.infrastructure.persistence.mapper")
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            MybatisPlusAutoConfiguration.class
    })
    static class LeaseTestConfiguration {
        @Bean
        GpuLeaseService gpuLeaseService(GpuLeaseMapper mapper) {
            return new GpuLeaseService(mapper, SLOT, 300);
        }
    }
}
