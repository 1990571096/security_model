package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Single recovery coordinator. Startup order is: classify dispatch intents first, replay only
 * provably-unsubmitted pending intents, converge every other execution to an explicit terminal
 * state, and only then open the scheduling readiness gate. The same coordinator owns bounded
 * pending scanning, so no second listener can fail a replayable pending task.
 */
@Component
public class DispatchRecoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DispatchRecoveryCoordinator.class);

    private final PreDefenseTaskMapper taskMapper;
    private final TaskDispatchIntentService intentService;
    private final AttemptProcessRegistry processRegistry;
    private final GpuLeaseService gpuLeaseService;
    private final TaskDispatchCoordinator dispatchCoordinator;
    private final SchedulingReadinessGate readinessGate;
    private final TaskRuntimeMetrics runtimeMetrics;
    private final long pendingScanAgeSeconds;
    private final long pendingExpireSeconds;
    private final int scanBatchSize;

    public DispatchRecoveryCoordinator(
            PreDefenseTaskMapper taskMapper,
            TaskDispatchIntentService intentService,
            AttemptProcessRegistry processRegistry,
            GpuLeaseService gpuLeaseService,
            TaskDispatchCoordinator dispatchCoordinator,
            SchedulingReadinessGate readinessGate,
            TaskRuntimeMetrics runtimeMetrics,
            @Value("${security-model.dispatch.pending-scan-age-seconds:30}") long pendingScanAgeSeconds,
            @Value("${security-model.dispatch.pending-expire-seconds:300}") long pendingExpireSeconds,
            @Value("${security-model.dispatch.scan-batch-size:20}") int scanBatchSize) {
        this.taskMapper = taskMapper;
        this.intentService = intentService;
        this.processRegistry = processRegistry;
        this.gpuLeaseService = gpuLeaseService;
        this.dispatchCoordinator = dispatchCoordinator;
        this.readinessGate = readinessGate;
        this.runtimeMetrics = runtimeMetrics;
        this.pendingScanAgeSeconds = pendingScanAgeSeconds;
        this.pendingExpireSeconds = pendingExpireSeconds;
        this.scanBatchSize = scanBatchSize;
    }

    public void recoverAtStartup() {
        List<PreDefenseTask> running = taskMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<PreDefenseTask>lambdaQuery()
                        .eq(PreDefenseTask::getStatus, "running"));
        int recovered = 0;
        int replayed = 0;
        boolean hadError = false;
        try {
            for (PreDefenseTask task : running) {
                try {
                    TaskDispatchIntent intent = intentService.findByRun(
                            task.getTaskId(), task.getCurrentRunId(), task.getCurrentAttempt());
                    if (intent == null) {
                        // Legacy task from before dispatch intents existed: no way to prove it was
                        // never submitted, so converge it and preserve the R2 process evidence rule.
                        recovered += failLegacy(task);
                        continue;
                    }
                    if (TaskDispatchIntent.PENDING.equals(intent.getStatus())) {
                        // Pending is the persisted proof that the executor was never invoked. Replay
                        // reacquires a fresh lease and revalidates the request before dispatch.
                        if (dispatchCoordinator.replayPending(intent, task)) {
                            replayed++;
                        } else {
                            recovered++;
                        }
                        continue;
                    }
                    recovered += convergeUncertain(task, intent);
                } catch (Exception exception) {
                    // One task's recovery failure must not leave the whole scheduler gated forever.
                    hadError = true;
                    log.error("启动恢复单任务失败: taskId={}, runId={}, reason={}",
                            task.getTaskId(), task.getCurrentRunId(), exception.getMessage());
                    recovered++;
                }
            }
        } finally {
            if (!running.isEmpty()) {
                log.info("启动调度恢复完成: scanned={}, replayed={}, converged={}",
                        running.size(), replayed, recovered);
            }
            readinessGate.markReady();
            runtimeMetrics.recordRecovery(!hadError);
        }
    }

    @Scheduled(fixedDelayString = "${security-model.dispatch.scan-interval-ms:10000}",
            initialDelayString = "${security-model.dispatch.scan-initial-delay-ms:30000}")
    public void scanPending() {
        if (!readinessGate.isReady()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            scanPendingIntents(now);
            scanSubmittingIntents(now);
        } catch (Exception exception) {
            log.warn("调度有界扫描失败: {}", exception.getMessage());
        }
    }

    private void scanPendingIntents(LocalDateTime now) {
        List<TaskDispatchIntent> pending = intentService.findPendingOlderThan(
                now.minusSeconds(pendingScanAgeSeconds), scanBatchSize);
        for (TaskDispatchIntent intent : pending) {
            PreDefenseTask task = taskMapper.selectById(intent.getTaskId());
            if (task == null || !"running".equals(task.getStatus())) {
                intentService.abandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_TASK_NOT_RUNNING", now);
                continue;
            }
            if (!belongsToCurrentAttempt(task, intent)) {
                // A stale pending intent must never release the lease of a newer attempt.
                intentService.abandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_IDENTITY_MISMATCH", now);
                continue;
            }
            if (intent.getCreatedAt() != null
                    && intent.getCreatedAt().isBefore(now.minusSeconds(pendingExpireSeconds))) {
                if (!intentService.abandonPending(intent.getTaskId(), intent.getRunId(),
                        intent.getAttempt(), "DISPATCH_EXPIRED", now)) {
                    // Another caller already promoted or closed this intent; it owns the decision.
                    continue;
                }
                gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
                dispatchCoordinator.failAndAbandon(intent.getTaskId(), intent.getRunId(),
                        intent.getAttempt(), "DISPATCH_EXPIRED",
                        "调度意图等待超过 " + pendingExpireSeconds + " 秒，显式失败并由用户重试");
                continue;
            }
            dispatchCoordinator.replayPending(intent, task);
        }
    }

    private void scanSubmittingIntents(LocalDateTime now) {
        List<TaskDispatchIntent> submitting = intentService.findSubmittingOlderThan(
                now.minusSeconds(pendingScanAgeSeconds), scanBatchSize);
        for (TaskDispatchIntent intent : submitting) {
            PreDefenseTask task = taskMapper.selectById(intent.getTaskId());
            if (task == null || !"running".equals(task.getStatus())) {
                intentService.abandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_TASK_NOT_RUNNING", now);
                continue;
            }
            if (!belongsToCurrentAttempt(task, intent)) {
                // An old submitting intent must not probe or kill the current attempt.
                intentService.abandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_IDENTITY_MISMATCH", now);
                continue;
            }
            if (processRegistry.isLocallyAccepted(task.getTaskId(), task.getCurrentRunId(),
                    task.getCurrentAttempt())) {
                // This JVM accepted the attempt; the heartbeat/executor owns its lifecycle.
                continue;
            }
            AttemptProcessRegistry.ReapOutcome outcome;
            try {
                outcome = processRegistry.reapIfOrphaned(task.getTaskId(), task.getCurrentAttempt());
            } catch (Exception exception) {
                outcome = AttemptProcessRegistry.ReapOutcome.UNKNOWN;
            }
            boolean processGone = isGone(outcome);
            if (processGone) {
                gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            } else {
                gpuLeaseService.markBlocked(intent.getTaskId(), intent.getRunId(),
                        "ORPHANED_PROCESS_" + outcomeName(outcome));
            }
            dispatchCoordinator.failAndAbandon(intent.getTaskId(), intent.getRunId(),
                    intent.getAttempt(), "DISPATCH_UNCERTAIN",
                    "派发状态不确定，按失败收敛，请用户手动重试");
        }
    }

    private boolean belongsToCurrentAttempt(PreDefenseTask task, TaskDispatchIntent intent) {
        return intent.getRunId() != null
                && intent.getRunId().equals(task.getCurrentRunId())
                && intent.getAttempt() != null
                && intent.getAttempt().equals(task.getCurrentAttempt());
    }

    private int failLegacy(PreDefenseTask task) {
        AttemptProcessRegistry.ReapOutcome outcome;
        try {
            outcome = processRegistry.reapIfOrphaned(task.getTaskId(), task.getCurrentAttempt());
        } catch (Exception exception) {
            outcome = AttemptProcessRegistry.ReapOutcome.UNKNOWN;
        }
        boolean processGone = isGone(outcome);
        int updated = taskMapper.failOrphanedForRun(task.getTaskId(), task.getCurrentRunId(),
                legacyFailure("EXECUTOR_RESTARTED", "应用重启导致任务中断，可安全重试"),
                LocalDateTime.now());
        if (processGone) {
            gpuLeaseService.releaseByRun(task.getTaskId(), task.getCurrentRunId());
        } else {
            gpuLeaseService.markBlocked(task.getTaskId(), task.getCurrentRunId(),
                    "ORPHANED_PROCESS_" + outcomeName(outcome));
            log.error("遗留执行进程状态未确认，GPU 槽位已隔离: taskId={}, runId={}, outcome={}",
                    task.getTaskId(), task.getCurrentRunId(), outcomeName(outcome));
        }
        return updated;
    }

    private int convergeUncertain(PreDefenseTask task, TaskDispatchIntent intent) {
        if (processRegistry.isLocallyAccepted(task.getTaskId(), task.getCurrentRunId(),
                task.getCurrentAttempt())) {
            return 0;
        }
        AttemptProcessRegistry.ReapOutcome outcome;
        try {
            outcome = processRegistry.reapIfOrphaned(task.getTaskId(), task.getCurrentAttempt());
        } catch (Exception exception) {
            outcome = AttemptProcessRegistry.ReapOutcome.UNKNOWN;
        }
        boolean processGone = isGone(outcome);
        if (processGone) {
            gpuLeaseService.releaseByRun(task.getTaskId(), task.getCurrentRunId());
        } else {
            gpuLeaseService.markBlocked(task.getTaskId(), task.getCurrentRunId(),
                    "ORPHANED_PROCESS_" + outcomeName(outcome));
        }
        dispatchCoordinator.failAndAbandon(task.getTaskId(), task.getCurrentRunId(),
                task.getCurrentAttempt(), "DISPATCH_UNCERTAIN",
                "上次 JVM 在派发过程中退出，无法确认执行是否开始；设备未确认空闲时保持隔离");
        return 1;
    }

    private boolean isGone(AttemptProcessRegistry.ReapOutcome outcome) {
        return outcome == AttemptProcessRegistry.ReapOutcome.NOT_STARTED
                || outcome == AttemptProcessRegistry.ReapOutcome.EXITED
                || outcome == AttemptProcessRegistry.ReapOutcome.REAPED;
    }

    private String outcomeName(AttemptProcessRegistry.ReapOutcome outcome) {
        return outcome == null ? AttemptProcessRegistry.ReapOutcome.UNKNOWN.name() : outcome.name();
    }

    private String legacyFailure(String code, String reason) {
        return "{\"executor\":\"security-model-platform\",\"stage\":\"recovery\",\"status\":\"failed\","
                + "\"message\":\"应用重启导致任务中断\",\"error_code\":\"" + code + "\","
                + "\"failure_reason\":\"" + reason + "\",\"artifacts\":{}}";
    }

    public Set<String> recoverableStatuses() {
        return Set.of(TaskDispatchIntent.PENDING, TaskDispatchIntent.SUBMITTING);
    }
}
