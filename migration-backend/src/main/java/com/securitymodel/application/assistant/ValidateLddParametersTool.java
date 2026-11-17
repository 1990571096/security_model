package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.application.task.TaskCommandException;
import com.securitymodel.application.task.TaskExecutionPlan;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Pure validation path: it reuses the exact create-task catalog checks and LDD configuration
 * policy, but never inserts a task, writes an outbox intent, acquires a GPU lease or creates
 * a confirmation. A validation failure is returned as structured evidence rather than
 * turning into an opaque tool_error.
 */
@Component
public class ValidateLddParametersTool implements AssistantTool {

    private final PreDefenseTaskCommandService commandService;
    private final ObjectMapper objectMapper;

    public ValidateLddParametersTool(PreDefenseTaskCommandService commandService,
                                     ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "datasetId", "可用数据集 ID", true);
        AssistantSchemas.integer(schema, "algorithmId", "已启用的 LDD 算法 ID", true);
        AssistantSchemas.integer(schema, "hyperId", "可选超参数模板 ID", false);
        AssistantSchemas.object(schema, "customParams",
                "待校验的 LDD 参数对象，例如 batch_size、num_epochs；未知键会被拒绝", false);
        return new AssistantToolDescriptor("validate_ldd_parameters",
                "只读校验 LDD 数据集、算法、超参数模板和参数范围；不创建任务、不生成确认凭据",
                false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        try {
            AssistantArgs.rejectUnknown(arguments,
                    Set.of("datasetId", "algorithmId", "hyperId", "customParams"));
            PreDefenseTaskCommand command = new PreDefenseTaskCommand();
            // The command validator requires a name; it does not affect parameter semantics.
            command.setTaskName("参数校验");
            command.setDatasetId(AssistantArgs.requireLong(arguments, "datasetId"));
            command.setAlgorithmId(AssistantArgs.requireLong(arguments, "algorithmId"));
            command.setHyperId(AssistantArgs.optionalLong(arguments, "hyperId"));
            command.setCustomParams(AssistantArgs.optionalObjectText(arguments, "customParams", 20_000));

            commandService.validateDraft(command);
            TaskExecutionPlan plan = commandService.plan(command);

            ObjectNode data = baseData(command, true, "PARAMETERS_VALID", "参数校验通过");
            JsonNode preview = plan.preview();
            data.put("configFingerprint", preview.path("configFingerprint").asText());
            if (preview.has("dataset")) {
                data.set("dataset", preview.get("dataset").deepCopy());
            }
            if (preview.has("algorithm")) {
                data.set("algorithm", preview.get("algorithm").deepCopy());
            }
            if (preview.has("hyper")) {
                data.set("hyper", preview.get("hyper").deepCopy());
            }
            ObjectNode normalized = preview.get("finalParameters") instanceof ObjectNode params
                    ? params.deepCopy() : objectMapper.createObjectNode();
            // The LDD policy may carry data_path from catalog defaults; it is not needed to
            // prove parameter validity and must not be copied into a user-facing answer.
            normalized.remove("data_path");
            data.set("normalizedParameters", normalized);
            data.put("sideEffect", "none");
            data.put("confirmationCreated", false);
            String summary = "参数校验通过：算法 #" + command.getAlgorithmId()
                    + "、数据集 #" + command.getDatasetId()
                    + "；未创建任务、未生成确认凭据。";
            return new AssistantToolResult(summary, data, null, null, null, null, null);
        } catch (TaskCommandException exception) {
            return invalid(arguments, exception.getCode() == null
                    ? "PARAMETER_INVALID" : exception.getCode(), exception.getMessage());
        } catch (AssistantException exception) {
            return invalid(arguments, exception.getCode(), exception.getMessage());
        }
    }

    private AssistantToolResult invalid(JsonNode arguments, String code, String message) {
        ObjectNode data = baseData(arguments, false, code, message);
        data.put("sideEffect", "none");
        data.put("confirmationCreated", false);
        String summary = "参数校验未通过（" + code + "）：" + message + "；未创建任务、未生成确认凭据。";
        return new AssistantToolResult(summary, data, null, null, null, null, null);
    }

    private ObjectNode baseData(PreDefenseTaskCommand command, boolean valid, String code, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("valid", valid);
        data.put("code", code);
        data.put("message", message);
        if (command.getDatasetId() != null) {
            data.put("datasetId", command.getDatasetId());
        }
        if (command.getAlgorithmId() != null) {
            data.put("algorithmId", command.getAlgorithmId());
        }
        if (command.getHyperId() != null) {
            data.put("hyperId", command.getHyperId());
        }
        data.put("parameterSource", "LDD_CONFIGURATION_POLICY");
        return data;
    }

    private ObjectNode baseData(JsonNode arguments, boolean valid, String code, String message) {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("valid", valid);
        data.put("code", code);
        data.put("message", message);
        JsonNode datasetId = arguments == null ? null : arguments.get("datasetId");
        JsonNode algorithmId = arguments == null ? null : arguments.get("algorithmId");
        JsonNode hyperId = arguments == null ? null : arguments.get("hyperId");
        if (datasetId != null && datasetId.isIntegralNumber()) {
            data.put("datasetId", datasetId.asLong());
        }
        if (algorithmId != null && algorithmId.isIntegralNumber()) {
            data.put("algorithmId", algorithmId.asLong());
        }
        if (hyperId != null && hyperId.isIntegralNumber()) {
            data.put("hyperId", hyperId.asLong());
        }
        data.put("parameterSource", "LDD_CONFIGURATION_POLICY");
        return data;
    }
}
