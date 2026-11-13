package com.securitymodel.application.export;

import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Converges exports that were pending or running when the JVM stopped.
 *
 * <p>It never re-dispatches automatically: a ZIP may have been half-written, so the only
 * honest outcome is an explicit failure the owner can retry through the existing
 * failure-detail endpoint. {@code pending} rows were also lost because the in-memory
 * dispatch that should have claimed them is gone.</p>
 */
@Component
public class ExportRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(ExportRecoveryRunner.class);

    private final CleanSubsetExportTaskMapper exportMapper;
    private final OperationAuditService auditService;

    public ExportRecoveryRunner(CleanSubsetExportTaskMapper exportMapper, OperationAuditService auditService) {
        this.exportMapper = exportMapper;
        this.auditService = auditService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOrphanedExports() {
        List<CleanSubsetExportTask> open = exportMapper.findOpen();
        int recovered = 0;
        for (CleanSubsetExportTask export : open) {
            LocalDateTime now = LocalDateTime.now();
            boolean running = "running".equals(export.getStatus());
            int updated = running
                    ? exportMapper.failOrphaned(export.getExportId(), "EXPORT_INTERRUPTED",
                    "应用重启后未能恢复导出进程，可重新提交导出", now)
                    : exportMapper.failBeforeExecution(export.getExportId(), "EXPORT_NOT_STARTED",
                    "应用重启前导出任务未开始执行，可重新提交导出", now);
            if (updated != 1) {
                // Another transition (cancel or retry) won the race; leave that outcome alone.
                continue;
            }
            recovered++;
            auditService.recordExport(export.getExportId(), "EXPORT_RECOVER",
                    export.getStatus(), "failed",
                    Map.of("exportId", export.getExportId(), "taskId", export.getTaskId()),
                    running ? "启动恢复：导出进程已中断" : "启动恢复：导出未开始执行");
        }
        if (!open.isEmpty()) {
            log.info("导出恢复扫描完成: scanned={}, recovered={}", open.size(), recovered);
        }
    }
}
