package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantSession;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantChatServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesAllowListedReadToolAndReturnsAnswer() {
        StubTool read = new StubTool("get_my_task_summary", false,
                new AssistantToolResult("任务 #1 pending", objectMapper.createObjectNode()
                        .put("taskId", 1), null, null, 1L, null, null));
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(read));
        AssistantModel model = sequence(
                AssistantModel.ModelReply.toolCall("get_my_task_summary",
                        objectMapper.createObjectNode().put("taskId", 1), "fake"),
                AssistantModel.ModelReply.answer("任务是 pending。", "fake"));
        AssistantChatService service = service(registry, model, null);

        AssistantChatResponse response = service.chat(null, "我的任务怎么样");

        assertEquals("answered", response.status());
        assertEquals(1, response.toolCalls());
        assertEquals(2, response.modelCalls());
        assertEquals(1, response.toolResults().size());
    }

    @Test
    void writeToolOnlyReturnsPendingConfirmation() {
        StubTool write = new StubTool("preview_start_task", true,
                new AssistantToolResult("preview", objectMapper.createObjectNode(), "start",
                        objectMapper.createObjectNode().put("taskId", 1), 1L, null, "ctx"));
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(write));
        AssistantModel model = sequence(
                AssistantModel.ModelReply.toolCall("preview_start_task",
                        objectMapper.createObjectNode().put("taskId", 1), "fake"),
                AssistantModel.ModelReply.answer("请确认后执行。", "fake"));

        AssistantPreviewService previewService = mock(AssistantPreviewService.class);
        when(previewService.preview(any(), any(), anyLong())).thenReturn(
                new AssistantPreviewResponse(9L, "raw-token", LocalDateTime.now().plusMinutes(5),
                        "start", objectMapper.createObjectNode().put("operation", "start")));
        AssistantChatService service = service(registry, model, previewService);

        AssistantChatResponse response = service.chat(null, "启动任务");

        assertEquals("answered", response.status());
        assertEquals(1, response.pendingConfirmations().size());
        assertEquals("raw-token", response.pendingConfirmations().get(0).token());
        assertEquals(9L, response.pendingConfirmations().get(0).confirmationId());
        // The write tool's execute() was never called; only preview was generated.
        assertEquals(0, write.executions.get());
    }

    @Test
    void unknownToolIsRejectedWithoutExecution() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(
                new StubTool("get_my_task_summary", false,
                        new AssistantToolResult("ok", objectMapper.createObjectNode(), null, null, null, null, null))));
        AssistantModel model = sequence(
                AssistantModel.ModelReply.toolCall("shell", objectMapper.createObjectNode(), "fake"),
                AssistantModel.ModelReply.answer("不会执行", "fake"));

        AssistantChatResponse response = service(registry, model, null).chat(null, "执行 shell");

        assertEquals("tool_error", response.status());
        assertEquals(0, response.toolResults().size());
    }

    @Test
    void modelDisabledReturnsExplicitFailureWithoutTools() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(
                new StubTool("get_my_task_summary", false,
                        new AssistantToolResult("ok", objectMapper.createObjectNode(), null, null, null, null, null))));
        AssistantModel model = request -> AssistantModel.ModelReply.unavailable("model_disabled", "未配置");

        AssistantChatResponse response = service(registry, model, null).chat(null, "你好");

        assertEquals("model_disabled", response.status());
        assertEquals(0, response.toolCalls());
        assertTrue(response.toolResults().isEmpty());
    }

    @Test
    void modelCannotExceedFiveToolCalls() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(
                new StubTool("get_my_task_summary", false,
                        new AssistantToolResult("ok", objectMapper.createObjectNode(), null, null, null, null, null))));
        AssistantModel model = request -> AssistantModel.ModelReply.toolCall(
                "get_my_task_summary", objectMapper.createObjectNode().put("taskId", 1), "fake");

        AssistantChatResponse response = service(registry, model, null).chat(null, "循环");

        assertEquals("limit_reached", response.status());
        assertEquals(5, response.toolCalls());
        assertEquals(6, response.modelCalls());
    }

    private AssistantChatService service(AssistantToolRegistry registry, AssistantModel model,
                                         AssistantPreviewService previewService) {
        AssistantQuotaService quota = mock(AssistantQuotaService.class);
        when(quota.remaining()).thenReturn(50);
        AssistantSessionService sessions = mock(AssistantSessionService.class);
        AssistantSession session = new AssistantSession();
        session.setSessionId(7L);
        when(sessions.getOrCreate(any(), anyLong(), any())).thenReturn(session);
        AssistantPreviewService preview = previewService == null
                ? mock(AssistantPreviewService.class) : previewService;
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireStableIdentity()).thenReturn(new AuthenticatedUser(2L, "alice", false));
        return new AssistantChatService(model, registry, preview,
                mock(AssistantAuditService.class), quota, sessions, currentUser, 6, 5, 60);
    }

    private AssistantModel sequence(AssistantModel.ModelReply... replies) {
        AtomicInteger index = new AtomicInteger();
        return request -> replies[Math.min(index.getAndIncrement(), replies.length - 1)];
    }

    private static class StubTool implements AssistantTool {
        private final String name;
        private final boolean write;
        private final AssistantToolResult result;
        private final AtomicInteger executions = new AtomicInteger();

        StubTool(String name, boolean write, AssistantToolResult result) {
            this.name = name;
            this.write = write;
            this.result = result;
        }

        @Override
        public AssistantToolDescriptor descriptor() {
            return new AssistantToolDescriptor(name, name, write, AssistantSchemas.object());
        }

        @Override
        public AssistantToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            executions.incrementAndGet();
            return result;
        }
    }
}
