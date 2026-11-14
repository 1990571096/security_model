package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Retry preview validates parameters; the actual reset/start happens only after confirmation. */
@Component
public class RetryTaskPreviewTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final PreDefenseTaskCommandService commandService;
    private final ObjectMapper objectMapper;

    public RetryTaskPreviewTool(TaskAccessGuard taskAccessGuard, PreDefenseTaskCommandService commandService,
                                ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "taskId", "当前用户自己的 failed 任务 ID", true);
        return new AssistantToolDescriptor("preview_retry_task",
                "预览重试失败任务；只校验状态和参数，不重置任务、不启动训练", true, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"failed".equals(task.getStatus())) {
            throw new AssistantException("ASSISTANT_TASK_STATE_INVALID",
                    org.springframework.http.HttpStatus.CONFLICT, "只有 failed 任务可以预览重试");
        }
        PreDefenseTaskCommand command = new PreDefenseTaskCommand();
        command.setTaskId(task.getTaskId());
        command.setTaskName(task.getTaskName());
        command.setDescription(task.getDescription());
        command.setDatasetId(task.getDatasetId());
        command.setAlgorithmId(task.getAlgorithmId());
        command.setHyperId(task.getHyperId());
        command.setCustomParams(task.getCustomParams());
        commandService.validateDraft(command);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("taskId", task.getTaskId());
        ObjectNode data = request.deepCopy();
        data.put("operation", "retry");
        data.put("taskName", task.getTaskName());
        data.put("status", task.getStatus());
        data.put("warning", "确认后会重置失败状态、生成新 run 并重新占用 GPU；仍使用原参数。");
        String summary = "重试预览：任务 #" + taskId + " 当前为 failed，原参数通过校验；确认前不会重置或启动。";
        return new AssistantToolResult(summary, data, "retry", request, task.getTaskId(),
                task.getCurrentRunId(),
                AssistantContextHasher.taskState("retry", task, ""));
    }
}
