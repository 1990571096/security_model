package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Validates a candidate task and returns a preview; it never inserts a task. */
@Component
public class CreateTaskPreviewTool implements AssistantTool {

    private final PreDefenseTaskCommandService commandService;
    private final ObjectMapper objectMapper;

    public CreateTaskPreviewTool(PreDefenseTaskCommandService commandService, ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.text(schema, "taskName", "任务名称，最多 100 字", 100, true);
        AssistantSchemas.text(schema, "description", "任务说明，最多 500 字", 500, false);
        AssistantSchemas.integer(schema, "datasetId", "可用数据集 ID", true);
        AssistantSchemas.integer(schema, "algorithmId", "已启用的 LDD 算法 ID", true);
        AssistantSchemas.integer(schema, "hyperId", "可选超参数模板 ID", false);
        AssistantSchemas.object(schema, "customParams", "可选自定义参数对象；不会自动改写 epochs 等既有值", false);
        return new AssistantToolDescriptor("preview_create_task",
                "校验并预览创建任务；只生成预览和确认凭据，不写库、不启动训练",
                true, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments,
                Set.of("taskName", "description", "datasetId", "algorithmId", "hyperId", "customParams"));
        PreDefenseTaskCommand command = new PreDefenseTaskCommand();
        command.setTaskName(AssistantArgs.requireText(arguments, "taskName", 100));
        command.setDescription(AssistantArgs.optionalText(arguments, "description", 500));
        command.setDatasetId(AssistantArgs.requireLong(arguments, "datasetId"));
        command.setAlgorithmId(AssistantArgs.requireLong(arguments, "algorithmId"));
        command.setHyperId(AssistantArgs.optionalLong(arguments, "hyperId"));
        command.setCustomParams(AssistantArgs.optionalObjectText(arguments, "customParams", 20_000));

        commandService.validateDraft(command);
        ObjectNode request = objectMapper.createObjectNode();
        request.put("taskName", command.getTaskName());
        if (command.getDescription() != null) {
            request.put("description", command.getDescription());
        }
        request.put("datasetId", command.getDatasetId());
        request.put("algorithmId", command.getAlgorithmId());
        if (command.getHyperId() != null) {
            request.put("hyperId", command.getHyperId());
        }
        request.set("customParams", parseObject(command.getCustomParams()));

        ObjectNode data = request.deepCopy();
        data.put("operation", "create");
        data.put("warning", "创建后任务为 pending，不会自动启动；启动仍需单独预览并确认。");
        data.put("parametersLocked", true);
        String summary = "创建任务预览：仅校验名称、数据集、算法和参数，任务尚未写入、不会自动启动。";
        return new AssistantToolResult(summary, data, "create", request, null, null,
                AssistantContextHasher.createState(request.toString()));
    }

    private JsonNode parseObject(String raw) {
        try {
            JsonNode node = raw == null ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception exception) {
            throw new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "customParams 必须是 JSON 对象");
        }
    }
}
