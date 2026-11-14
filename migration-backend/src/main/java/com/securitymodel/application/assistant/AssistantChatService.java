package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.domain.assistant.AssistantSession;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final ObjectMapper objectMapper;
    private final ExecutorService modelExecutor;
    private final Semaphore requestSlots = new Semaphore(4, true);
    private final Map<Long, Semaphore> userSlots = new ConcurrentHashMap<>();
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long timeoutMillis;
    private final int maxConcurrentPerUser;

    public AssistantChatService(
            AssistantModel model,
            AssistantToolRegistry registry,
            AssistantPreviewService previewService,
            AssistantAuditService auditService,
            AssistantQuotaService quotaService,
            AssistantSessionService sessionService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            @Value("${security-model.assistant.max-model-calls:6}") int maxModelCalls,
            @Value("${security-model.assistant.max-tool-calls:5}") int maxToolCalls,
            @Value("${security-model.assistant.timeout-seconds:60}") long timeoutSeconds,
            @Value("${security-model.assistant.max-concurrent-per-user:1}") int maxConcurrentPerUser) {
        this.model = model;
        this.registry = registry;
        this.previewService = previewService;
        this.auditService = auditService;
        this.quotaService = quotaService;
        this.sessionService = sessionService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
        this.modelExecutor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "assistant-model");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.maxModelCalls = Math.max(1, Math.min(12, maxModelCalls));
        this.maxToolCalls = Math.max(1, Math.min(10, maxToolCalls));
        this.timeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(5, Math.min(180, timeoutSeconds)));
        this.maxConcurrentPerUser = Math.max(1, Math.min(4, maxConcurrentPerUser));
    }

    public AssistantChatResponse chat(Long sessionId, String question) {
        if (!requestSlots.tryAcquire()) {
            throw new AssistantException("ASSISTANT_BUSY", HttpStatus.TOO_MANY_REQUESTS,
                    "助手当前请求过多，请稍后重试；普通任务接口不受影响。");
        }
        Long userId;
        try {
            userId = currentUserService.requireStableIdentity().userId();
        } catch (RuntimeException exception) {
            requestSlots.release();
            throw exception;
        }
        Semaphore userSlot = userSlots.computeIfAbsent(userId,
                ignored -> new Semaphore(maxConcurrentPerUser, true));
        if (!userSlot.tryAcquire()) {
            requestSlots.release();
            throw new AssistantException("ASSISTANT_BUSY", HttpStatus.TOO_MANY_REQUESTS,
                    "同一用户的助手请求过多，请等待当前回答完成；普通任务接口不受影响。");
        }
        try {
            return chatWithBudget(sessionId, question);
        } finally {
            userSlot.release();
            requestSlots.release();
        }
    }

    private AssistantChatResponse chatWithBudget(Long sessionId, String question) {
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
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return finish(session, question, "timeout",
                        "助手请求超时，未完成的工具调用不会写入业务数据。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            }
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            AssistantModel.ModelRequest request = new AssistantModel.ModelRequest(
                    safeQuestion, List.copyOf(messages), registry.descriptors(),
                    Math.max(0, maxToolCalls - toolCalls), remainingMillis);
            AssistantModel.ModelReply reply;
            Future<AssistantModel.ModelReply> future;
            try {
                future = modelExecutor.submit(() -> model.respond(request));
            } catch (RejectedExecutionException exception) {
                return finish(session, question, "busy",
                        "助手模型队列已满，请稍后重试；未执行任何工具。", traces, pending,
                        citations, "busy", modelCalls, toolCalls);
            }
            try {
                reply = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                return finish(session, question, "timeout",
                        "模型调用超过剩余时限，未完成的工具调用不会写入业务数据。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return finish(session, question, "timeout",
                        "助手请求被中断，未完成的工具调用不会写入业务数据。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            } catch (ExecutionException exception) {
                // Provider exceptions can contain request bodies, endpoints and credentials.
                // Never copy them into the user-visible response.
                return finish(session, question, "model_unavailable",
                        "模型调用失败，未执行工具；详情仅保留在服务端日志。",
                        traces, pending, citations, "model_unavailable", modelCalls, toolCalls);
            }
            modelCalls++;
            if (System.nanoTime() > deadline) {
                return finish(session, question, "timeout",
                        "模型返回时已超过总时限，未处理的工具调用不会执行。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            }
            if (!"ok".equals(reply.status())) {
                String safeStatus = "model_disabled".equals(reply.status())
                        ? "model_disabled" : "model_unavailable";
                return finish(session, question, safeStatus,
                        "模型暂不可用，未执行工具；详情仅保留在服务端日志。", traces, pending,
                        citations, reply.status(), modelCalls, toolCalls);
            }
            if (reply.toolCall() != null) {
                if (System.nanoTime() > deadline) {
                    return finish(session, question, "timeout",
                            "工具调用前已超过总时限，未执行该工具。", traces, pending,
                            citations, "timeout", modelCalls, toolCalls);
                }
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
                    if (System.nanoTime() > deadline) {
                        return finish(session, question, "timeout",
                                "工具已执行，但已超过总时限；后续工具调用不会执行。", traces, pending,
                                citations, "timeout", modelCalls, toolCalls);
                    }
                } catch (AssistantException exception) {
                    return finish(session, question, "tool_error", exception.getMessage(), traces, pending,
                            citations, exception.getCode(), modelCalls, toolCalls);
                }
                messages.add(new AssistantModel.ModelMessage("assistant",
                        "tool " + reply.toolCall().toolName() + " 已执行"));
                messages.add(new AssistantModel.ModelMessage("tool",
                        AssistantModelEvidence.forTool(objectMapper, reply.toolCall().toolName(),
                                execution.trace().summary(), execution.trace().data())));
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

    @PreDestroy
    void shutdownModelExecutor() {
        modelExecutor.shutdownNow();
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record ToolExecution(AssistantChatResponse.AssistantToolTrace trace,
                                 AssistantChatResponse.AssistantPendingConfirmation pending,
                                 List<JsonNode> citations) {
    }
}
