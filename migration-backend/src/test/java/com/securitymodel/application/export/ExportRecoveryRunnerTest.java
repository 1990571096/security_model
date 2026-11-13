package com.securitymodel.application.export;

import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.infrastructure.persistence.mapper.CleanSubsetExportTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportRecoveryRunnerTest {

    private final CleanSubsetExportTaskMapper mapper = mock(CleanSubsetExportTaskMapper.class);
    private final OperationAuditService audit = mock(OperationAuditService.class);
    private final ExportRecoveryRunner runner = new ExportRecoveryRunner(mapper, audit);

    @Test
    void convergesRunningAndPendingExportsToExplicitFailures() {
        CleanSubsetExportTask running = export(1L, "running");
        CleanSubsetExportTask pending = export(2L, "pending");
        when(mapper.findOpen()).thenReturn(List.of(running, pending));
        when(mapper.failOrphaned(eq(1L), eq("EXPORT_INTERRUPTED"), anyString(), any())).thenReturn(1);
        when(mapper.failBeforeExecution(eq(2L), eq("EXPORT_NOT_STARTED"), anyString(), any())).thenReturn(1);

        runner.recoverOrphanedExports();

        verify(mapper).failOrphaned(eq(1L), eq("EXPORT_INTERRUPTED"), anyString(), any());
        verify(mapper).failBeforeExecution(eq(2L), eq("EXPORT_NOT_STARTED"), anyString(), any());
        verify(audit, times(2)).recordExport(anyLong(), eq("EXPORT_RECOVER"), anyString(), eq("failed"),
                any(), anyString());
        assertEquals(2, org.mockito.Mockito.mockingDetails(mapper).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("fail")).count());
    }

    @Test
    void doesNotAuditAnExportWhoseStateTransitionLostTheRace() {
        CleanSubsetExportTask running = export(3L, "running");
        when(mapper.findOpen()).thenReturn(List.of(running));
        when(mapper.failOrphaned(eq(3L), anyString(), anyString(), any())).thenReturn(0);

        runner.recoverOrphanedExports();

        verify(audit, never()).recordExport(anyLong(), anyString(), anyString(), anyString(), any(), anyString());
    }

    private CleanSubsetExportTask export(long id, String status) {
        CleanSubsetExportTask task = new CleanSubsetExportTask();
        task.setExportId(id);
        task.setTaskId(100L + id);
        task.setStatus(status);
        return task;
    }
}
