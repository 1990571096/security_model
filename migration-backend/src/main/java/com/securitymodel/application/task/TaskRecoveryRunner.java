package com.securitymodel.application.task;

import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Converts interrupted running records into explicit retryable failures after a restart.
 */
@Component
public class TaskRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRecoveryRunner.class);
    private final PreDefenseTaskMapper taskMapper;

    public TaskRecoveryRunner(PreDefenseTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedTasks() {
        var running = taskMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.securitymodel.domain.task.PreDefenseTask>lambdaQuery()
                        .eq(com.securitymodel.domain.task.PreDefenseTask::getStatus, "running"));
        int recovered = 0;
        for (var task : running) {
            String reason = "应用重启后未恢复的执行任务，可安全重试";
            recovered += taskMapper.failOrphanedForRun(
                    task.getTaskId(), task.getCurrentRunId(),
                    "{\"executor\":\"security-model-platform\",\"stage\":\"recovery\",\"status\":\"failed\","
                            + "\"message\":\"应用重启导致任务中断\",\"error_code\":\"EXECUTOR_RESTARTED\","
                            + "\"failure_reason\":\"" + reason + "\",\"artifacts\":{}}",
                    LocalDateTime.now());
        }
        if (!running.isEmpty()) {
            log.info("任务恢复扫描完成: scanned={}, recovered={}", running.size(), recovered);
        }
    }
}
