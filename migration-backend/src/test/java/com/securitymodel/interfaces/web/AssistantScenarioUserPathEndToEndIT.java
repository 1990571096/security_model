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
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
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
    void singleWindowAnswersCapabilityQuestionsAndSavesOnlySafeToolActivity() throws Exception {
        String alice = login("alice");
        String sessionId = null;
        for (String question : List.of("解释一下你的能力", "介绍你自己", "你的功能是什么")) {
            ObjectNode input = MAPPER.createObjectNode()
                    .put("question", question).put("scenario", "experiment_assistant");
            if (sessionId != null) {
                input.put("sessionId", sessionId);
            }
            MvcResult result = mockMvc.perform(post("/api/assistant/chat")
                            .header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                            .content(input.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answerSource").value("server_capability"))
                    .andExpect(jsonPath("$.toolCalls").value(0))
                    .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("实验助手")))
                    .andReturn();
            sessionId = MAPPER.readTree(result.getResponse().getContentAsByteArray()).path("sessionId").asText();
        }
        ObjectNode input = MAPPER.createObjectNode().put("sessionId", sessionId)
                .put("scenario", "experiment_assistant").put("question", "当前有哪些记录");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content(input.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("needs_context"))
                .andExpect(jsonPath("$.modelCalls").value(0))
                .andExpect(jsonPath("$.toolCalls").value(0))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("对话历史")));
        input.put("question", "这个怎么算？");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content(input.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("needs_context"))
                .andExpect(jsonPath("$.toolCalls").value(0));
        input.put("question", "清洗纯度是什么？");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content(input.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("explain_clean_purity"))
                .andExpect(jsonPath("$.toolResults[0].status").value("ok"));
        input.put("question", "这个怎么算？");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice).contentType(MediaType.APPLICATION_JSON)
                        .content(input.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("explain_clean_purity"));
        mockMvc.perform(get("/api/assistant/sessions/{sessionId}", sessionId).header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.turns.length()").value(7))
                .andExpect(jsonPath("$.summary.turns[5].toolResults[0].toolName").value("explain_clean_purity"))
                .andExpect(jsonPath("$.summary.turns[5].toolResults[0].data").doesNotExist());
        mockMvc.perform(get("/api/assistant/sessions/{sessionId}", sessionId)
                        .header("Authorization", login("bob")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/assistant/tools").param("scope", "experiment_assistant")
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$[*].write").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
        assertEquals(0, MODEL.index.get());
    }

    @Test
    void singleWindowUsesPlatformDefinitionsAndLiveOwnerScopedTaskData() throws Exception {
        String alice = login("alice");
        long preciseId = 9123248940453748738L;
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id, progress,
                    create_by, create_time, owner_user_id, data_origin)
                VALUES (?, 'precise-id-task', 'running', 4, 14, 20, 'alice', NOW(), 2, 'user')
                """, preciseId);
        try {
            for (String question : List.of("清洗纯度可以说明什么", "干净样本占比高就够了吗？")) {
                mockMvc.perform(experimentChat(alice, question))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.answerSource").value("server_evidence"))
                        .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("保留样本数")))
                        .andExpect(jsonPath("$.structured.evidence.metric.definition").isString())
                        .andExpect(jsonPath("$.modelCalls").value(0));
            }
            var list = mockMvc.perform(experimentChat(alice, "看看当前有哪些任务"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.toolResults[0].toolName").value("list_my_tasks"))
                    .andExpect(jsonPath("$.structured.evidence.taskList.tasks[0].taskId").value(Long.toString(preciseId)))
                    .andReturn();
            var tasks = MAPPER.readTree(list.getResponse().getContentAsByteArray())
                    .path("structured").path("evidence").path("taskList").path("tasks");
            for (var task : tasks) {
                assertFalse(List.of("200", "300", "400").contains(task.path("taskId").asText()));
            }
            mockMvc.perform(experimentChat(alice, "我有哪些正在运行的任务？"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.structured.evidence.taskList.status").value("running"))
                    .andExpect(jsonPath("$.structured.evidence.taskList.tasks[*].status")
                            .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("running"))));
            mockMvc.perform(experimentChat(alice, "看看有哪些已取消的任务"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.structured.evidence.taskList.tasks").isEmpty());
            mockMvc.perform(experimentChat(alice, "帮我解读报告"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("needs_context"))
                    .andExpect(jsonPath("$.structured.evidence.taskList.tasks[0].taskId").value(Long.toString(preciseId)));
            mockMvc.perform(experimentChat(alice, "解读任务 #501 的报告"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("91.2%")));
            jdbc.update("""
                    UPDATE sys_pre_defense SET cleaning_result =
                        '{"metrics":{"clean_purity_percent_by_isolation_ratio":{"10":91.2},
                        "selected_sample_count_by_isolation_ratio":{"10":420}}}'
                    WHERE task_id = ?
                    """, REPORT_TASK);
            mockMvc.perform(experimentChat(alice, "解读任务 #501 的报告"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("10% · 清洗纯度 91.2% · 保留样本 420 个")));
            mockMvc.perform(experimentChat(login("bob"), "解读任务 #501 的报告"))
                    .andExpect(status().isForbidden());
            assertEquals(0, MODEL.index.get());
        } finally {
            jdbc.update("DELETE FROM sys_pre_defense WHERE task_id = ?", preciseId);
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder experimentChat(
            String authorization, String question, String sessionId) {
        return post("/api/assistant/chat").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.createObjectNode().put("scenario", "experiment_assistant")
                        .put("question", question).put("sessionId", sessionId).toString());
    }

    @Test
    void assistantRoutesParaphrasesAndKeepsNegatedStatusInOwnerScopedSql() throws Exception {
        String alice = login("alice");
        jdbc.update("UPDATE sys_pre_defense SET status = 'failed' WHERE task_id = ?", PREFLIGHT_TASK);
        MODEL.script(answer("{\"kind\":\"TASKS\"}"), answer("{\"kind\":\"METRIC\"}"));
        mockMvc.perform(experimentChat(alice, "把我手头的实验都列一下"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("list_my_tasks"))
                .andExpect(jsonPath("$.modelCalls").value(0));
        mockMvc.perform(experimentChat(alice, "实验记录给我过一眼"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("list_my_tasks"))
                .andExpect(jsonPath("$.modelCalls").value(1));
        mockMvc.perform(experimentChat(alice, "留下来的数据里没被污染的比例代表啥？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("explain_clean_purity"))
                .andExpect(jsonPath("$.modelCalls").value(0));
        mockMvc.perform(experimentChat(alice, "筛出的子集有多干净，该怎么理解这个数？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("explain_clean_purity"))
                .andExpect(jsonPath("$.modelCalls").value(1));
        mockMvc.perform(experimentChat(alice, "哪些任务没有失败？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structured.evidence.taskList.excludeStatus").value("failed"))
                .andExpect(jsonPath("$.structured.evidence.taskList.tasks[?(@.status == 'failed')]").isEmpty())
                .andExpect(jsonPath("$.structured.evidence.taskList.tasks[*].taskId")
                        .value(org.hamcrest.Matchers.hasItem(Long.toString(REPORT_TASK))))
                .andExpect(jsonPath("$.answer", org.hamcrest.Matchers.containsString("排除「已失败」")))
                .andExpect(jsonPath("$.modelCalls").value(0));
        mockMvc.perform(experimentChat(alice, "有哪些指标能评价实验效果？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("search_knowledge"))
                .andExpect(jsonPath("$.modelCalls").value(0));
        for (String question : List.of("列出昨天失败的任务", "哪些任务还没完成", "列出成功和失败的任务",
                "帮我取消任务 #501")) {
            mockMvc.perform(experimentChat(alice, question))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("needs_context"))
                    .andExpect(jsonPath("$.toolCalls").value(0));
        }
        assertEquals(2, MODEL.index.get());
    }

    @Test
    void assistantReusesOnlyLastVerifiedTaskAndRejectsInvalidModelRoutes() throws Exception {
        String alice = login("alice");
        var first = mockMvc.perform(experimentChat(alice, "查询任务 #501 的进度"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("get_my_task_summary"))
                .andReturn();
        String session = MAPPER.readTree(first.getResponse().getContentAsByteArray()).path("sessionId").asText();
        mockMvc.perform(experimentChat(alice, "那它的报告呢？", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("get_my_report_summary"))
                .andExpect(jsonPath("$.structured.evidence.taskId").value("501"));
        MODEL.script(answer("{\"kind\":\"REPORT\",\"taskId\":\"300\"}"),
                toolCall("get_my_task_summary", MAPPER.createObjectNode().put("taskId", "501")),
                answer("{\"kind\":\"TASKS\",\"status\":\"running\",\"excludeStatus\":\"failed\"}"),
                answer("这是未经过校验的模型文本"));
        for (String question : List.of("顺便核一下它好了没有", "请处理这个", "给我过一眼实验记录", "再看看")) {
            mockMvc.perform(experimentChat(alice, question))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("needs_context"))
                    .andExpect(jsonPath("$.modelStatus").value("intent_invalid"))
                    .andExpect(jsonPath("$.toolCalls").value(0));
        }
        assertEquals(4, MODEL.index.get());
        for (JsonNode captured : MODEL.requests) {
            assertFalse(captured.has("tools"));
            assertEquals(2, captured.path("messages").size());
            assertEquals("json_object", captured.path("response_format").path("type").asText());
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder experimentChat(
            String authorization, String question) {
        return post("/api/assistant/chat").header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(MAPPER.createObjectNode().put("scenario", "experiment_assistant")
                        .put("question", question).toString());
    }

    @Test
    void singleWindowRoutesKnowledgeAndOwnedFailureWithoutCallingTheModel() throws Exception {
        String alice = login("alice");
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"未登记的测试术语","scenario":"experiment_knowledge"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerSource").value("server_evidence"))
                .andExpect(jsonPath("$.modelStatus").value("not_called"))
                .andExpect(jsonPath("$.toolResults[0].toolName").value("search_knowledge"));

        jdbc.update("""
                UPDATE sys_pre_defense SET status='failed', current_run_id='diagnosis-run', current_attempt=1,
                    cleaning_result='{"run_id":"diagnosis-run","attempt":1,"error_code":"GPU_OOM"}'
                WHERE task_id=?
                """, PREFLIGHT_TASK);
        mockMvc.perform(get("/api/task-progress/pre_defense/{taskId}", PREFLIGHT_TASK)
                        .header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRunId").value("diagnosis-run"))
                .andExpect(jsonPath("$.currentAttempt").value(1));
        String diagnosis = """
                {"question":"分析失败","failureContext":{"taskId":"502","runId":"diagnosis-run","attempt":"1"}}
                """;
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(diagnosis))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("failure_diagnosis"))
                .andExpect(jsonPath("$.sessionId").isString())
                .andExpect(jsonPath("$.toolResults[0].toolName").value("diagnose_task_failure"))
                .andExpect(jsonPath("$.structured.diagnosis.taskId").value("502"))
                .andExpect(jsonPath("$.structured.diagnosis.stage").value("执行阶段"))
                .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("GPU_OOM")));
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", login("bob"))
                        .contentType(MediaType.APPLICATION_JSON).content(diagnosis))
                .andExpect(status().isForbidden());
        jdbc.update("UPDATE sys_pre_defense SET status='pending' WHERE task_id=?", PREFLIGHT_TASK);
        mockMvc.perform(post("/api/assistant/chat")
                        .header("Authorization", alice)
                        .contentType(MediaType.APPLICATION_JSON).content(diagnosis))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSISTANT_DIAGNOSIS_STALE"));
        assertEquals(0, MODEL.index.get());
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
        String body = MAPPER.writeValueAsString(Map.of(
                "username", username,
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
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/security_model_knowledge.sql"));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/security_model_task_dispatch.sql"));
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
        private final List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                    result.requests.add(MAPPER.readTree(request));
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
            requests.clear();
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
