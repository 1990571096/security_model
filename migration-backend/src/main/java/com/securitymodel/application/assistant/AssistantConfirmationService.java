package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.task.IdempotencyResult;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.assistant.AssistantConfirmation;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.AssistantConfirmationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * One-time confirmation. ACL, task/run state and canonical parameter hash are re-checked
 * at confirmation time; the token is consumed atomically before the business command.
 * If the command fails, the surrounding transaction rolls the consumption back.
 */
@Service
public class AssistantConfirmationService {

    private final AssistantConfirmationMapper confirmationMapper;
    private final AssistantWriteExecutor writeExecutor;
    private final TaskAccessGuard taskAccessGuard;
    private final AssistantAuditService auditService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public AssistantConfirmationService(
            AssistantConfirmationMapper confirmationMapper,
            AssistantWriteExecutor writeExecutor,
            TaskAccessGuard taskAccessGuard,
            AssistantAuditService auditService,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper) {
        this.confirmationMapper = confirmationMapper;
        this.writeExecutor = writeExecutor;
        this.taskAccessGuard = taskAccessGuard;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public AssistantConfirmationResult confirm(Long confirmationId, String token) {
        Long userId = currentUserService.requireStableIdentity().userId();
        AssistantConfirmation confirmation = confirmationMapper.findById(confirmationId);
        if (confirmation == null) {
            throw new AssistantException("ASSISTANT_CONFIRMATION_NOT_FOUND", HttpStatus.NOT_FOUND,
                    "确认凭据不存在");
        }
        if (!userId.equals(confirmation.getUserId())) {
            audit(confirmation, "DENIED", 0);
            throw new AssistantException("ASSISTANT_CONFIRMATION_FORBIDDEN", HttpStatus.FORBIDDEN,
                    "确认凭据不属于当前用户");
        }
        if (!AssistantContextHasher.sha256(token == null ? "" : token).equals(confirmation.getTokenHash())) {
            audit(confirmation, "TOKEN_MISMATCH", 0);
            throw new AssistantException("ASSISTANT_CONFIRMATION_INVALID", HttpStatus.FORBIDDEN,
                    "确认凭据无效");
        }
        if (AssistantConfirmation.CONSUMED.equals(confirmation.getStatus())) {
            audit(confirmation, "REPLAYED", 0);
            return replay(confirmation);
        }
        if (confirmation.getExpiresAt() == null
                || !confirmation.getExpiresAt().isAfter(LocalDateTime.now())) {
            audit(confirmation, "EXPIRED", 0);
            throw new AssistantException("ASSISTANT_CONFIRMATION_EXPIRED", HttpStatus.GONE,
                    "确认凭据已过期，请重新预览");
        }
        if (!AssistantConfirmation.PENDING.equals(confirmation.getStatus())) {
            throw new AssistantException("ASSISTANT_CONFIRMATION_BUSY", HttpStatus.CONFLICT,
                    "确认凭据正在处理或状态未知");
        }

        long started = System.nanoTime();
        String currentContext = currentContextHash(confirmation);
        if (!currentContext.equals(confirmation.getContextHash())) {
            audit(confirmation, "STALE", elapsedMs(started));
            throw new AssistantException("ASSISTANT_CONFIRMATION_STALE", HttpStatus.CONFLICT,
                    "任务、run 或参数已变化，请重新预览后再确认");
        }

        LocalDateTime now = LocalDateTime.now();
        int claimed = confirmationMapper.consume(confirmationId, confirmation.getTokenHash(), userId, now);
        if (claimed != 1) {
            AssistantConfirmation latest = confirmationMapper.findById(confirmationId);
            if (latest != null && AssistantConfirmation.CONSUMED.equals(latest.getStatus())) {
                return replay(latest);
            }
            throw new AssistantException("ASSISTANT_CONFIRMATION_BUSY", HttpStatus.CONFLICT,
                    "确认凭据已被处理，不能重复执行");
        }

        JsonNode request = parseRequest(confirmation.getRequestJson());
        IdempotencyResult result = writeExecutor.execute(confirmation.getOperation(), confirmationId, request);
        confirmationMapper.markConsumed(confirmationId, result.status(), result.body().toString(),
                LocalDateTime.now());
        audit(confirmation, "CONFIRMED", elapsedMs(started));
        return new AssistantConfirmationResult(result.status(), result.body(), false);
    }

    private String currentContextHash(AssistantConfirmation confirmation) {
        String operation = confirmation.getOperation();
        if ("create".equals(operation)) {
            return AssistantContextHasher.createState(confirmation.getRequestJson());
        }
        PreDefenseTask task = taskAccessGuard.requireOwned(confirmation.getTargetTaskId());
        JsonNode request = parseRequest(confirmation.getRequestJson());
        String extra = switch (operation) {
            case "cancel" -> confirmation.getTargetRunId();
            case "export" -> request.path("isolationRatio").asText();
            default -> "";
        };
        if (("cancel".equals(operation) || "export".equals(operation))
                && confirmation.getTargetRunId() != null
                && !confirmation.getTargetRunId().equals(task.getCurrentRunId())) {
            return "stale-run";
        }
        return AssistantContextHasher.taskState(operation, task, extra);
    }

    private JsonNode parseRequest(String raw) {
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("not object");
            }
            return node;
        } catch (Exception exception) {
            throw new AssistantException("ASSISTANT_CONFIRMATION_CORRUPT", HttpStatus.INTERNAL_SERVER_ERROR,
                    "确认请求记录无法读取");
        }
    }

    private AssistantConfirmationResult replay(AssistantConfirmation confirmation) {
        if (confirmation.getResultJson() == null) {
            throw new AssistantException("ASSISTANT_CONFIRMATION_CORRUPT", HttpStatus.INTERNAL_SERVER_ERROR,
                    "确认结果记录缺失");
        }
        try {
            return new AssistantConfirmationResult(
                    confirmation.getResultCode() == null ? 200 : confirmation.getResultCode(),
                    objectMapper.readTree(confirmation.getResultJson()), true);
        } catch (Exception exception) {
            throw new AssistantException("ASSISTANT_CONFIRMATION_CORRUPT", HttpStatus.INTERNAL_SERVER_ERROR,
                    "确认结果无法读取");
        }
    }

    private void audit(AssistantConfirmation confirmation, String resultCode, long elapsedMs) {
        auditService.record(null, "confirm_" + confirmation.getOperation(), confirmation.getOperation(),
                confirmation.getTargetTaskId(), confirmation.getTargetRunId(), resultCode, elapsedMs);
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
