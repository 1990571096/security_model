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

/** Start preview validates state/parameters but never acquires a GPU lease. */
@Component
public class StartTaskPreviewTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final PreDefenseTaskCommandService commandService;
    private final ObjectMapper objectMapper;

    public StartTaskPreviewTool(TaskAccessGuard taskAccessGuard, PreDefenseTaskCommandService commandService,
                                ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "taskId", "当前用户自己的 pending 任务 ID", true);
        return new AssistantToolDescriptor("preview_start_task",
                "预览启动任务；只校验状态和参数，不领取 GPU、不启动训练", true, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"pending".equals(task.getStatus())) {
            throw new AssistantException("ASSISTANT_TASK_STATE_INVALID",
                    org.springframework.http.HttpStatus.CONFLICT, "只有 pending 任务可以预览启动");
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
        data.put("operation", "start");
        data.put("taskName", task.getTaskName());
        data.put("status", task.getStatus());
        data.put("warning", "确认后会占用单 GPU 槽并启动真实任务；不会自动修改 128 batch / 5 epochs。");
        String summary = "启动预览：任务 #" + taskId + " 当前为 pending，参数已通过校验；确认前不会领取 GPU 或启动进程。";
        return new AssistantToolResult(summary, data, "start", request, task.getTaskId(),
                task.getCurrentRunId(),
                AssistantContextHasher.taskState("start", task, ""));
    }
}
