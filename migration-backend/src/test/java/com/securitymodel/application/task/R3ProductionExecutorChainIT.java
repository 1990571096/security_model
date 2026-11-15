package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * R3 production chain: real Spring context, real async proxy, real
 * {@link com.securitymodel.infrastructure.executor.PythonLddTaskExecutor}, real
 * transaction/mapper/GPU lease/dispatch coordinator, and a short Python protocol stub.
 * Never runs LDD, never allocates a GPU.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
class R3ProductionExecutorChainIT {

    private static final String JWT_SECRET = "r3-production-chain-secret-0123456789abcdef";
    private static final long TASK_ID = 910L;
    private static final long CRASH_TASK_ID = 920L;
    private static final String CRASH_RUN_ID = "run-crash-920";
    private static final Path WORKSPACE = createWorkspace();
    private static final Path SCRIPT = writeStubScript();
    private static final Path CRASH_WORKSPACE = createWorkspace();
    private static final Path CRASH_SCRIPT = writeCrashScript();

    static {
        OwnershipE2ESupport.schemaUrl();
        createDispatchSchema();
    }

    @Autowired
    private PreDefenseTaskCommandService commandService;
    @Autowired
    private com.securitymodel.infrastructure.executor.DefenseTaskExecutor executor;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockBean
    private ResourceQueryService resourceQueryService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
        registry.add("security-model.executor.python.workspace", () -> WORKSPACE.toString());
        registry.add("security-model.executor.workspace", () -> WORKSPACE.toString());
        registry.add("security-model.executor.python.script", () -> SCRIPT.toString());
        registry.add("security-model.executor.python.executable", () -> "python");
        registry.add("security-model.executor.python.timeout-seconds", () -> "3");
        registry.add("security-model.executor.task-pool.core-size", () -> "2");
        registry.add("security-model.executor.task-pool.max-size", () -> "4");
    }

    @BeforeEach
    void seed() {
        cleanTask();
        authenticate();
        when(resourceQueryService.findDatasetAuthoritative(1L)).thenReturn(dataset());
        when(resourceQueryService.findAlgorithmAuthoritative(1L)).thenReturn(algorithm());
        assertNotNull(resourceQueryService.findDatasetAuthoritative(1L), "dataset mock must be active");
        insertTask("stub:success");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        String runId = jdbc.query("SELECT current_run_id FROM sys_pre_defense WHERE task_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, TASK_ID);
        if (runId != null) {
            executor.cancelPreDefense(TASK_ID, runId);
            jdbc.update("UPDATE sys_pre_defense SET status = 'canceled' WHERE task_id = ? AND status = 'running'",
                    TASK_ID);
        }
        cleanTask();
    }

    @Test
    void normalCompletionUsesTheRealAsyncExecutorAndReleasesTheLease() throws Exception {
        startWithMode("success");
        String runId = currentRunId();
        awaitStatus("success", 20);
        assertNotNull(runId);

        JsonNode result = readCleaningResult();
        assertEquals("success", result.path("status").asText());
        assertEquals(runId, result.path("run_id").asText());
        assertEquals(100, jdbc.queryForObject(
                "SELECT progress FROM sys_pre_defense WHERE task_id = ?", Integer.class, TASK_ID));
        assertEquals("submitted", intentStatus());
        awaitLeaseReleased();
        assertProcessGone();
    }

    @Test
    void cancelStopsTheRealChildAndDoesNotLeaveARecoverableIntent() throws Exception {
        startWithMode("cancel");
        awaitProcessRecord();
        String runId = currentRunId();
        commandService.cancel(TASK_ID);

        assertEquals("canceled", awaitStatus("canceled", 20));
        assertTrue(intentStatus().equals("submitted") || intentStatus().equals("abandoned"),
                "canceled run must not leave a pending intent");
        assertProcessGone();
        awaitLeaseReleased();
        assertNotNull(runId);
    }

    @Test
    void timeoutFailsTheRunAndQuarantinesNothingAfterTheTreeIsGone() throws Exception {
        startWithMode("timeout");
        awaitStatus("failed", 20);

        JsonNode result = readCleaningResult();
        assertEquals("failed", result.path("status").asText());
        assertEquals("TASK_TIMEOUT", result.path("error_code").asText());
        assertNotNull(currentRunId());
        assertProcessGone();
        awaitLeaseReleased();
    }

    @Test
    void invalidResultIsRejectedBeforeAnySuccessIsRecorded() throws Exception {
        startWithMode("invalid");
        awaitStatus("failed", 20);

        JsonNode result = readCleaningResult();
        assertEquals("failed", result.path("status").asText());
        assertEquals("RESULT_PROTOCOL_ERROR", result.path("error_code").asText());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_pre_defense WHERE task_id = ? AND status = 'success'",
                Integer.class, TASK_ID));
        assertProcessGone();
        awaitLeaseReleased();
    }

    @Test
    void crashAfterAcceptBeforeBookkeepingRecoversWithoutReplayOrReassigningTheSlot() throws Exception {
        Path marker = CRASH_WORKSPACE.resolve("crash-ready-" + CRASH_TASK_ID);
        Files.deleteIfExists(marker);
        seedCrashWindow();
        Process child = new ProcessBuilder(java(), "-cp", classpath(),
                R3ProductionCrashChildMain.class.getName(), OwnershipE2ESupport.schemaUrl(),
                OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD,
                String.valueOf(CRASH_TASK_ID), CRASH_RUN_ID, "1", "default-gpu-0",
                "python", CRASH_WORKSPACE.toString(), CRASH_SCRIPT.toString())
                .redirectErrorStream(true)
                .start();
        long pythonPid = -1;
        try {
            awaitFile(marker, 20);
            pythonPid = awaitCrashProcessRecord();
            assertTrue(ProcessHandle.of(pythonPid).map(ProcessHandle::isAlive).orElse(false),
                    "the Python child must be alive when the Java JVM is killed");
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "crash child JVM did not exit");

            String recovery = runRecoveryChild(CRASH_TASK_ID, CRASH_RUN_ID, 1, "default-gpu-0",
                    CRASH_WORKSPACE.toString());
            assertTrue(recovery.contains("RECOVERED=startup"), recovery);
            assertEquals("failed", jdbc.queryForObject(
                    "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, CRASH_TASK_ID));
            assertEquals("abandoned", jdbc.queryForObject(
                    "SELECT status FROM sys_task_dispatch_intent WHERE task_id = ?",
                    String.class, CRASH_TASK_ID));
            assertFalse(ProcessHandle.of(pythonPid).map(ProcessHandle::isAlive).orElse(false),
                    "recovery must not reassign the slot while the orphan is known alive");
            assertNull(jdbc.queryForObject(
                    "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = 'default-gpu-0'", String.class));
            assertEquals(1, Files.readAllLines(
                    CRASH_WORKSPACE.resolve("pre_defense-" + CRASH_TASK_ID)
                            .resolve("attempt-1").resolve("executions.log")).size(),
                    "the real dispatch must start the executable exactly once");
        } finally {
            child.destroyForcibly();
            if (pythonPid > 0) {
                ProcessHandle.of(pythonPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            cleanupCrashWindow();
        }
    }

    private void seedCrashWindow() throws Exception {
        cleanupCrashWindow();
        String requestJson = "{\"taskId\":" + CRASH_TASK_ID + ",\"runId\":\"" + CRASH_RUN_ID
                + "\",\"attempt\":1,\"taskName\":\"r3-production-crash\",\"stage\":\"pre_defense\","
                + "\"description\":\"stub:crash\",\"createdBy\":\"alice\",\"customParams\":\"{}\","
                + "\"dataset\":{\"id\":1,\"name\":\"fixture-dataset\"},"
                + "\"algorithm\":{\"id\":1,\"name\":\"fixture-algorithm\"},"
                + "\"gpuLeaseToken\":\"accepted-token\",\"gpuSlotId\":\"default-gpu-0\","
                + "\"gpuFencingToken\":1}";
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, description, status, dataset_id, algorithm_id, custom_params,
                    progress, current_run_id, current_attempt, create_by, create_time, update_time,
                    owner_user_id, data_origin
                ) VALUES (?, 'r3-production-crash', 'stub:crash', 'running', 1, 1, ?, 0, ?, 1,
                    'alice', NOW(3), NOW(3), 2, 'user')
                """, CRASH_TASK_ID, finalConfig(), CRASH_RUN_ID);
        jdbc.update("""
                INSERT INTO sys_task_dispatch_intent
                  (task_id, run_id, attempt, status, schema_version, request_json, request_hash,
                   created_at, updated_at)
                VALUES (?, ?, 1, 'pending', 1, ?, ?, NOW(3), NOW(3))
                """, CRASH_TASK_ID, CRASH_RUN_ID, requestJson, sha256(requestJson));
        jdbc.update("""
                UPDATE sys_gpu_lease
                SET lease_token = 'accepted-token', task_id = ?, run_id = ?, attempt = 1,
                    acquired_at = NOW(3), heartbeat_at = NOW(3), expires_at = NOW(3) + INTERVAL 5 MINUTE,
                    released_at = NULL, blocked_at = NULL, blocked_reason = NULL
                WHERE slot_id = 'default-gpu-0'
                """, CRASH_TASK_ID, CRASH_RUN_ID);
    }

    private void cleanupCrashWindow() {
        jdbc.update("DELETE FROM sys_task_dispatch_intent WHERE task_id = ?", CRASH_TASK_ID);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", CRASH_TASK_ID);
        jdbc.update("""
                UPDATE sys_gpu_lease
                SET lease_token = NULL, task_id = NULL, run_id = NULL, attempt = NULL,
                    acquired_at = NULL, heartbeat_at = NULL, expires_at = NULL,
                    released_at = NOW(3), blocked_at = NULL, blocked_reason = NULL
                WHERE slot_id = 'default-gpu-0'
                """);
    }

    private long awaitCrashProcessRecord() throws Exception {
        Path record = CRASH_WORKSPACE.resolve("pre_defense-" + CRASH_TASK_ID)
                .resolve("attempt-1").resolve("process.json");
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(record)) {
                long pid = objectMapper.readTree(Files.readString(record)).path("pid").asLong(-1);
                if (pid > 0) {
                    return pid;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("crash-window Python process record was not written");
    }

    private void awaitFile(Path file, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("barrier file not written: " + file);
    }

    private String runRecoveryChild(long taskId, String runId, int attempt, String slot,
                                    String workspace) throws Exception {
        Process process = new ProcessBuilder(java(), "-cp", classpath(),
                R3RecoveryChildMain.class.getName(), OwnershipE2ESupport.schemaUrl(),
                OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD,
                String.valueOf(taskId), runId, String.valueOf(attempt), slot, "python",
                workspace, "startup")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "recovery child did not exit");
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String java() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }

    private String classpath() {
        return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    }

    private String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : hash) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }

    private void startWithMode(String mode) {
        jdbc.update("UPDATE sys_pre_defense SET description = ? WHERE task_id = ?",
                "stub:" + mode, TASK_ID);
        commandService.start(TASK_ID, null);
    }

    private String awaitStatus(String expected, int seconds) throws Exception {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task did not reach " + expected + ": "
                + jdbc.queryForObject("SELECT status FROM sys_pre_defense WHERE task_id = ?",
                String.class, TASK_ID));
    }

    private void awaitLeaseReleased() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            String token = jdbc.queryForObject(
                    "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = 'default-gpu-0'", String.class);
            if (token == null) {
                return;
            }
            Thread.sleep(100);
        }
        assertNull(jdbc.queryForObject(
                "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = 'default-gpu-0'", String.class));
    }

    private void awaitProcessRecord() throws Exception {
        Path record = WORKSPACE.resolve("pre_defense-" + TASK_ID).resolve("attempt-1").resolve("process.json");
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(record)
                    && objectMapper.readTree(Files.readString(record)).path("pid").asLong(-1) > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("real Python child process was not recorded");
    }

    private void assertProcessGone() throws Exception {
        Path record = WORKSPACE.resolve("pre_defense-" + TASK_ID).resolve("attempt-1").resolve("process.json");
        if (!Files.isRegularFile(record)) {
            return;
        }
        long pid = objectMapper.readTree(Files.readString(record)).path("pid").asLong(-1);
        if (pid > 0) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    return;
                }
                Thread.sleep(100);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "the real Python child must be gone before the lease can be released");
        }
    }

    private void assertLeaseReleased() {
        String token = jdbc.queryForObject(
                "SELECT lease_token FROM sys_gpu_lease WHERE slot_id = 'default-gpu-0'", String.class);
        assertNull(token, "the default slot must be free after a confirmed child exit");
    }

    private String currentRunId() {
        return jdbc.queryForObject(
                "SELECT current_run_id FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
    }

    private String intentStatus() {
        return jdbc.queryForObject(
                "SELECT status FROM sys_task_dispatch_intent WHERE task_id = ?", String.class, TASK_ID);
    }

    private JsonNode readCleaningResult() throws Exception {
        String raw = jdbc.queryForObject(
                "SELECT cleaning_result FROM sys_pre_defense WHERE task_id = ?", String.class, TASK_ID);
        return objectMapper.readTree(raw);
    }

    private void insertTask(String description) {
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, description, status, dataset_id, algorithm_id, custom_params,
                    progress, create_by, create_time, update_time, owner_user_id, data_origin
                ) VALUES (?, 'r3-production-chain', ?, 'pending', 1, 1, ?, 0,
                    'alice', NOW(3), NOW(3), 2, 'user')
                """, TASK_ID, description, finalConfig());
    }

    private void cleanTask() {
        jdbc.update("DELETE FROM sys_task_dispatch_intent WHERE task_id = ?", TASK_ID);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", TASK_ID);
        jdbc.update("""
                UPDATE sys_gpu_lease
                SET lease_token = NULL, task_id = NULL, run_id = NULL, attempt = NULL,
                    acquired_at = NULL, heartbeat_at = NULL, expires_at = NULL,
                    released_at = NOW(3), blocked_at = NULL, blocked_reason = NULL
                WHERE slot_id = 'default-gpu-0'
                """);
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

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("r3-production-chain-");
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create executor workspace", exception);
        }
    }

    private static Path writeStubScript() {
        try {
            Path script = WORKSPACE.resolve("stub_executor.py");
            Files.writeString(script, String.join("\n",
                    "import json, os, sys, time, datetime",
                    "def arg(name):",
                    "    return sys.argv[sys.argv.index(name) + 1]",
                    "request = json.load(open(arg('--request'), encoding='utf-8'))",
                    "progress = arg('--progress-file')",
                    "result_path = arg('--result')",
                    "root = os.path.dirname(os.path.abspath(result_path))",
                    "json.dump({'pids': []}, open(os.path.join(root, 'python-children.json'), 'w', encoding='utf-8'))",
                    "description = request.get('description') or ''",
                    "mode = 'success'",
                    "for candidate in ('timeout', 'cancel', 'invalid', 'failed'):",
                    "    if 'stub:' + candidate in description:",
                    "        mode = candidate",
                    "now = datetime.datetime.now(datetime.timezone.utc).isoformat()",
                    "json.dump({'run_id': request.get('runId'), 'attempt': request.get('attempt'), 'progress': 40},",
                    "          open(progress, 'w', encoding='utf-8'))",
                    "if mode in ('timeout', 'cancel'):",
                    "    time.sleep(30)",
                    "if mode == 'invalid':",
                    "    payload = {'executor': 'stub', 'stage': 'pre_defense', 'status': 'success'}",
                    "else:",
                    "    payload = {'executor': 'stub', 'stage': 'pre_defense', 'message': 'stub',",
                    "               'run_id': request.get('runId'), 'attempt': request.get('attempt'),",
                    "               'status': 'failed' if mode == 'failed' else 'success',",
                    "               'artifacts': {}, 'started_at': now, 'finished_at': now}",
                    "    if mode == 'failed':",
                    "        payload['error_code'] = 'STUB_FAILED'",
                    "        payload['failure_reason'] = 'stub failure'",
                    "json.dump(payload, open(result_path, 'w', encoding='utf-8'))",
                    "sys.exit(0)"), StandardCharsets.UTF_8);
            return script;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write executor stub", exception);
        }
    }

    private static Path writeCrashScript() {
        try {
            Path script = CRASH_WORKSPACE.resolve("crash_executor.py");
            Files.writeString(script, String.join("\n",
                    "import json, os, sys, time",
                    "def arg(name):",
                    "    return sys.argv[sys.argv.index(name) + 1]",
                    "request = json.load(open(arg('--request'), encoding='utf-8'))",
                    "root = os.path.dirname(os.path.abspath(arg('--result')))",
                    "json.dump({'pids': []}, open(os.path.join(root, 'python-children.json'), 'w', encoding='utf-8'))",
                    "with open(os.path.join(root, 'executions.log'), 'a', encoding='utf-8') as log:",
                    "    log.write('started\\n')",
                    "json.dump({'run_id': request.get('runId'), 'attempt': request.get('attempt'), 'progress': 40},",
                    "          open(arg('--progress-file'), 'w', encoding='utf-8'))",
                    "time.sleep(30)"), StandardCharsets.UTF_8);
            return script;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot write crash executor stub", exception);
        }
    }

    private static void createDispatchSchema() {
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
            throw new IllegalStateException("cannot create production-chain test tables", exception);
        }
    }
}
