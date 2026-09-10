package me.zhengjie.modules.system.service.policy;

import com.alibaba.fastjson2.JSONObject;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Admission policy for local LDD execution.
 *
 * <p>The current deployment is a single Spring Boot instance, so admission is
 * serialized in this JVM. A future distributed worker should move this policy
 * to a Redis or queue-backed lease.</p>
 */
@Component
public class TaskExecutionQuotaPolicy {

    private final PreDefenseMapper preDefenseMapper;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final int maxRunningTasks;
    private final int maxRunningTasksPerUser;
    private final long maxTaskTimeoutSeconds;

    public TaskExecutionQuotaPolicy(
            PreDefenseMapper preDefenseMapper,
            @Qualifier("taskAsync") ThreadPoolTaskExecutor taskExecutor,
            @Value("${security-model.executor.quota.max-running-tasks:4}") int maxRunningTasks,
            @Value("${security-model.executor.quota.max-running-tasks-per-user:2}") int maxRunningTasksPerUser,
            @Value("${security-model.executor.quota.max-task-timeout-seconds:3600}") long maxTaskTimeoutSeconds) {
        this.preDefenseMapper = preDefenseMapper;
        this.taskExecutor = taskExecutor;
        this.maxRunningTasks = maxRunningTasks;
        this.maxRunningTasksPerUser = maxRunningTasksPerUser;
        this.maxTaskTimeoutSeconds = maxTaskTimeoutSeconds;
    }

    /**
     * Validates and returns the quota snapshot used by the current attempt.
     */
    public synchronized JSONObject validateAndBuildSnapshot(PreDefense task) {
        List<PreDefense> runningTasks = runningTasks();
        int runningCount = runningTasks.size();
        int userRunningCount = countForUser(runningTasks, task == null ? null : task.getCreateBy());

        if (!isAcceptingTasks()) {
            throw new BadRequestException("TASK_QUEUE_UNAVAILABLE: 当前执行池不再接收新任务");
        }
        if (maxRunningTasks > 0 && runningCount >= maxRunningTasks) {
            throw new BadRequestException("TASK_QUOTA_EXCEEDED: 当前运行任务数已达到系统上限 "
                    + maxRunningTasks);
        }
        if (maxRunningTasksPerUser > 0 && userRunningCount >= maxRunningTasksPerUser) {
            throw new BadRequestException("TASK_USER_QUOTA_EXCEEDED: 当前用户运行任务数已达到上限 "
                    + maxRunningTasksPerUser);
        }

        JSONObject snapshot = new JSONObject();
        snapshot.put("maxRunningTasks", maxRunningTasks);
        snapshot.put("maxRunningTasksPerUser", maxRunningTasksPerUser);
        snapshot.put("maxTaskTimeoutSeconds", maxTaskTimeoutSeconds);
        snapshot.put("runningTasksBeforeClaim", runningCount);
        snapshot.put("userRunningTasksBeforeClaim", userRunningCount);
        snapshot.put("executorAcceptingTasks", true);
        return snapshot;
    }

    public synchronized Map<String, Object> getStatus() {
        List<PreDefense> runningTasks = runningTasks();
        int runningCount = runningTasks.size();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("maxRunningTasks", maxRunningTasks);
        status.put("runningTasks", runningCount);
        status.put("remainingTaskSlots", remainingSlots(maxRunningTasks, runningCount));
        status.put("maxRunningTasksPerUser", maxRunningTasksPerUser);
        status.put("maxTaskTimeoutSeconds", maxTaskTimeoutSeconds);
        status.put("acceptingTasks", isAcceptingTasks());
        return status;
    }

    private List<PreDefense> runningTasks() {
        List<PreDefense> tasks = preDefenseMapper.findByStatus(PreDefenseTaskStatePolicy.RUNNING);
        return tasks == null ? Collections.emptyList() : tasks;
    }

    private int countForUser(List<PreDefense> runningTasks, String createBy) {
        if (createBy == null) {
            return 0;
        }
        int count = 0;
        for (PreDefense task : runningTasks) {
            if (createBy.equals(task.getCreateBy())) {
                count++;
            }
        }
        return count;
    }

    private boolean isAcceptingTasks() {
        ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
        return executor != null && !executor.isShutdown() && !executor.isTerminated();
    }

    private int remainingSlots(int limit, int current) {
        return limit <= 0 ? -1 : Math.max(0, limit - current);
    }
}
