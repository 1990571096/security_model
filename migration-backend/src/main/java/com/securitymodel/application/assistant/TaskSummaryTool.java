package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Own-task summary only; owner check is the same service used by the REST API. */
@Component
public class TaskSummaryTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final ObjectMapper objectMapper;

    public TaskSummaryTool(TaskAccessGuard taskAccessGuard, ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "taskId", "当前用户自己的任务 ID", true);
        return new AssistantToolDescriptor("get_my_task_summary",
                "读取当前用户自己的任务状态和参数摘要；跨用户任务会被拒绝", false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("taskId", task.getTaskId());
        data.put("taskName", KnowledgeSanitizer.forModel(task.getTaskName(), 120));
        data.put("status", task.getStatus());
        data.put("progress", task.getProgress());
        data.put("datasetId", task.getDatasetId());
        data.put("algorithmId", task.getAlgorithmId());
        data.put("hyperId", task.getHyperId());
        data.put("currentAttempt", task.getCurrentAttempt());
        if (task.getCustomParams() != null) {
            data.put("customParams", KnowledgeSanitizer.forModel(task.getCustomParams(), 500));
        }
        if (task.getStartTime() != null) {
            data.put("startTime", task.getStartTime().toString());
        }
        if (task.getEndTime() != null) {
            data.put("endTime", task.getEndTime().toString());
        }
        String summary = "任务 #" + task.getTaskId() + "「" + data.path("taskName").asText()
                + "」状态 " + task.getStatus() + "，进度 " + (task.getProgress() == null ? 0 : task.getProgress())
                + "%；仅返回当前用户自己的任务。";
        return new AssistantToolResult(summary, data, null, null, task.getTaskId(),
                task.getCurrentRunId(), null);
    }
}
