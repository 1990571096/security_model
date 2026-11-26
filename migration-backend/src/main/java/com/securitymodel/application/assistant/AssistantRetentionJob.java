package com.securitymodel.application.assistant;

import com.securitymodel.infrastructure.persistence.mapper.AssistantConfirmationMapper;
import com.securitymodel.infrastructure.persistence.mapper.AssistantToolAuditMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Bounded retention cleanup; never deletes a pending confirmation early. */
@Component
public class AssistantRetentionJob {

    private final AssistantConfirmationMapper confirmationMapper;
    private final AssistantSessionService sessionService;
    private final AssistantToolAuditMapper auditMapper;

    public AssistantRetentionJob(AssistantConfirmationMapper confirmationMapper,
                                 AssistantSessionService sessionService,
                                 AssistantToolAuditMapper auditMapper) {
        this.confirmationMapper = confirmationMapper;
        this.sessionService = sessionService;
        this.auditMapper = auditMapper;
    }

    @Scheduled(fixedDelayString = "${security-model.assistant.cleanup-interval-ms:21600000}",
            initialDelayString = "${security-model.assistant.cleanup-interval-ms:21600000}")
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        confirmationMapper.expireBatch(now, 500);
        confirmationMapper.deleteExpiredBefore(now.minusDays(14));
        sessionService.deleteExpired(500);
        auditMapper.deleteBefore(now.minusDays(30));
    }
}
