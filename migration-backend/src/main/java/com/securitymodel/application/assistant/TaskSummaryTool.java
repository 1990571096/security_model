package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.task.DispatchRuntimePolicy;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Own-task summary only; owner check is the same service used by the REST API. */
@Component
public class TaskSummaryTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final DefenseTaskExecutor executor;
    private final DispatchRuntimePolicy runtimePolicy;
    private final ObjectMapper objectMapper;

    public TaskSummaryTool(TaskAccessGuard taskAccessGuard, DefenseTaskExecutor executor,
                           DispatchRuntimePolicy runtimePolicy,
                           ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.executor = executor;
        this.runtimePolicy = runtimePolicy;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.identifier(schema, "taskId", "当前用户自己的任务 ID", true);
        return new AssistantToolDescriptor("get_my_task_summary",
                "读取当前用户自己的任务状态和参数摘要；跨用户任务会被拒绝", false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("taskId", String.valueOf(task.getTaskId()));
        // Retained for callers that already bind to the explicit text field.
        data.put("taskIdText", String.valueOf(task.getTaskId()));
        data.put("taskName", KnowledgeSanitizer.forModel(task.getTaskName(), 120));
        data.put("status", task.getStatus());
        data.put("progress", task.getProgress());
        data.put("datasetId", task.getDatasetId() == null ? null : String.valueOf(task.getDatasetId()));
        data.put("algorithmId", task.getAlgorithmId() == null ? null : String.valueOf(task.getAlgorithmId()));
        data.put("hyperId", task.getHyperId() == null ? null : String.valueOf(task.getHyperId()));
        data.put("currentAttempt", task.getCurrentAttempt());
        if (task.getStartTime() != null) {
            data.put("startTime", task.getStartTime().toString());
        }
        if (task.getEndTime() != null) {
            data.put("endTime", task.getEndTime().toString());
        }
        ArrayNode blockers = objectMapper.createArrayNode();
        ArrayNode warnings = objectMapper.createArrayNode();
        if (!"pending".equals(task.getStatus())) {
            blockers.addObject().put("code", "TASK_NOT_PENDING")
                    .put("message", "只有 pending 任务可以启动");
        }
        data.put("executorMode", runtimePolicy.mode().name().toLowerCase(java.util.Locale.ROOT));
        data.put("executorRole", runtimePolicy.role().name().toLowerCase(java.util.Locale.ROOT));
        boolean workerOwnsExecution = runtimePolicy.rabbitMode() && !runtimePolicy.workerRole();
        if (workerOwnsExecution) {
            // In rabbit mode the API role does not run Python. Its own executor being
            // unconfigured says nothing about the Worker that consumes the message, so this
            // is an explicit unknown rather than a launch blocker.
            data.put("executorConfiguration", "worker_unknown");
            warnings.addObject().put("code", "WORKER_CONFIGURATION_UNKNOWN")
                    .put("message", "API 进程不承担执行，Worker 执行器配置无法在此预检确认；启动由 Worker 校验");
        } else if (!executor.isConfigured()) {
            data.put("executorConfiguration", "missing");
            blockers.addObject().put("code", "EXECUTOR_NOT_CONFIGURED")
                    .put("message", "本机执行器未配置或配置无效，请检查 Python 脚本、解释器及执行工作区");
        } else {
            data.put("executorConfiguration", "configured");
        }
        data.set("blockers", blockers);
        data.set("warnings", warnings);
        String summary = "任务 #" + task.getTaskId() + "「" + data.path("taskName").asText()
                + "」状态 " + task.getStatus() + "，进度 " + (task.getProgress() == null ? 0 : task.getProgress())
                + "%；阻塞项 " + blockers.size() + " 个，警告 " + warnings.size()
                + " 条，仅返回当前用户自己的任务。";
        return new AssistantToolResult(summary, data, null, null, task.getTaskId(),
                task.getCurrentRunId(), null);
    }
}
