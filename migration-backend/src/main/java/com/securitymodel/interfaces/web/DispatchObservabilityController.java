package com.securitymodel.interfaces.web;

import com.securitymodel.application.task.DispatchOperationsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin-only R4 dispatch observability; reuses the existing admin authorization. */
@RestController
@RequestMapping("/api/observability/dispatch")
public class DispatchObservabilityController {

    private final DispatchOperationsService operationsService;

    public DispatchObservabilityController(DispatchOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping
    public Map<String, Object> summary() {
        return operationsService.summary();
    }

    @GetMapping("/dead")
    public List<Map<String, Object>> dead(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return operationsService.dead(limit);
    }

    @PostMapping("/dead/{messageId}/replay")
    public Map<String, Object> replayDead(@PathVariable String messageId) {
        return operationsService.replayDead(messageId);
    }
}
