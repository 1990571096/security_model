package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(MySqlTaskPersistenceIT.PersistenceTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MySqlTaskPersistenceIT {

    private static final String SCHEMA_PREFIX = "security_model_it_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String HOST = env("MYSQL_IT_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    private static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");
    private static final AtomicInteger TASK_IDS = new AtomicInteger(1);
    private static volatile boolean schemaCreated;

    static {
        createIsolatedSchema();
    }

    @Autowired
    private PreDefenseTaskMapper taskMapper;

    @Autowired
    private com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper exportMapper;

    @Autowired
    private com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper intentMapper;

    @Autowired
    private com.securitymodel.infrastructure.persistence.mapper.RequestIdempotencyMapper idempotencyMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MySqlTaskPersistenceIT::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void createSchemaAndClearRows() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_pre_defense (
                  task_id BIGINT NOT NULL,
                  task_name VARCHAR(100) NOT NULL,
                  description TEXT NULL,
                  status VARCHAR(20) NOT NULL,
                  dataset_id BIGINT NOT NULL,
                  algorithm_id BIGINT NOT NULL,
                  hyper_id BIGINT NULL,
                  custom_params JSON NULL,
                  start_time DATETIME NULL,
                  end_time DATETIME NULL,
                  progress INT NULL DEFAULT 0,
                  output_dataset_id BIGINT NULL,
                  cleaning_result JSON NULL,
                  execution_snapshot JSON NULL,
                  current_run_id VARCHAR(64) NULL,
                  current_attempt INT NULL,
                  create_by VARCHAR(255) NULL,
                  update_by VARCHAR(255) NULL,
                  create_time DATETIME NULL,
                  update_time DATETIME NULL,
                  owner_user_id BIGINT NULL,
                  data_origin VARCHAR(16) NULL,
                  owner_backfill_status VARCHAR(16) NULL,
                  PRIMARY KEY (task_id),
                  KEY idx_status (status),
                  KEY idx_owner_status (owner_user_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM sys_pre_defense");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_pre_defense_export (
                  export_id BIGINT NOT NULL AUTO_INCREMENT,
                  task_id BIGINT NOT NULL,
                  isolation_ratio INT NOT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'pending',
                  progress INT NOT NULL DEFAULT 0,
                  file_path VARCHAR(1024) NULL,
                  metadata_path VARCHAR(1024) NULL,
                  error_code VARCHAR(64) NULL,
                  failure_reason VARCHAR(1000) NULL,
                  start_time DATETIME NULL,
                  end_time DATETIME NULL,
                  create_by VARCHAR(255) NULL,
                  update_by VARCHAR(255) NULL,
                  create_time DATETIME NULL,
                  update_time DATETIME NULL,
                  PRIMARY KEY (export_id),
                  UNIQUE KEY uk_pre_defense_export_task_ratio (task_id, isolation_ratio)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM sys_pre_defense_export");
        jdbc.execute("""
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
                  UNIQUE KEY uk_dispatch_intent_run (task_id, run_id, attempt),
                  KEY idx_dispatch_intent_status (status, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM sys_task_dispatch_intent");
        jdbc.execute("""
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
                  UNIQUE KEY uk_request_idempotency (owner_user_id, operation, idempotency_key),
                  KEY idx_request_idempotency_expiry (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("DELETE FROM sys_request_idempotency");
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (!schemaCreated || !SCHEMA.matches("security_model_it_[a-f0-9]{32}")) {
            return;
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + SCHEMA + "`");
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, SCHEMA);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1), "Temporary integration schema must be removed");
            }
        }
    }

    @Test
    void dispatchIntentUniqueKeyAndConditionalPromotionElectOneDispatcher() {
        long taskId = insertTask("running", "run-intent");
        insertIntent(taskId, "run-intent", 1, "pending", LocalDateTime.now().minusSeconds(60));

        TaskDispatchIntent first = new TaskDispatchIntent();
        first.setTaskId(taskId);
        first.setRunId("run-intent");
        first.setAttempt(1);
        first.setStatus("pending");
        first.setSchemaVersion(1);
        first.setRequestJson("{}");
        first.setRequestHash("0".repeat(64));
        first.setCreatedAt(LocalDateTime.now());
        first.setUpdatedAt(LocalDateTime.now());
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class, () -> intentMapper.insert(first));

        LocalDateTime now = LocalDateTime.now();
        assertEquals(1, intentMapper.markSubmitting(taskId, "run-intent", 1, now));
        assertEquals(0, intentMapper.markSubmitting(taskId, "run-intent", 1, now));
        assertEquals(1, intentMapper.markSubmitted(taskId, "run-intent", 1, now));
        assertEquals(0, intentMapper.markSubmitted(taskId, "run-intent", 1, now));
        assertEquals("submitted", intentMapper.findByRun(taskId, "run-intent", 1).getStatus());
    }

    @Test
    void dispatchIntentRollbackLeavesNoRow() throws Exception {
        long taskId = insertTask("running", "run-rollback");
        var connection = jdbc.getDataSource().getConnection();
        try (connection) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO sys_task_dispatch_intent
                    (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                     created_at, updated_at)
                    VALUES (?, ?, ?, 'pending', 1, '{}', ?, NOW(3), NOW(3))
                    """)) {
                statement.setLong(1, taskId);
                statement.setString(2, "run-rollback");
                statement.setInt(3, 1);
                statement.setString(4, "0".repeat(64));
                statement.executeUpdate();
            }
            connection.rollback();
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_task_dispatch_intent WHERE task_id = ?",
                Integer.class, taskId));
    }

    @Test
    void pendingScanSelectsOnlyOldPendingRows() {
        long taskId = insertTask("running", "run-scan");
        LocalDateTime now = LocalDateTime.now();
        insertIntent(taskId, "run-old", 1, "pending", now.minusSeconds(120));
        insertIntent(taskId, "run-fresh", 2, "pending", now.minusSeconds(5));
        insertIntent(taskId, "run-submitted", 3, "submitted", now.minusSeconds(120));

        List<TaskDispatchIntent> selected = intentMapper.findPendingOlderThan(
                now.minusSeconds(30), 20);
        assertEquals(1, selected.size());
        assertEquals("run-old", selected.get(0).getRunId());
    }

    @Test
    void idempotencyKeyIsScopedByOwnerAndExpiry() {
        LocalDateTime now = LocalDateTime.now();
        com.securitymodel.domain.task.RequestIdempotencyRecord first = idempotency(7L, "task.start", "k1",
                "hash-a", now, now.plusHours(24));
        idempotencyMapper.insert(first);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DuplicateKeyException.class,
                () -> idempotencyMapper.insert(idempotency(7L, "task.start", "k1",
                        "hash-b", now, now.plusHours(24))));

        idempotencyMapper.insert(idempotency(8L, "task.start", "k1",
                "hash-a", now, now.plusHours(24)));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_request_idempotency WHERE idempotency_key = 'k1'",
                Integer.class));

        assertNull(idempotencyMapper.findByKey(7L, "task.start", "k1", now.plusHours(25)));
        assertEquals(1, idempotencyMapper.deleteExpired(7L, "task.start", "k1", now.plusHours(25)));
    }

    @Test
    void launchTransactionRollbackLeavesNoTaskLeaseOrIntent() throws Exception {
        long taskId = insertTask("pending", null);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_gpu_lease (
                  slot_id VARCHAR(32) NOT NULL PRIMARY KEY,
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
                  blocked_reason VARCHAR(64) NULL
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        var connection = jdbc.getDataSource().getConnection();
        try (connection) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("""
                    UPDATE sys_pre_defense
                    SET status = 'running', current_run_id = 'run-tx', current_attempt = 1
                    WHERE task_id = ? AND status = 'pending'
                    """)) {
                statement.setLong(1, taskId);
                assertEquals(1, statement.executeUpdate());
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO sys_gpu_lease (slot_id, lease_token, task_id, run_id, attempt,
                      fencing_token, acquired_at, expires_at)
                    VALUES ('tx-slot', 'tx-token', ?, 'run-tx', 1, 1, NOW(3), NOW(3) + INTERVAL 5 MINUTE)
                    """)) {
                statement.setLong(1, taskId);
                assertEquals(1, statement.executeUpdate());
            }
            insertIntentOnConnection(connection, taskId, "run-tx", 1);
            connection.rollback();
        }
        assertEquals("pending", taskMapper.selectById(taskId).getStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_gpu_lease WHERE slot_id = 'tx-slot'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_task_dispatch_intent WHERE task_id = ?",
                Integer.class, taskId));
    }

    @Test
    void pendingIntentIsReplayedFromRealDatabaseWithoutBeingFailed() {
        long taskId = insertTask("running", "run-replay");
        insertIntent(taskId, "run-replay", 1, "pending", LocalDateTime.now().minusSeconds(10));
        PreDefenseTask task = taskMapper.selectById(taskId);
        TaskDispatchIntent intent = intentMapper.findByRun(taskId, "run-replay", 1);
        TaskDispatchCoordinator replay = mock(TaskDispatchCoordinator.class);
        when(replay.replayPending(intent, task)).thenReturn(true);
        TaskDispatchIntentService realIntents = new TaskDispatchIntentService(
                intentMapper, new com.fasterxml.jackson.databind.ObjectMapper());
        DispatchRecoveryCoordinator coordinator = new DispatchRecoveryCoordinator(
                taskMapper, realIntents, mock(com.securitymodel.infrastructure.executor.AttemptProcessRegistry.class),
                mock(GpuLeaseService.class), replay, new SchedulingReadinessGate(),
                mock(com.securitymodel.infrastructure.observability.TaskRuntimeMetrics.class), 30, 300, 20);

        coordinator.recoverAtStartup();

        verify(replay).replayPending(intent, task);
        assertEquals("running", taskMapper.selectById(taskId).getStatus());
    }

    @Test
    void r3SqlScriptsAreReRunnableAndRollbackOnlyOwnTables() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS sys_task_dispatch_intent");
        jdbc.execute("DROP TABLE IF EXISTS sys_request_idempotency");
        runSqlScript("sql/security_model_task_dispatch.sql");
        runSqlScript("sql/security_model_request_idempotency.sql");
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'sys_task_dispatch_intent'
                """, Integer.class));
        // Re-running the forward script must not fail or duplicate constraints.
        runSqlScript("sql/security_model_task_dispatch.sql");
        runSqlScript("sql/security_model_request_idempotency_rollback.sql");
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'sys_request_idempotency'
                """, Integer.class));
        runSqlScript("sql/security_model_task_dispatch_rollback.sql");
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'sys_task_dispatch_intent'
                """, Integer.class));
        // Restore the schema used by the remaining test setup.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys_task_dispatch_intent (
                  intent_id BIGINT NOT NULL AUTO_INCREMENT, task_id BIGINT NOT NULL,
                  run_id VARCHAR(64) NOT NULL, attempt INT NOT NULL, status VARCHAR(20) NOT NULL,
                  schema_version INT NOT NULL DEFAULT 1, request_json LONGTEXT NOT NULL,
                  request_hash CHAR(64) NOT NULL, runtime_hash CHAR(64) NULL, error_code VARCHAR(64) NULL,
                  created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
                  submitted_at DATETIME(3) NULL, PRIMARY KEY (intent_id),
                  UNIQUE KEY uk_dispatch_intent_run (task_id, run_id, attempt),
                  KEY idx_dispatch_intent_status (status, updated_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private void insertIntentOnConnection(java.sql.Connection connection, long taskId,
                                           String runId, int attempt) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sys_task_dispatch_intent
                (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                 created_at, updated_at)
                VALUES (?, ?, ?, 'pending', 1, '{}', ?, NOW(3), NOW(3))
                """)) {
            statement.setLong(1, taskId);
            statement.setString(2, runId);
            statement.setInt(3, attempt);
            statement.setString(4, "0".repeat(64));
            statement.executeUpdate();
        }
    }

    private void runSqlScript(String path) throws Exception {
        String script = java.nio.file.Files.readString(java.nio.file.Paths.get("..", path))
                .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : script.split(";")) {
            String sql = statement.trim();
            if (!sql.isEmpty()) {
                jdbc.execute(sql);
            }
        }
    }

    @Test
    void concurrentClaimsAllowExactlyOneWorkerToOwnPendingTask() throws Exception {
        long taskId = insertTask("pending", null);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = workers.submit(() -> claim(taskId, "run-a", ready, start));
            Future<Integer> second = workers.submit(() -> claim(taskId, "run-b", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            int affectedRows = first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS);
            assertEquals(1, affectedRows);

            PreDefenseTask stored = taskMapper.selectById(taskId);
            assertEquals("running", stored.getStatus());
            assertTrue(List.of("run-a", "run-b").contains(stored.getCurrentRunId()));
            assertEquals(1, stored.getCurrentAttempt());
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void completionWinsAfterRecoverySnapshotAndStaleRecoveryCannotOverwriteIt() throws Exception {
        long taskId = insertTask("running", "run-current");
        CountDownLatch recoveryReadSnapshot = new CountDownLatch(1);
        CountDownLatch allowRecoveryUpdate = new CountDownLatch(1);
        AtomicInteger recoveryUpdateRows = new AtomicInteger(-1);
        PreDefenseTaskMapper racingMapper = interceptRecoverySnapshot(
                taskMapper, recoveryReadSnapshot, allowRecoveryUpdate, recoveryUpdateRows);
        TaskRecoveryRunner recoveryRunner = recoveryRunner(racingMapper);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> recovery = worker.submit(recoveryRunner::recoverOrphanedTasks);
            assertTrue(recoveryReadSnapshot.await(5, TimeUnit.SECONDS));

            int completed = taskMapper.finishForRun(
                    taskId, "run-current", "success", 100, LocalDateTime.now());
            assertEquals(1, completed);

            allowRecoveryUpdate.countDown();
            recovery.get(10, TimeUnit.SECONDS);
            assertEquals(0, recoveryUpdateRows.get());
            assertEquals("success", taskMapper.selectById(taskId).getStatus());
        } finally {
            allowRecoveryUpdate.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void recoveryFailsOnlyOrphanedRunningTasks() {
        long orphanedId = insertTask("running", "orphan-run");
        long pendingId = insertTask("pending", null);
        TaskRecoveryRunner recoveryRunner = recoveryRunner(taskMapper);

        recoveryRunner.recoverOrphanedTasks();

        PreDefenseTask recovered = taskMapper.selectById(orphanedId);
        assertEquals("failed", recovered.getStatus());
        assertTrue(recovered.getCleaningResult().contains("EXECUTOR_RESTARTED"));
        assertNotNull(recovered.getEndTime());
        assertEquals("pending", taskMapper.selectById(pendingId).getStatus());
        assertNull(taskMapper.selectById(pendingId).getCleaningResult());
    }

    @Test
    void ownerScopedQueryReturnsOnlyThatUsersRows() {
        long aliceTask = insertTask("success", null, 7L, "alice", "legacy");
        long bobTask = insertTask("success", null, 8L, "bob", "legacy");
        long demoTask = insertTask("success", null, 7L, "alice", "demo");

        List<PreDefenseTask> aliceRows = taskMapper.selectList(
                Wrappers.<PreDefenseTask>lambdaQuery().eq(PreDefenseTask::getOwnerUserId, 7L));

        assertEquals(2, aliceRows.size());
        assertEquals(Set.of(aliceTask, demoTask), aliceRows.stream()
                .map(PreDefenseTask::getTaskId).collect(Collectors.toSet()));
        assertEquals("legacy", aliceRows.stream()
                .filter(item -> item.getTaskId() == aliceTask).findFirst().orElseThrow().getDataOrigin());
        assertEquals(bobTask, taskMapper.selectOne(
                Wrappers.<PreDefenseTask>lambdaQuery().eq(PreDefenseTask::getOwnerUserId, 8L)).getTaskId());
    }

    @Test
    void demoRowsAreExcludedFromTheOwnerScopedList() {
        long realTask = insertTask("success", null, 7L, "alice", "legacy");
        insertTask("success", null, 7L, "alice", "demo");

        List<PreDefenseTask> visible = taskMapper.selectList(
                Wrappers.<PreDefenseTask>lambdaQuery()
                        .eq(PreDefenseTask::getOwnerUserId, 7L)
                        .ne(PreDefenseTask::getDataOrigin, "demo"));

        assertEquals(1, visible.size());
        assertEquals(realTask, visible.get(0).getTaskId());
    }

    private int claim(long taskId, String runId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return taskMapper.claimForExecution(
                taskId, runId, 1, LocalDateTime.now(), "{\"run_id\":\"" + runId + "\"}");
    }

    private long insertTask(String status, String runId) {
        return insertTask(status, runId, null, null, null);
    }

    private long insertTask(String status, String runId, Long ownerUserId, String createBy, String dataOrigin) {
        long taskId = System.currentTimeMillis() * 1000 + TASK_IDS.getAndIncrement();
        jdbc.update("""
                        INSERT INTO sys_pre_defense
                          (task_id, task_name, status, dataset_id, algorithm_id, progress,
                           current_run_id, current_attempt, create_by, create_time, update_time,
                           owner_user_id, data_origin)
                        VALUES (?, ?, ?, 1, 1, 0, ?, ?, ?, NOW(), NOW(), ?, ?)
                        """,
                taskId, "integration-task-" + taskId, status, runId,
                runId == null ? null : 1, createBy, ownerUserId, dataOrigin);
        return taskId;
    }

    private void insertIntent(long taskId, String runId, int attempt, String status,
                              LocalDateTime updatedAt) {
        jdbc.update("""
                        INSERT INTO sys_task_dispatch_intent
                          (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                           created_at, updated_at)
                        VALUES (?, ?, ?, ?, 1, '{}', ?, ?, ?)
                        """,
                taskId, runId, attempt, status, "0".repeat(64), updatedAt, updatedAt);
    }

    private com.securitymodel.domain.task.RequestIdempotencyRecord idempotency(
            Long ownerUserId, String operation, String key, String hash,
            LocalDateTime now, LocalDateTime expiresAt) {
        com.securitymodel.domain.task.RequestIdempotencyRecord record =
                new com.securitymodel.domain.task.RequestIdempotencyRecord();
        record.setOwnerUserId(ownerUserId);
        record.setOperation(operation);
        record.setIdempotencyKey(key);
        record.setRequestHash(hash);
        record.setStatus("processing");
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setExpiresAt(expiresAt);
        return record;
    }

    private PreDefenseTaskMapper interceptRecoverySnapshot(
            PreDefenseTaskMapper delegate,
            CountDownLatch snapshotRead,
            CountDownLatch allowUpdate,
            AtomicInteger updateRows) {
        return (PreDefenseTaskMapper) Proxy.newProxyInstance(
                PreDefenseTaskMapper.class.getClassLoader(),
                new Class<?>[]{PreDefenseTaskMapper.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if ("selectList".equals(method.getName())) {
                            snapshotRead.countDown();
                            assertTrue(allowUpdate.await(5, TimeUnit.SECONDS));
                        } else if ("failOrphanedForRun".equals(method.getName())) {
                            updateRows.set((Integer) result);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @Test
    void recoversPendingAndRunningExportsToExplicitDatabaseFailures() {
        jdbc.update("INSERT INTO sys_pre_defense_export (task_id, isolation_ratio, status, progress) "
                + "VALUES (90, 5, 'pending', 0), (91, 10, 'running', 40)");

        new com.securitymodel.application.export.ExportRecoveryRunner(exportMapper,
                mock(com.securitymodel.application.audit.OperationAuditService.class))
                .recoverOrphanedExports();

        assertEquals("failed", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense_export WHERE task_id=90", String.class));
        assertEquals("EXPORT_NOT_STARTED", jdbc.queryForObject(
                "SELECT error_code FROM sys_pre_defense_export WHERE task_id=90", String.class));
        assertEquals("failed", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense_export WHERE task_id=91", String.class));
        assertEquals("EXPORT_INTERRUPTED", jdbc.queryForObject(
                "SELECT error_code FROM sys_pre_defense_export WHERE task_id=91", String.class));
    }

    private TaskRecoveryRunner recoveryRunner(PreDefenseTaskMapper mapper) {
        return new TaskRecoveryRunner(new DispatchRecoveryCoordinator(
                mapper, mock(TaskDispatchIntentService.class),
                mock(com.securitymodel.infrastructure.executor.AttemptProcessRegistry.class),
                mock(GpuLeaseService.class), mock(TaskDispatchCoordinator.class),
                new SchedulingReadinessGate(),
                mock(com.securitymodel.infrastructure.observability.TaskRuntimeMetrics.class), 30, 300, 20));
    }

    private static void createIsolatedSchema() {
        if (!SCHEMA.matches("security_model_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to create a non-isolated MySQL test schema");
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            schemaCreated = true;
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
    static class PersistenceTestConfiguration {
    }
}
