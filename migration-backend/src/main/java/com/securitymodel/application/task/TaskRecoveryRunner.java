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
    private final GpuLeaseService gpuLeaseService;
    private final com.securitymodel.infrastructure.executor.AttemptProcessRegistry processRegistry;

    public TaskRecoveryRunner(PreDefenseTaskMapper taskMapper, GpuLeaseService gpuLeaseService,
                              com.securitymodel.infrastructure.executor.AttemptProcessRegistry processRegistry) {
        this.taskMapper = taskMapper;
        this.gpuLeaseService = gpuLeaseService;
        this.processRegistry = processRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedTasks() {
        var running = taskMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.securitymodel.domain.task.PreDefenseTask>lambdaQuery()
                        .eq(com.securitymodel.domain.task.PreDefenseTask::getStatus, "running"));
        int recovered = 0;
        for (var task : running) {
            // A crashed JVM may leave its Python child alive on Windows. Never release the GPU
            // lease until that child is verified dead, or a new task could enter CUDA concurrently.
            var outcome = processRegistry.reapIfOrphaned(task.getTaskId(), task.getCurrentAttempt());
            if (outcome == null) {
                outcome = com.securitymodel.infrastructure.executor.AttemptProcessRegistry.ReapOutcome.UNKNOWN;
            }
            // Unknown (including a null probe result) is treated as "not proven gone".
            boolean processGone = outcome
                    == com.securitymodel.infrastructure.executor.AttemptProcessRegistry.ReapOutcome.NOT_STARTED
                    || outcome
                    == com.securitymodel.infrastructure.executor.AttemptProcessRegistry.ReapOutcome.EXITED
                    || outcome
                    == com.securitymodel.infrastructure.executor.AttemptProcessRegistry.ReapOutcome.REAPED;
            String reason = "应用重启后未恢复的执行任务，可安全重试";
            recovered += taskMapper.failOrphanedForRun(
                    task.getTaskId(), task.getCurrentRunId(),
                    "{\"executor\":\"security-model-platform\",\"stage\":\"recovery\",\"status\":\"failed\","
                            + "\"message\":\"应用重启导致任务中断\",\"error_code\":\"EXECUTOR_RESTARTED\","
                            + "\"failure_reason\":\"" + reason + "\",\"artifacts\":{}}",
                    LocalDateTime.now());
            // The JVM that owned the GPU lease is gone; release it so a retry does not wait
            // for the TTL. The call is keyed by task+run, so it cannot clear a newer lease.
            if (processGone) {
                gpuLeaseService.releaseByRun(task.getTaskId(), task.getCurrentRunId());
            } else {
                // TTL expiry proves nothing about a possibly still-running Python process.
                gpuLeaseService.markBlocked(task.getTaskId(), task.getCurrentRunId(),
                        "ORPHANED_PROCESS_" + outcome.name());
                log.error("遗留执行进程状态未确认，GPU 槽位已隔离: taskId={}, runId={}, outcome={}",
                        task.getTaskId(), task.getCurrentRunId(), outcome);
            }
        }
        if (!running.isEmpty()) {
            log.info("任务恢复扫描完成: scanned={}, recovered={}", running.size(), recovered);
        }
    }
}
