package com.securitymodel.application.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.domain.audit.OperationAudit;
import com.securitymodel.infrastructure.persistence.mapper.OperationAuditMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OperationAuditService {

    private final OperationAuditMapper auditMapper;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public OperationAuditService(
            OperationAuditMapper auditMapper,
            CurrentUserService currentUserService,
            ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    public void recordTask(
            Long taskId,
            String action,
            String beforeStatus,
            String afterStatus,
            Object requestSnapshot,
            String resultMessage) {
        record("pre_defense_task", taskId, action, beforeStatus, afterStatus, requestSnapshot, resultMessage);
    }

    public void recordExport(
            Long exportId,
            String action,
            String beforeStatus,
            String afterStatus,
            Object requestSnapshot,
            String resultMessage) {
        record("clean_subset_export", exportId, action, beforeStatus, afterStatus, requestSnapshot, resultMessage);
    }

    public void recordResource(String type, Long id, String action, String status, Object snapshot) {
        record(type, id, action, null, status, snapshot, "资源目录变更");
    }

    public List<OperationAudit> findTaskAudits(Long taskId) {
        return auditMapper.findTaskAudits(taskId);
    }

    private void record(
            String resourceType,
            Long resourceId,
            String action,
            String beforeStatus,
            String afterStatus,
            Object requestSnapshot,
            String resultMessage) {
        OperationAudit audit = new OperationAudit();
        audit.setResourceType(resourceType);
        audit.setResourceId(resourceId);
        audit.setAction(action);
        audit.setOperator(currentUserService.username());
        audit.setBeforeStatus(beforeStatus);
        audit.setAfterStatus(afterStatus);
        audit.setRequestSnapshot(toJson(requestSnapshot));
        audit.setResultMessage(resultMessage);
        audit.setCreatedTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String string) {
            return string;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"snapshot_error\":\"无法序列化请求快照\"}";
        }
    }
}
