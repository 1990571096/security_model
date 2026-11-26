package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filters recorded isolation ratios by a user-stated minimum sample count. No derived sample counts. */
@Component
public class ExperimentSubsetTool implements AssistantTool {

    static final String NAME = "select_isolation_subset";
    private static final int DEFAULT_MINIMUM = 20_000;

    private final TaskAccessGuard taskAccessGuard;
    private final PreDefenseReportQueryService reportService;
    private final CleanSubsetExportService exportService;
    private final ObjectMapper mapper;

    public ExperimentSubsetTool(TaskAccessGuard taskAccessGuard, PreDefenseReportQueryService reportService,
                                CleanSubsetExportService exportService, ObjectMapper mapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.reportService = reportService;
        this.exportService = exportService;
        this.mapper = mapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.identifier(schema, "taskId", "本人有权读取的任务 ID", true);
        AssistantSchemas.integer(schema, "minimumSamples", "至少保留多少个样本", true);
        AssistantSchemas.text(schema, "focus", "可选关注点：purity / sample_count / export", 32, false);
        return new AssistantToolDescriptor(NAME,
                "按用户给定的最小样本数筛选报告已记录的保留比例，并返回真实导出状态；"
                        + "不补算缺失比例，不承诺下载就绪", false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId", "minimumSamples", "focus"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        JsonNode minNode = arguments.get("minimumSamples");
        if (minNode == null || !minNode.isIntegralNumber() || !minNode.canConvertToInt()
                || minNode.asInt() < 0) {
            throw new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST,
                    "minimumSamples 必须是非负整数");
        }
        int minimum = minNode.asInt();
        String focus = AssistantArgs.optionalText(arguments, "focus", 32);
        if (focus != null && !Set.of("purity", "sample_count", "export").contains(focus)) {
            throw invalid("focus 只支持 purity、sample_count、export");
        }
        PreDefenseTask task = taskAccessGuard.requireReadable(taskId);
        Map<String, Object> report = reportService.getReport(taskId);
        Map<?, ?> cleaning = asMap(report.get("cleaningResult"));
        Map<?, ?> metrics = asMap(cleaning == null ? null : cleaning.get("metrics"));
        Map<String, BigDecimal> purity = ratioMap(metrics == null ? null
                : metrics.get("clean_purity_percent_by_isolation_ratio"));
        Map<String, BigDecimal> counts = ratioMap(metrics == null ? null
                : metrics.get("selected_sample_count_by_isolation_ratio"));
        List<CleanSubsetExportTask> exports = new ArrayList<>();
        Object rawExports = report.get("cleanDatasetExports");
        if (rawExports instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof CleanSubsetExportTask export) {
                    exports.add(export);
                }
            }
        }
        Map<String, CleanSubsetExportTask> exportByRatio = new LinkedHashMap<>();
        for (CleanSubsetExportTask export : exports) {
            if (export.getIsolationRatio() != null) {
                exportByRatio.put(String.valueOf(export.getIsolationRatio()), export);
            }
        }

        int eligibleCount = 0;
        int downloadableCount = 0;
        ObjectNode data = mapper.createObjectNode();
        data.put("kind", "subset_selection");
        data.put("taskId", task.getTaskId().toString());
        data.put("taskName", task.getTaskName());
        data.put("runId", task.getCurrentRunId());
        data.put("attempt", task.getCurrentAttempt());
        data.put("minimumSamples", minimum);
        data.put("focus", focus == null ? "export" : focus);
        data.put("reportEndpoint", "GET /api/pre-defense/report/" + task.getTaskId());
        ArrayNode available = data.putArray("available");
        ArrayNode excluded = data.putArray("excluded");
        List<String> ratios = new ArrayList<>(counts.keySet());
        ratios.sort(Comparator.comparing(BigDecimal::new));
        for (String ratio : ratios) {
            BigDecimal count = counts.get(ratio);
            BigDecimal purityValue = purity.get(ratio);
            ObjectNode item = mapper.createObjectNode();
            item.put("isolationRatio", ratio);
            item.put("sampleCount", count.stripTrailingZeros().toPlainString());
            item.put("cleanPurityPercent", purityValue == null
                    ? "未采集" : purityValue.stripTrailingZeros().toPlainString());
            if (count.compareTo(BigDecimal.valueOf(minimum)) >= 0) {
                CleanSubsetExportTask export = exportByRatio.get(ratio);
                Map<String, Object> availability = exportService.availability(task, Integer.valueOf(ratio), export);
                item.put("exportState", String.valueOf(availability.get("exportState")));
                item.put("exportLabel", String.valueOf(availability.get("exportLabel")));
                item.put("eligibleForExport", Boolean.TRUE.equals(availability.get("eligible")));
                item.put("canSubmitExport", Boolean.TRUE.equals(availability.get("canSubmit")));
                item.put("downloadReady", Boolean.TRUE.equals(availability.get("downloadReady")));
                item.put("fileState", String.valueOf(availability.get("fileState")));
                if (!Boolean.TRUE.equals(availability.get("eligible"))) {
                    item.put("exportReason", String.valueOf(availability.get("reason")));
                }
                if (availability.get("exportId") != null) {
                    item.put("exportId", String.valueOf(availability.get("exportId")));
                }
                if (availability.get("exportProgress") instanceof Number number) {
                    item.put("exportProgress", number.intValue());
                }
                if (Boolean.TRUE.equals(availability.get("eligible"))) {
                    eligibleCount++;
                }
                if (Boolean.TRUE.equals(availability.get("downloadReady"))) {
                    downloadableCount++;
                }
                available.add(item);
            } else {
                item.put("reason", "保留样本数低于下限 " + minimum + " 个");
                excluded.add(item);
            }
        }
        data.put("eligibleExportCount", eligibleCount);
        data.put("downloadReadyCount", downloadableCount);
        if (!available.isEmpty()) {
            data.put("recommendedRatio", available.get(0).path("isolationRatio").asText());
        } else {
            data.putNull("recommendedRatio");
            data.put("gap", "报告中没有达到样本下限的已记录比例，不能推荐档位。");
        }
        ObjectNode evidence = data.putObject("evidence");
        evidence.put("taskId", task.getTaskId().toString());
        evidence.put("reportEndpoint", "GET /api/pre-defense/report/" + task.getTaskId());
        evidence.put("page", "/tasks?taskId=" + task.getTaskId() + "&tab=report");
        ArrayNode citations = data.putArray("citations");
        ObjectNode citation = citations.addObject();
        citation.put("citationId", "subset-" + task.getTaskId() + "-" + minimum);
        citation.put("title", "任务 #" + task.getTaskId() + " 报告导出状态");
        citation.put("sourceType", "experiment_report");
        citation.put("sourceKey", "GET /api/pre-defense/report/" + task.getTaskId());
        citation.put("targetPath", "/tasks?taskId=" + task.getTaskId() + "&tab=report");
        citation.put("version", cleaning == null ? "unknown" : String.valueOf(cleaning.get("run_id")));
        citation.put("sectionPath", "cleaningResult.metrics · selected_sample_count_by_isolation_ratio");
        citation.put("startLine", 0);
        citation.put("endLine", 0);
        citation.put("snippet", "样本下限 " + minimum + "；可用档位 "
                + available.size() + " 个，排除 " + excluded.size() + " 个。");
        citation.put("visibility", "owner");

        String summary = "按至少 " + minimum + " 个样本筛选任务 #" + task.getTaskId()
                + "，报告记录中有 " + available.size() + " 个档位满足样本下限，"
                + excluded.size() + " 个被排除；其中 " + eligibleCount
                + " 个具备当前导出资格，" + downloadableCount + " 个已有可读 ZIP。";
        return new AssistantToolResult(summary, data, null, null, task.getTaskId(),
                task.getCurrentRunId(), null);
    }

    private String exportState(CleanSubsetExportTask export) {
        if (export == null) {
            return "not_requested";
        }
        return export.getStatus() == null ? "unknown" : export.getStatus();
    }

    private String exportLabel(CleanSubsetExportTask export) {
        if (export == null) {
            return "尚未导出，可在任务详情页提交";
        }
        return switch (export.getStatus() == null ? "" : export.getStatus()) {
            case "pending" -> "导出排队中";
            case "running" -> "正在生成";
            case "success" -> "下载就绪";
            case "failed" -> "导出失败，可在任务详情页重试";
            default -> "导出状态未知";
        };
    }

    private static Map<String, BigDecimal> ratioMap(Object raw) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        Map<?, ?> map = asMap(raw);
        if (map == null) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Number number) {
                result.put(entry.getKey().toString(), new BigDecimal(number.toString()));
            }
        }
        return result;
    }

    private static Map<?, ?> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? map : null;
    }

    private static AssistantException invalid(String message) {
        return new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST, message);
    }
}
