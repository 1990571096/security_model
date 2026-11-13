package com.securitymodel.application.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Task observability queries. Every list, page and statistic is scoped to the current owner;
 * only an admin with cross-user read enabled sees the whole database (see ADR-013).
 */
@Service
public class TaskProgressQueryService {

    private static final long NO_OWNER_SENTINEL = -1L;
    private static final String DEMO_ORIGIN = "demo";

    private final PreDefenseTaskMapper taskMapper;
    private final ResourceQueryService resourceQueryService;
    private final CurrentUserService currentUserService;
    private final TaskAccessGuard taskAccessGuard;

    public TaskProgressQueryService(
            PreDefenseTaskMapper taskMapper,
            ResourceQueryService resourceQueryService,
            CurrentUserService currentUserService,
            TaskAccessGuard taskAccessGuard) {
        this.taskMapper = taskMapper;
        this.resourceQueryService = resourceQueryService;
        this.currentUserService = currentUserService;
        this.taskAccessGuard = taskAccessGuard;
    }

    public List<TaskProgressView> findTasks(String status, String blurry) {
        return toViews(taskMapper.selectList(query(status, blurry)));
    }

    public TaskPage findPage(String status, String blurry, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new IllegalArgumentException("页码须大于 0，页大小须为 1 到 100");
        }
        Page<PreDefenseTask> result = taskMapper.selectPage(
                new Page<>(page, size), query(status, blurry));
        return new TaskPage(toViews(result.getRecords()), result.getTotal(), page, size);
    }

    public record TaskPage(List<TaskProgressView> records, long total, int page, int size) {}

    private LambdaQueryWrapper<PreDefenseTask> query(String status, String blurry) {
        var query = Wrappers.<PreDefenseTask>lambdaQuery().orderByDesc(PreDefenseTask::getTaskId);
        if (status != null && !status.isBlank()) {
            query.eq(PreDefenseTask::getStatus, status);
        }
        if (blurry != null && !blurry.isBlank()) {
            query.and(wrapper -> wrapper
                    .like(PreDefenseTask::getTaskName, blurry)
                    .or()
                    .like(PreDefenseTask::getDescription, blurry));
        }
        return applyScope(query);
    }

    /**
     * Restricts the query to rows the caller may see. A caller without a stable user id
     * (legacy token, or an unattributed session) gets an owner sentinel that matches nothing.
     */
    private LambdaQueryWrapper<PreDefenseTask> applyScope(LambdaQueryWrapper<PreDefenseTask> query) {
        if (taskAccessGuard.canReadAcrossUsers()) {
            return query;
        }
        query.ne(PreDefenseTask::getDataOrigin, DEMO_ORIGIN);
        Long userId = currentUserService.userId();
        return query.eq(PreDefenseTask::getOwnerUserId, userId == null ? NO_OWNER_SENTINEL : userId);
    }

    public TaskProgressView findTask(Long taskId) {
        PreDefenseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }
        if (!taskAccessGuard.canRead(task)) {
            throw new AccessDeniedException("无权访问该任务");
        }
        return toViews(List.of(task)).get(0);
    }

    public Map<String, Object> statistics() {
        Map<String, Object> statusStats = new LinkedHashMap<>();
        for (String status : List.of("pending", "running", "success", "failed", "canceled")) {
            statusStats.put(status, taskMapper.selectCount(scopedCount(status)));
        }
        long total = taskMapper.selectCount(applyScope(Wrappers.<PreDefenseTask>lambdaQuery()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typeStats", Map.of("pre_defense", total));
        result.put("statusStats", statusStats);
        result.put("runningCount", statusStats.get("running"));
        result.put("totalCount", total);
        result.put("pendingCount", statusStats.get("pending"));
        result.put("successCount", statusStats.get("success"));
        result.put("failedCount", statusStats.get("failed"));
        result.put("canceledCount", statusStats.get("canceled"));
        result.put("scope", taskAccessGuard.canReadAcrossUsers() ? "all-users" : "current-user");
        return result;
    }

    private LambdaQueryWrapper<PreDefenseTask> scopedCount(String status) {
        return applyScope(Wrappers.<PreDefenseTask>lambdaQuery().eq(PreDefenseTask::getStatus, status));
    }

    private List<TaskProgressView> toViews(List<PreDefenseTask> tasks) {
        if (tasks.isEmpty()) return List.of();
        Set<Long> algorithmIds = tasks.stream().map(PreDefenseTask::getAlgorithmId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> datasetIds = tasks.stream().map(PreDefenseTask::getDatasetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Algorithm> algorithms = resourceQueryService.findAlgorithmsByIds(algorithmIds);
        Map<Long, Dataset> datasets = resourceQueryService.findDatasetsByIds(datasetIds);
        return tasks.stream().map(task -> toView(task, algorithms, datasets)).toList();
    }

    private TaskProgressView toView(PreDefenseTask task, Map<Long, Algorithm> algorithms,
                                    Map<Long, Dataset> datasets) {
        TaskProgressView view = new TaskProgressView();
        view.setTaskId(task.getTaskId());
        view.setTaskName(task.getTaskName());
        view.setDescription(task.getDescription());
        view.setStatus(task.getStatus());
        view.setProgress(task.getProgress());
        view.setStartTime(task.getStartTime());
        view.setEndTime(task.getEndTime());
        view.setCreateTime(task.getCreateTime());
        view.setCreateBy(task.getCreateBy());
        view.setDatasetId(task.getDatasetId());
        view.setOutputDatasetId(task.getOutputDatasetId());
        view.setCleaningResult(task.getCleaningResult());
        view.setAlgorithmId(task.getAlgorithmId());
        view.setHyperId(task.getHyperId());
        view.setCustomParams(task.getCustomParams());

        Algorithm algorithm = algorithms.get(task.getAlgorithmId());
        if (algorithm != null) view.setAlgorithmName(algorithm.getAlgorithmName());
        Dataset dataset = datasets.get(task.getDatasetId());
        if (dataset != null) view.setDatasetName(dataset.getDatasetName());
        if (task.getStartTime() != null) {
            LocalDateTime end = task.getEndTime() == null ? LocalDateTime.now() : task.getEndTime();
            view.setRunningDuration(Math.max(0, Duration.between(task.getStartTime(), end).toMillis()));
        } else {
            view.setRunningDuration(0L);
        }
        return view;
    }
}
