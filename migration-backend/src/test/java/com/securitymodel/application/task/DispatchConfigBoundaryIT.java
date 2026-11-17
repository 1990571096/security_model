package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Two configuration-missing boundaries on a real isolated MySQL schema:
 * startup recovery replaying a pending intent, and the Worker consumption path.
 *
 * <p>The executor script is deliberately blank. Both paths must converge with the stable
 * {@code EXECUTOR_NOT_CONFIGURED} code, must not start Python, must release any lease owned
 * by the run, and must leave the task/intent pair in a consistent terminal state.</p>
 */
@SpringBootTest(classes = SecurityModelApplication.class)
class DispatchConfigBoundaryIT {

    private static final String JWT_SECRET = "dispatch-config-boundary-secret-0123456789abcdef";
    private static final long REPLAY_TASK_ID = 930L;
    private static final long WORKER_TASK_ID = 940L;
    private static final String REPLAY_RUN_ID = "run-replay-930";
    private static final String WORKER_RUN_ID = "run-worker-940";
    private static final String SLOT_ID = "default-gpu-0";
    private static final String EMPTY_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    static {
        OwnershipE2ESupport.schemaUrl();
        createDispatchTables();
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DispatchRecoveryCoordinator recoveryCoordinator;
    @Autowired
    private TaskDispatchCoordinator dispatchCoordinator;
    @Autowired
    private TaskOutboxService outboxService;
    @Autowired
    private TaskDispatchConsumptionService consumptionService;
    @Autowired
    private TaskDispatchIntentService intentService;
    @Autowired
    private com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper taskMapper;
    @Autowired
    private com.securitymodel.infrastructure.executor.AttemptProcessRegistry processRegistry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
        registry.add("security-model.executor.python.script", () -> "");
        registry.add("security-model.executor.workspace", () -> "");
        registry.add("security-model.executor.python.workspace", () -> "");
    }

    @BeforeEach
    void clean() {
        deleteTasks();
    }

    @AfterEach
    void cleanup() {
        deleteTasks();
    }

    @Test
    void startupReplayWithoutPythonScriptFailsConsistentlyAndReleasesTheLease() {
        seedRunningTask(REPLAY_TASK_ID, REPLAY_RUN_ID);
        TaskDispatchIntent intent = seedPendingIntent(REPLAY_TASK_ID, REPLAY_RUN_ID);
        holdLease(REPLAY_TASK_ID, REPLAY_RUN_ID);

        DispatchRecoveryCoordinator.RecoveryResult result = recoveryCoordinator.recoverAtStartup();

        assertTrue(result.safeToConsume(), "a fully classified configuration failure must not keep the gate closed");
        assertEquals(0, result.replayed(), "no replay may be reported when the executor is unconfigured");
        assertEquals(1, result.converged(), "the pending intent must be explicitly converged");
        assertTaskFailedWith(REPLAY_TASK_ID, "EXECUTOR_NOT_CONFIGURED");
        assertEquals(TaskDispatchIntent.ABANDONED, intentStatus(REPLAY_TASK_ID));
        assertLeaseReleased();
        assertEquals(0, processRegistry.localAcceptedCount(),
                "configuration failure must not leave a locally accepted execution");
    }

    @Test
    void workerConsumptionWithoutPythonScriptAcksAfterTerminalConvergence() {
        seedRunningTask(WORKER_TASK_ID, WORKER_RUN_ID);
        long intentId = seedPendingIntent(WORKER_TASK_ID, WORKER_RUN_ID).getIntentId();
        String messageId = UUID.randomUUID().toString();
        DispatchEvent event = new DispatchEvent(DispatchEvent.SCHEMA_VERSION, messageId,
                DispatchEvent.EVENT_TASK_DISPATCH, WORKER_TASK_ID, WORKER_RUN_ID, 1,
                OwnershipE2ESupport.ALICE_ID, intentId, EMPTY_HASH, LocalDateTime.now());

        DispatchMessageHandler handler = new DispatchMessageHandler(
                new DispatchRuntimePolicy("rabbit", "worker"), outboxService, consumptionService,
                intentService, dispatchCoordinator, taskMapper, processRegistry,
                mock(DispatchTransport.class), "config-boundary-worker", 300, 3);

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle(payload(event), 0));
        assertTaskFailedWith(WORKER_TASK_ID, "EXECUTOR_NOT_CONFIGURED");
        assertEquals(TaskDispatchIntent.ABANDONED, intentStatus(WORKER_TASK_ID));
        assertLeaseReleased();
        assertEquals("terminal", consumptionStatus(messageId));
        assertEquals("failed", consumptionOutcome(messageId));

        assertEquals(DispatchMessageHandler.Action.ACK, handler.handle(payload(event), 0));
        assertEquals(1, consumptionCount(messageId), "duplicate delivery must not add a ledger row");
        assertFalse(processRegistry.isLocallyAccepted(WORKER_TASK_ID, WORKER_RUN_ID, 1));
    }

    private void seedRunningTask(long taskId, String runId) {
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, description, status, dataset_id, algorithm_id, custom_params,
                    progress, current_run_id, current_attempt, create_by, create_time, update_time,
                    owner_user_id, data_origin
                ) VALUES (?, ?, 'config-boundary', 'running', 1, 1, '{}', 0, ?, 1,
                    'alice', NOW(3), NOW(3), ?, 'user')
                """, taskId, "config-boundary-" + taskId, runId, OwnershipE2ESupport.ALICE_ID);
    }

    private TaskDispatchIntent seedPendingIntent(long taskId, String runId) {
        jdbc.update("""
                INSERT INTO sys_task_dispatch_intent
                  (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                   created_at, updated_at)
                VALUES (?, ?, 1, 'pending', 1, '{}', ?, NOW(3), NOW(3))
                """, taskId, runId, EMPTY_HASH);
        return intentService.findByRun(taskId, runId, 1);
    }

    private void holdLease(long taskId, String runId) {
        jdbc.update("""
                UPDATE sys_gpu_lease
                SET lease_token = ?, task_id = ?, run_id = ?, attempt = 1,
                    acquired_at = NOW(3), heartbeat_at = NOW(3),
                    expires_at = NOW(3) + INTERVAL 5 MINUTE,
                    released_at = NULL, blocked_at = NULL, blocked_reason = NULL
                WHERE slot_id = ?
                """, UUID.randomUUID().toString(), taskId, runId, SLOT_ID);
    }

    private void assertTaskFailedWith(long taskId, String errorCode) {
        assertEquals("failed", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, taskId));
        String raw = jdbc.queryForObject(
                "SELECT cleaning_result FROM sys_pre_defense WHERE task_id = ?", String.class, taskId);
        try {
            assertEquals(errorCode, objectMapper.readTree(raw).path("error_code").asText());
        } catch (Exception exception) {
            throw new AssertionError("task failure result is not valid JSON: " + raw, exception);
        }
    }

    private String intentStatus(long taskId) {
        return jdbc.queryForObject("""
                SELECT status FROM sys_task_dispatch_intent WHERE task_id = ? ORDER BY intent_id DESC LIMIT 1
                """, String.class, taskId);
    }

    private void assertLeaseReleased() {
        String token = jdbc.queryForObject(
                "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = ?", String.class, SLOT_ID);
        assertNull(token, "the run-owned lease must be released after a configuration failure");
        assertNull(jdbc.queryForObject(
                "SELECT blocked_at FROM sys_gpu_lease WHERE slot_id = ?", LocalDateTime.class, SLOT_ID),
                "a pre-execution configuration failure must not quarantine the slot");
    }

    private String consumptionStatus(String messageId) {
        return jdbc.queryForObject(
                "SELECT status FROM sys_dispatch_consumption WHERE message_id = ?", String.class, messageId);
    }

    private String consumptionOutcome(String messageId) {
        return jdbc.queryForObject(
                "SELECT outcome FROM sys_dispatch_consumption WHERE message_id = ?", String.class, messageId);
    }

    private int consumptionCount(String messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_dispatch_consumption WHERE message_id = ?",
                Integer.class, messageId);
    }

    private String payload(DispatchEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new AssertionError("cannot serialize dispatch event", exception);
        }
    }

    private void deleteTasks() {
        jdbc.update("DELETE FROM sys_dispatch_consumption WHERE task_id IN (?, ?)",
                REPLAY_TASK_ID, WORKER_TASK_ID);
        jdbc.update("DELETE FROM sys_task_dispatch_intent WHERE task_id IN (?, ?)",
                REPLAY_TASK_ID, WORKER_TASK_ID);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id IN (?, ?)",
                REPLAY_TASK_ID, WORKER_TASK_ID);
        jdbc.update("""
                UPDATE sys_gpu_lease SET lease_token = NULL, task_id = NULL, run_id = NULL,
                    attempt = NULL, acquired_at = NULL, heartbeat_at = NULL, expires_at = NULL,
                    released_at = NOW(3), blocked_at = NULL, blocked_reason = NULL
                WHERE slot_id = ?
                """, SLOT_ID);
    }

    private static void createDispatchTables() {
        try (Connection connection = DriverManager.getConnection(
                OwnershipE2ESupport.schemaUrl(), OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD);
             Statement statement = connection.createStatement()) {
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
                    CREATE TABLE IF NOT EXISTS sys_dispatch_consumption (
                      consumption_id BIGINT NOT NULL AUTO_INCREMENT,
                      message_id VARCHAR(86) NOT NULL,
                      task_id BIGINT NOT NULL,
                      run_id VARCHAR(64) NOT NULL,
                      attempt INT NOT NULL,
                      worker_id VARCHAR(128) NULL,
                      status VARCHAR(20) NOT NULL,
                      outcome VARCHAR(20) NULL,
                      error_code VARCHAR(64) NULL,
                      claim_owner VARCHAR(128) NULL,
                      claim_until DATETIME(3) NULL,
                      claim_token CHAR(36) NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      terminal_at DATETIME(3) NULL,
                      PRIMARY KEY (consumption_id),
                      UNIQUE KEY uk_consumption_message (message_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create dispatch boundary test tables", exception);
        }
    }
}
