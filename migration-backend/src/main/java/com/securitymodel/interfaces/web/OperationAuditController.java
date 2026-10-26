package com.securitymodel.interfaces.web;

import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.domain.audit.OperationAudit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class OperationAuditController {

    private final OperationAuditService auditService;
    private final PreDefenseTaskCommandService taskCommandService;

    public OperationAuditController(
            OperationAuditService auditService,
            PreDefenseTaskCommandService taskCommandService) {
        this.auditService = auditService;
        this.taskCommandService = taskCommandService;
    }

    @GetMapping("/task/{taskId}")
    public List<OperationAudit> taskAudits(@PathVariable Long taskId) {
        taskCommandService.findRequired(taskId);
        return auditService.findTaskAudits(taskId);
    }
}
