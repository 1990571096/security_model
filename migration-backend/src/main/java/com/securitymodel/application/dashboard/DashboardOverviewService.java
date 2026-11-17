package com.securitymodel.application.dashboard;

import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.TaskProgressQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace overview built only from rows the caller is allowed to read.
 *
 * <p>The dashboard deliberately does not invent experiment metrics. Attack success rate and
 * clean accuracy need a model-evaluation pipeline that this project does not run, so the
 * response states that metrics are not collected instead of filling the cards with demo
 * numbers. Task counts, the daily trend, resource counts and report readiness are real
 * MySQL aggregates and are scoped exactly like {@code /api/task-progress}.</p>
 */
@Service
public class DashboardOverviewService {

    private static final List<String> DEFENSE_STAGES = List.of("pre", "during", "post");
    private static final int RECENT_TASK_LIMIT = 5;

    private final TaskProgressQueryService taskProgressQueryService;
    private final ResourceQueryService resourceQueryService;

    public DashboardOverviewService(
            TaskProgressQueryService taskProgressQueryService,
            ResourceQueryService resourceQueryService) {
        this.taskProgressQueryService = taskProgressQueryService;
        this.resourceQueryService = resourceQueryService;
    }

    public Map<String, Object> overview(int days) {
        if (days < 1 || days > 30) {
            throw new IllegalArgumentException("趋势天数须为 1 到 30");
        }
        Map<String, Object> statistics = taskProgressQueryService.statistics();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scope", statistics.get("scope"));
        result.put("sampledAt", LocalDateTime.now().toString());
        result.put("tasks", tasks(statistics));
        result.put("taskTrend", taskProgressQueryService.trend(days));
        result.put("resources", resources());
        result.put("reportReadiness", reportReadiness(statistics));
        result.put("recentTasks", recentTasks());
        result.put("metrics", metrics());
        return result;
    }

    /**
     * Minimal projection for the dashboard: no cleaning result, parameters, owner or paths,
     * so the overview cannot become a side channel for task detail data.
     */
    private List<Map<String, Object>> recentTasks() {
        return taskProgressQueryService.findPage(null, null, 1, RECENT_TASK_LIMIT).records().stream()
                .map(view -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("taskId", view.getTaskId());
                    row.put("taskName", view.getTaskName());
                    row.put("datasetName", view.getDatasetName());
                    row.put("algorithmName", view.getAlgorithmName());
                    row.put("status", view.getStatus());
                    row.put("progress", view.getProgress());
                    row.put("createTime", view.getCreateTime());
                    row.put("endTime", view.getEndTime());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> tasks(Map<String, Object> statistics) {
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("total", statistics.get("totalCount"));
        for (String status : List.of("pending", "running", "success", "failed", "canceled")) {
            tasks.put(status, statistics.get(status + "Count"));
        }
        return tasks;
    }

    private Map<String, Object> resources() {
        List<Dataset> datasets = resourceQueryService.findDatasets();
        List<Algorithm> algorithms = resourceQueryService.findAlgorithms();
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("datasetCount", datasets.size());
        resources.put("enabledDatasetCount", datasets.stream()
                .filter(dataset -> Boolean.TRUE.equals(dataset.getEnabled())).count());
        resources.put("algorithmCount", algorithms.size());
        resources.put("enabledAlgorithmCount", algorithms.stream()
                .filter(algorithm -> Boolean.TRUE.equals(algorithm.getEnabled())).count());
        Map<String, Long> algorithmsByStage = new LinkedHashMap<>();
        for (String stage : DEFENSE_STAGES) {
            algorithmsByStage.put(stage, algorithms.stream()
                    .filter(algorithm -> stage.equals(algorithm.getDefenseStage())).count());
        }
        resources.put("algorithmsByStage", algorithmsByStage);
        return resources;
    }

    /**
     * "Report readiness" is limited to what this table can prove: a successful task with a
     * non-blank cleaning result. It is not a count of generated PDF/Word reports.
     */
    private Map<String, Object> reportReadiness(Map<String, Object> statistics) {
        long success = number(statistics.get("successCount"));
        long withResult = taskProgressQueryService.successWithResultCount();
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("successfulTasks", success);
        readiness.put("tasksWithCleaningResult", withResult);
        readiness.put("ratio", success == 0 ? null : round(withResult * 100.0 / success));
        return readiness;
    }

    private Map<String, Object> metrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("available", false);
        metrics.put("retentionRate", null);
        metrics.put("attackSuccessRate", null);
        metrics.put("note", "工作台不聚合正式实验指标：攻击成功率与干净准确率需要模型评估链路，当前未采集；"
                + "清洗结果可在任务报告中查看。演示常量已移除。");
        return metrics;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
