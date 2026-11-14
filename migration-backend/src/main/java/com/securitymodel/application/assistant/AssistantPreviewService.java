package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantConfirmation;
import com.securitymodel.infrastructure.persistence.mapper.AssistantConfirmationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Converts a write-tool preview into a one-time token. The raw token is returned once and
 * never persisted; only its SHA-256 hash is stored.
 */
@Service
public class AssistantPreviewService {

    private final AssistantToolRegistry registry;
    private final AssistantConfirmationMapper confirmationMapper;
    private final AssistantAuditService auditService;
    private final CurrentUserService currentUserService;
    private final SecureRandom random = new SecureRandom();
    private final int confirmationTtlSeconds;
    private final int maxPending;

    public AssistantPreviewService(
            AssistantToolRegistry registry,
            AssistantConfirmationMapper confirmationMapper,
            AssistantAuditService auditService,
            CurrentUserService currentUserService,
            @Value("${security-model.assistant.confirmation-ttl-seconds:300}") int confirmationTtlSeconds,
            @Value("${security-model.assistant.max-pending-confirmations:10}") int maxPending) {
        this.registry = registry;
        this.confirmationMapper = confirmationMapper;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.confirmationTtlSeconds = Math.max(30, Math.min(900, confirmationTtlSeconds));
        this.maxPending = Math.max(1, maxPending);
    }

    public AssistantPreviewResponse preview(String toolName, JsonNode arguments) {
        return preview(toolName, arguments, null);
    }

    public AssistantPreviewResponse preview(String toolName, JsonNode arguments, Long sessionId) {
        Long userId = currentUserService.requireStableIdentity().userId();
        AssistantTool tool = registry.find(toolName);
        if (!tool.descriptor().write()) {
            throw new AssistantException("ASSISTANT_TOOL_NOT_WRITE", HttpStatus.BAD_REQUEST,
                    "只读工具不能生成写确认");
        }
        if (confirmationMapper.countActive(userId, LocalDateTime.now()) >= maxPending) {
            throw new AssistantException("ASSISTANT_PENDING_LIMIT", HttpStatus.TOO_MANY_REQUESTS,
                    "待确认操作过多，请先确认或等待过期");
        }

        long started = System.nanoTime();
        AssistantToolResult result;
        try {
            result = tool.execute(arguments);
        } catch (RuntimeException exception) {
            auditService.record(sessionId, toolName, null, null, null, "FAILED",
                    elapsedMs(started));
            throw exception;
        }
        if (result.operation() == null || result.request() == null || result.contextHash() == null) {
            throw new AssistantException("ASSISTANT_PREVIEW_INVALID", HttpStatus.BAD_REQUEST,
                    "写工具没有返回可确认的预览");
        }

        String token = newToken();
        AssistantConfirmation confirmation = new AssistantConfirmation();
        confirmation.setTokenHash(AssistantContextHasher.sha256(token));
        confirmation.setUserId(userId);
        confirmation.setOperation(result.operation());
        confirmation.setTargetTaskId(result.targetTaskId());
        confirmation.setTargetRunId(result.targetRunId());
        confirmation.setContextHash(result.contextHash());
        confirmation.setRequestJson(result.request().toString());
        confirmation.setPreviewJson(result.data().toString());
        confirmation.setStatus(AssistantConfirmation.PENDING);
        confirmation.setCreatedAt(LocalDateTime.now());
        confirmation.setExpiresAt(LocalDateTime.now().plusSeconds(confirmationTtlSeconds));
        confirmationMapper.insert(confirmation);

        auditService.record(sessionId, toolName, result.operation(), result.targetTaskId(),
                result.targetRunId(), "PREVIEWED", elapsedMs(started));
        return new AssistantPreviewResponse(confirmation.getConfirmationId(), token,
                confirmation.getExpiresAt(), result.operation(), result.data());
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
