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

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;
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

    public static final String EXPERIMENT_ASSISTANT = "experiment_assistant";
    public static final String KNOWLEDGE_TOOL = "search_knowledge";
    private static final long INTENT_TIMEOUT_MILLIS = 6000;
    private static final String CAPABILITIES = "我是实验平台的实验助手。我可以查询你自己的任务，解读报告和清洗指标，"
            + "也可以查阅知识库并给出出处。启动、取消和导出可以在任务页面操作。";
    private static final Pattern CAPABILITY_QUESTION = Pattern.compile(
            "(?:你好|您好|hi|hello|你是谁|你是什么助手|(?:请)?介绍(?:一下)?(?:你)?自己|自我介绍(?:一下)?"
                    + "|(?:请)?(?:解释|介绍|说说|说明)(?:一下)?你的(?:能力|功能)"
                    + "|你(?:都)?(?:可以|能)?(?:做什么|做些什么|做哪些事|帮我做什么|帮我做些什么)"
                    + "|(?:你|助手|实验助手)(?:都)?有(?:哪些|什么)(?:能力|功能)"
                    + "|(?:你|助手|实验助手)的(?:能力|功能)(?:是什么|有哪些|是什么样的))[。！？?!，,]*");
    private static final Pattern UNSPECIFIED_RECORDS_QUESTION = Pattern.compile(
            "(?:(?:请)?(?:列出|看(?:一)?下|查(?:一)?下))?(?:当前|现在|目前)?(?:都)?"
                    + "(?:有(?:哪(?:些|几条)|什么)记录|有(?:多少|几)条?记录|(?:的)?记录)"
                    + "[。！？?!，,]*");
    private static final Pattern KNOWLEDGE_FOLLOW_UP = Pattern.compile(
            "^(?:那(?:么|它|这个|这些|其中)|这个|这些|它们?|上(?:面|一个)|刚才(?:说的|提到的)).*"
                    + "|^那.{1,45}(?:呢|吗)[？?。]*$");

    static boolean isKnowledgeFollowUp(String question) {
        return KNOWLEDGE_FOLLOW_UP.matcher(question).matches();
    }

    static boolean isCapabilityQuestion(String question) {
        return CAPABILITY_QUESTION.matcher(normalizeIntentQuestion(question)).matches();
    }

    static boolean isUnspecifiedRecordsQuestion(String question) {
        return UNSPECIFIED_RECORDS_QUESTION.matcher(normalizeIntentQuestion(question)).matches();
    }

    static String normalizeIntentQuestion(String question) {
        return Normalizer.normalize(question.strip(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

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
    private static final Set<String> EXPERIMENT_TOOLS = Set.of(KNOWLEDGE_TOOL, ExperimentMetricTool.NAME,
            MyTaskListTool.NAME, "get_my_task_summary", "get_my_report_summary",
            ExperimentComparisonTool.NAME, ExperimentSubsetTool.NAME, DraftNextRunTool.NAME);

    public static boolean isExperimentTool(AssistantToolDescriptor descriptor) {
        return !descriptor.write() && EXPERIMENT_TOOLS.contains(descriptor.name());
    }

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
        return chat(sessionId, question, null, null);
    }

    public AssistantChatResponse chat(Long sessionId, String question, String scenarioName) {
        return chat(sessionId, question, scenarioName, null);
    }

    public AssistantChatResponse chat(Long sessionId, String question, String scenarioName,
                                      ExperimentSelection selection) {
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
            return chatWithBudget(sessionId, question, scope, selection);
        } finally {
            userSlot.release();
            requestSlots.release();
        }
    }

    private ScenarioScope resolveScope(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            return null;
        }
        if (EXPERIMENT_ASSISTANT.equals(scenarioName)) {
            return new ScenarioScope(scenarioName, EXPERIMENT_TOOLS, null);
        }
        if ("experiment_knowledge".equals(scenarioName)) {
            return new ScenarioScope(scenarioName, Set.of(KNOWLEDGE_TOOL), KNOWLEDGE_TOOL);
        }
        AssistantScenarioCatalog.Scenario scenario = scenarioCatalog.find(scenarioName.trim());
        if (scenario == null) {
            throw new AssistantException("ASSISTANT_SCENARIO_UNKNOWN", HttpStatus.BAD_REQUEST,
                    "未知助手场景");
        }
        return new ScenarioScope(scenario.name(), Set.copyOf(scenario.tools()),
                scenario.requiredEvidenceTool());
    }

    private AssistantChatResponse chatWithBudget(Long sessionId, String question, ScenarioScope scope,
                                                  ExperimentSelection selection) {
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
        if (scope != null && EXPERIMENT_ASSISTANT.equals(scope.name())) {
            if (isCapabilityQuestion(question)) {
                return finish(session, question, new ScenarioScope("assistant_capabilities", Set.of(), null),
                        "answered", CAPABILITIES,
                        traces, pending, citations, "not_called", 0, 0);
            }
            if (isUnspecifiedRecordsQuestion(question)) {
                return finish(session, question, scope, "needs_context",
                        "你想看哪类记录？实验任务可在「任务中心」查看，助手会话在「对话历史」，"
                                + "已索引文档在「知识检索」。也可以告诉我你想找的资料名称或具体任务。",
                        traces, pending, citations, "not_called", 0, 0);
            }
            return runExperimentWorkflow(session, question, safeQuestion, scope, selection, traces, citations, deadline);
        }
        if (scope != null && "experiment_knowledge".equals(scope.name())) {
            var arguments = objectMapper.createObjectNode().put("question", safeQuestion);
            var call = new AssistantModel.ToolCall(KNOWLEDGE_TOOL, arguments);
            try {
                executeTool(session, call, timeoutMillis, scope, execution -> {
                    traces.add(execution.trace());
                    citations.addAll(execution.citations());
                });
            } catch (AssistantException exception) {
                addFailedTrace(traces, KNOWLEDGE_TOOL, exception);
                return finish(session, question, scope, "tool_error", exception.getMessage(), traces, pending,
                        citations, exception.getCode(), 0, 1);
            }
            return finish(session, question, scope, "answered", "", traces, pending, citations, "not_called", 0, 1);
        }
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
                    addFailedTrace(traces, reply.toolCall().toolName(), exception);
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

    private AssistantChatResponse runExperimentWorkflow(AssistantSession session, String question, String query,
                                                         ScenarioScope scope, ExperimentSelection selection,
                                                         List<AssistantChatResponse.AssistantToolTrace> traces,
                                                         Set<JsonNode> citations, long deadline) {
        var intent = ExperimentAssistantIntent.route(query);
        if (intent.kind() == ExperimentAssistantIntent.Kind.UNKNOWN
                && isolationRatio(query) != null && sessionService.lastComparison(session) != null) {
            intent = new ExperimentAssistantIntent(ExperimentAssistantIntent.Kind.COMPARE);
        }
        Set<String> explicitIds = ExperimentAssistantIntent.explicitTaskIds(normalizeIntentQuestion(query));
        boolean taskReference = explicitIds.isEmpty() && ExperimentAssistantIntent.refersToTask(query);
        String previousTaskId = taskReference ? sessionService.lastReadTaskId(session) : null;
        if (intent.kind() == ExperimentAssistantIntent.Kind.UNKNOWN && isKnowledgeFollowUp(query)
                && previousTaskId == null && !query.matches(".*(?:任务|报告|状态|进度).*")) {
            String topic = sessionService.lastKnowledgeQuestion(session);
            if (topic == null) {
                if (!model.supportsIntentClassification()) {
                    return finish(session, question, scope, "degraded",
                            "模型暂不可用，本次只执行本地规则：可读取本人任务、对照报告和检索知识库。"
                                    + "请改用「解读任务 #123」「对比任务 #A 和 #B，保留比例 30%」等明确指令。",
                            traces, List.of(), citations, "model_disabled", 0, 0);
                }
                return finish(session, question, scope, "needs_context",
                        "你指的是哪个指标、报告或实验步骤？请带上名称。",
                        traces, List.of(), citations, "not_called", 0, 0);
            }
            intent = ExperimentAssistantIntent.route(topic);
            if (intent.kind() != ExperimentAssistantIntent.Kind.METRIC) {
                intent = new ExperimentAssistantIntent(ExperimentAssistantIntent.Kind.KNOWLEDGE);
                query = KnowledgeSanitizer.forModel(query, 300) + "\n" + KnowledgeSanitizer.forModel(topic, 180);
            }
        }
        IntentDecision decision = intent.kind() == ExperimentAssistantIntent.Kind.UNKNOWN
                ? classifyIntent(query, previousTaskId, deadline)
                : new IntentDecision(intent, "not_called", 0);
        intent = decision.intent();

        if (intent.kind() == ExperimentAssistantIntent.Kind.COMPARE) {
            return runComparisonWorkflow(session, question, query, scope, selection, traces, citations,
                    decision, explicitIds, deadline);
        }
        if (intent.kind() == ExperimentAssistantIntent.Kind.SUBSET) {
            String taskId = explicitIds.stream().findFirst().orElse(null);
            if (taskId == null) {
                var context = sessionService.lastComparison(session);
                taskId = context == null ? sessionService.lastReadTaskId(session)
                        : context.path("primaryTaskId").asText(null);
            }
            var workflow = new ScenarioScope(scope.name(), scope.tools(), ExperimentSubsetTool.NAME, null);
            if (taskId == null) {
                return finish(session, question, workflow, "needs_context",
                        "请先指定任务 ID 或从任务报告进入助手，再按样本量下限筛选保留比例。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            int minimum = minimumSamples(query);
            var arguments = objectMapper.createObjectNode().put("taskId", taskId)
                    .put("minimumSamples", minimum).put("focus", "export");
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return finish(session, question, workflow, "timeout", "助手请求超时，请稍后重试。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            try {
                executeTool(session, new AssistantModel.ToolCall(ExperimentSubsetTool.NAME, arguments),
                        remaining, workflow, result -> {
                            traces.add(result.trace());
                            citations.addAll(result.citations());
                        });
                return finish(session, question, workflow, "answered", "",
                        traces, List.of(), citations, decision.status(), decision.calls(), 1);
            } catch (AssistantException exception) {
                addFailedTrace(traces, ExperimentSubsetTool.NAME, exception);
                return finish(session, question, workflow, "tool_error", exception.getMessage(),
                        traces, List.of(), citations, exception.getCode(), decision.calls(), 1);
            }
        }
        if (intent.kind() == ExperimentAssistantIntent.Kind.DRAFT) {
            String taskId = explicitIds.stream().findFirst().orElse(null);
            var context = sessionService.lastComparison(session);
            List<ExperimentSelection.RunReference> seeded = selection == null
                    ? List.of() : selection.runs();
            if (taskId == null) {
                taskId = context == null ? sessionService.lastReadTaskId(session)
                        : context.path("primaryTaskId").asText(null);
            }
            var workflow = new ScenarioScope(scope.name(), scope.tools(), DraftNextRunTool.NAME, null);
            if (taskId == null) {
                return finish(session, question, workflow, "needs_context",
                        "请先指定作为基线的任务 ID；草案只改训练轮数，不会创建任务。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            var arguments = objectMapper.createObjectNode().put("taskId", taskId);
            var bound = referenceFor(taskId, seeded, comparisonRuns(context));
            if (bound.runId() != null && bound.attempt() != null) {
                arguments.put("runId", bound.runId()).put("attempt", bound.attempt());
            }
            Integer epochs = requestedEpochs(query);
            if (epochs != null) {
                arguments.putObject("changes").put("num_epochs", epochs);
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return finish(session, question, workflow, "timeout", "助手请求超时，请稍后重试。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            try {
                executeTool(session, new AssistantModel.ToolCall(DraftNextRunTool.NAME, arguments),
                        remaining, workflow, result -> {
                            traces.add(result.trace());
                            citations.addAll(result.citations());
                        });
                return finish(session, question, workflow, "answered", "",
                        traces, List.of(), citations, decision.status(), decision.calls(), 1);
            } catch (AssistantException exception) {
                addFailedTrace(traces, DraftNextRunTool.NAME, exception);
                return finish(session, question, workflow, "tool_error", exception.getMessage(),
                        traces, List.of(), citations, exception.getCode(), decision.calls(), 1);
            }
        }

        var arguments = objectMapper.createObjectNode();
        String tool;
        boolean needsTask = false;
        switch (intent.kind()) {
            case CAPABILITY -> {
                return finish(session, question, new ScenarioScope("assistant_capabilities", Set.of(), null),
                        "answered", CAPABILITIES, traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            case METRIC -> tool = ExperimentMetricTool.NAME;
            case TASKS -> {
                tool = MyTaskListTool.NAME;
                if (intent.status() != null) {
                    arguments.put("status", intent.status());
                }
                if (intent.excludeStatus() != null) {
                    arguments.put("excludeStatus", intent.excludeStatus());
                }
            }
            case TASK, REPORT -> {
                String taskId = intent.taskId();
                if (taskId == null) {
                    taskId = explicitIds.size() == 1 ? explicitIds.iterator().next() : previousTaskId;
                }
                needsTask = taskId == null;
                tool = needsTask ? MyTaskListTool.NAME : intent.kind() == ExperimentAssistantIntent.Kind.REPORT
                        ? "get_my_report_summary" : "get_my_task_summary";
                if (!needsTask) {
                    arguments.put("taskId", taskId);
                }
            }
            case KNOWLEDGE -> {
                tool = KNOWLEDGE_TOOL;
                arguments.put("question", query);
            }
            default -> {
                if (java.util.Set.of("model_disabled", "intent_unavailable", "intent_timeout", "intent_busy")
                        .contains(decision.status())) {
                    return finish(session, question, scope, "degraded",
                            "模型暂不可用或意图解析超时，本次只执行本地规则：可读取本人任务、对照报告和检索知识库。"
                                    + "请改用「解读任务 #123」「对比任务 #A 和 #B，保留比例 30%」等明确指令。",
                            traces, List.of(), citations, decision.status(), decision.calls(), 0);
                }
                String message = switch (intent.kind()) {
                    case AMBIGUOUS_TASK -> "请指定要核对的一个任务 ID。";
                    case UNSUPPORTED -> "助手支持查询本人任务、解读报告和检索实验资料。任务操作及组合筛选请在任务中心处理。";
                    default -> "请补充你要查询的对象或条件，例如「列出我的运行中任务」「解读任务 #123 的报告」或「清洗纯度是什么」。";
                };
                return finish(session, question, scope, "needs_context", message,
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
        }
        String taskChoiceAction = needsTask ? intent.kind().name() : null;
        var workflow = new ScenarioScope(scope.name(), scope.tools(), tool, taskChoiceAction);
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            return finish(session, question, workflow, "timeout", "助手请求超时，请稍后重试。",
                    traces, List.of(), citations, decision.status(), decision.calls(), 0);
        }
        try {
            var execution = executeTool(session, new AssistantModel.ToolCall(tool, arguments), remaining, workflow,
                    result -> {
                        traces.add(result.trace());
                        citations.addAll(result.citations());
                    });
            if (needsTask) {
                String prompt = execution.trace().data().path("tasks").isEmpty()
                        ? "你的账户下暂无实验任务。可以先问报告指标的含义，或到任务中心创建任务。"
                        : intent.kind() == ExperimentAssistantIntent.Kind.REPORT
                        ? "请指定一项实验，例如「解读任务 #123 的报告」。"
                        : "请指定一项实验，例如「查询任务 #123 的进度」。";
                return finish(session, question, workflow, "needs_context", prompt,
                        traces, List.of(), citations, decision.status(), decision.calls(), 1);
            }
            if (KNOWLEDGE_TOOL.equals(tool) && citations.isEmpty()) {
                return finish(session, question, workflow, "no_evidence",
                        "知识库没有找到可引用的资料。可以给出指标名、文档名，或提供任务 ID 来核对报告。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 1);
            }
            return finish(session, question, workflow, "answered", "",
                    traces, List.of(), citations, decision.status(), decision.calls(), 1);
        } catch (AssistantException exception) {
            addFailedTrace(traces, tool, exception);
            return finish(session, question, workflow, "tool_error", exception.getMessage(),
                    traces, List.of(), citations, exception.getCode(), decision.calls(), 1);
        }
    }

    private AssistantChatResponse runComparisonWorkflow(AssistantSession session, String question, String query,
                                                         ScenarioScope scope, ExperimentSelection selection,
                                                         List<AssistantChatResponse.AssistantToolTrace> traces,
                                                         Set<JsonNode> citations, IntentDecision decision,
                                                         Set<String> explicitIds, long deadline) {
        String ratio = isolationRatio(query);
        if (ratio == null && selection != null) {
            ratio = selection.ratio();
        }
        var context = sessionService.lastComparison(session);
        if (ratio == null && context != null) {
            ratio = context.path("ratio").asText(null);
        }
        if (ratio == null) {
            ratio = "30";
        }

        List<ExperimentSelection.RunReference> seeded = selection == null
                ? List.of() : selection.runs();
        List<ExperimentSelection.RunReference> stored = comparisonRuns(context);
        List<ExperimentSelection.RunReference> selected = new ArrayList<>();

        String primaryTaskId = explicitIds.stream().findFirst().orElse(null);
        List<String> baselineIds = explicitIds.stream().skip(1).toList();
        if (primaryTaskId != null) {
            selected.add(referenceFor(primaryTaskId, seeded, stored));
            baselineIds.stream().limit(2).map(id -> referenceFor(id, seeded, stored)).forEach(selected::add);
            if (baselineIds.isEmpty() && context != null
                    && primaryTaskId.equals(context.path("primaryTaskId").asText())) {
                comparisonRuns(context).stream().skip(1).limit(2).forEach(selected::add);
            } else if (baselineIds.isEmpty() && seeded.size() >= 2
                    && primaryTaskId.equals(seeded.get(0).taskId())) {
                seeded.stream().skip(1).limit(2).forEach(selected::add);
            }
        } else if (!seeded.isEmpty()) {
            selected.addAll(seeded.stream().limit(3).toList());
        } else if (!stored.isEmpty()) {
            selected.addAll(stored.stream().limit(3).toList());
        }

        var workflow = new ScenarioScope(scope.name(), scope.tools(), ExperimentComparisonTool.NAME,
                selected.size() < 2 ? "COMPARE" : null);
        if (selected.isEmpty()) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return finish(session, question, workflow, "timeout", "助手请求超时，请稍后重试。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            try {
                var list = executeTool(session, new AssistantModel.ToolCall(MyTaskListTool.NAME,
                        objectMapper.createObjectNode().put("status", "success")), remaining, workflow, result -> {
                            traces.add(result.trace());
                            citations.addAll(result.citations());
                        });
                markSelection(list.trace(), null, ratio, "primary");
                String prompt = list.trace().data().path("tasks").isEmpty()
                        ? "你的账户下暂无可读取的成功运行，无法进行对照。"
                        : "请先选择主运行和对照运行；发送「以任务 #A 为主、对比任务 #B，保留比例 "
                        + ratio + "%」。下方候选来自你的成功任务。";
                return finish(session, question, workflow, "needs_context", prompt,
                        traces, List.of(), citations, decision.status(), decision.calls(), 1);
            } catch (AssistantException exception) {
                addFailedTrace(traces, MyTaskListTool.NAME, exception);
                return finish(session, question, workflow, "tool_error", exception.getMessage(),
                        traces, List.of(), citations, exception.getCode(), decision.calls(), 1);
            }
        }

        ExperimentSelection.RunReference primary = selected.get(0);
        if (selected.size() < 2) {
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining <= 0) {
                return finish(session, question, workflow, "timeout", "助手请求超时，请稍后重试。",
                        traces, List.of(), citations, decision.status(), decision.calls(), 0);
            }
            ExperimentSelection.RunReference boundPrimary = primary;
            if (primary.runId() == null || primary.attempt() == null) {
                try {
                    var summary = executeTool(session, new AssistantModel.ToolCall("get_my_report_summary",
                            objectMapper.createObjectNode().put("taskId", primary.taskId())), remaining, workflow,
                            result -> {
                                traces.add(result.trace());
                                citations.addAll(result.citations());
                            });
                    boundPrimary = new ExperimentSelection.RunReference(primary.taskId(),
                            summary.trace().data().path("runId").asText(null),
                            summary.trace().data().path("attempt").isInt()
                                    ? summary.trace().data().path("attempt").asInt() : null);
                } catch (AssistantException exception) {
                    addFailedTrace(traces, "get_my_report_summary", exception);
                    return finish(session, question, workflow, "tool_error", exception.getMessage(),
                            traces, List.of(), citations, exception.getCode(), decision.calls(), 1);
                }
            }
            try {
                var list = executeTool(session, new AssistantModel.ToolCall(MyTaskListTool.NAME,
                        objectMapper.createObjectNode().put("status", "success")), remaining, workflow, result -> {
                            traces.add(result.trace());
                            citations.addAll(result.citations());
                        });
                markSelection(list.trace(), boundPrimary, ratio, "baseline");
                String prompt = list.trace().data().path("tasks").isEmpty()
                        ? "你的账户下暂无可读取的成功运行，无法进行对照。"
                        : "主运行已绑定任务 #" + boundPrimary.taskId()
                        + (boundPrimary.runId() == null ? "" : "、运行 " + boundPrimary.runId()
                        + " 第 " + boundPrimary.attempt() + " 次尝试")
                        + "。请选择要作为对照的另一次运行；选中后我会按 " + ratio + "% 对齐纯度和样本数。";
                return finish(session, question, workflow, "needs_context", prompt,
                        traces, List.of(), citations, decision.status(), decision.calls(), 1);
            } catch (AssistantException exception) {
                addFailedTrace(traces, MyTaskListTool.NAME, exception);
                return finish(session, question, workflow, "tool_error", exception.getMessage(),
                        traces, List.of(), citations, exception.getCode(), decision.calls(), 1);
            }
        }

        for (ExperimentSelection.RunReference run : selected) {
            long taskRemaining = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (taskRemaining <= 1) {
                return finish(session, question, workflow, "timeout", "助手请求超时，未完成全部报告读取。",
                        traces, List.of(), citations, decision.status(), decision.calls(), traces.size());
            }
            try {
                executeTool(session, new AssistantModel.ToolCall("get_my_report_summary",
                        objectMapper.createObjectNode().put("taskId", run.taskId())), taskRemaining, workflow,
                        result -> {
                            traces.add(result.trace());
                            citations.addAll(result.citations());
                        });
            } catch (AssistantException exception) {
                addFailedTrace(traces, "get_my_report_summary", exception);
                return finish(session, question, workflow, "tool_error", exception.getMessage(),
                        traces, List.of(), citations, exception.getCode(), decision.calls(), traces.size());
            }
        }
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) {
            return finish(session, question, workflow, "timeout", "助手请求超时，未执行对照工具。",
                    traces, List.of(), citations, decision.status(), decision.calls(), traces.size());
        }
        var arguments = objectMapper.createObjectNode();
        var ids = arguments.putArray("taskIds");
        selected.forEach(run -> ids.add(run.taskId()));
        var runs = arguments.putArray("runs");
        for (ExperimentSelection.RunReference run : selected) {
            var item = runs.addObject().put("taskId", run.taskId());
            if (run.runId() != null) {
                item.put("runId", run.runId());
            }
            if (run.attempt() != null) {
                item.put("attempt", run.attempt());
            }
        }
        arguments.put("isolationRatio", ratio);
        arguments.put("focus", "subset");
        try {
            executeTool(session, new AssistantModel.ToolCall(ExperimentComparisonTool.NAME, arguments),
                    remaining, workflow, result -> {
                        traces.add(result.trace());
                        citations.addAll(result.citations());
                    });
            return finish(session, question, workflow, "answered", "",
                    traces, List.of(), citations, decision.status(), decision.calls(), traces.size());
        } catch (AssistantException exception) {
            addFailedTrace(traces, ExperimentComparisonTool.NAME, exception);
            return finish(session, question, workflow, "tool_error", exception.getMessage(),
                    traces, List.of(), citations, exception.getCode(), decision.calls(), traces.size());
        }
    }

    private List<ExperimentSelection.RunReference> comparisonRuns(JsonNode context) {
        if (context == null || !context.isObject()) {
            return List.of();
        }
        List<ExperimentSelection.RunReference> result = new ArrayList<>();
        JsonNode runs = context.path("runs");
        if (runs.isArray()) {
            for (JsonNode run : runs) {
                String taskId = run.path("taskId").asText("");
                if (!taskId.matches("[1-9][0-9]{0,18}")) {
                    continue;
                }
                result.add(new ExperimentSelection.RunReference(taskId,
                        run.path("runId").asText(null),
                        run.path("attempt").isInt() ? run.path("attempt").asInt() : null));
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        String primary = context.path("primaryTaskId").asText("");
        if (primary.matches("[1-9][0-9]{0,18}")) {
            result.add(new ExperimentSelection.RunReference(primary,
                    context.path("primaryRunId").asText(null),
                    context.path("primaryAttempt").isInt() ? context.path("primaryAttempt").asInt() : null));
        }
        context.path("baselineTaskIds").forEach(id -> {
            if (id.asText().matches("[1-9][0-9]{0,18}")) {
                result.add(new ExperimentSelection.RunReference(id.asText(), null, null));
            }
        });
        return result;
    }

    private ExperimentSelection.RunReference referenceFor(
            String taskId, List<ExperimentSelection.RunReference> first,
            List<ExperimentSelection.RunReference> second) {
        for (var run : first) {
            if (taskId.equals(run.taskId())) {
                return run;
            }
        }
        for (var run : second) {
            if (taskId.equals(run.taskId())) {
                return run;
            }
        }
        return new ExperimentSelection.RunReference(taskId, null, null);
    }

    private void markSelection(AssistantChatResponse.AssistantToolTrace trace,
                               ExperimentSelection.RunReference primary, String ratio, String missing) {
        if (!(trace.data() instanceof ObjectNode data)) {
            return;
        }
        ObjectNode selection = data.putObject("selection");
        selection.put("mode", "compare");
        selection.put("ratio", ratio);
        selection.put("missing", missing);
        if (primary != null) {
            selection.put("primaryTaskId", primary.taskId());
            if (primary.runId() != null) {
                selection.put("primaryRunId", primary.runId());
            }
            if (primary.attempt() != null) {
                selection.put("primaryAttempt", primary.attempt());
            }
        }
    }

    private static String isolationRatio(String query) {
        var matcher = java.util.regex.Pattern
                .compile("(?<![0-9])([0-9]{1,3}(?:\\.[0-9]{1,2})?)%")
                .matcher(query);
        return matcher.find() ? matcher.group(1) : null;
    }

    static int minimumSamples(String query) {
        var matcher = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*(万|千)?\\s*(张|个|条|样本)")
                .matcher(query);
        if (!matcher.find()) {
            return 20_000;
        }
        java.math.BigDecimal value = new java.math.BigDecimal(matcher.group(1));
        if ("万".equals(matcher.group(2))) {
            value = value.multiply(java.math.BigDecimal.valueOf(10_000));
        } else if ("千".equals(matcher.group(2))) {
            value = value.multiply(java.math.BigDecimal.valueOf(1_000));
        }
        return value.max(java.math.BigDecimal.ZERO).min(java.math.BigDecimal.valueOf(10_000_000)).intValue();
    }

    static Integer requestedEpochs(String query) {
        // Prefer the target of an explicit change ("5 → 6", "增加到 6 轮"), not the
        // baseline number that often appears earlier in the same sentence.
        var change = java.util.regex.Pattern
                .compile("(?:增加到|增加至|提升到|改为|改成|调整为|调整到|调到|设为|设置成|设置为)"
                        + "\\s*([0-9]{1,3})\\s*(?:个)?\\s*(?:轮|epochs?)?",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query);
        if (change.find()) {
            return Integer.parseInt(change.group(1));
        }
        var arrow = java.util.regex.Pattern
                .compile("(?:从\\s*)?[0-9]{1,3}\\s*(?:个)?\\s*(?:轮|epochs?)?\\s*(?:→|->|到)"
                        + "\\s*([0-9]{1,3})\\s*(?:个)?\\s*(?:轮|epochs?)?",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query);
        if (arrow.find()) {
            return Integer.parseInt(arrow.group(1));
        }
        var named = java.util.regex.Pattern
                .compile("num_epochs\\s*=\\s*([0-9]{1,3})",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query);
        if (named.find()) {
            return Integer.parseInt(named.group(1));
        }
        var explicit = java.util.regex.Pattern
                .compile("(?:训练轮数|轮数|epochs?)\\s*(?:=|为|是)?\\s*([0-9]{1,3})\\s*"
                        + "(?:个)?\\s*(?:轮|epochs?)",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(query);
        return explicit.find() ? Integer.parseInt(explicit.group(1)) : null;
    }

    private IntentDecision classifyIntent(String query, String previousTaskId, long deadline) {
        var unknown = new ExperimentAssistantIntent(ExperimentAssistantIntent.Kind.UNKNOWN);
        if (!model.supportsIntentClassification()) {
            return new IntentDecision(unknown, "model_disabled", 0);
        }
        long budget = Math.min(INTENT_TIMEOUT_MILLIS, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        if (budget <= 0) {
            return new IntentDecision(unknown, "intent_timeout", 0);
        }
        var input = objectMapper.createObjectNode().put("question", query);
        var ids = input.putArray("explicitTaskIds");
        ExperimentAssistantIntent.explicitTaskIds(normalizeIntentQuestion(query)).forEach(ids::add);
        if (previousTaskId != null) {
            input.put("previousTaskId", previousTaskId);
        }
        var request = new AssistantModel.ModelRequest(query,
                List.of(new AssistantModel.ModelMessage("user", input.toString())),
                List.of(), 0, budget, ExperimentAssistantIntent.classifierPrompt());
        Future<AssistantModel.ModelReply> future;
        try {
            future = modelExecutor.submit(() -> model.respond(request));
        } catch (RejectedExecutionException exception) {
            return new IntentDecision(unknown, "intent_busy", 0);
        }
        try {
            var reply = future.get(budget, TimeUnit.MILLISECONDS);
            if (reply == null || !"ok".equals(reply.status())) {
                return new IntentDecision(unknown, "intent_unavailable", 1);
            }
            var intent = reply.toolCall() == null
                    ? ExperimentAssistantIntent.parseClassification(objectMapper, reply.answer(), query, previousTaskId)
                    : unknown;
            return new IntentDecision(intent,
                    intent.kind() == ExperimentAssistantIntent.Kind.UNKNOWN ? "intent_invalid" : "intent_classified", 1);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return new IntentDecision(unknown, "intent_timeout", 1);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new IntentDecision(unknown, "intent_interrupted", 1);
        } catch (ExecutionException exception) {
            return new IntentDecision(unknown, "intent_unavailable", 1);
        }
    }

    private record IntentDecision(ExperimentAssistantIntent intent, String status, int calls) {}

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

    private void addFailedTrace(List<AssistantChatResponse.AssistantToolTrace> traces,
                                String name, AssistantException exception) {
        if (registry.descriptors().stream().anyMatch(descriptor -> descriptor.name().equals(name))) {
            traces.add(new AssistantChatResponse.AssistantToolTrace(name,
                    "工具未返回结果（" + exception.getCode() + "）", null, null, "failed"));
        }
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
        if (scope != null && scope.requiredEvidenceTool() != null && "answered".equals(status)
                && traces.stream().noneMatch(trace -> scope.requiredEvidenceTool().equals(trace.toolName())
                        && "ok".equals(trace.status()))) {
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
        if (scope != null && scope.requiredEvidenceTool() == null && "answered".equals(effectiveStatus)) {
            answerSource = "server_capability";
        } else if (scope != null && "answered".equals(effectiveStatus)) {
            // Scenario answers are assembled from server evidence. The model's free-form
            // prose is never used as the authoritative answer, so a fabricated number or
            // [citation:99] cannot reach the user-facing answer or the saved turn.
            effectiveAnswer = buildScenarioAnswer(scope.name(), structured);
            answerSource = "server_evidence";
        }
        sessionService.appendTurn(session, question, effectiveStatus, effectiveAnswer, toolNames,
                confirmationIds, citationIds, traces);
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
        if (scope.taskChoiceAction() != null && "needs_context".equals(status)) {
            root.put("taskChoiceAction", scope.taskChoiceAction());
        }
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
                case MyTaskListTool.NAME -> evidence.set("taskList", data);
                case ExperimentMetricTool.NAME -> evidence.set("metric", data);
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
                case ExperimentComparisonTool.NAME -> evidence.set("comparison", data.deepCopy());
                case ExperimentSubsetTool.NAME -> evidence.set("subset", data.deepCopy());
                case DraftNextRunTool.NAME -> evidence.set("draft", data.deepCopy());
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
        if (EXPERIMENT_ASSISTANT.equals(scenarioName)) {
            JsonNode evidence = structured.path("evidence");
            return switch (structured.path("requiredEvidenceTool").asText()) {
                case ExperimentMetricTool.NAME -> {
                    JsonNode metric = evidence.path("metric");
                    yield metric.path("definition").asText() + "\n" + metric.path("interpretation").asText()
                            + "\n" + metric.path("limitation").asText();
                }
                case MyTaskListTool.NAME -> myTasksAnswer(evidence.path("taskList"));
                case "get_my_task_summary" -> taskAnswer(evidence);
                case "get_my_report_summary" -> reportAnswer(evidence);
                case ExperimentComparisonTool.NAME -> comparisonAnswer(evidence.path("comparison"));
                case ExperimentSubsetTool.NAME -> subsetAnswer(evidence.path("subset"));
                case DraftNextRunTool.NAME -> draftAnswer(evidence.path("draft"));
                default -> {
                    JsonNode citation = structured.path("citations").path(0);
                    yield "知识库摘录｜" + citation.path("title").asText() + "\n"
                            + citation.path("snippet").asText();
                }
            };
        }
        if ("experiment_knowledge".equals(scenarioName)) {
            return structured.path("citations").isEmpty()
                    ? "共享知识库和当前可读资料中没有找到足够依据。"
                    : "找到以下相关资料，请对照引用内容核查。";
        }
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

    private String myTasksAnswer(JsonNode taskList) {
        JsonNode rows = taskList.path("tasks");
        String filter = taskList.has("status") ? taskStatus(taskList.path("status").asText()) + "的"
                : taskList.has("excludeStatus") ? "排除「" + taskStatus(taskList.path("excludeStatus").asText()) + "」后的" : "";
        if (rows.isEmpty()) {
            return "你暂时没有" + filter + "实验任务。";
        }
        StringBuilder answer = new StringBuilder("你有 ").append(taskList.path("total").asLong())
                .append(" 条").append(filter).append("实验任务");
        if (taskList.path("total").asLong() > rows.size()) {
            answer.append("，以下是最近 ").append(rows.size()).append(" 条");
        }
        answer.append("。");
        for (JsonNode row : rows) {
            answer.append("\n#").append(row.path("taskId").asText())
                    .append(" ").append(row.path("taskName").asText())
                    .append(" · ").append(taskStatus(row.path("status").asText()));
        }
        return answer.toString();
    }

    private String taskStatus(String status) {
        return switch (status) {
            case "pending" -> "待启动";
            case "running" -> "运行中";
            case "success" -> "已成功";
            case "failed" -> "已失败";
            case "canceled" -> "已取消";
            default -> "状态未知";
        };
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
        JsonNode purity = metrics.path("cleanPurityPercentByIsolationRatio");
        JsonNode counts = metrics.path("selectedSampleCountByIsolationRatio");
        if ((purity.isMissingNode() || purity.isNull()) && (counts.isMissingNode() || counts.isNull())) {
            answer.append("服务端未取得可用指标，不能给出纯度或样本数量结论。");
        } else if (purity.isObject() || counts.isObject()) {
            var ratios = new LinkedHashSet<String>();
            purity.fieldNames().forEachRemaining(ratios::add);
            counts.fieldNames().forEachRemaining(ratios::add);
            answer.append("\n报告按保留比例记录了以下结果：");
            for (String ratio : ratios.stream().filter(key -> key.matches("[0-9]{1,3}(\\.[0-9]{1,4})?"))
                    .limit(12).toList()) {
                answer.append("\n").append(ratio).append("% · 清洗纯度 ")
                        .append(formatMetric(purity.path(ratio), "%"))
                        .append(" · 保留样本 ").append(formatMetric(counts.path(ratio), " 个"));
            }
            answer.append("\n比较时同时看纯度和保留样本数。");
        } else {
            answer.append("服务端报告指标：隔离比例清洗纯度 ")
                    .append(formatMetric(purity, "%"))
                    .append("，隔离比例选取样本数 ")
                    .append(formatMetric(counts, " 个")).append("。");
        }
        answer.append("\n导出：").append(evidence.path("exportAvailability").asBoolean(false)
                ? "可用" : "不可用").append("。");
        if (evidence.hasNonNull("failureSummary")) {
            answer.append("失败摘要：")
                    .append(KnowledgeSanitizer.forModel(evidence.path("failureSummary").asText(), 300)).append("。");
        }
        return answer.toString();
    }

    private String draftAnswer(JsonNode draft) {
        if (!draft.isObject()) {
            return "未取得基线任务参数，不能生成下一轮草案。";
        }
        StringBuilder answer = new StringBuilder("基于任务 #").append(draft.path("taskId").asText());
        if (draft.hasNonNull("baselineRunId")) {
            answer.append(" 运行 ").append(draft.path("baselineRunId").asText())
                    .append(" / 第 ").append(draft.path("baselineAttempt").asInt()).append(" 次尝试");
        }
        answer.append(" 的实际生效配置生成下一轮草案：只改训练轮数 ")
                .append(draft.path("before").asInt()).append(" → ").append(draft.path("after").asInt())
                .append(" epochs。");
        JsonNode delta = draft.path("deltaCheck");
        answer.append("\n配置比对：")
                .append(draft.path("reproducible").asBoolean(false)
                        ? "新表单预览与生效配置相比只有 num_epochs 变化。"
                        : "配置差异不止 num_epochs，当前草案不能证明单变量复现。");
        if (delta.path("changedFields").isArray() && !delta.path("changedFields").isEmpty()) {
            answer.append(" 变化字段：")
                    .append(String.join("、", textValues(delta.path("changedFields")))).append("。");
        }
        JsonNode validation = draft.path("validation");
        answer.append("\n参数校验：").append(draft.path("valid").asBoolean(false) ? "通过" : "未通过")
                .append(validation.hasNonNull("code") ? "（" + validation.path("code").asText() + "）" : "")
                .append("；未创建任务、未写调度、未生成确认凭据。");
        JsonNode params = draft.path("proposedParams");
        if (params.isObject()) {
            answer.append("\n草案参数：");
            int count = 0;
            for (java.util.Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext() && count < 8; ) {
                var field = it.next();
                if ("data_path".equals(field.getKey()) || !field.getValue().isValueNode()) {
                    continue;
                }
                answer.append("\n· ").append(field.getKey()).append(" = ").append(field.getValue().asText());
                count++;
            }
        }
        answer.append("\n提交入口：在任务中心打开新建任务表单，使用同一数据集、算法和其余参数；"
                + "助手不会替你提交。");
        return answer.toString();
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private String subsetAnswer(JsonNode subset) {
        if (!subset.isObject()) {
            return "未取得报告中的保留样本记录，不能筛选档位。";
        }
        StringBuilder answer = new StringBuilder("任务 #").append(subset.path("taskId").asText())
                .append("，按至少 ").append(subset.path("minimumSamples").asInt()).append(" 个保留样本筛选。");
        JsonNode available = subset.path("available");
        if (available.isEmpty()) {
            answer.append("\n没有达到下限的已记录档位；不补算缺失比例。");
        } else {
            answer.append("\n满足条件的档位：");
            for (JsonNode item : available) {
                answer.append("\n· ").append(item.path("isolationRatio").asText()).append("% · 样本 ")
                        .append(item.path("sampleCount").asText()).append(" · 纯度 ")
                        .append(item.path("cleanPurityPercent").asText()).append("% · ")
                        .append(item.path("exportLabel").asText());
            }
            answer.append("\n建议从 ").append(subset.path("recommendedRatio").asText())
                    .append("% 开始核对；导出仍需在任务详情页提交，助手不创建导出任务。");
            if (subset.path("downloadReadyCount").asInt(0) == 0) {
                answer.append("\n当前没有已验证可读的 ZIP；若显示“可提交”只代表具备导出资格，不代表文件已生成。");
            }
            for (JsonNode item : available) {
                if (!item.path("eligibleForExport").asBoolean(true)) {
                    answer.append("\n· ").append(item.path("isolationRatio").asText())
                            .append("% 当前不可导出：").append(item.path("exportReason").asText("资格未知"));
                }
            }
        }
        JsonNode excluded = subset.path("excluded");
        if (!excluded.isEmpty()) {
            answer.append("\n被排除：");
            for (JsonNode item : excluded) {
                answer.append("\n· ").append(item.path("isolationRatio").asText()).append("% · 样本 ")
                        .append(item.path("sampleCount").asText()).append("（")
                        .append(item.path("reason").asText()).append("）");
            }
        }
        answer.append("\n证据：").append(subset.path("evidence").path("reportEndpoint").asText())
                .append("；导出状态取自同一报告的真实接口。");
        return answer.toString();
    }

    private String comparisonAnswer(JsonNode comparison) {
        if (!comparison.isObject() || comparison.path("runs").isEmpty()) {
            return "未取得可比对的报告证据，不能给出差值。";
        }
        String ratio = comparison.path("ratio").asText("30");
        StringBuilder answer = new StringBuilder("按 ").append(ratio).append("% 保留比例对齐")
                .append("；纯度差按百分点、样本数差按个。");
        boolean stale = "stale".equals(comparison.path("status").asText());
        if (stale) {
            answer.append("\n原先选择的运行已被新尝试覆盖或与报告不一致；原选择保留，但已停止计算差值。")
                    .append("请从下方当前证据重新选择运行。");
        }
        JsonNode runs = comparison.path("runs");
        for (JsonNode run : runs) {
            JsonNode purity = run.path("cleanPurityPercentByIsolationRatio");
            JsonNode counts = run.path("selectedSampleCountByIsolationRatio");
            answer.append("\n任务 #").append(run.path("taskId").asText())
                    .append(run.path("taskName").asText("").isEmpty() ? "" : "「" + run.path("taskName").asText() + "」")
                    .append("：清洗纯度 ").append(formatMetric(purity.path(ratio), "%"))
                    .append("，保留样本 ").append(formatMetric(counts.path(ratio), " 个"))
                    .append("；运行 ").append(run.path("runId").asText("未知"))
                    .append(" 第 ").append(run.path("attempt").asText("未知")).append(" 次尝试。");
        }
        JsonNode deltas = comparison.path("deltas");
        if (deltas.isEmpty()) {
            answer.append("\n差值：不可计算。");
        } else {
            for (JsonNode delta : deltas) {
                answer.append("\n主运行 #").append(delta.path("primaryTaskId").asText())
                        .append(" 相比对照 #").append(delta.path("baselineTaskId").asText())
                        .append("：纯度差 ")
                        .append(formatMetric(delta.path("cleanPurityPercentagePoints"), " 个百分点"))
                        .append("，样本数差 ")
                        .append(formatMetric(delta.path("sampleCountDifference"), " 个")).append("。");
            }
        }
        answer.append("\n条件核对：");
        for (JsonNode item : comparison.path("comparability")) {
            String state = item.path("state").asText();
            answer.append("\n· ").append(item.path("label").asText())
                    .append(" ").append("same".equals(state) ? "一致"
                            : "different".equals(state) ? "不同" : "未知")
                    .append("（主：").append(item.path("primary").asText())
                    .append("；对照：").append(item.path("baseline").asText()).append("）");
        }
        answer.append("\n证据出处：");
        for (JsonNode run : runs) {
            answer.append("\n· ").append(run.path("reportEndpoint").asText());
        }
        JsonNode gaps = comparison.path("gaps");
        if (!gaps.isEmpty()) {
            answer.append("\n证据缺口：");
            int count = 0;
            for (JsonNode gap : gaps) {
                if ("RUN_NOT_SUCCESS".equals(gap.path("code").asText()) && "success".equals(
                        runs.path(0).path("status").asText())) {
                    continue;
                }
                answer.append("\n· ").append(KnowledgeSanitizer.forModel(gap.path("message").asText(), 240));
                if (++count >= 6) {
                    break;
                }
            }
        }
        answer.append("\n边界：这里只复述报告记录并计算同比例差值；条件不同或数据版本未知时，"
                + "不能把差异归因于训练轮数等单一变量，也不提供下游准确率或攻击成功率结论。");
        return answer.toString();
    }

    private String comparisonAnswerLegacy(JsonNode evidence) {
        return reportAnswer(evidence);
    }

    private String formatMetric(JsonNode value, String unit) {
        String text = value.asText();
        return text.matches("-?[0-9]{1,12}(\\.[0-9]{1,8})?") ? text + unit : "未记录";
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

    private record ScenarioScope(String name, Set<String> tools, String requiredEvidenceTool, String taskChoiceAction) {
        private ScenarioScope(String name, Set<String> tools, String requiredEvidenceTool) {
            this(name, tools, requiredEvidenceTool, null);
        }
    }
}
