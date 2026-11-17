package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.SecurityModelApplication;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.support.OwnershipE2ESupport;
import com.sun.net.httpserver.HttpServer;
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
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real HTTP + real Spring assistant service + real MySQL-backed tools, with a local
 * scripted model. This is the user-path acceptance for the three scenario contract:
 * the browser sends scenario, server evidence builds the visible answer, invalid
 * parameters are shown explicitly, and a fabricated metric/citation never reaches the
 * answer or the saved turn. It does not call real DeepSeek, LDD or GPU.
 */
@SpringBootTest(classes = SecurityModelApplication.class)
@AutoConfigureMockMvc
class AssistantScenarioUserPathEndToEndIT {

    private static final String JWT_SECRET = "e2e-assistant-secret-0123456789abcdef0123456789";
    private static final String API_KEY = "local-fake-key-never-log";
    private static final long REPORT_TASK = 501L;
    private static final long PREFLIGHT_TASK = 502L;
    private static final ScriptedModel MODEL = ScriptedModel.start();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @MockBean
    private ResourceQueryService resourceQueryService;

    static {
        OwnershipE2ESupport.schemaUrl();
        createAssistantSchema();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipE2ESupport::schemaUrl);
        registry.add("spring.datasource.username", () -> OwnershipE2ESupport.USERNAME);
        registry.add("spring.datasource.password", () -> OwnershipE2ESupport.PASSWORD);
        registry.add("security-model.auth.jwt-secret", () -> JWT_SECRET);
        registry.add("security-model.ownership.admin-cross-user-read", () -> "false");
        registry.add("security-model.assistant.model.provider", () -> "deepseek");
        registry.add("security-model.assistant.model.enabled", () -> "true");
        registry.add("security-model.assistant.model.base-url", MODEL::url);
        registry.add("security-model.assistant.model.name", () -> "fake-chat");
        registry.add("security-model.assistant.model.api-key", () -> API_KEY);
        registry.add("security-model.assistant.model.timeout-seconds", () -> "3");
        registry.add("security-model.assistant.model.max-retries", () -> "0");
        registry.add("security-model.dispatch.mode", () -> "db");
        registry.add("security-model.dispatch.role", () -> "api");
    }

    @BeforeEach
    void seedOwnedTasks() {
        when(resourceQueryService.findDatasetAuthoritative(4L)).thenReturn(validDataset());
        when(resourceQueryService.findAlgorithmAuthoritative(14L)).thenReturn(validAlgorithm());
        when(resourceQueryService.findHyper(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(null);

        jdbc.update("DELETE FROM sys_pre_defense WHERE task_id IN (?, ?)", REPORT_TASK, PREFLIGHT_TASK);
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, status, dataset_id, algorithm_id, cleaning_result,
                    progress, create_by, create_time, owner_user_id, data_origin
                ) VALUES (?, 'alice-report-metrics', 'success', 4, 14, ?, 100,
                    'alice', NOW(), 2, 'user')
                """, REPORT_TASK, """
                {"metrics":{"clean_purity_percent_by_isolation_ratio":91.2,
                "selected_sample_count_by_isolation_ratio":420}}
                """);
        jdbc.update("""
                INSERT INTO sys_pre_defense (
                    task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, create_time, owner_user_id, data_origin
                ) VALUES (?, 'alice-preflight', 'pending', 4, 14, 0, 'alice', NOW(), 2, 'user')
                """, PREFLIGHT_TASK);
        jdbc.update("DELETE FROM sys_assistant_confirmation");
        jdbc.update("DELETE FROM sys_assistant_session");
        jdbc.update("DELETE FROM sys_assistant_tool_audit");
        jdbc.update("DELETE FROM sys_assistant_usage_daily");
        jdbc.update("DELETE FROM sys_request_idempotency WHERE operation LIKE 'assistant.%'");
        MODEL.reset();
    }

    @Test
    void browserScenarioPayloadBuildsServerAnswerAndKeepsFabricationsOutOfTheSavedTurn() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode().put("datasetId", 4).put("algorithmId", 14);
        arguments.putObject("customParams").put("batch_size", 128).put("num_epochs", 5);
        MODEL.script(toolCall("validate_ldd_parameters", arguments),
                answer("模型伪造：batch_size=999，纯度 99.9%，见 [citation:99]。"));

        String alice = login("alice");
        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "检查 LDD 参数",
                                "scenario", "ldd_parameter_validation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("ldd_parameter_validation"))
                .andExpect(jsonPath("$.answerSource").value("server_evidence"))
                .andExpect(jsonPath("$.structured.evidence.validation.valid").value(true))
                .andExpect(jsonPath("$.structured.requiredEvidenceTool")
                        .value("validate_ldd_parameters"))
                .andExpect(jsonPath("$.pendingConfirmations").isEmpty())
                .andReturn();

        JsonNode response = MAPPER.readTree(result.getResponse().getContentAsString());
        String answer = response.path("answer").asText();
        assertTrue(answer.contains("batch_size=128"));
        assertFalse(answer.contains("batch_size=999"));
        assertFalse(answer.contains("99.9"));
        assertFalse(answer.contains("[citation:99]"));
        assertEquals("server_evidence", response.path("answerSource").asText());

        String saved = jdbc.queryForObject(
                "SELECT summary_json FROM sys_assistant_session ORDER BY session_id DESC LIMIT 1",
                String.class);
        assertFalse(saved.contains("99.9"));
        assertFalse(saved.contains("[citation:99]"));
        assertFalse(saved.contains("batch_size=999"));
    }

    @Test
    void reportScenarioUsesServerMetricsAndRejectsForgedCitation() throws Exception {
        MODEL.script(toolCall("get_my_report_summary",
                MAPPER.createObjectNode().put("taskId", REPORT_TASK)),
                answer("任务纯度 99.9%，结论见 [citation:99]。"));

        String alice = login("alice");
        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "解释任务 501 的结果",
                                "scenario", "result_explanation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.metrics.cleanPurityPercentByIsolationRatio")
                        .value(91.2))
                .andExpect(jsonPath("$.structured.evidence.metrics.selectedSampleCountByIsolationRatio")
                        .value(420))
                .andExpect(jsonPath("$.structured.citations").isEmpty())
                .andReturn();

        JsonNode response = MAPPER.readTree(result.getResponse().getContentAsString());
        assertTrue(response.path("answer").asText().contains("91.2"));
        assertFalse(response.path("answer").asText().contains("99.9"));
        assertFalse(response.path("answer").asText().contains("[citation:99]"));
    }

    @Test
    void invalidParametersAreExplicitInTheUserFacingAnswer() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode().put("datasetId", 4).put("algorithmId", 14);
        arguments.putObject("customParams").put("batch_size", 999).put("num_epochs", 5);
        MODEL.script(toolCall("validate_ldd_parameters", arguments),
                answer("参数完全没问题，可以启动。"));

        String alice = login("alice");
        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "检查越界参数",
                                "scenario", "ldd_parameter_validation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.validation.valid").value(false))
                .andExpect(jsonPath("$.structured.evidence.validation.code")
                        .value("PARAMETER_OUT_OF_RANGE"))
                .andReturn();

        String answer = MAPPER.readTree(result.getResponse().getContentAsString())
                .path("answer").asText();
        assertTrue(answer.contains("参数校验未通过"));
        assertTrue(answer.contains("PARAMETER_OUT_OF_RANGE"));
        assertFalse(answer.contains("参数完全没问题"));
        assertFalse(answer.contains("batch_size=999"));
    }

    @Test
    void scenarioToolDenialReturnsRealHttp403AndWritesNoConfirmation() throws Exception {
        ObjectNode arguments = MAPPER.createObjectNode().put("taskName", "伪造任务");
        MODEL.script(toolCall("preview_create_task", arguments), answer("不应到达"));

        String alice = login("alice");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "解释任务结果",
                                "scenario", "result_explanation"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO"));

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_assistant_confirmation", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_assistant_tool_audit WHERE result_code = 'SCENARIO_DENIED'",
                Integer.class));
    }

    @Test
    void taskPreflightUsesServerStateAndKeepsModelProseOutOfTheAnswer() throws Exception {
        MODEL.script(toolCall("get_my_task_summary",
                MAPPER.createObjectNode().put("taskId", PREFLIGHT_TASK)),
                answer("任务肯定可以启动，执行器一定没问题。"));

        String alice = login("alice");
        MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "任务 502 能启动吗",
                                "scenario", "task_preflight"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.task.status").value("pending"))
                .andReturn();

        String answer = MAPPER.readTree(result.getResponse().getContentAsString())
                .path("answer").asText();
        assertTrue(answer.contains("任务 #502"));
        assertTrue(answer.contains("当前状态 pending"));
        assertFalse(answer.contains("肯定可以启动"));
    }

    @Test
    void resourceAndParameterFailuresAreExplicitInTheUserFacingAnswer() throws Exception {
        String alice = login("alice");
        ObjectNode arguments = MAPPER.createObjectNode().put("datasetId", 4).put("algorithmId", 14);
        arguments.putObject("customParams").put("batch_size", 128);

        when(resourceQueryService.findDatasetAuthoritative(4L)).thenReturn(null);
        MODEL.script(toolCall("validate_ldd_parameters", arguments), answer("可以启动"));
        MvcResult unavailable = mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "校验不可用数据集",
                                "scenario", "ldd_parameter_validation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.validation.valid").value(false))
                .andExpect(jsonPath("$.structured.evidence.validation.code")
                        .value("DATASET_UNAVAILABLE"))
                .andReturn();

        // Restore the valid resources, then exercise the real LDD policy's unknown-key path.
        when(resourceQueryService.findDatasetAuthoritative(4L)).thenReturn(validDataset());
        when(resourceQueryService.findAlgorithmAuthoritative(14L)).thenReturn(validAlgorithm());
        ObjectNode unknown = MAPPER.createObjectNode().put("datasetId", 4).put("algorithmId", 14);
        unknown.putObject("customParams").put("injected", 1);
        MODEL.reset();
        MODEL.script(toolCall("validate_ldd_parameters", unknown), answer("参数没问题"));
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "校验未知参数",
                                "scenario", "ldd_parameter_validation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.validation.valid").value(false))
                .andExpect(jsonPath("$.structured.evidence.validation.code").value("PARAMETER_UNKNOWN"));

        // A catalog row that is not the LDD implementation must be rejected by the same
        // create-task resource check, not silently accepted because the ID exists.
        when(resourceQueryService.findAlgorithmAuthoritative(14L)).thenReturn(notLddAlgorithm());
        MODEL.reset();
        MODEL.script(toolCall("validate_ldd_parameters", arguments), answer("参数没问题"));
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsString(Map.of(
                                "question", "校验非 LDD 算法",
                                "scenario", "ldd_parameter_validation"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.validation.valid").value(false))
                .andExpect(jsonPath("$.structured.evidence.validation.code").value("NOT_LDD_ALGORITHM"));

        String unavailableAnswer = MAPPER.readTree(unavailable.getResponse().getContentAsString())
                .path("answer").asText();
        assertTrue(unavailableAnswer.contains("DATASET_UNAVAILABLE"));
        assertFalse(unavailableAnswer.contains("可以启动"));
    }

    private Dataset validDataset() {
        Dataset dataset = new Dataset();
        dataset.setDatasetId(4L);
        dataset.setDatasetName("cifar10");
        dataset.setEnabled(true);
        dataset.setParseStatus("success");
        dataset.setSampleCount(50_000);
        dataset.setClassCount(10);
        return dataset;
    }

    private Algorithm validAlgorithm() {
        Algorithm algorithm = new Algorithm();
        algorithm.setAlgorithmId(14L);
        algorithm.setAlgorithmName("ldd");
        algorithm.setEnabled(true);
        algorithm.setDefenseStage("pre");
        algorithm.setImplementationPath("vab://pre-defense/ldd");
        algorithm.setDefaultConfig("""
                {"dataset":"cifar10","trigger_type":"badnet","trigger_label":0,
                "poisoned_mode":"all2one","poisoned_portion":0.1,"batch_size":128,
                "num_epochs":10,"lr_poison":0.01,"r":0.1,"alpha1":0.1,
                "alpha2":0.1,"seed":42}
                """);
        return algorithm;
    }

    private Algorithm notLddAlgorithm() {
        Algorithm algorithm = validAlgorithm();
        algorithm.setImplementationPath("vab://pre-defense/other");
        return algorithm;
    }

    private String login(String username) throws Exception {
        String codeResponse = mockMvc.perform(get("/auth/code"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String uuid = MAPPER.readTree(codeResponse).path("uuid").asText();
        String image = MAPPER.readTree(codeResponse).path("img").asText();
        String svg = new String(java.util.Base64.getDecoder().decode(image.split(",")[1]));
        var captcha = java.util.regex.Pattern.compile("<text[^>]*>(\\d{4})</text>").matcher(svg);
        assertTrue(captcha.find());
        String body = MAPPER.writeValueAsString(Map.of(
                "uuid", uuid, "code", captcha.group(1), "username", username,
                "password", OwnershipE2ESupport.RAW_PASSWORD));
        String response = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(response).path("token").asText();
    }

    private static String toolCall(String name, JsonNode arguments) {
        return "{\"model\":\"fake-chat\",\"choices\":[{\"message\":{\"content\":null,"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"" + name + "\",\"arguments\":"
                + quote(arguments.toString()) + "}}]}}]}";
    }

    private static String answer(String content) {
        return "{\"model\":\"fake-chat\",\"choices\":[{\"message\":{\"content\":"
                + quote(content) + "}}]}";
    }

    private static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void createAssistantSchema() {
        try (Connection connection = DriverManager.getConnection(
                OwnershipE2ESupport.schemaUrl(), OwnershipE2ESupport.USERNAME, OwnershipE2ESupport.PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_confirmation (
                      confirmation_id BIGINT NOT NULL AUTO_INCREMENT,
                      token_hash CHAR(64) NOT NULL, user_id BIGINT NOT NULL,
                      operation VARCHAR(32) NOT NULL, target_task_id BIGINT NULL,
                      target_run_id VARCHAR(64) NULL, context_hash CHAR(64) NOT NULL,
                      request_json MEDIUMTEXT NOT NULL, preview_json MEDIUMTEXT NOT NULL,
                      status VARCHAR(16) NOT NULL, result_code INT NULL, result_json MEDIUMTEXT NULL,
                      created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
                      consumed_at DATETIME(3) NULL, PRIMARY KEY (confirmation_id),
                      UNIQUE KEY uk_assistant_confirmation_token (token_hash)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_session (
                      session_id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL,
                      title VARCHAR(160) NOT NULL, status VARCHAR(16) NOT NULL,
                      summary_json MEDIUMTEXT NULL, created_at DATETIME(3) NOT NULL,
                      updated_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (session_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_tool_audit (
                      audit_id BIGINT NOT NULL AUTO_INCREMENT, session_id BIGINT NULL,
                      user_id BIGINT NOT NULL, tool_name VARCHAR(64) NOT NULL,
                      operation VARCHAR(32) NULL, target_task_id BIGINT NULL,
                      target_run_id VARCHAR(64) NULL, result_code VARCHAR(64) NOT NULL,
                      duration_ms INT NOT NULL, created_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (audit_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_assistant_usage_daily (
                      user_id BIGINT NOT NULL, usage_date DATE NOT NULL,
                      request_count INT NOT NULL, updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (user_id, usage_date)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS sys_request_idempotency (
                      record_id BIGINT NOT NULL AUTO_INCREMENT, owner_user_id BIGINT NOT NULL,
                      operation VARCHAR(64) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
                      request_hash CHAR(64) NOT NULL, resource_id BIGINT NULL,
                      status VARCHAR(20) NOT NULL, response_code INT NULL, response_body LONGTEXT NULL,
                      created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
                      PRIMARY KEY (record_id),
                      UNIQUE KEY uk_request_idempotency (owner_user_id, operation, idempotency_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot create assistant E2E schema", exception);
        }
    }

    private static final class ScriptedModel {
        private final HttpServer server;
        private final AtomicInteger index = new AtomicInteger();
        private final List<String> responses = new java.util.concurrent.CopyOnWriteArrayList<>();

        private ScriptedModel(HttpServer server) {
            this.server = server;
        }

        static ScriptedModel start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ScriptedModel result = new ScriptedModel(server);
                server.createContext("/chat/completions", exchange -> {
                    String request = new String(exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8);
                    assertFalse(request.contains(API_KEY));
                    int current = result.index.getAndIncrement();
                    String response = current < result.responses.size()
                            ? result.responses.get(current) : answer("默认回答");
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
                server.start();
                return result;
            } catch (IOException exception) {
                throw new IllegalStateException("cannot start scripted assistant model", exception);
            }
        }

        void reset() {
            responses.clear();
            index.set(0);
        }

        void script(String... replies) {
            responses.addAll(List.of(replies));
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        }
    }
}
