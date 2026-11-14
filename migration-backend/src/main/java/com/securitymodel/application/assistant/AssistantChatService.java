package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.domain.assistant.AssistantSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Bounded tool loop. Limits are enforced by the server, not by the model: at most six
 * model calls, five allow-listed tool calls and sixty seconds wall clock. A write tool only
 * returns a preview; confirmation is a separate authenticated endpoint.
 */
@Service
public class AssistantChatService {

    private final AssistantModel model;
    private final AssistantToolRegistry registry;
    private final AssistantPreviewService previewService;
    private final AssistantAuditService auditService;
    private final AssistantQuotaService quotaService;
    private final AssistantSessionService sessionService;
    private final CurrentUserService currentUserService;
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long timeoutMillis;

    public AssistantChatService(
            AssistantModel model,
            AssistantToolRegistry registry,
            AssistantPreviewService previewService,
            AssistantAuditService auditService,
            AssistantQuotaService quotaService,
            AssistantSessionService sessionService,
            CurrentUserService currentUserService,
            @Value("${security-model.assistant.max-model-calls:6}") int maxModelCalls,
            @Value("${security-model.assistant.max-tool-calls:5}") int maxToolCalls,
            @Value("${security-model.assistant.timeout-seconds:60}") long timeoutSeconds) {
        this.model = model;
        this.registry = registry;
        this.previewService = previewService;
        this.auditService = auditService;
        this.quotaService = quotaService;
        this.sessionService = sessionService;
        this.currentUserService = currentUserService;
        this.maxModelCalls = Math.max(1, Math.min(12, maxModelCalls));
        this.maxToolCalls = Math.max(1, Math.min(10, maxToolCalls));
        this.timeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(5, Math.min(180, timeoutSeconds)));
    }

    public AssistantChatResponse chat(Long sessionId, String question) {
        Long userId = currentUserService.requireStableIdentity().userId();
        if (question == null || question.isBlank()) {
            throw new AssistantException("ASSISTANT_QUESTION_REQUIRED", HttpStatus.BAD_REQUEST,
                    "问题不能为空");
        }
        if (question.length() > 500) {
            throw new AssistantException("ASSISTANT_QUESTION_TOO_LONG", HttpStatus.BAD_REQUEST,
                    "问题最多 500 字");
        }
        quotaService.acquire();
        AssistantSession session = sessionService.getOrCreate(sessionId, userId, question);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        String safeQuestion = KnowledgeSanitizer.forModel(question, 500);
        List<AssistantModel.ModelMessage> messages = new ArrayList<>();
        messages.add(new AssistantModel.ModelMessage("user", safeQuestion));
        List<AssistantChatResponse.AssistantToolTrace> traces = new ArrayList<>();
        List<AssistantChatResponse.AssistantPendingConfirmation> pending = new ArrayList<>();
        Set<JsonNode> citations = new LinkedHashSet<>();
        int modelCalls = 0;
        int toolCalls = 0;

        while (true) {
            if (System.nanoTime() > deadline) {
                return finish(session, question, "timeout",
                        "助手请求超时，未完成的工具调用不会写入业务数据。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            }
            if (modelCalls >= maxModelCalls) {
                return finish(session, question, "limit_reached",
                        "模型调用次数已达上限，未完成的写操作不会执行。", traces, pending,
                        citations, "max_model_calls", modelCalls, toolCalls);
            }
            AssistantModel.ModelReply reply = model.respond(new AssistantModel.ModelRequest(
                    safeQuestion, List.copyOf(messages), registry.descriptors(),
                    Math.max(0, maxToolCalls - toolCalls)));
            modelCalls++;
            if (!"ok".equals(reply.status())) {
                return finish(session, question, reply.status(), reply.message(), traces, pending,
                        citations, reply.status(), modelCalls, toolCalls);
            }
            if (reply.toolCall() != null) {
                if (toolCalls >= maxToolCalls) {
                    return finish(session, question, "limit_reached",
                            "工具调用次数已达上限，未完成的写操作不会执行。", traces, pending,
                            citations, "max_tool_calls", modelCalls, toolCalls);
                }
                toolCalls++;
                ToolExecution execution;
                try {
                    execution = executeTool(session, reply.toolCall(), trace -> {
                        if (trace.pending() != null) {
                            pending.add(trace.pending());
                        }
                        if (trace.citations() != null) {
                            citations.addAll(trace.citations());
                        }
                    });
                    traces.add(execution.trace());
                } catch (AssistantException exception) {
                    return finish(session, question, "tool_error", exception.getMessage(), traces, pending,
                            citations, exception.getCode(), modelCalls, toolCalls);
                }
                messages.add(new AssistantModel.ModelMessage("assistant",
                        "tool " + reply.toolCall().toolName() + " -> " + execution.trace().summary()));
                messages.add(new AssistantModel.ModelMessage("tool", execution.trace().summary()));
                continue;
            }
            if (reply.answer() == null || reply.answer().isBlank()) {
                return finish(session, question, "model_invalid",
                        "模型没有返回可校验的回答。", traces, pending, citations,
                        "empty_answer", modelCalls, toolCalls);
            }
            return finish(session, question, "answered", reply.answer(), traces, pending,
                    citations, reply.model() == null ? "model" : reply.model(), modelCalls, toolCalls);
        }
    }

    private ToolExecution executeTool(AssistantSession session, AssistantModel.ToolCall call,
                                      java.util.function.Consumer<ToolExecution> observer) {
        AssistantTool tool = registry.find(call.toolName());
        long started = System.nanoTime();
        try {
            if (tool.descriptor().write()) {
                AssistantPreviewResponse preview = previewService.preview(
                        call.toolName(), call.arguments(), session.getSessionId());
                AssistantChatResponse.AssistantToolTrace trace = new AssistantChatResponse.AssistantToolTrace(
                        call.toolName(), preview.preview().path("warning").asText(
                        "已生成预览，必须由用户单独确认后才会执行。"),
                        preview.preview(), elapsedMs(started));
                AssistantChatResponse.AssistantPendingConfirmation confirmation =
                        new AssistantChatResponse.AssistantPendingConfirmation(
                                preview.confirmationId(), preview.token(), preview.expiresAt(),
                                preview.operation(), preview.preview());
                ToolExecution execution = new ToolExecution(trace, confirmation, null);
                observer.accept(execution);
                return execution;
            }
            AssistantToolResult result = tool.execute(call.arguments());
            auditService.record(session.getSessionId(), call.toolName(), result.operation(),
                    result.targetTaskId(), result.targetRunId(), "OK", elapsedMs(started));
            ToolExecution execution = new ToolExecution(new AssistantChatResponse.AssistantToolTrace(
                    call.toolName(), result.summary(), result.data(), elapsedMs(started)),
                    null, citations(result.data()));
            observer.accept(execution);
            return execution;
        } catch (AssistantException | org.springframework.security.access.AccessDeniedException exception) {
            auditService.record(session.getSessionId(), call.toolName(), null, null, null,
                    "FAILED", elapsedMs(started));
            throw exception;
        } catch (RuntimeException exception) {
            auditService.record(session.getSessionId(), call.toolName(), null, null, null,
                    "ERROR", elapsedMs(started));
            throw new AssistantException("ASSISTANT_TOOL_FAILED", HttpStatus.BAD_GATEWAY,
                    "工具执行失败，未写入业务数据");
        }
    }

    private List<JsonNode> citations(JsonNode data) {
        List<JsonNode> result = new ArrayList<>();
        if (data != null && data.has("citations") && data.get("citations").isArray()) {
            data.get("citations").forEach(result::add);
        }
        return result;
    }

    private AssistantChatResponse finish(AssistantSession session, String question, String status,
                                         String answer,
                                         List<AssistantChatResponse.AssistantToolTrace> traces,
                                         List<AssistantChatResponse.AssistantPendingConfirmation> pending,
                                         Set<JsonNode> citations, String modelStatus,
                                         int modelCalls, int toolCalls) {
        List<String> toolNames = traces.stream().map(AssistantChatResponse.AssistantToolTrace::toolName).toList();
        List<Long> confirmationIds = pending.stream()
                .map(AssistantChatResponse.AssistantPendingConfirmation::confirmationId).toList();
        List<String> citationIds = new ArrayList<>();
        for (JsonNode citation : citations) {
            if (citation.has("citationId")) {
                citationIds.add(citation.path("citationId").asText());
            }
        }
        sessionService.appendTurn(session, question, status, answer, toolNames, confirmationIds, citationIds);
        return new AssistantChatResponse(session.getSessionId(), status, answer, traces, pending,
                new ArrayList<>(citations), modelStatus, answer, modelCalls, toolCalls,
                quotaService.remaining());
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record ToolExecution(AssistantChatResponse.AssistantToolTrace trace,
                                 AssistantChatResponse.AssistantPendingConfirmation pending,
                                 List<JsonNode> citations) {
    }
}
