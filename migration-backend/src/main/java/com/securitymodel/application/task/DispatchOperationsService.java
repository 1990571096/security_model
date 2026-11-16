package com.securitymodel.application.task;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.task.DispatchOutbox;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Admin-only R4 operational view; no payload, command line, path or credential is returned. */
@Service
public class DispatchOperationsService {

    private final CurrentUserService currentUserService;
    private final DispatchRuntimePolicy runtimePolicy;
    private final TaskOutboxService outboxService;
    private final TaskDispatchConsumptionService consumptionService;
    private final AttemptProcessRegistry processRegistry;
    private final PreDefenseTaskMapper taskMapper;
    private final TaskDispatchIntentService intentService;
    private final OperationAuditService auditService;
    private final WorkerOwnershipProbe ownershipProbe;

    public DispatchOperationsService(
            CurrentUserService currentUserService,
            DispatchRuntimePolicy runtimePolicy,
            TaskOutboxService outboxService,
            TaskDispatchConsumptionService consumptionService,
            AttemptProcessRegistry processRegistry,
            PreDefenseTaskMapper taskMapper,
            TaskDispatchIntentService intentService,
            OperationAuditService auditService,
            WorkerOwnershipProbe ownershipProbe) {
        this.currentUserService = currentUserService;
        this.runtimePolicy = runtimePolicy;
        this.outboxService = outboxService;
        this.consumptionService = consumptionService;
        this.processRegistry = processRegistry;
        this.taskMapper = taskMapper;
        this.intentService = intentService;
        this.auditService = auditService;
        this.ownershipProbe = ownershipProbe;
    }

    public Map<String, Object> summary() {
        currentUserService.requireAdmin();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", runtimePolicy.mode().name().toLowerCase());
        result.put("role", runtimePolicy.role().name().toLowerCase());
        try {
            result.put("outboxNew", outboxService.countByStatus(DispatchOutbox.NEW));
            result.put("outboxPublishing", outboxService.countByStatus(DispatchOutbox.PUBLISHING));
            result.put("outboxFailed", outboxService.countByStatus(DispatchOutbox.FAILED));
            result.put("outboxPublished", outboxService.countByStatus(DispatchOutbox.PUBLISHED));
            result.put("outboxDead", outboxService.countByStatus(DispatchOutbox.DEAD));
            result.put("outboxBacklog", outboxService.countBacklog(now));
            LocalDateTime oldest = outboxService.oldestUnpublishedAt();
            result.put("oldestUnpublishedAt", oldest == null ? null : oldest.toString());
            result.put("oldestWaitSeconds", oldest == null ? 0
                    : Math.max(0, Duration.between(oldest, now).getSeconds()));
            result.put("expiredConsumptionClaims", consumptionService.countExpiredProcessing(now));
            result.put("outboxAvailable", true);
        } catch (DataAccessException exception) {
            // The default db profile may not have migrated the R4 tables yet. Report the
            // unavailable capability instead of failing the whole admin observability page.
            result.put("outboxAvailable", false);
            result.put("outboxError", "OUTBOX_TABLE_UNAVAILABLE");
        }
        result.put("workerLocalAccepted", processRegistry.localAcceptedCount());
        Long ownerConnectionId = ownershipProbe.ownerConnectionId();
        result.put("workerOwned", ownerConnectionId != null);
        result.put("workerOwnerConnectionId", ownerConnectionId);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> replayDead(String messageId) {
        var identity = currentUserService.requireStableIdentity();
        DispatchOutbox outbox;
        try {
            outbox = outboxService.findByMessageId(messageId);
        } catch (DataAccessException exception) {
            throw new TaskCommandException("OUTBOX_TABLE_UNAVAILABLE: R4 Outbox 表尚未迁移");
        }
        if (outbox == null) {
            throw new TaskCommandException("DISPATCH_DEAD_NOT_FOUND: 死信不存在");
        }
        if (!DispatchOutbox.DEAD.equals(outbox.getStatus())) {
            throw new TaskCommandException("DISPATCH_DEAD_NOT_DEAD: 当前 Outbox 不是 dead 状态");
        }
        // Row locks order all replay checks before the conditional dead -> new update.
        PreDefenseTask task = taskMapper.selectByIdForUpdate(outbox.getTaskId());
        if (task == null) {
            throw new TaskCommandException("DLQ_REPLAY_TASK_MISSING: 任务不存在");
        }
        boolean owner = identity.userId() != null
                && identity.userId().equals(task.getOwnerUserId());
        if (!identity.admin() && !owner) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "仅任务所有者或管理员可重放死信");
        }
        if (!"running".equals(task.getStatus())) {
            throw new TaskCommandException(
                    "DLQ_REPLAY_REQUIRES_NEW_ATTEMPT: 任务已不在 running，失败/不确定执行必须走任务重试创建新 attempt");
        }
        if (!java.util.Objects.equals(outbox.getRunId(), task.getCurrentRunId())
                || !java.util.Objects.equals(outbox.getAttempt(), task.getCurrentAttempt())) {
            throw new TaskCommandException(
                    "DLQ_REPLAY_IDENTITY_MISMATCH: 死信的 runId/attempt 已不是任务当前尝试");
        }
        TaskDispatchIntent intent = intentService.findByRunForUpdate(outbox.getTaskId(),
                outbox.getRunId(), outbox.getAttempt());
        if (intent == null || !TaskDispatchIntent.PENDING.equals(intent.getStatus())) {
            throw new TaskCommandException(
                    "DLQ_REPLAY_REQUIRES_NEW_ATTEMPT: 意图不是 pending，不能复用旧 attempt");
        }
        LocalDateTime now = LocalDateTime.now();
        DispatchOutbox replayed = outboxService.replayDead(messageId, now);
        if (replayed == null) {
            throw new TaskCommandException("DLQ_REPLAY_CONFLICT: 死信状态已变化，请刷新后重试");
        }
        auditService.recordTask(outbox.getTaskId(), "REPLAY_DEAD", "dead", "new",
                Map.of("previousMessageId", messageId,
                        "messageId", replayed.getMessageId(),
                        "attempt", outbox.getAttempt()),
                "任务所有者或管理员重放未执行的 Outbox 死信");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", replayed.getMessageId());
        result.put("previousMessageId", messageId);
        result.put("taskId", outbox.getTaskId());
        result.put("runId", outbox.getRunId());
        result.put("attempt", outbox.getAttempt());
        result.put("status", DispatchOutbox.NEW);
        return result;
    }

    public List<Map<String, Object>> dead(int limit) {
        currentUserService.requireAdmin();
        try {
            return outboxService.findDead(Math.max(1, Math.min(limit, 100))).stream()
                    .map(item -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("messageId", item.getMessageId());
                        row.put("taskId", item.getTaskId());
                        row.put("runId", item.getRunId());
                        row.put("attempt", item.getAttempt());
                        row.put("status", item.getStatus());
                        row.put("lastError", item.getLastError());
                        row.put("updatedAt", item.getUpdatedAt() == null
                                ? null : item.getUpdatedAt().toString());
                        return row;
                    }).toList();
        } catch (DataAccessException exception) {
            return List.of();
        }
    }
}
