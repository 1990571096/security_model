package com.securitymodel.interfaces.web;

import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.application.task.IdempotencyResult;
import com.securitymodel.application.task.RequestIdempotencyService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/pre-defense")
public class PreDefenseTaskController {

    private final PreDefenseTaskCommandService taskCommandService;
    private final RequestIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public PreDefenseTaskController(PreDefenseTaskCommandService taskCommandService,
                                    RequestIdempotencyService idempotencyService,
                                    ObjectMapper objectMapper) {
        this.taskCommandService = taskCommandService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{taskId}")
    public PreDefenseTask detail(@PathVariable Long taskId) {
        return taskCommandService.findRequired(taskId);
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody PreDefenseTaskCommand command) {
        IdempotencyResult result = idempotencyService.execute("task.create", idempotencyKey, command, null,
                () -> new IdempotencyResult(201, objectMapper.valueToTree(taskCommandService.create(command))));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PutMapping
    public PreDefenseTask update(@RequestBody PreDefenseTaskCommand command) {
        return taskCommandService.update(requiredCommandId(command), command);
    }

    @PutMapping("/{taskId}")
    public PreDefenseTask updateByPath(
            @PathVariable Long taskId,
            @RequestBody PreDefenseTaskCommand command) {
        return taskCommandService.update(taskId, command);
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@RequestBody Collection<Long> taskIds) {
        taskCommandService.delete(taskIds);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/start/{taskId}")
    public ResponseEntity<JsonNode> start(
            @PathVariable Long taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        IdempotencyResult result = idempotencyService.execute("task.start", idempotencyKey,
                Map.of("taskId", taskId), taskId,
                () -> new IdempotencyResult(200, objectMapper.valueToTree(taskCommandService.start(taskId))));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/retry/{taskId}")
    public ResponseEntity<JsonNode> retry(
            @PathVariable Long taskId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        IdempotencyResult result = idempotencyService.execute("task.retry", idempotencyKey,
                Map.of("taskId", taskId), taskId,
                () -> new IdempotencyResult(200, objectMapper.valueToTree(taskCommandService.retry(taskId))));
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @PostMapping("/cancel/{taskId}")
    public Map<String, Object> cancel(@PathVariable Long taskId) {
        return taskCommandService.cancel(taskId);
    }

    private Long requiredCommandId(PreDefenseTaskCommand command) {
        if (command == null || command.getTaskId() == null) {
            throw new com.securitymodel.application.task.TaskCommandException("任务ID不能为空");
        }
        return command.getTaskId();
    }
}
