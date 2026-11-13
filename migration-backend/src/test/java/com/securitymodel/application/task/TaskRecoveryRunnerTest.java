package com.securitymodel.application.task;

import com.securitymodel.domain.task.PreDefenseTask;
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
    private final TaskRecoveryRunner recoveryRunner = new TaskRecoveryRunner(taskMapper, gpuLeaseService);

    @Test
    void marksInterruptedRunningTaskAsRetryableFailure() {
        PreDefenseTask task = runningTask(21L, "run-21");
        when(taskMapper.selectList(any())).thenReturn(List.of(task));
        when(taskMapper.failOrphanedForRun(
                eq(21L), eq("run-21"), any(), any())).thenReturn(1);

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

        recoveryRunner.recoverOrphanedTasks();

        verify(taskMapper).failOrphanedForRun(eq(22L), eq("stale-run"), any(), any());
    }

    private PreDefenseTask runningTask(Long taskId, String runId) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(taskId);
        task.setStatus("running");
        task.setCurrentRunId(runId);
        return task;
    }
}
