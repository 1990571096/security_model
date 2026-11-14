package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Cancel preview binds the current run id; it does not terminate anything yet. */
@Component
public class CancelTaskPreviewTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final ObjectMapper objectMapper;

    public CancelTaskPreviewTool(TaskAccessGuard taskAccessGuard, ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "taskId", "当前用户自己的运行中任务 ID", true);
        return new AssistantToolDescriptor("preview_cancel_task",
                "预览取消任务；只生成确认凭据，不终止进程", true, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"running".equals(task.getStatus())) {
            throw new AssistantException("ASSISTANT_TASK_STATE_INVALID",
                    org.springframework.http.HttpStatus.CONFLICT, "只有 running 任务可以预览取消");
        }
        if (task.getCurrentRunId() == null || task.getCurrentRunId().isBlank()) {
            throw new AssistantException("ASSISTANT_TASK_STATE_INVALID",
                    org.springframework.http.HttpStatus.CONFLICT, "任务缺少当前运行标识，无法安全取消");
        }
        ObjectNode request = objectMapper.createObjectNode();
        request.put("taskId", task.getTaskId());
        ObjectNode data = request.deepCopy();
        data.put("operation", "cancel");
        data.put("taskName", task.getTaskName());
        data.put("currentRunId", task.getCurrentRunId());
        data.put("currentAttempt", task.getCurrentAttempt());
        data.put("warning", "确认后会终止当前进程树；若 run 已变化，确认将失效。");
        String summary = "取消预览：任务 #" + taskId + " 当前 run=" + task.getCurrentRunId()
                + "；确认前不会终止任何进程。";
        return new AssistantToolResult(summary, data, "cancel", request, task.getTaskId(),
                task.getCurrentRunId(),
                AssistantContextHasher.taskState("cancel", task, task.getCurrentRunId()));
    }
}
