package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.hyper.Hyper;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PreDefenseTaskCommandService {

    private static final String LDD_IMPLEMENTATION = "vab://pre-defense/ldd";

    private final PreDefenseTaskMapper taskMapper;
    private final ResourceQueryService resourceQueryService;
    private final DefenseTaskExecutor defenseTaskExecutor;
    private final LddConfigurationPolicy lddConfigurationPolicy;
    private final TaskExecutionQuotaPolicy taskExecutionQuotaPolicy;
    private final GpuLeaseService gpuLeaseService;
    private final ExecutionFingerprintService executionFingerprintService;
    private final AfterCommitTaskDispatcher afterCommitTaskDispatcher;
    private final ObjectMapper objectMapper;
    private final OperationAuditService operationAuditService;
    private final CurrentUserService currentUserService;
    private final TaskAccessGuard taskAccessGuard;
    private final SchedulingReadinessGate readinessGate;
    private final TaskDispatchIntentService dispatchIntentService;
    private final TaskDispatchCoordinator dispatchCoordinator;

    public PreDefenseTaskCommandService(
            PreDefenseTaskMapper taskMapper,
            ResourceQueryService resourceQueryService,
            DefenseTaskExecutor defenseTaskExecutor,
            LddConfigurationPolicy lddConfigurationPolicy,
            TaskExecutionQuotaPolicy taskExecutionQuotaPolicy,
            GpuLeaseService gpuLeaseService,
            ExecutionFingerprintService executionFingerprintService,
            AfterCommitTaskDispatcher afterCommitTaskDispatcher,
            ObjectMapper objectMapper,
            OperationAuditService operationAuditService,
            CurrentUserService currentUserService,
            TaskAccessGuard taskAccessGuard,
            SchedulingReadinessGate readinessGate,
            TaskDispatchIntentService dispatchIntentService,
            TaskDispatchCoordinator dispatchCoordinator) {
        this.taskMapper = taskMapper;
        this.resourceQueryService = resourceQueryService;
        this.defenseTaskExecutor = defenseTaskExecutor;
        this.lddConfigurationPolicy = lddConfigurationPolicy;
        this.taskExecutionQuotaPolicy = taskExecutionQuotaPolicy;
        this.gpuLeaseService = gpuLeaseService;
        this.executionFingerprintService = executionFingerprintService;
        this.afterCommitTaskDispatcher = afterCommitTaskDispatcher;
        this.objectMapper = objectMapper;
        this.operationAuditService = operationAuditService;
        this.currentUserService = currentUserService;
        this.taskAccessGuard = taskAccessGuard;
        this.readinessGate = readinessGate;
        this.dispatchIntentService = dispatchIntentService;
        this.dispatchCoordinator = dispatchCoordinator;
    }

    @Transactional(rollbackFor = Exception.class)
    public PreDefenseTask create(PreDefenseTaskCommand command) {
        validateCommand(command);
        if (taskMapper.selectCount(Wrappers.<PreDefenseTask>lambdaQuery()
                .eq(PreDefenseTask::getTaskName, command.getTaskName())) > 0) {
            throw new TaskCommandException("任务名称已存在");
        }

        PreDefenseTask task = new PreDefenseTask();
        applyCommand(task, command);
        task.setStatus("pending");
        task.setProgress(0);
        var identity = currentUserService.requireStableIdentity();
        task.setCreateBy(identity.username());
        task.setUpdateBy(identity.username());
        task.setOwnerUserId(identity.userId());
        task.setDataOrigin("user");
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(task.getCreateTime());
        taskMapper.insert(task);
        operationAuditService.recordTask(
                task.getTaskId(), "CREATE", null, task.getStatus(), command, "创建 LDD 清洗任务");
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public PreDefenseTask update(Long taskId, PreDefenseTaskCommand command) {
        validateCommand(command);
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"pending".equals(task.getStatus())) {
            throw new TaskCommandException("只有等待中的任务才能编辑");
        }
        long duplicateCount = taskMapper.selectCount(Wrappers.<PreDefenseTask>lambdaQuery()
                .eq(PreDefenseTask::getTaskName, command.getTaskName())
                .ne(PreDefenseTask::getTaskId, taskId));
        if (duplicateCount > 0) {
            throw new TaskCommandException("任务名称已存在");
        }

        applyCommand(task, command);
        task.setUpdateBy(currentUserService.username());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        operationAuditService.recordTask(
                taskId, "UPDATE", "pending", task.getStatus(), command, "编辑 LDD 清洗任务");
        return findRequired(taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Collection<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new TaskCommandException("任务ID列表不能为空");
        }
        List<PreDefenseTask> tasks = taskMapper.selectBatchIds(taskIds);
        if (tasks.size() != taskIds.size()) {
            throw new TaskCommandException("存在不存在的任务");
        }
        tasks.forEach(taskAccessGuard::requireOwner);
        if (tasks.stream().anyMatch(task -> "running".equals(task.getStatus()))) {
            throw new TaskCommandException("运行中的任务不能删除");
        }
        taskMapper.deleteBatchIds(taskIds);
        tasks.forEach(task -> operationAuditService.recordTask(
                task.getTaskId(), "DELETE", task.getStatus(), "deleted",
                Map.of("taskId", task.getTaskId()), "删除 LDD 清洗任务"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(Long taskId) {
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"pending".equals(task.getStatus())) {
            throw new TaskCommandException("只有等待中的任务才能启动");
        }
        launchTask(task);
        operationAuditService.recordTask(
                taskId, "START", "pending", "running",
                Map.of("taskId", taskId), "启动 LDD 清洗任务");
        return Map.of("message", "LDD 任务已启动", "taskId", taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> retry(Long taskId) {
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"failed".equals(task.getStatus())) {
            throw new TaskCommandException("只有失败的任务才能重试");
        }
        if (taskMapper.resetForRetry(taskId) != 1) {
            throw new TaskCommandException("任务状态已变化，请刷新后重试");
        }
        launchTask(taskAccessGuard.requireOwned(taskId));
        operationAuditService.recordTask(
                taskId, "RETRY", "failed", "running",
                Map.of("taskId", taskId), "重试 LDD 清洗任务");
        return Map.of("message", "LDD 任务已重试", "taskId", taskId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> cancel(Long taskId) {
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"running".equals(task.getStatus())) {
            throw new TaskCommandException("只有运行中的任务才能取消");
        }
        if (task.getCurrentRunId() == null || task.getCurrentRunId().isBlank()) {
            throw new TaskCommandException("当前任务缺少运行标识，无法安全取消");
        }
        LocalDateTime endTime = LocalDateTime.now();
        int updated = taskMapper.cancelForRun(taskId, task.getCurrentRunId(), buildCanceledResult(task, endTime), endTime);
        if (updated != 1) {
            throw new TaskCommandException("任务状态已变化，请刷新后重试");
        }
        dispatchIntentService.abandon(taskId, task.getCurrentRunId(), task.getCurrentAttempt(),
                "TASK_CANCELED", LocalDateTime.now());
        afterCommitTaskDispatcher.dispatch(
                () -> defenseTaskExecutor.cancelPreDefense(taskId, task.getCurrentRunId()),
                null);
        operationAuditService.recordTask(
                taskId, "CANCEL", "running", "canceled",
                Map.of("taskId", taskId), "取消 LDD 清洗任务");
        return Map.of("message", "LDD 任务已取消", "taskId", taskId);
    }

    /** Read path used by the detail endpoint and the audit listing: owner, or admin cross-user read. */
    public PreDefenseTask findRequired(Long taskId) {
        return taskAccessGuard.requireReadable(taskId);
    }

    /**
     * Validation-only entry for the assistant preview. It normalizes the mutable command
     * exactly like create/update but never inserts or launches anything.
     */
    public PreDefenseTaskCommand validateDraft(PreDefenseTaskCommand command) {
        validateCommand(command);
        return command;
    }

    private void validateCommand(PreDefenseTaskCommand command) {
        if (command == null || command.getTaskName() == null
                || command.getTaskName().isBlank()) {
            throw new TaskCommandException("任务名称不能为空");
        }
        if (command.getDatasetId() == null) {
            throw new TaskCommandException("数据集ID不能为空");
        }
        if (command.getAlgorithmId() == null) {
            throw new TaskCommandException("算法ID不能为空");
        }

        Dataset dataset = resourceQueryService.findDataset(command.getDatasetId());
        if (dataset == null || !Boolean.TRUE.equals(dataset.getEnabled())
                || !"success".equals(dataset.getParseStatus())) {
            throw new TaskCommandException("指定的数据集不存在或未解析成功");
        }

        Algorithm algorithm = resourceQueryService.findAlgorithm(command.getAlgorithmId());
        if (algorithm == null || !Boolean.TRUE.equals(algorithm.getEnabled())
                || !"pre".equals(algorithm.getDefenseStage())
                || !LDD_IMPLEMENTATION.equals(algorithm.getImplementationPath())) {
            throw new TaskCommandException("当前平台仅开放已启用的 LDD 训练前防御算法");
        }

        if (command.getHyperId() != null) {
            Hyper hyper = resourceQueryService.findHyper(command.getHyperId());
            if (hyper == null || !Boolean.TRUE.equals(hyper.getEnabled())
                    || !command.getAlgorithmId().equals(hyper.getAlgorithmId())) {
                throw new TaskCommandException("超参数模板不存在、未启用或与 LDD 算法不匹配");
            }
        }
        normalizeParams(command);
        Hyper hyper = command.getHyperId() == null ? null : resourceQueryService.findHyper(command.getHyperId());
        lddConfigurationPolicy.mergeAndValidate(
                algorithm.getDefaultConfig(),
                hyper == null ? null : hyper.getParameterConfig(),
                command.getCustomParams());
    }

    private void applyCommand(PreDefenseTask task, PreDefenseTaskCommand command) {
        task.setTaskName(command.getTaskName().trim());
        task.setDescription(command.getDescription());
        task.setDatasetId(command.getDatasetId());
        task.setAlgorithmId(command.getAlgorithmId());
        task.setHyperId(command.getHyperId());
        task.setCustomParams(command.getCustomParams());
    }

    private void normalizeParams(PreDefenseTaskCommand command) {
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            node = objectMapper.readTree(
                    command.getCustomParams() == null || command.getCustomParams().isBlank()
                            ? "{}" : command.getCustomParams());
        } catch (Exception exception) {
            throw new TaskCommandException("自定义参数格式不正确，必须是 JSON 对象");
        }
        if (node == null || !node.isObject()) {
            throw new TaskCommandException("自定义参数必须是 JSON 对象");
        }
        ObjectNode object = (ObjectNode) node;
        if (object.has("epochs") && !object.has("num_epochs")) {
            object.set("num_epochs", object.get("epochs"));
            object.remove("epochs");
        }
        command.setCustomParams(object.toString());
    }

    private void launchTask(PreDefenseTask task) {
        if (!readinessGate.isReady()) {
            throw new TaskCommandException("SCHEDULING_NOT_READY: 启动恢复尚未完成，暂不接受新的执行请求");
        }
        validateTaskForLaunch(task);
        int attempt = nextAttempt(task.getExecutionSnapshot());
        String runId = UUID.randomUUID().toString();
        // The lease and the task claim share one transaction: losing the GPU slot rolls the
        // claim back, and a failed claim rolls the lease back, so neither can half-commit.
        GpuLease lease = gpuLeaseService.acquire(task.getTaskId(), runId, attempt);
        DefenseTaskRequest request = buildExecutionRequest(task, runId, attempt, lease);
        ObjectNode quota = taskExecutionQuotaPolicy.validateAndBuildSnapshot(task);
        LocalDateTime startTime = LocalDateTime.now();
        String snapshot = appendExecutionSnapshot(task.getExecutionSnapshot(), request, startTime, quota, lease);
        int claimed = taskMapper.claimForExecution(
                task.getTaskId(), request.getRunId(), request.getAttempt(), startTime, snapshot);
        if (claimed != 1) {
            throw new TaskCommandException("任务状态已变化，请刷新后重试");
        }
        ObjectNode runtime = executionFingerprintService.buildRuntimeSnapshot(request);
        dispatchIntentService.createPending(request,
                executionFingerprintService.sha256(runtime.toString()), startTime);
        afterCommitTaskDispatcher.dispatch(
                () -> dispatchCoordinator.dispatchAfterCommit(request),
                null);
    }

    private void validateTaskForLaunch(PreDefenseTask task) {
        PreDefenseTaskCommand command = new PreDefenseTaskCommand();
        command.setTaskId(task.getTaskId());
        command.setTaskName(task.getTaskName());
        command.setDescription(task.getDescription());
        command.setDatasetId(task.getDatasetId());
        command.setAlgorithmId(task.getAlgorithmId());
        command.setHyperId(task.getHyperId());
        command.setCustomParams(task.getCustomParams());
        validateCommand(command);
    }

    private DefenseTaskRequest buildExecutionRequest(PreDefenseTask task, String runId, int attempt,
                                                     GpuLease lease) {
        Dataset dataset = resourceQueryService.findDataset(task.getDatasetId());
        Algorithm algorithm = resourceQueryService.findAlgorithm(task.getAlgorithmId());
        Hyper hyper = task.getHyperId() == null ? null : resourceQueryService.findHyper(task.getHyperId());
        return DefenseTaskRequest.builder()
                .taskId(task.getTaskId())
                .attempt(attempt)
                .runId(runId)
                .taskName(task.getTaskName())
                .stage("pre_defense")
                .description(task.getDescription())
                .createdBy(task.getCreateBy())
                .customParams(task.getCustomParams())
                .dataset(toDatasetRef(dataset))
                .algorithm(toAlgorithmRef(algorithm))
                .hyperParameter(toHyperRef(hyper))
                .gpuLeaseToken(lease.getLeaseToken())
                .gpuSlotId(lease.getSlotId())
                .gpuFencingToken(lease.getFencingToken())
                .build();
    }

    private String appendExecutionSnapshot(
            String existingSnapshot,
            DefenseTaskRequest request,
            LocalDateTime startTime,
            ObjectNode quota,
            GpuLease lease) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        ArrayNode attempts = objectMapper.createArrayNode();
        if (existingSnapshot != null && !existingSnapshot.isBlank()) {
            try {
                var previous = objectMapper.readTree(existingSnapshot);
                if (previous.has("attempts") && previous.get("attempts").isArray()) {
                    previous.get("attempts").forEach(attempts::add);
                }
            } catch (Exception ignored) {
                // Broken historical snapshot should not block a fresh run.
            }
        }
        ObjectNode attempt = objectMapper.createObjectNode();
        attempt.put("attempt", request.getAttempt());
        attempt.put("runId", request.getRunId());
        attempt.put("launchedAt", startTime.toString());
        attempt.set("request", objectMapper.valueToTree(request));
        attempt.set("runtime", executionFingerprintService.buildRuntimeSnapshot(request));
        attempt.set("quota", quota);
        ObjectNode gpuLease = objectMapper.createObjectNode();
        gpuLease.put("slotId", lease.getSlotId());
        gpuLease.put("fencingToken", lease.getFencingToken());
        gpuLease.put("expiresAt", lease.getExpiresAt() == null ? null : lease.getExpiresAt().toString());
        attempt.set("gpuLease", gpuLease);
        attempt.put("snapshotHash", executionFingerprintService.sha256(attempt.toString()));
        attempts.add(attempt);
        snapshot.put("version", 2);
        snapshot.set("attempts", attempts);
        return snapshot.toString();
    }

    private int nextAttempt(String existingSnapshot) {
        if (existingSnapshot == null || existingSnapshot.isBlank()) {
            return 1;
        }
        try {
            var snapshot = objectMapper.readTree(existingSnapshot);
            return snapshot.has("attempts") && snapshot.get("attempts").isArray()
                    ? snapshot.get("attempts").size() + 1
                    : 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private DefenseResourceRef toDatasetRef(Dataset dataset) {
        return DefenseResourceRef.builder()
                .id(dataset.getDatasetId())
                .name(dataset.getDatasetName())
                .type(dataset.getDatasetType())
                .path(dataset.getFilePath())
                .build();
    }

    private DefenseResourceRef toAlgorithmRef(Algorithm algorithm) {
        return DefenseResourceRef.builder()
                .id(algorithm.getAlgorithmId())
                .name(algorithm.getAlgorithmName())
                .type(algorithm.getAlgorithmType())
                .category(algorithm.getAlgorithmCategory())
                .path(algorithm.getImplementationPath())
                .configJson(algorithm.getDefaultConfig())
                .schemaJson(algorithm.getConfigSchema())
                .build();
    }

    private DefenseResourceRef toHyperRef(Hyper hyper) {
        if (hyper == null) {
            return null;
        }
        return DefenseResourceRef.builder()
                .id(hyper.getTemplateId())
                .name(hyper.getTemplateName())
                .type("hyper_parameter")
                .configJson(hyper.getParameterConfig())
                .schemaJson(hyper.getParameterSchema())
                .build();
    }

    private String buildCanceledResult(PreDefenseTask task, LocalDateTime endTime) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executor", "security-model-platform");
        result.put("stage", "pre_defense");
        result.put("status", "canceled");
        result.put("message", "任务已由用户取消");
        result.put("run_id", task.getCurrentRunId());
        result.put("attempt", task.getCurrentAttempt());
        result.put("error_code", "TASK_CANCELED");
        result.put("failure_reason", "用户取消任务");
        result.put("started_at", task.getStartTime() == null ? null : task.getStartTime().toString());
        result.put("finished_at", endTime.toString());
        result.put("artifacts", Map.of());
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            throw new TaskCommandException("生成取消结果失败");
        }
    }
}
