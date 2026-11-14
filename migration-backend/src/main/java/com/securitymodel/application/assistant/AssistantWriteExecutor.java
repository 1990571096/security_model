package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.task.IdempotencyResult;
import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.application.task.RequestIdempotencyService;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Executes a confirmed write through the existing command services. The confirmation row
 * is consumed in the same transaction, so a failed command leaves the token pending and
 * a committed command leaves a replayable result.
 */
@Service
public class AssistantWriteExecutor {

    private final PreDefenseTaskCommandService commandService;
    private final CleanSubsetExportService exportService;
    private final RequestIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public AssistantWriteExecutor(PreDefenseTaskCommandService commandService,
                                  CleanSubsetExportService exportService,
                                  RequestIdempotencyService idempotencyService,
                                  ObjectMapper objectMapper) {
        this.commandService = commandService;
        this.exportService = exportService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    public IdempotencyResult execute(String operation, Long confirmationId, JsonNode request) {
        Long taskId = request.has("taskId") && request.get("taskId").isIntegralNumber()
                ? request.get("taskId").asLong() : null;
        String idempotencyKey = "assistant-confirm-" + confirmationId;
        return idempotencyService.execute("assistant." + operation, idempotencyKey, request, taskId,
                () -> switch (operation) {
                    case "create" -> create(request);
                    case "start" -> taskAction("start", request);
                    case "cancel" -> taskAction("cancel", request);
                    case "retry" -> taskAction("retry", request);
                    case "export" -> export(request);
                    default -> throw new AssistantException("ASSISTANT_OPERATION_NOT_ALLOWED",
                            org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的写操作");
                });
    }

    private IdempotencyResult create(JsonNode request) {
        PreDefenseTaskCommand command = new PreDefenseTaskCommand();
        command.setTaskName(request.path("taskName").asText());
        command.setDescription(request.has("description") ? request.path("description").asText() : null);
        command.setDatasetId(request.path("datasetId").asLong());
        command.setAlgorithmId(request.path("algorithmId").asLong());
        command.setHyperId(request.has("hyperId") && request.get("hyperId").isIntegralNumber()
                ? request.get("hyperId").asLong() : null);
        command.setCustomParams(request.path("customParams").isObject()
                ? request.path("customParams").toString() : "{}");
        PreDefenseTask task = commandService.create(command);
        return new IdempotencyResult(201, objectMapper.valueToTree(Map.of(
                "taskId", task.getTaskId(),
                "status", task.getStatus(),
                "message", "任务已创建，仍需单独确认启动"
        )));
    }

    private IdempotencyResult taskAction(String operation, JsonNode request) {
        Long taskId = request.path("taskId").asLong();
        Map<String, Object> result = switch (operation) {
            case "start" -> commandService.start(taskId);
            case "cancel" -> commandService.cancel(taskId);
            case "retry" -> commandService.retry(taskId);
            default -> throw new AssistantException("ASSISTANT_OPERATION_NOT_ALLOWED",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的任务操作");
        };
        return new IdempotencyResult(200, objectMapper.valueToTree(result));
    }

    private IdempotencyResult export(JsonNode request) {
        Long taskId = request.path("taskId").asLong();
        Integer ratio = request.has("isolationRatio") && request.get("isolationRatio").isIntegralNumber()
                ? request.get("isolationRatio").asInt() : null;
        CleanSubsetExportTask task = exportService.submit(taskId, ratio);
        return new IdempotencyResult(200, objectMapper.valueToTree(Map.of(
                "exportId", task.getExportId(),
                "taskId", task.getTaskId(),
                "isolationRatio", task.getIsolationRatio(),
                "status", task.getStatus()
        )));
    }
}
