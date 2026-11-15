package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real service-layer race test: actual transaction, task mapper, GPU lease, quota,
 * dispatch intent and dispatch coordinator. Only the Python/LDD executor and the
 * catalog lookup are replaced, so no process or resource seed is required.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
class TaskCommandServicePipelineIT {

    private static final String JWT_SECRET = "pipeline-race-secret-0123456789abcdef0123456789";
    private static final long TASK_ID = 900L;
    private static final String RUN_ID = "pipeline-race-run";

    static {
        OwnershipE2ESupport.schemaUrl();
        createLeaseSchema();
    }

    @Autowired
    private PreDefenseTaskCommandService commandService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PreDefenseTaskMapper taskMapper;
    @Autowired
    private GpuLeaseService gpuLeaseService;
    @Autowired
    private RequestIdempotencyService idempotencyService;
    @SpyBean
    private ExecutionFingerprintService fingerprintService;
    @MockBean
    private DefenseTaskExecutor executor;
    @MockBean
    private ResourceQueryService resourceQueryService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
    }

    @BeforeEach
    void seedPendingTask() {
        jdbc.update("DELETE FROM sys_task_dispatch_intent WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_gpu_lease WHERE task_id = ?", TASK_ID);
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, status, dataset_id, algorithm_id, custom_params,
                    progress, create_by, create_time, update_time, owner_user_id, data_origin
                ) VALUES (?, 'pipeline-race', 'pending', 1, 1, ?, 0,
                    'alice', NOW(3), NOW(3), 2, 'user')
                """, TASK_ID, finalConfig());
        when(resourceQueryService.findDatasetAuthoritative(1L)).thenReturn(dataset());
        when(resourceQueryService.findAlgorithmAuthoritative(1L)).thenReturn(algorithm());
        authenticate();
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM sys_task_dispatch_intent WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_gpu_lease WHERE task_id = ?", TASK_ID);
    }

    @Test
    void twoConcurrentStartsProduceOneRunOneIntentAndOneExecution() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = List.of(
                    pool.submit(() -> start(ready, go)),
                    pool.submit(() -> start(ready, go)));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            int started = 0;
            for (Future<String> outcome : outcomes) {
                if ("started".equals(outcome.get(10, TimeUnit.SECONDS))) {
                    started++;
                }
            }
            assertEquals(1, started, "only one start may win the row lock");
        } finally {
            pool.shutdownNow();
        }

        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID));
        assertTrue(jdbc.queryForObject(
                "SELECT current_run_id FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID)
                .length() > 10);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_task_dispatch_intent WHERE task_id = ?", Integer.class, TASK_ID));
        verify(executor, timeout(5000).times(1)).runPreDefense(any());
        verify(executor, never()).cancelPreDefense(any(Long.class), any());
    }

    @Test
    void launchFailureRollsBackClaimLeaseIntentAndIdempotencyRecord() {
        doThrow(new IllegalStateException("injected-after-claim"))
                .when(fingerprintService).buildRuntimeSnapshot(any());
        String key = "rollback-" + UUID.randomUUID();
        try {
            assertThrows(IllegalStateException.class, () -> idempotencyService.execute(
                    "task.start", key, Map.of("taskId", TASK_ID), TASK_ID,
                    () -> {
                        commandService.start(TASK_ID, null);
                        return new IdempotencyResult(200, objectMapper.createObjectNode());
                    }));

            assertEquals("pending", jdbc.queryForObject(
                    "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID));
            assertNull(jdbc.queryForObject(
                    "SELECT current_run_id FROM sys_pre_defense WHERE task_id = ?",
                    String.class, TASK_ID));
            assertTrue(jdbc.queryForList(
                    "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = 'default-gpu-0'",
                    String.class).stream().allMatch(java.util.Objects::isNull));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_task_dispatch_intent WHERE task_id = ?",
                    Integer.class, TASK_ID));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_request_idempotency WHERE idempotency_key = ?",
                    Integer.class, key));
            verify(executor, never()).runPreDefense(any());
        } finally {
            reset(fingerprintService);
        }
    }

    @Test
    void editAndStartRaceNeverBuildsRequestFromTwoParameterVersions() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = List.of(
                    pool.submit(() -> edit(ready, go)),
                    pool.submit(() -> start(ready, go)));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<String> outcome : outcomes) {
                outcome.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String status = jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
        if ("running".equals(status)) {
            ArgumentCaptor<DefenseTaskRequest> request = ArgumentCaptor.forClass(DefenseTaskRequest.class);
            verify(executor, timeout(5000).times(1)).runPreDefense(request.capture());
            String stored = jdbc.queryForObject(
                    "SELECT custom_params FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
            assertEquals(objectMapper.readTree(stored).path("batch_size").asInt(),
                    objectMapper.readTree(request.getValue().getCustomParams())
                            .path("batch_size").asInt());
        } else {
            assertEquals("pending", status);
            verify(executor, never()).runPreDefense(any());
        }
    }

    @Test
    void deleteAndStartRaceNeverExecutesADeletedTask() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = List.of(
                    pool.submit(() -> delete(ready, go)),
                    pool.submit(() -> start(ready, go)));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<String> outcome : outcomes) {
                outcome.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_pre_defense WHERE task_id = ?", Integer.class, TASK_ID);
        if (remaining == 1) {
            assertEquals("running", jdbc.queryForObject(
                    "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_task_dispatch_intent WHERE task_id = ?",
                    Integer.class, TASK_ID));
            verify(executor, timeout(5000).times(1)).runPreDefense(any());
        } else {
            assertEquals(0, remaining, "delete must remove the row exactly once");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_task_dispatch_intent WHERE task_id = ?",
                    Integer.class, TASK_ID));
            verify(executor, never()).runPreDefense(any());
        }
    }

    @Test
    void cancelBeforeCompletionWinsAndLateCompletionCannotChangeTerminalState() throws Exception {
        commandService.start(TASK_ID, null);
        commandService.cancel(TASK_ID);
        assertEquals("canceled", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID));

        String runId = jdbc.queryForObject(
                "SELECT current_run_id FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
        assertEquals(0, taskMapper.finishForRun(TASK_ID, runId, "success", 100,
                LocalDateTime.now()), "a late completion must not terminalize the same run twice");

        assertEquals("canceled", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID));
        String intentStatus = jdbc.queryForObject(
                "SELECT status FROM sys_task_dispatch_intent WHERE task_id = ?",
                String.class, TASK_ID);
        assertTrue(intentStatus.equals("submitted") || intentStatus.equals("abandoned"),
                "canceled run must not leave the intent recoverable, status=" + intentStatus);
        verify(executor, timeout(5000).times(1)).cancelPreDefense(TASK_ID, runId);
    }

    @Test
    void completionBeforeCancelWinsAndCancelIsRejectedWithoutSideEffects() throws Exception {
        jdbc.update("""
                UPDATE sys_pre_defense
                SET status = 'running', current_run_id = ?, current_attempt = 1, start_time = NOW(3)
                WHERE task_id = ?
                """, RUN_ID, TASK_ID);

        assertEquals(1, taskMapper.updateResultForRun(TASK_ID, RUN_ID, null,
                "{\"status\":\"success\",\"artifacts\":{}}"));
        assertEquals(1, taskMapper.finishForRun(TASK_ID, RUN_ID, "success", 100,
                LocalDateTime.now()));

        authenticate();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(TaskCommandException.class,
                    () -> commandService.cancel(TASK_ID));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals("success", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID));
        verify(executor, never()).cancelPreDefense(any(Long.class), any());
    }

    @Test
    void cancelAndInstantSuccessLeaveExactlyOneTerminalState() throws Exception {
        jdbc.update("""
                UPDATE sys_pre_defense
                SET status = 'running', current_run_id = ?, current_attempt = 1, start_time = NOW(3)
                WHERE task_id = ?
                """, RUN_ID, TASK_ID);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> outcomes = List.of(
                    pool.submit(() -> cancel(ready, go)),
                    pool.submit(() -> finish(ready, go)));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Future<String> outcome : outcomes) {
                outcome.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String status = jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
        assertTrue(status.equals("canceled") || status.equals("success"), "status=" + status);
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_pre_defense
                WHERE task_id = ? AND status IN ('running', 'pending')
                """, Integer.class, TASK_ID));
    }

    private String cancel(CountDownLatch ready, CountDownLatch go) {
        authenticate();
        ready.countDown();
        try {
            assertTrue(go.await(5, TimeUnit.SECONDS));
            commandService.cancel(TASK_ID);
            return "canceled";
        } catch (TaskCommandException expected) {
            return "rejected";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String finish(CountDownLatch ready, CountDownLatch go) {
        ready.countDown();
        try {
            assertTrue(go.await(5, TimeUnit.SECONDS));
            return taskMapper.finishForRun(TASK_ID, RUN_ID, "success", 100,
                    LocalDateTime.now()) == 1 ? "finished" : "rejected";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    private String edit(CountDownLatch ready, CountDownLatch go) {
        authenticate();
        ready.countDown();
        try {
            assertTrue(go.await(5, TimeUnit.SECONDS));
            PreDefenseTaskCommand command = new PreDefenseTaskCommand();
            command.setTaskName("pipeline-race-edited");
            command.setDatasetId(1L);
            command.setAlgorithmId(1L);
            command.setCustomParams(finalConfig().replace("\"batch_size\":128", "\"batch_size\":64"));
            commandService.update(TASK_ID, command);
            return "edited";
        } catch (TaskCommandException expected) {
            return "rejected";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String delete(CountDownLatch ready, CountDownLatch go) {
        authenticate();
        ready.countDown();
        try {
            assertTrue(go.await(5, TimeUnit.SECONDS));
            commandService.delete(List.of(TASK_ID));
            return "deleted";
        } catch (TaskCommandException expected) {
            return "rejected";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String start(CountDownLatch ready, CountDownLatch go) {
        authenticate();
        ready.countDown();
        try {
            assertTrue(go.await(5, TimeUnit.SECONDS));
            commandService.start(TASK_ID, null);
            return "started";
        } catch (TaskCommandException expected) {
            return "rejected";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(2L, "alice", false), null, List.of()));
    }

    private String finalConfig() {
        return "{\"dataset\":\"cifar10\",\"trigger_type\":\"badnets\",\"trigger_label\":0,"
                + "\"poisoned_mode\":\"all2one\",\"poisoned_portion\":0.1,\"batch_size\":128,"
                + "\"num_epochs\":5,\"lr_poison\":0.01,\"r\":0.5,\"alpha1\":1.0,\"alpha2\":1.0,"
                + "\"seed\":11}";
    }

    private Dataset dataset() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(1L);
        dataset.setDatasetName("CIFAR-10");
        dataset.setDatasetType("image");
        dataset.setFilePath("/uploads/datasets/cifar10.zip");
        dataset.setSampleCount(50000);
        dataset.setClassCount(10);
        dataset.setFileSize(162345000L);
        dataset.setParseStatus("success");
        dataset.setEnabled(true);
        dataset.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return dataset;
    }

    private Algorithm algorithm() {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(1L);
        algorithm.setAlgorithmName("LDD");
        algorithm.setAlgorithmType("deep_learning");
        algorithm.setDefenseStage("pre");
        algorithm.setImplementationPath("vab://pre-defense/ldd");
        algorithm.setEnabled(true);
        algorithm.setDefaultConfig(finalConfig());
        algorithm.setUpdateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        return algorithm;
    }

    private static void createLeaseSchema() {
        try (Connection connection = DriverManager.getConnection(
                OwnershipE2ESupport.schemaUrl(), OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
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
                      blocked_at DATETIME(3) NULL,
                      blocked_reason VARCHAR(64) NULL,
                      PRIMARY KEY (slot_id),
                      KEY idx_gpu_lease_active (lease_token, expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    INSERT IGNORE INTO sys_gpu_lease (slot_id, fencing_token)
                    VALUES ('default-gpu-0', 0)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_task_dispatch_intent (
                      intent_id BIGINT NOT NULL AUTO_INCREMENT,
                      task_id BIGINT NOT NULL,
                      run_id VARCHAR(64) NOT NULL,
                      attempt INT NOT NULL,
                      status VARCHAR(20) NOT NULL,
                      schema_version INT NOT NULL DEFAULT 1,
                      request_json LONGTEXT NOT NULL,
                      request_hash CHAR(64) NOT NULL,
                      runtime_hash CHAR(64) NULL,
                      error_code VARCHAR(64) NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      submitted_at DATETIME(3) NULL,
                      PRIMARY KEY (intent_id),
                      UNIQUE KEY uk_dispatch_intent_run (task_id, run_id, attempt)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_request_idempotency (
                      record_id BIGINT NOT NULL AUTO_INCREMENT,
                      owner_user_id BIGINT NOT NULL,
                      operation VARCHAR(64) NOT NULL,
                      idempotency_key VARCHAR(128) NOT NULL,
                      request_hash CHAR(64) NOT NULL,
                      resource_id BIGINT NULL,
                      status VARCHAR(20) NOT NULL,
                      response_code INT NULL,
                      response_body LONGTEXT NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      expires_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (record_id),
                      UNIQUE KEY uk_request_idempotency (owner_user_id, operation, idempotency_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建真实派发链路测试表", exception);
        }
    }
}
