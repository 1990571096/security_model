package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRecoveryRunnerTest {

    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final GpuLeaseService gpuLeaseService = mock(GpuLeaseService.class);
    private final AttemptProcessRegistry processRegistry = mock(AttemptProcessRegistry.class);
    private final SchedulingReadinessGate readinessGate = new SchedulingReadinessGate();
    private final TaskDispatchIntentService intentService = mock(TaskDispatchIntentService.class);
    private final TaskDispatchCoordinator dispatchCoordinator = mock(TaskDispatchCoordinator.class);
    private final DispatchRecoveryCoordinator coordinator = new DispatchRecoveryCoordinator(
            taskMapper, intentService, processRegistry, gpuLeaseService, dispatchCoordinator,
            readinessGate, mock(TaskRuntimeMetrics.class), 30, 300, 20);
    private final TaskRecoveryRunner recoveryRunner = new TaskRecoveryRunner(coordinator);

    @Test
    void marksInterruptedRunningTaskAsRetryableFailure() {
        PreDefenseTask task = runningTask(21L, "run-21");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.failOrphanedForRun(
                eq(21L), eq("run-21"), any(), any())).thenReturn(1);
        when(processRegistry.reapIfOrphaned(21L, null)).thenReturn(AttemptProcessRegistry.ReapOutcome.EXITED);

        recoveryRunner.recoverOrphanedTasks();

        verify(taskMapper).failOrphanedForRun(
                eq(21L),
                eq("run-21"),
                org.mockito.ArgumentMatchers.argThat(result ->
                        result.contains("\"error_code\":\"EXECUTOR_RESTARTED\"")
                                && result.contains("\"status\":\"failed\"")),
                any());
        verify(gpuLeaseService).releaseByRun(21L, "run-21");
    }

    @Test
    void doesNotWriteWhenThereAreNoInterruptedTasks() {
        when(taskMapper.selectList(any())).thenReturn(List.of());

        recoveryRunner.recoverOrphanedTasks();

        verify(taskMapper, never()).failOrphanedForRun(any(), any(), any(), any());
    }

    @Test
    void usesRunIdConditionalUpdateWhenAnotherTransitionWinsTheRace() {
        PreDefenseTask task = runningTask(22L, "stale-run");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.failOrphanedForRun(
                eq(22L), eq("stale-run"), any(), any())).thenReturn(0);
        when(processRegistry.reapIfOrphaned(22L, null)).thenReturn(AttemptProcessRegistry.ReapOutcome.EXITED);

        recoveryRunner.recoverOrphanedTasks();

        verify(taskMapper).failOrphanedForRun(eq(22L), eq("stale-run"), any(), any());
    }

    @Test
    void keepsLeaseWhenOrphanedPythonCannotBeKilled() {
        PreDefenseTask task = runningTask(23L, "run-23");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.failOrphanedForRun(any(), any(), any(), any())).thenReturn(1);
        when(processRegistry.reapIfOrphaned(23L, null))
                .thenReturn(AttemptProcessRegistry.ReapOutcome.KILL_FAILED);

        recoveryRunner.recoverOrphanedTasks();

        verify(gpuLeaseService, never()).releaseByRun(23L, "run-23");
        verify(gpuLeaseService).markBlocked(23L, "run-23", "ORPHANED_PROCESS_KILL_FAILED");
    }

    @Test
    void quarantinesSlotWhenProcessIdentityIsUnknown() {
        PreDefenseTask task = runningTask(24L, "run-24");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.failOrphanedForRun(any(), any(), any(), any())).thenReturn(1);
        when(processRegistry.reapIfOrphaned(24L, null))
                .thenReturn(AttemptProcessRegistry.ReapOutcome.UNKNOWN);

        recoveryRunner.recoverOrphanedTasks();

        verify(gpuLeaseService, never()).releaseByRun(24L, "run-24");
        verify(gpuLeaseService).markBlocked(24L, "run-24", "ORPHANED_PROCESS_UNKNOWN");
    }

    private PreDefenseTask runningTask(Long taskId, String runId) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(taskId);
        task.setStatus("running");
        task.setCurrentRunId(runId);
        return task;
    }
}
