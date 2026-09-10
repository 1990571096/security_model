package me.zhengjie.modules.system.service.impl;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import me.zhengjie.modules.system.service.policy.PreDefenseTaskStatePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Closes task executions that cannot survive a local application restart.
 *
 * <p>The Python process is owned by the application process in the current
 * deployment model. After a restart there is no reliable way to reattach to
 * the old process, so leaving the database row in {@code running} would make
 * the task impossible to retry or explain. The runner turns only the matching
 * orphaned attempt into a retryable failure.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreDefenseTaskRecoveryRunner implements ApplicationRunner {

    private final PreDefenseMapper preDefenseMapper;

    @Value("${security-model.executor.recover-stale-running-tasks:true}")
    private boolean recoverStaleRunningTasks;

    @Override
    public void run(ApplicationArguments args) {
        if (!recoverStaleRunningTasks) {
            log.info("Skip recovery of stale pre-defense tasks by configuration");
            return;
        }

        List<PreDefense> runningTasks = preDefenseMapper.findByStatus(
                PreDefenseTaskStatePolicy.RUNNING);
        if (runningTasks == null || runningTasks.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (PreDefense task : runningTasks) {
            LocalDateTime endTime = LocalDateTime.now();
            String result = buildRecoveryResult(task, endTime);
            int updated = preDefenseMapper.failOrphanedForRun(
                    task.getTaskId(),
                    task.getCurrentRunId(),
                    result,
                    endTime);
            if (updated == 1) {
                recovered++;
                log.warn("Recovered stale pre-defense task as failed: taskId={}, runId={}, attempt={}",
                        task.getTaskId(), task.getCurrentRunId(), task.getCurrentAttempt());
            }
        }
        log.info("Pre-defense startup recovery completed: scanned={}, recovered={}",
                runningTasks.size(), recovered);
    }

    private String buildRecoveryResult(PreDefense task, LocalDateTime endTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "security-model-platform");
        result.put("stage", "pre_defense");
        result.put("status", PreDefenseTaskStatePolicy.FAILED);
        result.put("message", "应用重启后未能恢复原执行进程，任务已收敛为可重试失败");
        result.put("run_id", task.getCurrentRunId());
        result.put("attempt", task.getCurrentAttempt());
        result.put("error_code", "EXECUTOR_RESTARTED");
        result.put("failure_reason", "应用进程重启或异常退出，原 Python 执行进程已失去运行上下文");
        result.put("started_at", task.getStartTime() == null ? null : task.getStartTime().toString());
        result.put("finished_at", endTime.toString());

        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("available", false);
        artifacts.put("message", "应用重启恢复未生成新的任务产物；原执行目录如存在仍保留在执行工作区");
        result.put("artifacts", artifacts);
        return JSON.toJSONString(result);
    }
}
