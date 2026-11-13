package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
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
    private final GpuLeaseService gpuLeaseService;
    private final ExecutionFingerprintService fingerprintService;
    private final PreDefenseTaskMapper taskMapper;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public TaskDispatchCoordinator(TaskDispatchIntentService intentService,
                                   DefenseTaskExecutor executor,
                                   GpuLeaseService gpuLeaseService,
                                   ExecutionFingerprintService fingerprintService,
                                   PreDefenseTaskMapper taskMapper,
                                   SysUserMapper userMapper,
                                   ObjectMapper objectMapper) {
        this.intentService = intentService;
        this.executor = executor;
        this.gpuLeaseService = gpuLeaseService;
        this.fingerprintService = fingerprintService;
        this.taskMapper = taskMapper;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    /** Runs after the launch transaction commits; never throws into the caller's thread. */
    public void dispatchAfterCommit(DefenseTaskRequest request) {
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
            executor.runPreDefense(request);
        } catch (RuntimeException exception) {
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
        }
        DefenseTaskRequest request;
        try {
            request = intentService.readRequest(intent);
            ObjectNode runtime = fingerprintService.buildRuntimeSnapshot(request);
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
            executor.runPreDefense(request);
            intentService.markSubmitted(intent.getTaskId(), intent.getRunId(),
                    intent.getAttempt(), LocalDateTime.now());
            return true;
        } catch (RuntimeException exception) {
            String code = exception instanceof TaskCommandException
                    && exception.getMessage() != null
                    && exception.getMessage().contains("GPU_")
                    ? exception.getMessage().split(":")[0] : "DISPATCH_REPLAY_FAILED";
            gpuLeaseService.releaseByRun(intent.getTaskId(), intent.getRunId());
            failAndAbandon(intent.getTaskId(), intent.getRunId(), intent.getAttempt(),
                    code, "重放失败: " + exception.getMessage());
            return false;
        }
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
        result.put("failure_reason", reason);
        result.put("finished_at", LocalDateTime.now().toString());
        result.put("artifacts", Map.of());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            return "{\"status\":\"failed\",\"error_code\":\"" + errorCode + "\"}";
        }
    }
}
