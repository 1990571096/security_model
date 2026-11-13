package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
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
        TaskRecoveryRunner recoveryRunner = new TaskRecoveryRunner(racingMapper, mock(GpuLeaseService.class),
                mock(com.securitymodel.infrastructure.executor.AttemptProcessRegistry.class));
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
        TaskRecoveryRunner recoveryRunner = new TaskRecoveryRunner(taskMapper, mock(GpuLeaseService.class),
                mock(com.securitymodel.infrastructure.executor.AttemptProcessRegistry.class));

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
