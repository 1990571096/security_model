package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real RabbitMQ delivery with an unconfigured Python executor. Two identical deliveries
 * must be consumed and acknowledged by the real listener, converge the task and intent
 * exactly once, leave one terminal ledger row, no local accepted execution and no lease.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
@EnabledIfEnvironmentVariable(named = "RABBIT_IT", matches = "true")
class DispatchConfigBoundaryRabbitIT {

    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "");
    private static final String EXCHANGE = "r4.config.boundary.exchange." + SUFFIX;
    private static final String QUEUE = "r4.config.boundary.queue." + SUFFIX;
    private static final String ROUTING_KEY = "r4.config.boundary.route." + SUFFIX;
    private static final String DLX = EXCHANGE + ".dlx";
    private static final String DLQ = QUEUE + ".dlq";
    private static final String RETRY_PREFIX = "r4.config.boundary.retry." + SUFFIX + ".";
    private static final long TASK_ID = 950L;
    private static final String RUN_ID = "run-rabbit-config-950";
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
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin amqpAdmin;
    @Autowired
    @Qualifier("dispatchWorkerContainer")
    private SimpleMessageListenerContainer container;
    @Autowired
    private AttemptProcessRegistry processRegistry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret",
                () -> "dispatch-rabbit-boundary-secret-0123456789abcdef");
        registry.add("security-model.executor.python.script", () -> "");
        registry.add("security-model.executor.workspace", () -> "");
        registry.add("security-model.executor.python.workspace", () -> "");
        registry.add("security-model.dispatch.mode", () -> "rabbit");
        registry.add("security-model.dispatch.role", () -> "worker");
        registry.add("security-model.dispatch.rabbit.exchange", () -> EXCHANGE);
        registry.add("security-model.dispatch.rabbit.queue", () -> QUEUE);
        registry.add("security-model.dispatch.rabbit.routing-key", () -> ROUTING_KEY);
        registry.add("security-model.dispatch.rabbit.dead-letter-exchange", () -> DLX);
        registry.add("security-model.dispatch.rabbit.dead-letter-queue", () -> DLQ);
        registry.add("security-model.dispatch.rabbit.dead-letter-routing-key", () -> ROUTING_KEY + ".dead");
        registry.add("security-model.dispatch.rabbit.retry-queue-prefix", () -> RETRY_PREFIX);
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> "5673");
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    @BeforeEach
    void seed() {
        clean();
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, description, status, dataset_id, algorithm_id, custom_params,
                    progress, current_run_id, current_attempt, create_by, create_time, update_time,
                    owner_user_id, data_origin
                ) VALUES (?, 'rabbit-config-boundary', 'rabbit', 'running', 1, 1, '{}', 0, ?, 1,
                    'alice', NOW(3), NOW(3), ?, 'user')
                """, TASK_ID, RUN_ID, OwnershipE2ESupport.ALICE_ID);
        jdbc.update("""
                INSERT INTO sys_task_dispatch_intent
                  (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                   created_at, updated_at)
                VALUES (?, ?, 1, 'pending', 1, '{}', ?, NOW(3), NOW(3))
                """, TASK_ID, RUN_ID, EMPTY_HASH);
        jdbc.update("""
                UPDATE sys_gpu_lease
                SET lease_token = ?, task_id = ?, run_id = ?, attempt = 1,
                    acquired_at = NOW(3), heartbeat_at = NOW(3),
                    expires_at = NOW(3) + INTERVAL 5 MINUTE,
                    released_at = NULL, blocked_at = NULL, blocked_reason = NULL
                WHERE slot_id = ?
                """, UUID.randomUUID().toString(), TASK_ID, RUN_ID, SLOT_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    @AfterEach
    void cleanup() {
        try {
            container.stop();
        } finally {
            clean();
        }
    }

    @Test
    void duplicateRealDeliveriesAckOnceAndConvergeWithoutPythonOrLease() throws Exception {
        long intentId = jdbc.queryForObject("""
                SELECT intent_id FROM sys_task_dispatch_intent WHERE task_id = ? AND run_id = ?
                """, Long.class, TASK_ID, RUN_ID);
        String messageId = UUID.randomUUID().toString();
        DispatchEvent event = new DispatchEvent(DispatchEvent.SCHEMA_VERSION, messageId,
                DispatchEvent.EVENT_TASK_DISPATCH, TASK_ID, RUN_ID, 1,
                OwnershipE2ESupport.ALICE_ID, intentId, EMPTY_HASH, LocalDateTime.now());
        String payload = objectMapper.writeValueAsString(event);
        publish(payload, messageId);
        publish(payload, messageId);

        waitUntil(() -> "failed".equals(jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID))
                && jdbc.queryForObject(
                "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = ?", String.class, SLOT_ID) == null,
                10_000);
        assertEquals("abandoned", jdbc.queryForObject("""
                SELECT status FROM sys_task_dispatch_intent WHERE task_id = ? AND run_id = ?
                """, String.class, TASK_ID, RUN_ID));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_dispatch_consumption WHERE message_id = ?
                """, Integer.class, messageId));
        assertEquals("terminal", jdbc.queryForObject("""
                SELECT status FROM sys_dispatch_consumption WHERE message_id = ?
                """, String.class, messageId));
        assertEquals("failed", jdbc.queryForObject("""
                SELECT outcome FROM sys_dispatch_consumption WHERE message_id = ?
                """, String.class, messageId));
        assertEquals(0, processRegistry.localAcceptedCount());
        assertNull(jdbc.queryForObject(
                "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = ?", String.class, SLOT_ID));
        assertNull(jdbc.queryForObject(
                "SELECT blocked_at FROM sys_gpu_lease WHERE slot_id = ?", LocalDateTime.class, SLOT_ID));

        waitUntil(() -> amqpAdmin.getQueueInfo(QUEUE) != null
                && amqpAdmin.getQueueInfo(QUEUE).getMessageCount() == 0, 5_000);
        assertTrue(amqpAdmin.getQueueInfo(QUEUE).getMessageCount() == 0,
                "the real queue must converge after both deliveries are acknowledged");
    }

    private void publish(String payload, String messageId) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, payload, message -> {
            message.getMessageProperties().setMessageId(messageId);
            message.getMessageProperties().setContentType("application/json");
            return message;
        });
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void clean() {
        jdbc.update("DELETE FROM sys_dispatch_consumption WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_task_dispatch_intent WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", TASK_ID);
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
