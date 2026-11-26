package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conditional dispatch and replay. Only the caller that wins
 * {@code pending -> submitting} may invoke the executor; mark-submitted happens after
 * the executor accepts the request, not before.
 */
@Component
public class TaskDispatchCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskDispatchCoordinator.class);

    private final TaskDispatchIntentService intentService;
    private final DefenseTaskExecutor executor;
    private final AttemptProcessRegistry processRegistry;
    private final GpuLeaseService gpuLeaseService;
    private final DispatchClaimTransactionService claimTransactionService;
    private final ExecutionFingerprintService fingerprintService;
    private final PreDefenseTaskMapper taskMapper;
    private final SysUserMapper userMapper;
    private final DatasetMapper datasetMapper;
    private final AlgorithmMapper algorithmMapper;
    private final ObjectMapper objectMapper;

    public TaskDispatchCoordinator(TaskDispatchIntentService intentService,
                                   DefenseTaskExecutor executor,
                                   AttemptProcessRegistry processRegistry,
                                   GpuLeaseService gpuLeaseService,
                                   ExecutionFingerprintService fingerprintService,
                                   PreDefenseTaskMapper taskMapper,
                                   SysUserMapper userMapper,
                                   DatasetMapper datasetMapper,
                                   AlgorithmMapper algorithmMapper,
                                   ObjectMapper objectMapper) {
        this(intentService, executor, processRegistry, gpuLeaseService, fingerprintService,
                taskMapper, userMapper, datasetMapper, algorithmMapper, objectMapper,
                new DispatchClaimTransactionService(intentService, gpuLeaseService));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskDispatchCoordinator(TaskDispatchIntentService intentService,
                                   DefenseTaskExecutor executor,
                                   AttemptProcessRegistry processRegistry,
                                   GpuLeaseService gpuLeaseService,
                                   ExecutionFingerprintService fingerprintService,
                                   PreDefenseTaskMapper taskMapper,
                                   SysUserMapper userMapper,
                                   DatasetMapper datasetMapper,
                                   AlgorithmMapper algorithmMapper,
                                   ObjectMapper objectMapper,
                                   DispatchClaimTransactionService claimTransactionService) {
        this.intentService = intentService;
        this.executor = executor;
        this.processRegistry = processRegistry;
        this.gpuLeaseService = gpuLeaseService;
        this.claimTransactionService = claimTransactionService;
        this.fingerprintService = fingerprintService;
        this.taskMapper = taskMapper;
        this.userMapper = userMapper;
        this.datasetMapper = datasetMapper;
        this.algorithmMapper = algorithmMapper;
        this.objectMapper = objectMapper;
    }

    public enum QueueDispatchResult { TERMINAL, BUSY, STALE, UNCERTAIN }

    /**
     * Worker entry point. It blocks until Python exits, so the caller can decide ACK from a
     * real completion signal instead of the async method's return. GPU_BUSY rolls the intent
     * back to pending; it must not fail the task or leak a lease.
     */
    public QueueDispatchResult consume(TaskDispatchIntent intent, PreDefenseTask task) {
        if (task == null || !"running".equals(task.getStatus())
                || !intent.getRunId().equals(task.getCurrentRunId())
                || !intent.getAttempt().equals(task.getCurrentAttempt())) {
            return QueueDispatchResult.STALE;
        }
        if (!executor.isConfigured()) {
            // The Worker owns its own execution configuration. A missing script cannot be
            // fixed by redelivery, so converge the attempt and release this run's lease
            // instead of claiming a slot that no process can ever use.
            gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "EXECUTOR_NOT_CONFIGURED",
                    "Worker 执行器配置无效，请检查 Python 脚本、解释器和工作目录");
            return QueueDispatchResult.TERMINAL;
        }
        DefenseTaskRequest request;
        try {
            request = intentService.readRequest(intent);
            validateRequestIdentity(intent, task, request);
            validateResources(request);
            ObjectNode runtime = fingerprintService.buildRuntimeSnapshot(request);
            String runtimeHash = fingerprintService.sha256(runtime.toString());
            if (intent.getRuntimeHash() != null && !intent.getRuntimeHash().equals(runtimeHash)) {
                failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_SNAPSHOT_CHANGED", "数据或算法指纹已变化，拒绝消费");
                return QueueDispatchResult.STALE;
            }
        } catch (TaskCommandException exception) {
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "DISPATCH_SNAPSHOT_INVALID", exception.getMessage());
            return QueueDispatchResult.STALE;
        }
        DispatchClaimTransactionService.ClaimOutcome claim;
        try {
            claim = claimTransactionService.claim(intent, LocalDateTime.now());
        } catch (TaskCommandException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("GPU_")) {
                log.info("Worker 暂未取得 GPU 槽位: taskId={}, runId={}, reason={}",
                        intent.getTaskId(), intent.getRunId(), exception.getMessage());
                return QueueDispatchResult.BUSY;
            }
            throw exception;
        }
        if (!claim.claimed()) {
            return QueueDispatchResult.STALE;
        }
        GpuLease lease = claim.lease();
        request.setGpuLeaseToken(lease.getLeaseToken());
        request.setGpuSlotId(lease.getSlotId());
        request.setGpuFencingToken(lease.getFencingToken());
        PreDefenseTask afterLease = taskMapper.selectById(intent.getTaskId());
        if (afterLease == null || !"running".equals(afterLease.getStatus())) {
            gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            intentService.abandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "TASK_CANCELED", LocalDateTime.now());
            return QueueDispatchResult.TERMINAL;
        }
        try {
            processRegistry.markAccepted(intent.getTaskId(), intent.getRunId(), intent.getAttempt());
            executor.executeAndWait(request);
        } catch (RuntimeException exception) {
            // The process may already have started; never roll back or re-run blindly.
            log.warn("Worker 执行结果不确定: taskId={}, runId={}, reason={}",
                    intent.getTaskId(), intent.getRunId(), exception.getMessage());
            return QueueDispatchResult.UNCERTAIN;
        }
        try {
            intentService.markSubmitted(intent.getTaskId(), intent.getRunId(),
                    intent.getAttempt(), LocalDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("Worker 完成但意图回写失败: taskId={}, runId={}, reason={}",
                    intent.getTaskId(), intent.getRunId(), exception.getMessage());
        }
        PreDefenseTask finished = taskMapper.selectById(intent.getTaskId());
        return finished != null && isTerminal(finished.getStatus())
                ? QueueDispatchResult.TERMINAL : QueueDispatchResult.UNCERTAIN;
    }

    private boolean isTerminal(String status) {
        return "success".equals(status) || "failed".equals(status) || "canceled".equals(status);
    }

    /** Runs after the launch transaction commits; never throws into the caller's thread. */
    public void dispatchAfterCommit(DefenseTaskRequest request) {
        if (!executor.isConfigured()) {
            // Defensive: start/retry precheck should have rejected this before the transaction.
            // The request was never handed to the executor, so this run's lease is safe to release.
            gpuLeaseService.releaseByRun(request.getTaskId(), request.getRunId());
            failAndAbandon(request.getTaskId(), request.getRunId(), request.getAttempt(),
                    "EXECUTOR_NOT_CONFIGURED",
                    "执行器配置无效，请检查 Python 脚本、解释器和工作目录");
            return;
        }
        try {
            if (!intentService.markSubmitting(request.getTaskId(), request.getRunId(),
                    request.getAttempt(), LocalDateTime.now())) {
                return;
            }
        } catch (RuntimeException exception) {
            log.warn("调度意图抢占失败: taskId={}, runId={}, reason={}",
                    request.getTaskId(), request.getRunId(), exception.getMessage());
            return;
        }
        try {
            processRegistry.markAccepted(request.getTaskId(), request.getRunId(), request.getAttempt());
            executor.runPreDefense(request);
        } catch (RuntimeException exception) {
            processRegistry.clearAccepted(request.getTaskId(), request.getRunId(), request.getAttempt());
            String code = exception instanceof TaskCommandException
                    && exception.getMessage() != null
                    && exception.getMessage().contains("GPU_")
                    ? exception.getMessage().split(":")[0] : "TASK_DISPATCH_FAILED";
            // Rejection happens before the executor accepts the request, so this run's lease
            // can be released; a running process never reaches this branch.
            gpuLeaseService.releaseByRun(request.getTaskId(), request.getRunId());
            failAndAbandon(request.getTaskId(), request.getRunId(), request.getAttempt(),
                    code, "任务提交执行池失败: " + exception.getMessage());
            return;
        }
        try {
            intentService.markSubmitted(request.getTaskId(), request.getRunId(),
                    request.getAttempt(), LocalDateTime.now());
        } catch (RuntimeException exception) {
            // The executor has already accepted the work. Do not turn a bookkeeping failure
            // into a task failure; recovery will classify the still-submitting intent.
            log.warn("执行器已接受任务但提交状态回写失败: taskId={}, runId={}, reason={}",
                    request.getTaskId(), request.getRunId(), exception.getMessage());
        }
    }

    /**
     * Replays one pending intent. The caller must already have verified the task identity and
     * that no process can be alive (pending means the executor was never invoked).
     */
    public boolean replayPending(TaskDispatchIntent intent, PreDefenseTask task) {
        if (task == null || !"running".equals(task.getStatus())
                || !intent.getRunId().equals(task.getCurrentRunId())
                || !intent.getAttempt().equals(task.getCurrentAttempt())) {
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "DISPATCH_IDENTITY_MISMATCH", "任务身份已变化，拒绝重放");
            return false;
        }
        if (task.getOwnerUserId() != null) {
            var owner = userMapper.selectById(task.getOwnerUserId());
            if (owner == null || !Boolean.TRUE.equals(owner.getEnabled())) {
                failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_IDENTITY_MISMATCH", "任务归属账号已停用或不存在，拒绝重放");
                return false;
            }
        } else {
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "DISPATCH_IDENTITY_MISMATCH", "任务没有稳定归属账号，拒绝自动重放");
            return false;
        }
        if ("demo".equals(task.getDataOrigin())) {
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "DISPATCH_DEMO_FORBIDDEN", "演示任务不允许自动重放");
            return false;
        }
        if (!executor.isConfigured()) {
            // A pending intent is persisted proof that the executor was never invoked for
            // this attempt. With invalid configuration, replay cannot make progress; release
            // the old lease and converge instead of leaving a running task behind.
            gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "EXECUTOR_NOT_CONFIGURED",
                    "恢复派发拒绝：请检查执行器的 Python 脚本、解释器和工作目录");
            return false;
        }
        DefenseTaskRequest request;
        try {
            request = intentService.readRequest(intent);
            validateRequestIdentity(intent, task, request);
            validateResources(request);
            ObjectNode runtime = fingerprintService.buildRuntimeSnapshot(request);
            if (!runtime.path("datasetFingerprint").path("available").asBoolean(false)
                    || !runtime.path("algorithmFingerprint").path("available").asBoolean(false)) {
                throw new TaskCommandException("DISPATCH_RESOURCE_INVALID: 数据或算法路径不可用");
            }
            String runtimeHash = fingerprintService.sha256(runtime.toString());
            if (intent.getRuntimeHash() != null && !intent.getRuntimeHash().equals(runtimeHash)) {
                failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_SNAPSHOT_CHANGED", "数据或算法指纹已变化，拒绝自动重放");
                return false;
            }
            if (!runtime.path("datasetFingerprint").path("available").asBoolean()
                    || !runtime.path("algorithmFingerprint").path("available").asBoolean()) {
                failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                        "DISPATCH_RESOURCE_UNAVAILABLE", "数据集或算法已不可用");
                return false;
            }
        } catch (TaskCommandException exception) {
            log.warn("调度重放快照校验失败: taskId={}, runId={}, reason={}",
                    intent.getTaskId(), intent.getRunId(), exception.getMessage());
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    "DISPATCH_SNAPSHOT_INVALID", exception.getMessage());
            return false;
        }

        if (!intentService.markSubmitting(intent.getTaskId(), intent.getRunId(),
                intent.getAttempt(), LocalDateTime.now())) {
            return false;
        }
        try {
            // The previous JVM's lease may still name this run. Release only this run's row,
            // then acquire a fresh token; if another task now owns the slot, fail explicitly.
            gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            GpuLease lease = gpuLeaseService.acquire(intent.getTaskId(), intent.getRunId(),
                    intent.getAttempt());
            request.setGpuLeaseToken(lease.getLeaseToken());
            request.setGpuSlotId(lease.getSlotId());
            request.setGpuFencingToken(lease.getFencingToken());
            processRegistry.markAccepted(intent.getTaskId(), intent.getRunId(), intent.getAttempt());
            executor.runPreDefense(request);
        } catch (RuntimeException exception) {
            processRegistry.clearAccepted(intent.getTaskId(), intent.getRunId(), intent.getAttempt());
            String code = exception instanceof TaskCommandException
                    && exception.getMessage() != null
                    && exception.getMessage().contains("GPU_")
                    ? exception.getMessage().split(":")[0] : "DISPATCH_REPLAY_FAILED";
            gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    code, "重放失败: " + exception.getMessage());
            return false;
        }
        try {
            intentService.markSubmitted(intent.getTaskId(), intent.getRunId(),
                    intent.getAttempt(), LocalDateTime.now());
        } catch (RuntimeException exception) {
            // Accepted by the executor: the process/queue owns the work now. A bookkeeping
            // failure must not release the slot or converge the task to failed.
            log.warn("重放执行器已接受任务但提交状态回写失败: taskId={}, runId={}, reason={}",
                    intent.getTaskId(), intent.getRunId(), exception.getMessage());
        }
        return true;
    }

    public void failAndAbandon(Long taskId, String runId, Integer attempt,
                               String errorCode, String reason) {
        try {
            taskMapper.failBeforeExecution(taskId, runId, attempt,
                    failureResult(taskId, runId, attempt, errorCode, reason), LocalDateTime.now());
        } catch (Exception exception) {
            log.warn("任务失败收敛写入失败: taskId={}, runId={}, reason={}",
                    taskId, runId, exception.getMessage());
        }
        try {
            intentService.abandon(taskId, runId, attempt, errorCode, LocalDateTime.now());
        } catch (Exception exception) {
            log.warn("调度意图收敛写入失败: taskId={}, runId={}, reason={}",
                    taskId, runId, exception.getMessage());
        }
    }

    private void validateRequestIdentity(TaskDispatchIntent intent, PreDefenseTask task,
                                         DefenseTaskRequest request) {
        if (request.getTaskId() == null || !request.getTaskId().equals(intent.getTaskId())
                || request.getRunId() == null || !request.getRunId().equals(intent.getRunId())
                || request.getAttempt() == null || !request.getAttempt().equals(intent.getAttempt())
                || task.getTaskId() == null || !task.getTaskId().equals(intent.getTaskId())
                || task.getCurrentRunId() == null || !task.getCurrentRunId().equals(intent.getRunId())
                || task.getCurrentAttempt() == null || !task.getCurrentAttempt().equals(intent.getAttempt())) {
            throw new TaskCommandException("DISPATCH_IDENTITY_MISMATCH: 快照身份与当前任务不一致");
        }
        if (request.getCreatedBy() == null || request.getCreatedBy().isBlank()) {
            throw new TaskCommandException("DISPATCH_IDENTITY_MISMATCH: 快照缺少创建人");
        }
    }

    private void validateResources(DefenseTaskRequest request) {
        if (request.getDataset() == null || request.getDataset().getId() == null
                || request.getAlgorithm() == null || request.getAlgorithm().getId() == null) {
            throw new TaskCommandException("DISPATCH_RESOURCE_INVALID: 数据集或算法引用缺失");
        }
        var dataset = datasetMapper.selectById(request.getDataset().getId());
        if (dataset == null || !Boolean.TRUE.equals(dataset.getEnabled())) {
            throw new TaskCommandException("DISPATCH_RESOURCE_DISABLED: 数据集已禁用或不存在");
        }
        var algorithm = algorithmMapper.selectById(request.getAlgorithm().getId());
        if (algorithm == null || !Boolean.TRUE.equals(algorithm.getEnabled())) {
            throw new TaskCommandException("DISPATCH_RESOURCE_DISABLED: 算法已禁用或不存在");
        }
    }
    private String failureResult(Long taskId, String runId, Integer attempt,
                                 String errorCode, String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "security-model-platform");
        result.put("stage", "dispatch");
        result.put("status", "failed");
        result.put("task_id", taskId);
        result.put("run_id", runId);
        result.put("attempt", attempt);
        result.put("error_code", errorCode);
        result.put("failure_reason", com.securitymodel.application.task.TaskFailureMessages.forCode(errorCode));
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("artifacts", Map.of());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            return "{\"status\":\"failed\",\"error_code\":\"" + errorCode + "\"}";
        }
    }
}
