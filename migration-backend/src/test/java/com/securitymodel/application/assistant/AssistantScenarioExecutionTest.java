package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Server-side scenario enforcement. The model is programmable: it can request a tool that
 * exists but is outside the selected scenario, or answer without the required evidence.
 */
class AssistantScenarioExecutionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void scenarioFiltersModelToolsAndAssemblesServerEvidence() {
        ObjectNode validationArguments = objectMapper.createObjectNode()
                .put("datasetId", 4).put("algorithmId", 14);
        validationArguments.putObject("customParams").put("batch_size", 128).put("num_epochs", 5);
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("validate_ldd_parameters", validationArguments, "fake"),
                AssistantModel.ModelReply.answer("模型伪造结论：batch_size=999，纯度为 99.9%。", "fake"));
        AssistantChatService service = service(registry(), model, mock(AssistantPreviewService.class));

        AssistantChatResponse response = service.chat(null, "检查 LDD 参数", "ldd_parameter_validation");

        assertEquals("answered", response.status());
        assertEquals("ldd_parameter_validation", response.scenario());
        assertEquals("server_evidence", response.answerSource());
        assertEquals(List.of("get_parameter_guide", "search_knowledge", "validate_ldd_parameters"),
                model.lastRequest.get().tools().stream().map(AssistantToolDescriptor::name).sorted().toList());
        assertNotNull(response.structured());
        assertEquals("ldd_parameter_validation", response.structured().path("scenario").asText());
        assertEquals("validate_ldd_parameters", response.structured().path("requiredEvidenceTool").asText());
        assertTrue(response.structured().path("evidence").path("validation").path("valid").asBoolean());
        assertTrue(response.answer().contains("batch_size=128"));
        assertFalse(response.answer().contains("batch_size=999"));
        assertFalse(response.answer().contains("99.9"));
        assertFalse(response.structured().has("failure"));
    }

    @Test
    void resultExplanationCannotRequestAWritePreview() {
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("preview_create_task",
                        objectMapper.createObjectNode().put("taskName", "伪造任务"), "fake"));
        AssistantPreviewService preview = mock(AssistantPreviewService.class);
        AssistantChatService service = service(registry(), model, preview);

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service.chat(null, "解释任务结果", "result_explanation"));

        assertEquals("ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO", exception.getCode());
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN, exception.getStatus());
        verify(preview, never()).preview(any(), any(), anyLong(), anyLong());
    }

    @Test
    void forgedMetricsInModelAnswerDoNotReplaceServerToolValues() {
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("get_my_report_summary",
                        objectMapper.createObjectNode().put("taskId", 500), "fake"),
                AssistantModel.ModelReply.answer("任务 500 的纯度为 99.9%。", "fake"));
        AssistantChatService service = service(registry(), model, mock(AssistantPreviewService.class));

        AssistantChatResponse response = service.chat(null, "解释任务 500", "result_explanation");

        assertEquals("answered", response.status());
        assertEquals("server_evidence", response.answerSource());
        assertEquals(91.2, response.structured().path("evidence").path("metrics")
                .path("cleanPurityPercentByIsolationRatio").asDouble(), 0.001);
        assertEquals(420, response.structured().path("evidence").path("metrics")
                .path("selectedSampleCountByIsolationRatio").asInt());
        assertFalse(response.structured().path("evidence").path("metrics")
                .path("cleanPurityPercentByIsolationRatio").asText().contains("99.9"));
        assertFalse(response.answer().contains("99.9"));
        assertTrue(response.answer().contains("91.2"));
    }

    @Test
    void forgedCitationInModelAnswerDoesNotCreateACitation() {
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("get_my_report_summary",
                        objectMapper.createObjectNode().put("taskId", 500), "fake"),
                AssistantModel.ModelReply.answer("结论见 [citation:99] 第 1-6 行。", "fake"));
        AssistantChatService service = service(registry(), model, mock(AssistantPreviewService.class));

        AssistantChatResponse response = service.chat(null, "解释任务 500", "result_explanation");

        assertTrue(response.citations().isEmpty());
        assertTrue(response.structured().path("citations").isEmpty());
        assertFalse(response.answer().contains("[citation:99]"));
        assertTrue(response.answer().contains("服务端工具证据组装"));
    }

    @Test
    void missingRequiredEvidenceProducesStableDegradedResult() {
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.answer("任务看起来可以启动。", "fake"));
        AssistantChatService service = service(registry(), model, mock(AssistantPreviewService.class));

        AssistantChatResponse response = service.chat(null, "任务 500 能否启动", "task_preflight");

        assertEquals("scenario_incomplete", response.status());
        assertEquals("scenario_required_tool_missing", response.modelStatus());
        assertEquals("scenario_required_tool_missing",
                response.structured().path("failure").path("code").asText());
    }

    @Test
    void unknownScenarioIsRejectedBeforeAnyToolExecution() {
        RecordingModel model = new RecordingModel(AssistantModel.ModelReply.answer("不应执行", "fake"));
        AssistantChatService service = service(registry(), model, mock(AssistantPreviewService.class));

        AssistantException exception = assertThrows(AssistantException.class,
                () -> service.chat(null, "测试", "not_a_scenario"));
        assertEquals("ASSISTANT_SCENARIO_UNKNOWN", exception.getCode());
        assertTrue(model.requests.isEmpty());
    }

    private AssistantChatService service(AssistantToolRegistry registry, AssistantModel model,
                                         AssistantPreviewService preview) {
        AssistantQuotaService quota = mock(AssistantQuotaService.class);
        when(quota.remaining()).thenReturn(50);
        AssistantSessionService sessions = mock(AssistantSessionService.class);
        AssistantSession session = new AssistantSession();
        session.setSessionId(7L);
        when(sessions.getOrCreate(any(), anyLong(), any())).thenReturn(session);
        CurrentUserService user = mock(CurrentUserService.class);
        when(user.requireStableIdentity()).thenReturn(new AuthenticatedUser(2L, "alice", false));
        AssistantAuditService audit = mock(AssistantAuditService.class);
        return new AssistantChatService(model, registry, preview, audit, quota, sessions, user,
                objectMapper, 6, 5, 60, 1);
    }

    private JsonNode validationData(boolean valid, int batchSize) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("valid", valid);
        data.put("code", valid ? "PARAMETERS_VALID" : "PARAMETER_OUT_OF_RANGE");
        data.put("message", valid ? "参数校验通过"
                : "LDD 参数 batch_size 必须是范围为 [16, 512] 的整数");
        data.put("datasetId", 4);
        data.put("algorithmId", 14);
        data.put("parameterSource", "LDD_CONFIGURATION_POLICY");
        data.put("configFingerprint", "server-fingerprint");
        data.putObject("algorithm").put("algorithmId", 14).put("name", "LDD");
        data.putObject("dataset").put("datasetId", 4).put("name", "cifar10");
        if (valid) {
            data.putObject("normalizedParameters")
                    .put("batch_size", batchSize)
                    .put("num_epochs", 5);
        }
        return data;
    }

    @Test
    void invalidParametersAreShownFromServerValidationNotModelProse() {
        ObjectNode validationArguments = objectMapper.createObjectNode()
                .put("datasetId", 4).put("algorithmId", 14);
        validationArguments.putObject("customParams").put("batch_size", 999).put("num_epochs", 5);
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("validate_ldd_parameters", validationArguments, "fake"),
                AssistantModel.ModelReply.answer("参数完全没问题，batch_size=999 可执行。", "fake"));
        AssistantChatService service = service(registry(validationData(false, 999)), model,
                mock(AssistantPreviewService.class));

        AssistantChatResponse response = service.chat(null, "检查越界参数", "ldd_parameter_validation");

        assertEquals("answered", response.status());
        assertEquals("server_evidence", response.answerSource());
        assertFalse(response.structured().path("evidence").path("validation").path("valid").asBoolean());
        assertEquals("PARAMETER_OUT_OF_RANGE",
                response.structured().path("evidence").path("validation").path("code").asText());
        assertTrue(response.answer().contains("参数校验未通过"));
        assertTrue(response.answer().contains("PARAMETER_OUT_OF_RANGE"));
        assertFalse(response.answer().contains("参数完全没问题"));
        assertFalse(response.answer().contains("batch_size=999"));
    }

    private AssistantToolRegistry registry() {
        return registry(validationData(true, 128));
    }

    private AssistantToolRegistry registry(JsonNode validation) {
        ObjectNode parameterData = objectMapper.createObjectNode();
        parameterData.putArray("algorithms").addObject()
                .put("algorithmId", 14).put("name", "LDD").put("type", "ldd");
        parameterData.putArray("datasets").addObject().put("datasetId", 4).put("name", "cifar10");
        ObjectNode taskData = objectMapper.createObjectNode().put("taskId", 500)
                .put("status", "pending").put("progress", 0);
        taskData.set("blockers", objectMapper.createArrayNode());
        taskData.set("warnings", objectMapper.createArrayNode());
        ObjectNode reportData = objectMapper.createObjectNode().put("taskId", 500)
                .put("status", "failed").put("cleanPurityPercentByIsolationRatio", 91.2)
                .put("selectedSampleCountByIsolationRatio", 420).put("failureSummary", "执行失败");
        reportData.put("cleanSubsetExportAvailable", true);
        List<AssistantTool> tools = new ArrayList<>();
        tools.add(tool("validate_ldd_parameters", false, new AssistantToolResult(
                validation.path("valid").asBoolean() ? "参数校验通过" : "参数校验未通过",
                validation, null, null, null, null, null)));
        tools.add(tool("get_parameter_guide", false, new AssistantToolResult(
                "参数目录", parameterData, null, null, null, null, null)));
        tools.add(tool("search_knowledge", false, new AssistantToolResult(
                "知识", objectMapper.createObjectNode(), null, null, null, null, null)));
        tools.add(tool("preview_create_task", true, new AssistantToolResult(
                "创建预览", objectMapper.createObjectNode(), "create", objectMapper.createObjectNode(),
                null, null, "ctx")));
        tools.add(tool("get_my_task_summary", false, new AssistantToolResult(
                "任务摘要", taskData, null, null, 500L, "run-500", null)));
        tools.add(tool("preview_start_task", true, new AssistantToolResult(
                "启动预览", objectMapper.createObjectNode(), "start", objectMapper.createObjectNode(),
                500L, "run-500", "ctx")));
        tools.add(tool("get_my_report_summary", false, new AssistantToolResult(
                "报告摘要", reportData, null, null, 500L, "run-500", null)));
        return new AssistantToolRegistry(tools);
    }

    private AssistantTool tool(String name, boolean write, AssistantToolResult result) {
        return new AssistantTool() {
            @Override
            public AssistantToolDescriptor descriptor() {
                return new AssistantToolDescriptor(name, "scenario probe", write, AssistantSchemas.object());
            }

            @Override
            public AssistantToolResult execute(JsonNode arguments) {
                return result;
            }
        };
    }

    private static final class RecordingModel implements AssistantModel {
        private final List<ModelReply> replies;
        private final AtomicReference<ModelRequest> lastRequest = new AtomicReference<>();
        private final List<ModelRequest> requests = new ArrayList<>();
        private int index;

        private RecordingModel(ModelReply... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public ModelReply respond(ModelRequest request) {
            requests.add(request);
            lastRequest.set(request);
            if (index >= replies.size()) {
                return ModelReply.answer("默认回答", "fake");
            }
            return replies.get(index++);
        }
    }
}
