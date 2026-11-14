package com.securitymodel.application.assistant;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantToolAudit;
import com.securitymodel.infrastructure.persistence.mapper.AssistantToolAuditMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tool-level audit. Deliberately stores no prompt, no raw arguments, no document text and
 * no credentials: tool name, operator, target, result code and duration are enough to
 * reconstruct who asked for what without leaking the conversation.
 */
@Service
public class AssistantAuditService {

    private final AssistantToolAuditMapper auditMapper;
    private final CurrentUserService currentUserService;

    public AssistantAuditService(AssistantToolAuditMapper auditMapper,
                                 CurrentUserService currentUserService) {
        this.auditMapper = auditMapper;
        this.currentUserService = currentUserService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long sessionId, String toolName, String operation, Long targetTaskId,
                       String targetRunId, String resultCode, long durationMs) {
        AssistantToolAudit audit = new AssistantToolAudit();
        audit.setSessionId(sessionId);
        audit.setUserId(currentUserService.requireStableIdentity().userId());
        audit.setToolName(toolName);
        audit.setOperation(operation);
        audit.setTargetTaskId(targetTaskId);
        audit.setTargetRunId(targetRunId);
        audit.setResultCode(resultCode);
        audit.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs)));
        audit.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    public List<AssistantToolAudit> findMine(int limit) {
        Long userId = currentUserService.requireStableIdentity().userId();
        return auditMapper.findByUser(userId, Math.max(1, Math.min(100, limit)));
    }
}
