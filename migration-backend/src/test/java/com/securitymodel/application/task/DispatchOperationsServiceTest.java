package com.securitymodel.application.task;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOperationsServiceTest {

    private final CurrentUserService currentUserService = Mockito.mock(CurrentUserService.class);
    private final TaskOutboxService outboxService = Mockito.mock(TaskOutboxService.class);
    private final TaskDispatchConsumptionService consumptionService = Mockito.mock(TaskDispatchConsumptionService.class);
    private final AttemptProcessRegistry processRegistry = Mockito.mock(AttemptProcessRegistry.class);
    private final PreDefenseTaskMapper taskMapper = Mockito.mock(PreDefenseTaskMapper.class);
    private final TaskDispatchIntentService intentService = Mockito.mock(TaskDispatchIntentService.class);
    private final OperationAuditService auditService = Mockito.mock(OperationAuditService.class);
    private final WorkerOwnershipProbe ownershipProbe = Mockito.mock(WorkerOwnershipProbe.class);
    private final GpuLeaseService gpuLeaseService = Mockito.mock(GpuLeaseService.class);
    private final SchedulingReadinessGate readinessGate = new SchedulingReadinessGate();
    private final ObjectProvider<WorkerConsumerLifecycle> lifecycleProvider = Mockito.mock(ObjectProvider.class);

    @Test
    void dbModeExposesApiOutboxWorkerConsumptionAndGpuGroups() {
        Mockito.when(outboxService.countByStatus(Mockito.anyString())).thenReturn(3L);
        Mockito.when(outboxService.countBacklog(Mockito.any())).thenReturn(2L);
        Mockito.when(outboxService.oldestUnpublishedAt()).thenReturn(LocalDateTime.now().minusSeconds(90));
        Mockito.when(consumptionService.countExpiredProcessing(Mockito.any())).thenReturn(0L);
        Mockito.when(consumptionService.countByStatus("processing")).thenReturn(1L);
        Mockito.when(consumptionService.countByStatus("retry")).thenReturn(2L);
        Mockito.when(consumptionService.countByStatus("terminal")).thenReturn(9L);
        Mockito.when(consumptionService.oldestProcessingAt()).thenReturn(LocalDateTime.now().minusSeconds(30));
        Mockito.when(processRegistry.localAcceptedCount()).thenReturn(0);
        Mockito.when(gpuLeaseService.getSlotId()).thenReturn("default-gpu-0");
        Mockito.when(gpuLeaseService.findBlocked()).thenReturn(List.of());
        Mockito.when(gpuLeaseService.findActive()).thenReturn(List.of());
        readinessGate.markReady();

        Map<String, Object> summary = service().summary();

        assertEquals("db", summary.get("mode"));
        assertEquals(2L, summary.get("outboxBacklog"));
        assertEquals(1L, summary.get("consumptionProcessing"));
        assertEquals(2L, summary.get("consumptionRetry"));
        assertEquals(9L, summary.get("consumptionTerminal"));
        assertTrue(summary.containsKey("oldestProcessingSeconds"));
        assertEquals(false, summary.get("workerConsumerExpected"));
        assertEquals(true, summary.get("dispatchReady"));
        assertEquals("idle", ((Map<?, ?>) summary.get("gpuSlot")).get("state"));
        assertTrue(summary.containsKey("workerLocalAccepted"));
    }

    @Test
    void blockedGpuSlotReportsQuarantineWithoutThrowing() {
        GpuLease blocked = new GpuLease();
        blocked.setTaskId(42L);
        blocked.setRunId("run-42");
        blocked.setAttempt(1);
        blocked.setBlockedReason("EXECUTOR_TREE_ALIVE");
        blocked.setBlockedAt(LocalDateTime.now());
        blocked.setFencingToken(7L);
        Mockito.when(gpuLeaseService.getSlotId()).thenReturn("default-gpu-0");
        Mockito.when(gpuLeaseService.findBlocked()).thenReturn(List.of(blocked));
        Mockito.when(gpuLeaseService.findActive()).thenReturn(List.of());
        Mockito.when(outboxService.countByStatus(Mockito.anyString())).thenReturn(0L);
        Mockito.when(consumptionService.countByStatus(Mockito.anyString())).thenReturn(0L);

        Map<String, Object> slot = (Map<String, Object>) service().summary().get("gpuSlot");

        assertEquals("blocked", slot.get("state"));
        assertEquals("EXECUTOR_TREE_ALIVE", slot.get("blockedReason"));
        assertEquals(42L, slot.get("taskId"));
        assertEquals("run-42", slot.get("runId"));
        assertFalse(Boolean.FALSE.equals(slot.get("available")));
    }

    private DispatchOperationsService service() {
        return new DispatchOperationsService(currentUserService, new DispatchRuntimePolicy("db", "api"),
                outboxService, consumptionService, processRegistry, taskMapper, intentService,
                auditService, ownershipProbe, gpuLeaseService, readinessGate, lifecycleProvider);
    }
}
