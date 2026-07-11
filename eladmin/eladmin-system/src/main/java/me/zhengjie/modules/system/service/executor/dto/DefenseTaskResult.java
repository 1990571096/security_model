package me.zhengjie.modules.system.service.executor.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base result envelope shared by mock and real algorithm executors.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefenseTaskResult {

    private String executor;

    private String stage;

    private String status;

    private String message;

    private String errorCode;

    private String failureReason;

    private String startedAt;

    private String finishedAt;

    private DefenseTaskArtifact artifact;

    public Map<String, Object> toJsonMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", executor);
        result.put("stage", stage);
        result.put("status", status);
        result.put("message", message);
        if (errorCode != null) {
            result.put("error_code", errorCode);
        }
        if (failureReason != null) {
            result.put("failure_reason", failureReason);
        }
        result.put("started_at", startedAt);
        result.put("finished_at", finishedAt);
        if (artifact != null) {
            Map<String, Object> artifacts = new LinkedHashMap<>();
            artifacts.put("type", artifact.getType());
            artifacts.put("available", artifact.getAvailable());
            artifacts.put("path", artifact.getPath());
            artifacts.put("message", artifact.getMessage());
            result.put("artifacts", artifacts);
        }
        return result;
    }
}
