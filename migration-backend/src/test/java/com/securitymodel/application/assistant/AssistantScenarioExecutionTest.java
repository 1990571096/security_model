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
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("get_parameter_guide",
                        objectMapper.createObjectNode().put("algorithmId", 14), "fake"),
                AssistantModel.ModelReply.answer("LDD 默认参数为 batch_size=128。", "fake"));
        AssistantChatService service = service(registry(), model, mock(AssistantPreviewService.class));

        AssistantChatResponse response = service.chat(null, "检查 LDD 参数", "ldd_parameter_validation");

        assertEquals("answered", response.status());
        assertEquals("ldd_parameter_validation", response.scenario());
        assertEquals(List.of("get_parameter_guide", "preview_create_task", "search_knowledge"),
                model.lastRequest.get().tools().stream().map(AssistantToolDescriptor::name).sorted().toList());
        assertNotNull(response.structured());
        assertEquals("ldd_parameter_validation", response.structured().path("scenario").asText());
        assertEquals("get_parameter_guide", response.structured().path("requiredEvidenceTool").asText());
        assertEquals(14, response.structured().path("evidence").path("findings").get(0)
                .path("algorithmId").asInt());
        assertFalse(response.structured().has("failure"));
    }

    @Test
    void resultExplanationCannotRequestAWritePreview() {
        RecordingModel model = new RecordingModel(
                AssistantModel.ModelReply.toolCall("preview_create_task",
                        objectMapper.createObjectNode().put("taskName", "伪造任务"), "fake"));
        AssistantPreviewService preview = mock(AssistantPreviewService.class);
        AssistantChatService service = service(registry(), model, preview);

        AssistantChatResponse response = service.chat(null, "解释任务结果", "result_explanation");

        assertEquals("tool_error", response.status());
        assertEquals("ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO", response.modelStatus());
        assertEquals("ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO",
                response.structured().path("failure").path("code").asText());
        assertTrue(response.pendingConfirmations().isEmpty());
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
        assertEquals(91.2, response.structured().path("evidence").path("metrics")
                .path("cleanPurityPercentByIsolationRatio").asDouble(), 0.001);
        assertEquals(420, response.structured().path("evidence").path("metrics")
                .path("selectedSampleCountByIsolationRatio").asInt());
        assertFalse(response.structured().path("evidence").path("metrics")
                .path("cleanPurityPercentByIsolationRatio").asText().contains("99.9"));
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

    private AssistantToolRegistry registry() {
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
