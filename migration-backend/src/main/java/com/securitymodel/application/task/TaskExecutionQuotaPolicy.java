package com.securitymodel.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@Component
public class TaskExecutionQuotaPolicy {
    private final PreDefenseTaskMapper taskMapper;
    private final ThreadPoolTaskExecutor executor;
    private final ObjectMapper objectMapper;
    private final int maxRunning;
    private final int maxPerUser;
    private final long maxTimeoutSeconds;

    public TaskExecutionQuotaPolicy(
            PreDefenseTaskMapper taskMapper,
            @Qualifier("taskAsync") ThreadPoolTaskExecutor executor,
            ObjectMapper objectMapper,
            @Value("${security-model.executor.quota.max-running-tasks:4}") int maxRunning,
            @Value("${security-model.executor.quota.max-running-tasks-per-user:2}") int maxPerUser,
            @Value("${security-model.executor.quota.max-task-timeout-seconds:3600}") long maxTimeoutSeconds) {
        this.taskMapper = taskMapper;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.maxRunning = maxRunning;
        this.maxPerUser = maxPerUser;
        this.maxTimeoutSeconds = maxTimeoutSeconds;
    }

    public synchronized ObjectNode validateAndBuildSnapshot(PreDefenseTask task) {
        List<PreDefenseTask> running = taskMapper.selectList(
                Wrappers.<PreDefenseTask>lambdaQuery().eq(PreDefenseTask::getStatus, "running"));
        int userCount = (int) running.stream()
                .filter(item -> task.getCreateBy() != null && task.getCreateBy().equals(item.getCreateBy()))
                .count();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        if (pool.isShutdown() || pool.isTerminated()) {
            throw new TaskCommandException("TASK_QUEUE_UNAVAILABLE: 当前执行池不再接收新任务");
        }
        if (maxRunning > 0 && running.size() >= maxRunning) {
            throw new TaskCommandException("TASK_QUOTA_EXCEEDED: 当前运行任务数已达到系统上限 " + maxRunning);
        }
        if (maxPerUser > 0 && userCount >= maxPerUser) {
            throw new TaskCommandException("TASK_USER_QUOTA_EXCEEDED: 当前用户运行任务数已达到上限 " + maxPerUser);
        }
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("maxRunningTasks", maxRunning);
        snapshot.put("maxRunningTasksPerUser", maxPerUser);
        snapshot.put("maxTaskTimeoutSeconds", maxTimeoutSeconds);
        snapshot.put("runningTasksBeforeClaim", running.size());
        snapshot.put("userRunningTasksBeforeClaim", userCount);
        snapshot.put("executorAcceptingTasks", true);
        return snapshot;
    }

    public Map<String, Object> status() {
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        List<PreDefenseTask> running = taskMapper.selectList(
                Wrappers.<PreDefenseTask>lambdaQuery().eq(PreDefenseTask::getStatus, "running"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxRunningTasks", maxRunning);
        result.put("runningTasks", running.size());
        result.put("remainingTaskSlots", maxRunning <= 0 ? -1 : Math.max(0, maxRunning - running.size()));
        result.put("maxRunningTasksPerUser", maxPerUser);
        result.put("maxTaskTimeoutSeconds", maxTimeoutSeconds);
        result.put("acceptingTasks", !pool.isShutdown() && !pool.isTerminated());
        return result;
    }
}
