package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.application.assistant.AssistantSessionService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.GpuLeaseService;
import com.securitymodel.application.task.TaskDispatchCoordinator;
import com.securitymodel.application.task.TaskExecutionQuotaPolicy;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.assistant.AssistantSession;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.support.OwnershipE2ESupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP + real MySQL confirmation test. The task executor is a Mockito stub, so no
 * Python/LDD process is started. It proves: preview writes nothing, one confirmation
 * cancels once, a duplicate returns the original result, and another user is rejected.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
@AutoConfigureMockMvc
class AssistantConfirmationEndToEndIT {

    private static final String JWT_SECRET = "e2e-assistant-secret-0123456789abcdef0123456789";
    private static final long ALICE_RUNNING_TASK = 700L;
    private static final long ALICE_PENDING_TASK = 701L;
    private static final long ALICE_FAILED_TASK = 702L;
    private static final long ALICE_EXPORT_TASK = 703L;
    private static final String RUN_ID = "assistant-e2e-run";

    static {
        OwnershipE2ESupport.schemaUrl();
        createAssistantSchema();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AssistantSessionService sessionService;
    @MockBean
    private DefenseTaskExecutor executor;
    @MockBean
    private ResourceQueryService resourceQueryService;
    @MockBean
    private GpuLeaseService gpuLeaseService;
    @MockBean
    private TaskExecutionQuotaPolicy quotaPolicy;
    @MockBean
    private TaskDispatchCoordinator dispatchCoordinator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
    }

    @BeforeEach
    void resetRunningTask() {
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_RUNNING_TASK);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_PENDING_TASK);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_FAILED_TASK);
        jdbc.update("DELETE FROM sys_pre_defense_export WHERE task_id = ?", ALICE_EXPORT_TASK);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_EXPORT_TASK);
        jdbc.update("DELETE FROM sys_assistant_confirmation");
        jdbc.update("DELETE FROM sys_assistant_session");
        jdbc.update("DELETE FROM sys_assistant_tool_audit");
        jdbc.update("DELETE FROM sys_assistant_usage_daily");
        jdbc.update("DELETE FROM sys_request_idempotency WHERE operation LIKE 'assistant.%'");
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, status, dataset_id, algorithm_id, progress,
                    create_by, create_time, update_time, owner_user_id, data_origin,
                    current_run_id, current_attempt
                ) VALUES (?, 'alice-assistant-running', 'running', 1, 1, 30,
                    'alice', NOW(3), NOW(3), 2, 'user', ?, 1)
                """, ALICE_RUNNING_TASK, RUN_ID);

        when(resourceQueryService.findDataset(1L)).thenReturn(dataset());
        when(resourceQueryService.findAlgorithm(1L)).thenReturn(algorithm());
        when(gpuLeaseService.acquire(anyLong(), any(), anyInt())).thenReturn(lease());
        when(quotaPolicy.validateAndBuildSnapshot(any())).thenReturn(objectMapper.createObjectNode());
    }

    @AfterEach
    void removeTestTask() {
        // The recovery coordinator treats a running row without a real process as UNKNOWN
        // and isolates the slot. Clean it up so later IT classes share a clean schema.
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_RUNNING_TASK);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_PENDING_TASK);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_FAILED_TASK);
        jdbc.update("DELETE FROM sys_pre_defense_export WHERE task_id = ?", ALICE_EXPORT_TASK);
        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", ALICE_EXPORT_TASK);
        jdbc.update("DELETE FROM sys_assistant_confirmation");
        jdbc.update("DELETE FROM sys_assistant_session");
        jdbc.update("DELETE FROM sys_assistant_tool_audit");
        jdbc.update("DELETE FROM sys_assistant_usage_daily");
        jdbc.update("DELETE FROM sys_request_idempotency WHERE operation LIKE 'assistant.%'");
    }

    @Test
    void previewDoesNotWriteAndOneConfirmationExecutesOnce() throws Exception {
        String alice = login("alice");
        String bob = login("bob");

        String preview = mockMvc.perform(post("/api/assistant/tools/preview_cancel_task/preview")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + ALICE_RUNNING_TASK + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.operation").value("cancel"))
                .andReturn().getResponse().getContentAsString();

        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_RUNNING_TASK));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_assistant_tool_audit
                WHERE user_id = 2 AND tool_name = 'preview_cancel_task' AND result_code = 'PREVIEWED'
                """, Integer.class));

        long confirmationId = objectMapper.readTree(preview).path("confirmationId").asLong();
        String token = objectMapper.readTree(preview).path("token").asText();

        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationReplayed").value(false));

        assertEquals("canceled", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_RUNNING_TASK));
        verify(executor, times(1)).cancelPreDefense(ALICE_RUNNING_TASK, RUN_ID);

        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmationReplayed").value(true));
        verify(executor, times(1)).cancelPreDefense(ALICE_RUNNING_TASK, RUN_ID);

        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSISTANT_CONFIRMATION_FORBIDDEN"));
    }

    @Test
    void taskChangeAfterPreviewMakesConfirmationStaleAndExecutesNothing() throws Exception {
        String alice = login("alice");
        String preview = mockMvc.perform(post("/api/assistant/tools/preview_cancel_task/preview")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + ALICE_RUNNING_TASK + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long confirmationId = objectMapper.readTree(preview).path("confirmationId").asLong();
        String token = objectMapper.readTree(preview).path("token").asText();

        // A concurrent run started/changed after the user previewed: the previewed parameters
        // no longer describe the current task version.
        jdbc.update("UPDATE sys_pre_defense SET current_run_id = 'mutated-run' WHERE task_id = ?",
                ALICE_RUNNING_TASK);

        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSISTANT_CONFIRMATION_STALE"));

        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_RUNNING_TASK));
        verify(executor, times(0)).cancelPreDefense(ALICE_RUNNING_TASK, "mutated-run");
    }

    @Test
    void modelDisabledReturnsExplicitStatusAndCoreApiStillWorks() throws Exception {
        String alice = login("alice");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"查看我的任务\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("model_disabled"))
                .andExpect(jsonPath("$.toolCalls").value(0))
                .andExpect(jsonPath("$.modelCalls").value(1));
        mockMvc.perform(get("/api/pre-defense/100").header("Authorization", alice))
                .andExpect(status().isOk());
    }

    @Test
    void crossUserCancelPreviewIsRejected() throws Exception {
        String bob = login("bob");
        mockMvc.perform(post("/api/assistant/tools/preview_cancel_task/preview")
                        .header("Authorization", bob)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + ALICE_RUNNING_TASK + "}"))
                .andExpect(status().isForbidden());
        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_RUNNING_TASK));
    }

    private Dataset dataset() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(1L);
        dataset.setDatasetName("CIFAR-10 test");
        dataset.setDatasetType("image");
        dataset.setFilePath("dataset://cifar10");
        dataset.setEnabled(true);
        dataset.setParseStatus("success");
        return dataset;
    }

    private Algorithm algorithm() {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(1L);
        algorithm.setAlgorithmName("LDD");
        algorithm.setAlgorithmType("pre_defense");
        algorithm.setAlgorithmCategory("data_cleaning");
        algorithm.setDefenseStage("pre");
        algorithm.setImplementationPath("vab://pre-defense/ldd");
        algorithm.setEnabled(true);
        algorithm.setDefaultConfig("{\"dataset\":\"cifar10\",\"trigger_type\":\"badnets\","
                + "\"trigger_label\":0,\"poisoned_mode\":\"all2one\",\"poisoned_portion\":0.1,"
                + "\"batch_size\":128,\"num_epochs\":5,\"lr_poison\":0.01,\"r\":0.5,"
                + "\"alpha1\":1.0,\"alpha2\":1.0,\"seed\":11}");
        return algorithm;
    }

    private GpuLease lease() {
        GpuLease lease = new GpuLease();
        lease.setSlotId("default-gpu-0");
        lease.setLeaseToken("assistant-e2e-lease");
        lease.setTaskId(ALICE_RUNNING_TASK);
        lease.setRunId("assistant-e2e-run");
        lease.setAttempt(1);
        lease.setFencingToken(1L);
        lease.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return lease;
    }

    @Test
    void createConfirmationPersistsPendingTaskAndNeverStartsIt() throws Exception {
        String alice = login("alice");
        String taskName = "assistant-create-" + UUID.randomUUID();
        var createArgs = objectMapper.createObjectNode()
                .put("taskName", taskName)
                .put("datasetId", 1)
                .put("algorithmId", 1);
        createArgs.set("customParams", objectMapper.createObjectNode().put("seed", 11));

        String preview = mockMvc.perform(post("/api/assistant/tools/preview_create_task/preview")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createArgs.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("create"))
                .andReturn().getResponse().getContentAsString();

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_pre_defense WHERE task_name = ?", Integer.class, taskName));

        long confirmationId = objectMapper.readTree(preview).path("confirmationId").asLong();
        String token = objectMapper.readTree(preview).path("token").asText();
        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("pending"));

        assertEquals("pending", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_name = ?", String.class, taskName));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT owner_user_id FROM sys_pre_defense WHERE task_name = ?", Long.class, taskName));
        verify(executor, times(0)).runPreDefense(any());
    }

    @Test
    void startConfirmationClaimsPendingTaskAndCreatesOneRun() throws Exception {
        String alice = login("alice");
        insertTask(ALICE_PENDING_TASK, "assistant-pending", "pending", null, 1, null);

        String preview = mockMvc.perform(post("/api/assistant/tools/preview_start_task/preview")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + ALICE_PENDING_TASK + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("start"))
                .andReturn().getResponse().getContentAsString();

        long confirmationId = objectMapper.readTree(preview).path("confirmationId").asLong();
        String token = objectMapper.readTree(preview).path("token").asText();
        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isOk());

        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_PENDING_TASK));
        assertTrue(jdbc.queryForObject(
                "SELECT current_run_id FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_PENDING_TASK)
                .length() > 10);
        verify(dispatchCoordinator, times(1)).dispatchAfterCommit(any());
    }

    @Test
    void retryConfirmationAppendsAttemptTwoAfterFailedRun() throws Exception {
        String alice = login("alice");
        String snapshot = """
                {"version":2,"attempts":[{"attempt":1,"runId":"old-run",
                 "request":{"taskName":"assistant-failed"}}]}
                """;
        insertTask(ALICE_FAILED_TASK, "assistant-failed", "failed", "old-run", 1, snapshot);

        String preview = mockMvc.perform(post("/api/assistant/tools/preview_retry_task/preview")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + ALICE_FAILED_TASK + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("retry"))
                .andReturn().getResponse().getContentAsString();

        long confirmationId = objectMapper.readTree(preview).path("confirmationId").asLong();
        String token = objectMapper.readTree(preview).path("token").asText();
        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isOk());

        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM sys_pre_defense WHERE task_id = ?", String.class, ALICE_FAILED_TASK));
        assertEquals(2, jdbc.queryForObject(
                "SELECT current_attempt FROM sys_pre_defense WHERE task_id = ?", Integer.class, ALICE_FAILED_TASK));
        verify(dispatchCoordinator, times(1)).dispatchAfterCommit(any());
    }

    @Test
    void exportConfirmationCreatesOneExportRowForOwnedSuccessTask() throws Exception {
        String alice = login("alice");
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, status, dataset_id, algorithm_id, cleaning_result,
                    progress, create_by, create_time, owner_user_id, data_origin
                )
                SELECT ?, 'assistant-export', status, dataset_id, algorithm_id, cleaning_result,
                       progress, 'alice', NOW(), 2, 'user'
                FROM sys_pre_defense WHERE task_id = 500
                """, ALICE_EXPORT_TASK);

        String preview = mockMvc.perform(post("/api/assistant/tools/preview_export_task/preview")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"taskId\":" + ALICE_EXPORT_TASK + ",\"isolationRatio\":"
                                + OwnershipE2ESupport.EXPORT_RATIO + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("export"))
                .andReturn().getResponse().getContentAsString();

        long confirmationId = objectMapper.readTree(preview).path("confirmationId").asLong();
        String token = objectMapper.readTree(preview).path("token").asText();
        mockMvc.perform(post("/api/assistant/confirmations/confirm")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmationId", confirmationId, "token", token))))
                .andExpect(status().isOk());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_pre_defense_export WHERE task_id = ?",
                Integer.class, ALICE_EXPORT_TASK));
    }

    private void insertTask(long taskId, String taskName, String status, String runId,
                            Integer attempt, String snapshot) {
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, status, dataset_id, algorithm_id, progress,
                    create_by, create_time, update_time, owner_user_id, data_origin,
                    current_run_id, current_attempt, execution_snapshot
                ) VALUES (?, ?, ?, 1, 1, 0, 'alice', NOW(3), NOW(3), 2, 'user', ?, ?, ?)
                """, taskId, taskName, status, runId, attempt, snapshot);
    }

    private String login(String username) throws Exception {
        String codeResponse = mockMvc.perform(get("/auth/code"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String uuid = objectMapper.readTree(codeResponse).path("uuid").asText();
        String image = objectMapper.readTree(codeResponse).path("img").asText();
        String svg = new String(java.util.Base64.getDecoder().decode(image.split(",")[1]));
        var captcha = java.util.regex.Pattern.compile("<text[^>]*>(\\d{4})</text>").matcher(svg);
        org.junit.jupiter.api.Assertions.assertTrue(captcha.find());
        String body = objectMapper.writeValueAsString(Map.of(
                "uuid", uuid, "code", captcha.group(1), "username", username,
                "password", OwnershipE2ESupport.RAW_PASSWORD));
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("token").asText();
    }

    @Test
    void deletedSessionCannotBeResurrectedByLateTurnAppend() throws Exception {
        AssistantSession session = sessionService.getOrCreate(null, 2L, "删除会话");
        assertTrue(sessionService.appendTurn(session, "第一问", "answered", "第一答",
                List.of(), List.of(), List.of()));
        sessionService.deleteOwned(session.getSessionId(), 2L);

        assertFalse(sessionService.appendTurn(session, "迟到回答", "answered", "不得复活",
                List.of(), List.of(), List.of()));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, summary_json FROM sys_assistant_session WHERE session_id = ?",
                session.getSessionId());
        assertEquals("deleted", row.get("status"));
        assertFalse(String.valueOf(row.get("summary_json")).contains("迟到回答"));
    }

    @Test
    void concurrentTurnsAreMergedWithConditionalUpdate() throws Exception {
        AssistantSession session = sessionService.getOrCreate(null, 2L, "并发会话");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> appendAfter(start, session, "问题一", "回答一"));
            Future<Boolean> second = pool.submit(() -> appendAfter(start, session, "问题二", "回答二"));
            start.countDown();
            assertTrue(first.get(5, TimeUnit.SECONDS));
            assertTrue(second.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        String stored = jdbc.queryForObject(
                "SELECT summary_json FROM sys_assistant_session WHERE session_id = ?",
                String.class, session.getSessionId());
        assertTrue(stored.contains("问题一"));
        assertTrue(stored.contains("问题二"));
    }

    private boolean appendAfter(CountDownLatch start, AssistantSession session,
                                String question, String answer) throws Exception {
        assertTrue(start.await(2, TimeUnit.SECONDS));
        return sessionService.appendTurn(session, question, "answered", answer,
                List.of(), List.of(), List.of());
    }

    private static void createAssistantSchema() {
        try (Connection connection = DriverManager.getConnection(
                OwnershipE2ESupport.schemaUrl(), OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_confirmation (
                      confirmation_id BIGINT NOT NULL AUTO_INCREMENT,
                      token_hash CHAR(64) NOT NULL,
                      user_id BIGINT NOT NULL,
                      operation VARCHAR(32) NOT NULL,
                      target_task_id BIGINT NULL,
                      target_run_id VARCHAR(64) NULL,
                      context_hash CHAR(64) NOT NULL,
                      request_json MEDIUMTEXT NOT NULL,
                      preview_json MEDIUMTEXT NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      result_code INT NULL,
                      result_json MEDIUMTEXT NULL,
                      created_at DATETIME(3) NOT NULL,
                      expires_at DATETIME(3) NOT NULL,
                      consumed_at DATETIME(3) NULL,
                      PRIMARY KEY (confirmation_id),
                      UNIQUE KEY uk_assistant_confirmation_token (token_hash)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_session (
                      session_id BIGINT NOT NULL AUTO_INCREMENT,
                      user_id BIGINT NOT NULL,
                      title VARCHAR(160) NOT NULL,
                      status VARCHAR(16) NOT NULL,
                      summary_json MEDIUMTEXT NULL,
                      created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      expires_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (session_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_tool_audit (
                      audit_id BIGINT NOT NULL AUTO_INCREMENT,
                      session_id BIGINT NULL,
                      user_id BIGINT NOT NULL,
                      tool_name VARCHAR(64) NOT NULL,
                      operation VARCHAR(32) NULL,
                      target_task_id BIGINT NULL,
                      target_run_id VARCHAR(64) NULL,
                      result_code VARCHAR(64) NOT NULL,
                      duration_ms INT NOT NULL,
                      created_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (audit_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_usage_daily (
                      user_id BIGINT NOT NULL,
                      usage_date DATE NOT NULL,
                      request_count INT NOT NULL,
                      updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (user_id, usage_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
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
            throw new IllegalStateException("无法创建 R7 集成测试表", exception);
        }
    }
}
