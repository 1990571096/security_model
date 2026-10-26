package com.securitymodel.interfaces.web;

import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/api/pre-defense")
public class PreDefenseTaskController {

    private final PreDefenseTaskCommandService taskCommandService;

    public PreDefenseTaskController(PreDefenseTaskCommandService taskCommandService) {
        this.taskCommandService = taskCommandService;
    }

    @GetMapping("/{taskId}")
    public PreDefenseTask detail(@PathVariable Long taskId) {
        return taskCommandService.findRequired(taskId);
    }

    @PostMapping
    public ResponseEntity<PreDefenseTask> create(@RequestBody PreDefenseTaskCommand command) {
        return ResponseEntity.status(201).body(taskCommandService.create(command));
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
    public Map<String, Object> start(@PathVariable Long taskId) {
        return taskCommandService.start(taskId);
    }

    @PostMapping("/retry/{taskId}")
    public Map<String, Object> retry(@PathVariable Long taskId) {
        return taskCommandService.retry(taskId);
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
