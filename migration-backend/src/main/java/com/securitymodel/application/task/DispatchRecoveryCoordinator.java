package com.securitymodel.application.task;

import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final DispatchRuntimePolicy runtimePolicy;
    private final TaskOutboxService taskOutboxService;
    private final TaskDispatchConsumptionService consumptionService;
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
            long pendingScanAgeSeconds,
            long pendingExpireSeconds,
            int scanBatchSize) {
        this(taskMapper, intentService, processRegistry, gpuLeaseService, dispatchCoordinator,
                readinessGate, runtimeMetrics, new DispatchRuntimePolicy(), null, null,
                pendingScanAgeSeconds, pendingExpireSeconds, scanBatchSize);
    }

    @Autowired
    public DispatchRecoveryCoordinator(
            PreDefenseTaskMapper taskMapper,
            TaskDispatchIntentService intentService,
            AttemptProcessRegistry processRegistry,
            GpuLeaseService gpuLeaseService,
            TaskDispatchCoordinator dispatchCoordinator,
            SchedulingReadinessGate readinessGate,
            TaskRuntimeMetrics runtimeMetrics,
            DispatchRuntimePolicy runtimePolicy,
            TaskOutboxService taskOutboxService,
            TaskDispatchConsumptionService consumptionService,
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
        this.runtimePolicy = runtimePolicy;
        this.taskOutboxService = taskOutboxService;
        this.consumptionService = consumptionService;
        this.pendingScanAgeSeconds = pendingScanAgeSeconds;
        this.pendingExpireSeconds = pendingExpireSeconds;
        this.scanBatchSize = scanBatchSize;
    }

    public RecoveryResult recoverAtStartup() {
        if (!runtimePolicy.runsRecovery()) {
            // rabbit API must not scan running tasks: a Worker may still own them. It only
            // opens the readiness gate so command transactions can write Outbox rows.
            readinessGate.markReady();
            runtimeMetrics.recordRecovery(true);
            log.info("当前角色不承担执行恢复: mode={}, role={}",
                    runtimePolicy.mode(), runtimePolicy.role());
            return new RecoveryResult(true, 0, 0, 0, 0, 0, 0,
                    List.of(), List.of());
        }
        List<PreDefenseTask> running;
        try {
            running = taskMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<PreDefenseTask>lambdaQuery()
                            .eq(PreDefenseTask::getStatus, "running"));
        } catch (RuntimeException exception) {
            readinessGate.markNotReady();
            runtimeMetrics.recordRecovery(false);
            log.error("运行任务扫描失败，调度门保持关闭: {}", exception.getMessage());
            return new RecoveryResult(false, 0, 0, 0, 0, 1, 0,
                    List.of(), List.of("TASK_SCAN_FAILED"));
        }
        int replayed = 0;
        int converged = 0;
        int deferred = 0;
        int errors = 0;
        List<String> failedTasks = new ArrayList<>();
        List<String> quarantinedSlots = new ArrayList<>();
        boolean safeToConsume = false;
        try {
            for (PreDefenseTask task : running) {
                try {
                    if (runtimePolicy.rabbitMode() && hasUnfinishedConsumption(task)) {
                        log.info("存在未终态消费账本，恢复不接管: taskId={}, runId={}",
                                task.getTaskId(), task.getCurrentRunId());
                        deferred++;
                        continue;
                    }
                    TaskDispatchIntent intent = intentService.findByRun(
                            task.getTaskId(), task.getCurrentRunId(), task.getCurrentAttempt());
                    if (intent == null) {
                        // Legacy task from before dispatch intents existed: no way to prove it was
                        // never submitted, so converge it and preserve the R2 process evidence rule.
                        Classification classification = failLegacy(task);
                        converged += classification.updated();
                        deferred += classification.deferred() ? 1 : 0;
                        addQuarantine(quarantinedSlots, classification);
                        continue;
                    }
                    if (TaskDispatchIntent.PENDING.equals(intent.getStatus())) {
                        if (runtimePolicy.rabbitMode()) {
                            // rabbit: a pending intent is not executed by recovery. Only
                            // ensure the Outbox row exists; the relay/consumer owns delivery.
                            ensureOutbox(intent, task);
                            converged++;
                        } else {
                            // Pending is the persisted proof that the executor was never invoked.
                            // Replay reacquires a fresh lease and revalidates the request.
                            if (dispatchCoordinator.replayPending(intent, task)) {
                                replayed++;
                            } else {
                                converged++;
                            }
                        }
                        continue;
                    }
                    Classification classification = convergeUncertain(task, intent);
                    converged += classification.updated();
                    deferred += classification.deferred() ? 1 : 0;
                    addQuarantine(quarantinedSlots, classification);
                } catch (Exception exception) {
                    // The failed task remains unclassified. Keep the gate closed and the
                    // consumer stopped; the next heartbeat retries recovery for this task.
                    errors++;
                    failedTasks.add(task.getTaskId() + ":" + task.getCurrentRunId());
                    log.error("启动恢复单任务失败，调度门保持关闭: taskId={}, runId={}, reason={}",
                            task.getTaskId(), task.getCurrentRunId(), exception.getMessage());
                }
            }
            int localAccepted = localAcceptedCount();
            List<String> blocked = blockedSlots();
            quarantinedSlots.addAll(blocked);
            safeToConsume = errors == 0 && localAccepted == 0 && quarantinedSlots.isEmpty();
            if (safeToConsume) {
                readinessGate.markReady();
            } else {
                readinessGate.markNotReady();
            }
            runtimeMetrics.recordRecovery(safeToConsume);
            return new RecoveryResult(safeToConsume, running.size(), replayed, converged,
                    deferred, errors, localAccepted, List.copyOf(quarantinedSlots),
                    List.copyOf(failedTasks));
        } finally {
            log.info("启动调度恢复完成: scanned={}, replayed={}, converged={}, deferred={}, "
                            + "errors={}, safeToConsume={}, quarantined={}, failedTasks={}",
                    running.size(), replayed, converged, deferred, errors, safeToConsume,
                    quarantinedSlots.size(), failedTasks);
        }
    }

    private void addQuarantine(List<String> quarantinedSlots, Classification classification) {
        if (classification.quarantinedSlot() != null) {
            quarantinedSlots.add(classification.quarantinedSlot());
        }
    }

    private int localAcceptedCount() {
        try {
            return processRegistry.localAcceptedCount();
        } catch (RuntimeException exception) {
            log.error("本地 accepted 执行计数不可读，保持调度门关闭: {}", exception.getMessage());
            return 1;
        }
    }

    private List<String> blockedSlots() {
        if (gpuLeaseService == null) {
            return List.of();
        }
        try {
            return gpuLeaseService.findBlocked().stream()
                    .sorted(Comparator.comparing(lease -> lease.getTaskId() == null
                            ? "" : lease.getTaskId().toString()))
                    .map(lease -> lease.getTaskId() + ":" + lease.getRunId())
                    .toList();
        } catch (RuntimeException exception) {
            log.error("GPU 隔离槽位不可读，保持调度门关闭: {}", exception.getMessage());
            // A non-empty marker keeps safeToConsume false without claiming a specific slot.
            return List.of("UNKNOWN_GPU_LEASE_STATE");
        }
    }

    @Scheduled(fixedDelayString = "${security-model.dispatch.scan-interval-ms:10000}",
            initialDelayString = "${security-model.dispatch.scan-initial-delay-ms:30000}")
    public void scanPending() {
        if (!runtimePolicy.runsRecovery() || !readinessGate.isReady()) {
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
            if (runtimePolicy.rabbitMode() && hasUnfinishedConsumption(task)) {
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
            if (runtimePolicy.rabbitMode()) {
                // Do not start Python from recovery. Rebuild the durable event if an older
                // intent predates the outbox table; relay will publish it.
                ensureOutbox(intent, task);
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
            if (runtimePolicy.rabbitMode() && hasUnfinishedConsumption(task)) {
                // The message ledger proves a Worker claimed this attempt; recovery must not
                // fail it while broker redelivery/claim expiry still owns the decision.
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

    private void ensureOutbox(TaskDispatchIntent intent, PreDefenseTask task) {
        if (taskOutboxService == null) {
            throw new TaskCommandException("DISPATCH_OUTBOX_UNAVAILABLE: Outbox 持久层未装配");
        }
        DefenseTaskRequest request = intentService.readRequest(intent);
        taskOutboxService.ensurePending(request, intent, LocalDateTime.now(),
                task.getOwnerUserId());
    }

    private boolean belongsToCurrentAttempt(PreDefenseTask task, TaskDispatchIntent intent) {
        return intent.getRunId() != null
                && intent.getRunId().equals(task.getCurrentRunId())
                && intent.getAttempt() != null
                && intent.getAttempt().equals(task.getCurrentAttempt());
    }

    private boolean hasUnfinishedConsumption(PreDefenseTask task) {
        return consumptionService != null
                && consumptionService.hasUnfinishedOwnership(task.getTaskId(),
                task.getCurrentRunId(), task.getCurrentAttempt());
    }

    private Classification failLegacy(PreDefenseTask task) {
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
            return new Classification(updated, false, null);
        }
        gpuLeaseService.markBlocked(task.getTaskId(), task.getCurrentRunId(),
                "ORPHANED_PROCESS_" + outcomeName(outcome));
        log.error("遗留执行进程状态未确认，GPU 槽位已隔离: taskId={}, runId={}, outcome={}",
                task.getTaskId(), task.getCurrentRunId(), outcomeName(outcome));
        return new Classification(updated, false, task.getTaskId() + ":" + task.getCurrentRunId());
    }

    private Classification convergeUncertain(PreDefenseTask task, TaskDispatchIntent intent) {
        if (processRegistry.isLocallyAccepted(task.getTaskId(), task.getCurrentRunId(),
                task.getCurrentAttempt())) {
            return new Classification(0, true, null);
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
        return new Classification(1, false,
                processGone ? null : task.getTaskId() + ":" + task.getCurrentRunId());
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

    /**
     * Explicit recovery contract. {@code safeToConsume} is true only when every running task
     * was classified, no local executor is still accepted, and no GPU slot is quarantined.
     */
    public record RecoveryResult(boolean safeToConsume, int scanned, int replayed, int converged,
                                 int deferred, int errors, int localAccepted,
                                 List<String> quarantinedSlots, List<String> failedTasks) {
    }

    private record Classification(int updated, boolean deferred, String quarantinedSlot) {
    }
}
