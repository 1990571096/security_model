package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.domain.assistant.AssistantSession;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
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
    private final AssistantScenarioCatalog scenarioCatalog;
    private final AssistantPreviewService previewService;
    private final AssistantAuditService auditService;
    private final AssistantQuotaService quotaService;
    private final AssistantSessionService sessionService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;
    private final ExecutorService modelExecutor;
    private final ExecutorService toolExecutor;
    private final Semaphore requestSlots = new Semaphore(4, true);
    private final Map<Long, Semaphore> userSlots = new ConcurrentHashMap<>();
    private final int maxModelCalls;
    private final int maxToolCalls;
    private final long timeoutMillis;
    private final int maxConcurrentPerUser;

    @Autowired
    public AssistantChatService(
            AssistantModel model,
            AssistantToolRegistry registry,
            AssistantScenarioCatalog scenarioCatalog,
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
        this.scenarioCatalog = scenarioCatalog;
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
        this.toolExecutor = new DelegatingSecurityContextExecutorService(
                new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(16), runnable -> {
                            Thread thread = new Thread(runnable, "assistant-tool");
                            thread.setDaemon(true);
                            return thread;
                        }, new ThreadPoolExecutor.AbortPolicy()));
        this.maxModelCalls = Math.max(1, Math.min(12, maxModelCalls));
        this.maxToolCalls = Math.max(1, Math.min(10, maxToolCalls));
        this.timeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(5, Math.min(180, timeoutSeconds)));
        this.maxConcurrentPerUser = Math.max(1, Math.min(4, maxConcurrentPerUser));
    }

    /** Backward-compatible constructor for unit tests that do not wire the catalog bean. */
    public AssistantChatService(
            AssistantModel model,
            AssistantToolRegistry registry,
            AssistantPreviewService previewService,
            AssistantAuditService auditService,
            AssistantQuotaService quotaService,
            AssistantSessionService sessionService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper,
            int maxModelCalls,
            int maxToolCalls,
            long timeoutSeconds,
            int maxConcurrentPerUser) {
        this(model, registry, new AssistantScenarioCatalog(registry, false), previewService, auditService,
                quotaService, sessionService, currentUserService, objectMapper, maxModelCalls,
                maxToolCalls, timeoutSeconds, maxConcurrentPerUser);
    }

    public AssistantChatResponse chat(Long sessionId, String question) {
        return chat(sessionId, question, null);
    }

    public AssistantChatResponse chat(Long sessionId, String question, String scenarioName) {
        ScenarioScope scope = resolveScope(scenarioName);
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
            return chatWithBudget(sessionId, question, scope);
        } finally {
            userSlot.release();
            requestSlots.release();
        }
    }

    private ScenarioScope resolveScope(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return null;
        }
        AssistantScenarioCatalog.Scenario scenario = scenarioCatalog.find(scenarioName.trim());
        if (scenario == null) {
            throw new AssistantException("ASSISTANT_SCENARIO_UNKNOWN", HttpStatus.BAD_REQUEST,
                    "未知助手场景");
        }
        return new ScenarioScope(scenario.name(), Set.copyOf(scenario.tools()),
                scenario.requiredEvidenceTool());
    }

    private AssistantChatResponse chatWithBudget(Long sessionId, String question, ScenarioScope scope) {
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
                return finish(session, question, scope, "timeout",
                        "助手请求超时，后续工具与模型调用不会继续；底层操作可能仍在收尾。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            }
            if (modelCalls >= maxModelCalls) {
                return finish(session, question, scope, "limit_reached",
                        "模型调用次数已达上限，未完成的写操作不会执行。", traces, pending,
                        citations, "max_model_calls", modelCalls, toolCalls);
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return finish(session, question, scope, "timeout",
                        "助手请求超时，后续工具与模型调用不会继续；底层操作可能仍在收尾。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            }
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            List<AssistantToolDescriptor> descriptors = registry.descriptors().stream()
                    .filter(descriptor -> scope == null || scope.tools().contains(descriptor.name()))
                    .toList();
            AssistantModel.ModelRequest request = new AssistantModel.ModelRequest(
                    safeQuestion, List.copyOf(messages), descriptors,
                    Math.max(0, maxToolCalls - toolCalls), remainingMillis);
            AssistantModel.ModelReply reply;
            Future<AssistantModel.ModelReply> future;
            try {
                future = modelExecutor.submit(() -> model.respond(request));
            } catch (RejectedExecutionException exception) {
                return finish(session, question, scope, "busy",
                        "助手模型队列已满，请稍后重试；未执行任何工具。", traces, pending,
                        citations, "busy", modelCalls, toolCalls);
            }
            try {
                reply = future.get(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                return finish(session, question, scope, "timeout",
                        "模型调用超过剩余时限，后续工具与模型调用不会继续；底层操作可能仍在收尾。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            } catch (InterruptedException exception) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                return finish(session, question, scope, "timeout",
                        "助手请求被中断，后续工具与模型调用不会继续；底层操作可能仍在收尾。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            } catch (ExecutionException exception) {
                // Provider exceptions can contain request bodies, endpoints and credentials.
                // Never copy them into the user-visible response.
                return finish(session, question, scope, "model_unavailable",
                        "模型调用失败，未执行工具；详情仅保留在服务端日志。",
                        traces, pending, citations, "model_unavailable", modelCalls, toolCalls);
            }
            modelCalls++;
            if (System.nanoTime() > deadline) {
                return finish(session, question, scope, "timeout",
                        "模型返回时已超过总时限，未处理的工具调用不会执行。", traces, pending,
                        citations, "timeout", modelCalls, toolCalls);
            }
            if (!"ok".equals(reply.status())) {
                String safeStatus = "model_disabled".equals(reply.status())
                        ? "model_disabled" : "model_unavailable";
                return finish(session, question, scope, safeStatus,
                        "模型暂不可用，未执行工具；详情仅保留在服务端日志。", traces, pending,
                        citations, reply.status(), modelCalls, toolCalls);
            }
            if (reply.toolCall() != null) {
                if (System.nanoTime() > deadline) {
                    return finish(session, question, scope, "timeout",
                            "工具调用前已超过总时限，未执行该工具。", traces, pending,
                            citations, "timeout", modelCalls, toolCalls);
                }
                if (toolCalls >= maxToolCalls) {
                    return finish(session, question, scope, "limit_reached",
                            "工具调用次数已达上限，未完成的写操作不会执行。", traces, pending,
                            citations, "max_tool_calls", modelCalls, toolCalls);
                }
                toolCalls++;
                ToolExecution execution;
                long toolBudgetMillis = Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                try {
                    execution = executeTool(session, reply.toolCall(), toolBudgetMillis, scope, trace -> {
                        if (trace.pending() != null) {
                            pending.add(trace.pending());
                        }
                        if (trace.citations() != null) {
                            citations.addAll(trace.citations());
                        }
                    });
                    traces.add(execution.trace());
                    if (System.nanoTime() > deadline) {
                        return finish(session, question, scope, "timeout",
                                "工具已执行，但已超过总时限；后续工具调用不会执行。", traces, pending,
                                citations, "timeout", modelCalls, toolCalls);
                    }
                } catch (AssistantException exception) {
                    if ("ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO".equals(exception.getCode())) {
                        // Scenario rejection is a request-level policy denial, not an
                        // ordinary tool failure: expose the real HTTP 403 instead of
                        // disguising it as a 200 tool_error body.
                        throw exception;
                    }
                    if ("ASSISTANT_TOOL_TIMEOUT".equals(exception.getCode())) {
                        return finish(session, question, scope, "timeout",
                                "工具执行超过剩余时限，后续工具与模型调用不会继续；底层操作可能仍在收尾。", traces, pending,
                                citations, "tool_timeout", modelCalls, toolCalls);
                    }
                    return finish(session, question, scope, "tool_error", exception.getMessage(), traces, pending,
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
                return finish(session, question, scope, "model_invalid",
                        "模型没有返回可校验的回答。", traces, pending, citations,
                        "empty_answer", modelCalls, toolCalls);
            }
            return finish(session, question, scope, "answered", reply.answer(), traces, pending,
                    citations, reply.model() == null ? "model" : reply.model(), modelCalls, toolCalls);
        }
    }

    private ToolExecution executeTool(AssistantSession session, AssistantModel.ToolCall call,
                                      long timeoutMillis, ScenarioScope scope,
                                      java.util.function.Consumer<ToolExecution> observer) {
        if (scope != null && !scope.tools().contains(call.toolName())) {
            auditService.record(session.getSessionId(), call.toolName(), null, null, null,
                    "SCENARIO_DENIED", 0);
            throw new AssistantException("ASSISTANT_TOOL_NOT_ALLOWED_FOR_SCENARIO",
                    HttpStatus.FORBIDDEN, "当前场景不允许调用该工具");
        }
        AssistantTool tool = registry.find(call.toolName());
        long started = System.nanoTime();
        try {
            if (tool.descriptor().write()) {
                AssistantPreviewResponse preview = executeWithBudget(
                        () -> previewService.preview(
                                call.toolName(), call.arguments(), session.getSessionId(), timeoutMillis),
                        timeoutMillis);
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
            AssistantToolResult result = executeReadWithBudget(tool, call.arguments(), timeoutMillis);
            if (elapsedMs(started) > timeoutMillis) {
                throw timeout();
            }
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

    private AssistantToolResult executeReadWithBudget(AssistantTool tool,
                                                      com.fasterxml.jackson.databind.JsonNode arguments,
                                                      long timeoutMillis) {
        return executeWithBudget(() -> tool.execute(arguments, timeoutMillis), timeoutMillis);
    }

    private <T> T executeWithBudget(java.util.concurrent.Callable<T> call, long timeoutMillis) {
        Future<T> future;
        try {
            future = toolExecutor.submit(call);
        } catch (RejectedExecutionException exception) {
            throw new AssistantException("ASSISTANT_TOOL_BUSY", HttpStatus.SERVICE_UNAVAILABLE,
                    "工具执行队列已满，请稍后重试");
        }
        try {
            return future.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw timeout();
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw timeout();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof AssistantException assistantException) {
                throw assistantException;
            }
            if (cause instanceof org.springframework.security.access.AccessDeniedException denied) {
                throw denied;
            }
            throw new AssistantException("ASSISTANT_TOOL_FAILED", HttpStatus.BAD_GATEWAY,
                    "工具执行失败，未写入业务数据");
        }
    }

    private AssistantException timeout() {
        return new AssistantException("ASSISTANT_TOOL_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT,
                "工具执行超过剩余时限");
    }

    private List<JsonNode> citations(JsonNode data) {
        List<JsonNode> result = new ArrayList<>();
        if (data != null && data.has("citations") && data.get("citations").isArray()) {
            data.get("citations").forEach(result::add);
        }
        return result;
    }

    private AssistantChatResponse finish(AssistantSession session, String question, ScenarioScope scope,
                                         String status, String answer,
                                         List<AssistantChatResponse.AssistantToolTrace> traces,
                                         List<AssistantChatResponse.AssistantPendingConfirmation> pending,
                                         Set<JsonNode> citations, String modelStatus,
                                         int modelCalls, int toolCalls) {
        String effectiveStatus = status;
        String effectiveAnswer = answer;
        String effectiveModelStatus = modelStatus;
        if (scope != null && "answered".equals(status)
                && traces.stream().noneMatch(trace -> scope.requiredEvidenceTool().equals(trace.toolName()))) {
            effectiveStatus = "scenario_incomplete";
            effectiveAnswer = "未取得场景必要证据（" + scope.requiredEvidenceTool()
                    + "），不能给出结论；未执行任何写操作。";
            effectiveModelStatus = "scenario_required_tool_missing";
        }
        List<String> toolNames = traces.stream().map(AssistantChatResponse.AssistantToolTrace::toolName).toList();
        List<Long> confirmationIds = pending.stream()
                .map(AssistantChatResponse.AssistantPendingConfirmation::confirmationId).toList();
        List<String> citationIds = new ArrayList<>();
        for (JsonNode citation : citations) {
            if (citation.has("citationId")) {
                citationIds.add(citation.path("citationId").asText());
            }
        }
        JsonNode structured = scope == null ? null : buildStructured(scope, effectiveStatus, effectiveAnswer,
                effectiveModelStatus, traces, pending, citations);
        String answerSource = scope == null ? "model" : "server_fallback";
        if (scope != null && "answered".equals(effectiveStatus)) {
            // Scenario answers are assembled from server evidence. The model's free-form
            // prose is never used as the authoritative answer, so a fabricated number or
            // [citation:99] cannot reach the user-facing answer or the saved turn.
            effectiveAnswer = buildScenarioAnswer(scope.name(), structured);
            answerSource = "server_evidence";
        }
        sessionService.appendTurn(session, question, effectiveStatus, effectiveAnswer, toolNames,
                confirmationIds, citationIds);
        return new AssistantChatResponse(session.getSessionId(), effectiveStatus, effectiveAnswer, answerSource,
                traces, pending, new ArrayList<>(citations), effectiveModelStatus, effectiveAnswer, modelCalls,
                toolCalls, quotaService.remaining(), scope == null ? null : scope.name(), structured);
    }

    private JsonNode buildStructured(ScenarioScope scope, String status, String answer, String modelStatus,
                                     List<AssistantChatResponse.AssistantToolTrace> traces,
                                     List<AssistantChatResponse.AssistantPendingConfirmation> pending,
                                     Set<JsonNode> citations) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("scenario", scope.name());
        root.put("status", status);
        root.put("requiredEvidenceTool", scope.requiredEvidenceTool());
        root.set("toolNames", objectMapper.valueToTree(
                traces.stream().map(AssistantChatResponse.AssistantToolTrace::toolName).toList()));
        root.put("pendingConfirmation", !pending.isEmpty());
        root.set("confirmationIds", objectMapper.valueToTree(
                pending.stream().map(AssistantChatResponse.AssistantPendingConfirmation::confirmationId).toList()));
        root.set("citations", objectMapper.valueToTree(new ArrayList<>(citations)));
        ObjectNode evidence = root.putObject("evidence");
        for (AssistantChatResponse.AssistantToolTrace trace : traces) {
            JsonNode data = trace.data() == null ? objectMapper.createObjectNode() : trace.data();
            switch (trace.toolName()) {
                case "get_parameter_guide" -> {
                    evidence.set("catalog", data);
                    ArrayNode findings = evidence.putArray("findings");
                    for (JsonNode algorithm : data.path("algorithms")) {
                        findings.addObject().put("algorithmId", algorithm.path("algorithmId").asText())
                                .put("name", algorithm.path("name").asText())
                                .put("type", algorithm.path("type").asText());
                    }
                }
                case "get_my_task_summary" -> {
                    evidence.set("task", data);
                    evidence.set("blockers", data.path("blockers").deepCopy());
                    evidence.set("warnings", data.path("warnings").deepCopy());
                }
                case "validate_ldd_parameters" -> {
                    evidence.set("validation", data.deepCopy());
                    if (data.has("normalizedParameters")) {
                        evidence.set("finalParameters", data.get("normalizedParameters").deepCopy());
                    }
                    if (data.has("configFingerprint")) {
                        evidence.put("configFingerprint", data.path("configFingerprint").asText());
                    }
                }
                case "get_my_report_summary" -> {
                    evidence.put("taskId", data.path("taskId").asText());
                    evidence.put("taskStatus", data.path("status").asText());
                    ObjectNode metrics = evidence.putObject("metrics");
                    if (data.has("cleanPurityPercentByIsolationRatio")) {
                        metrics.set("cleanPurityPercentByIsolationRatio",
                                data.get("cleanPurityPercentByIsolationRatio").deepCopy());
                    }
                    if (data.has("selectedSampleCountByIsolationRatio")) {
                        metrics.set("selectedSampleCountByIsolationRatio",
                                data.get("selectedSampleCountByIsolationRatio").deepCopy());
                    }
                    if (metrics.isEmpty()) {
                        metrics.put("state", "unavailable");
                    }
                    evidence.put("exportAvailability", data.path("cleanSubsetExportAvailable").asBoolean(false));
                    if (data.has("failureSummary")) {
                        evidence.put("failureSummary", data.path("failureSummary").asText());
                    }
                }
                default -> {
                    // search_knowledge contributes citations only; its raw text is not copied.
                }
            }
        }
        for (AssistantChatResponse.AssistantPendingConfirmation confirmation : pending) {
            JsonNode preview = confirmation.preview();
            if (preview.has("finalParameters")) {
                evidence.set("finalParameters", preview.get("finalParameters").deepCopy());
            }
            if (preview.has("configFingerprint")) {
                evidence.put("configFingerprint", preview.path("configFingerprint").asText());
            }
            if (preview.has("blockers")) {
                evidence.set("blockers", preview.get("blockers").deepCopy());
            }
        }
        if (!"answered".equals(status)) {
            ObjectNode failure = root.putObject("failure");
            failure.put("code", modelStatus == null ? status : modelStatus);
            failure.put("message", answer == null ? "场景未完成" : KnowledgeSanitizer.forModel(answer, 300));
        }
        return root;
    }

    /**
     * Builds the only user-visible prose for a scenario. Every number, status, validation
     * conclusion and citation below comes from {@code structured}, which was assembled from
     * tool results. Model prose is deliberately not concatenated or paraphrased here.
     */
    private String buildScenarioAnswer(String scenarioName, JsonNode structured) {
        JsonNode evidence = structured.path("evidence");
        String body = switch (scenarioName) {
            case "ldd_parameter_validation" -> lddAnswer(evidence);
            case "task_preflight" -> taskAnswer(evidence);
            case "result_explanation" -> reportAnswer(evidence);
            default -> "场景结果已由服务端证据生成。";
        };
        String citations = citationAnswer(structured.path("citations"));
        String source = "本回答由服务端工具证据组装，未采用未经校验的模型正文。";
        return source + "\n" + body + (citations.isEmpty() ? "" : "\n引用：" + citations);
    }

    private String lddAnswer(JsonNode evidence) {
        JsonNode validation = evidence.path("validation");
        if (!validation.isObject()) {
            return "未取得参数校验证据，不能判断参数是否可执行。";
        }
        if (!validation.path("valid").asBoolean(false)) {
            return "参数校验未通过（" + validation.path("code").asText("PARAMETER_INVALID") + "）："
                    + KnowledgeSanitizer.forModel(validation.path("message").asText("参数不符合服务端要求"), 300)
                    + "。未创建任务、未生成确认凭据。";
        }
        String algorithm = validation.path("algorithm").path("name").asText("");
        String dataset = validation.path("dataset").path("name").asText("");
        String params = scalarSummary(evidence.path("finalParameters"), 8);
        StringBuilder answer = new StringBuilder("参数校验通过");
        if (!algorithm.isEmpty()) {
            answer.append("：算法「").append(algorithm).append("」");
        }
        if (!dataset.isEmpty()) {
            answer.append("、数据集「").append(dataset).append("」");
        }
        answer.append("。服务端配置策略校验后的参数");
        answer.append(params.isEmpty() ? "已通过范围和类型检查" : "为 " + params);
        answer.append("。未创建任务、未生成确认凭据。");
        return answer.toString();
    }

    private String taskAnswer(JsonNode evidence) {
        JsonNode task = evidence.path("task");
        if (!task.isObject()) {
            return "未取得任务摘要证据，不能判断任务是否可启动。";
        }
        StringBuilder answer = new StringBuilder("任务 #").append(task.path("taskId").asText())
                .append("「").append(task.path("taskName").asText("")).append("」当前状态 ")
                .append(task.path("status").asText("unknown"))
                .append("，进度 ").append(task.path("progress").asInt(0)).append("%。");
        String executor = task.path("executorConfiguration").asText("");
        if ("worker_unknown".equals(executor)) {
            answer.append("执行器配置无法在 API 进程确认，需由 Worker 在消费时校验。");
        } else if ("missing".equals(executor)) {
            answer.append("本机执行器未配置，启动会被拒绝。");
        } else if ("configured".equals(executor)) {
            answer.append("本机执行器已配置。");
        }
        answer.append(blockersAnswer(evidence.path("blockers"), "阻塞项"))
                .append(blockersAnswer(evidence.path("warnings"), "提示"));
        return answer.toString();
    }

    private String reportAnswer(JsonNode evidence) {
        JsonNode metrics = evidence.path("metrics");
        StringBuilder answer = new StringBuilder("任务 #").append(evidence.path("taskId").asText())
                .append(" 当前状态 ").append(evidence.path("taskStatus").asText("unknown")).append("。");
        if (metrics.isObject() && "unavailable".equals(metrics.path("state").asText())) {
            answer.append("服务端未取得可用指标，不能给出纯度或样本数量结论。");
        } else {
            answer.append("服务端报告指标：隔离比例清洗纯度 ")
                    .append(metrics.path("cleanPurityPercentByIsolationRatio").asText("—")).append("%")
                    .append("，隔离比例选取样本数 ")
                    .append(metrics.path("selectedSampleCountByIsolationRatio").asText("—")).append("。");
        }
        answer.append("导出：").append(evidence.path("exportAvailability").asBoolean(false)
                ? "可用" : "不可用").append("。");
        if (evidence.hasNonNull("failureSummary")) {
            answer.append("失败摘要：")
                    .append(KnowledgeSanitizer.forModel(evidence.path("failureSummary").asText(), 300)).append("。");
        }
        return answer.toString();
    }

    private String blockersAnswer(JsonNode items, String label) {
        if (!items.isArray() || items.isEmpty()) {
            return "";
        }
        StringBuilder answer = new StringBuilder(label).append("：");
        int count = 0;
        for (JsonNode item : items) {
            if (count > 0) {
                answer.append("；");
            }
            answer.append(item.path("code").asText("ITEM")).append(" ")
                    .append(KnowledgeSanitizer.forModel(item.path("message").asText(""), 160));
            if (++count >= 4) {
                break;
            }
        }
        answer.append("。");
        return answer.toString();
    }

    private String scalarSummary(JsonNode object, int limit) {
        if (!object.isObject()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext() && parts.size() < limit) {
            java.util.Map.Entry<String, JsonNode> entry = fields.next();
            if ("data_path".equals(entry.getKey()) || !entry.getValue().isValueNode()) {
                continue;
            }
            parts.add(entry.getKey() + "=" + entry.getValue().asText());
        }
        return String.join("，", parts);
    }

    private String citationAnswer(JsonNode citations) {
        if (!citations.isArray() || citations.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode citation : citations) {
            if (parts.size() >= 4) {
                break;
            }
            String id = citation.path("citationId").asText("");
            String sourceKey = citation.path("sourceKey").asText("");
            if (id.isEmpty() && sourceKey.isEmpty()) {
                continue;
            }
            parts.add(id + " " + sourceKey + " L" + citation.path("startLine").asInt()
                    + "-" + citation.path("endLine").asInt());
        }
        return String.join("；", parts);
    }

    @PreDestroy
    void shutdownModelExecutor() {
        modelExecutor.shutdownNow();
        toolExecutor.shutdownNow();
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private record ToolExecution(AssistantChatResponse.AssistantToolTrace trace,
                                 AssistantChatResponse.AssistantPendingConfirmation pending,
                                 List<JsonNode> citations) {
    }

    private record ScenarioScope(String name, Set<String> tools, String requiredEvidenceTool) {
    }
}
