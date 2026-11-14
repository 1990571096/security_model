package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Report summary with an explicit metric whitelist. Raw result JSON, artifact paths,
 * data paths, logs and another user's report never reach the assistant response.
 */
@Component
public class ReportSummaryTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final CleanSubsetExportService exportService;
    private final ObjectMapper objectMapper;

    public ReportSummaryTool(TaskAccessGuard taskAccessGuard, CleanSubsetExportService exportService,
                             ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "taskId", "当前用户自己的任务 ID", true);
        return new AssistantToolDescriptor("get_my_report_summary",
                "读取当前用户自己的报告摘要、真实清洗纯度和可导出状态；不返回路径、日志或原始结果",
                false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);

        ObjectNode data = objectMapper.createObjectNode();
        data.put("taskId", task.getTaskId());
        data.put("taskName", KnowledgeSanitizer.forModel(task.getTaskName(), 120));
        data.put("status", task.getStatus());
        data.put("progress", task.getProgress());
        if (task.getCleaningResult() != null && !task.getCleaningResult().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(task.getCleaningResult());
                copyText(root, data, "error_code", "errorCode");
                copyText(root, data, "failure_reason", "failureReason");
                JsonNode artifacts = root.path("artifacts");
                if (artifacts.has("selection_kind")) {
                    data.put("selectionKind", artifacts.path("selection_kind").asText());
                }
                JsonNode metrics = root.path("metrics");
                if (metrics.has("clean_purity_percent_by_isolation_ratio")) {
                    data.set("cleanPurityPercentByIsolationRatio",
                            metrics.get("clean_purity_percent_by_isolation_ratio"));
                }
                if (metrics.has("selected_sample_count_by_isolation_ratio")) {
                    data.set("selectedSampleCountByIsolationRatio",
                            metrics.get("selected_sample_count_by_isolation_ratio"));
                }
                JsonNode config = root.path("effective_config");
                if (config.has("dataset")) {
                    data.put("dataset", config.path("dataset").asText());
                }
            } catch (Exception ignored) {
                data.put("resultParseStatus", "unavailable");
            }
        }
        Map<String, Object> export = exportService.describe(task);
        data.put("cleanSubsetExportAvailable", Boolean.TRUE.equals(export.get("available")));
        if (export.get("isolationRatios") instanceof java.util.List<?> ratios) {
            data.set("exportIsolationRatios", objectMapper.valueToTree(ratios));
        }

        String summary = "任务 #" + task.getTaskId() + " 状态 " + task.getStatus()
                + (data.has("cleanPurityPercentByIsolationRatio")
                ? "，有真实清洗纯度指标" : "，暂无清洗纯度指标")
                + "；不返回原始结果、日志或文件路径。";
        return new AssistantToolResult(summary, data, null, null, task.getTaskId(),
                task.getCurrentRunId(), null);
    }

    private void copyText(JsonNode root, ObjectNode target, String source, String targetField) {
        if (root.has(source) && !root.path(source).asText().isBlank()) {
            target.put(targetField, KnowledgeSanitizer.forModel(root.path(source).asText(), 300));
        }
    }
}
