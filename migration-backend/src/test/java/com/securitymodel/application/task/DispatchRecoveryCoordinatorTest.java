package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchRecoveryCoordinatorTest {

    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final TaskDispatchIntentService intentService = mock(TaskDispatchIntentService.class);
    private final AttemptProcessRegistry processRegistry = mock(AttemptProcessRegistry.class);
    private final GpuLeaseService gpuLeaseService = mock(GpuLeaseService.class);
    private final TaskDispatchCoordinator dispatchCoordinator = mock(TaskDispatchCoordinator.class);
    private final SchedulingReadinessGate gate = new SchedulingReadinessGate();
    private final DispatchRecoveryCoordinator coordinator = new DispatchRecoveryCoordinator(
            taskMapper, intentService, processRegistry, gpuLeaseService, dispatchCoordinator,
            gate, 30, 300, 20);

    @Test
    void pendingIntentIsReplayedAndNotFailedBeforeDispatch() {
        PreDefenseTask task = runningTask(1L, "run-1", 1);
        TaskDispatchIntent intent = intent(1L, "run-1", 1, TaskDispatchIntent.PENDING,
                LocalDateTime.now().minusSeconds(10));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(intentService.findByRun(1L, "run-1", 1)).thenReturn(intent);
        when(dispatchCoordinator.replayPending(intent, task)).thenReturn(true);

        coordinator.recoverAtStartup();

        verify(dispatchCoordinator).replayPending(intent, task);
        verify(taskMapper, never()).failOrphanedForRun(any(), any(), any(), any());
        verify(taskMapper, never()).failBeforeExecution(any(), any(), any(), any(), any());
        org.junit.jupiter.api.Assertions.assertTrue(gate.isReady());
    }

    @Test
    void submittingIntentIsUncertainAndQuarantinesLiveProcess() {
        PreDefenseTask task = runningTask(1L, "run-1", 1);
        TaskDispatchIntent intent = intent(1L, "run-1", 1, TaskDispatchIntent.SUBMITTING,
                LocalDateTime.now().minusSeconds(10));
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(intentService.findByRun(1L, "run-1", 1)).thenReturn(intent);
        when(processRegistry.reapIfOrphaned(1L, 1))
                .thenReturn(AttemptProcessRegistry.ReapOutcome.KILL_FAILED);

        coordinator.recoverAtStartup();

        verify(gpuLeaseService).markBlocked(1L, "run-1", "ORPHANED_PROCESS_KILL_FAILED");
        verify(gpuLeaseService, never()).releaseByRun(1L, "run-1");
        verify(dispatchCoordinator).failAndAbandon(eq(1L), eq("run-1"), eq(1),
                eq("DISPATCH_UNCERTAIN"), any());
    }

    @Test
    void pendingPastExpiryFailsExplicitlyDuringBoundedScan() {
        gate.markReady();
        PreDefenseTask task = runningTask(1L, "run-1", 1);
        TaskDispatchIntent intent = intent(1L, "run-1", 1, TaskDispatchIntent.PENDING,
                LocalDateTime.now().minusSeconds(600));
        when(intentService.findPendingOlderThan(any(), eq(20))).thenReturn(List.of(intent));
        when(intentService.findSubmittingOlderThan(any(), eq(20))).thenReturn(List.of());
        when(taskMapper.selectById(1L)).thenReturn(task);

        coordinator.scanPending();

        verify(gpuLeaseService).releaseByRun(1L, "run-1");
        verify(dispatchCoordinator).failAndAbandon(eq(1L), eq("run-1"), eq(1),
                eq("DISPATCH_EXPIRED"), any());
        verify(dispatchCoordinator, never()).replayPending(any(), any());
    }

    private PreDefenseTask runningTask(Long taskId, String runId, Integer attempt) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(taskId);
        task.setStatus("running");
        task.setCurrentRunId(runId);
        task.setCurrentAttempt(attempt);
        return task;
    }

    private TaskDispatchIntent intent(Long taskId, String runId, Integer attempt,
                                     String status, LocalDateTime createdAt) {
        TaskDispatchIntent intent = new TaskDispatchIntent();
        intent.setTaskId(taskId);
        intent.setRunId(runId);
        intent.setAttempt(attempt);
        intent.setStatus(status);
        intent.setCreatedAt(createdAt);
        return intent;
    }
}
