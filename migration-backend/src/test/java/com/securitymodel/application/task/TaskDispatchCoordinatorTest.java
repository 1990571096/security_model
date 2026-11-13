package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.lease.GpuLease;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskDispatchCoordinatorTest {

    private final TaskDispatchIntentService intentService = mock(TaskDispatchIntentService.class);
    private final DefenseTaskExecutor executor = mock(DefenseTaskExecutor.class);
    private final GpuLeaseService gpuLeaseService = mock(GpuLeaseService.class);
    private final ExecutionFingerprintService fingerprintService = mock(ExecutionFingerprintService.class);
    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final com.securitymodel.infrastructure.persistence.mapper.SysUserMapper userMapper =
            mock(com.securitymodel.infrastructure.persistence.mapper.SysUserMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TaskDispatchCoordinator coordinator = new TaskDispatchCoordinator(
            intentService, executor, gpuLeaseService, fingerprintService, taskMapper, userMapper, objectMapper);

    @Test
    void onlyTheConditionalPromotionWinnerDispatches() {
        DefenseTaskRequest request = request();
        when(intentService.markSubmitting(eq(1L), eq("run-1"), eq(1), any())).thenReturn(false);

        coordinator.dispatchAfterCommit(request);

        verify(executor, never()).runPreDefense(any());
        verify(taskMapper, never()).failBeforeExecution(any(), any(), any(), any(), any());
    }

    @Test
    void executorRejectionConvergesTaskAndAbandonsIntent() {
        DefenseTaskRequest request = request();
        when(intentService.markSubmitting(eq(1L), eq("run-1"), eq(1), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new TaskCommandException("GPU_BUSY: 占用"))
                .when(executor).runPreDefense(any());

        coordinator.dispatchAfterCommit(request);

        verify(taskMapper).failBeforeExecution(eq(1L), eq("run-1"), eq(1), any(), any());
        verify(intentService).abandon(eq(1L), eq("run-1"), eq(1), eq("GPU_BUSY"), any());
    }

    @Test
    void acceptedWorkIsNotFailedWhenBookkeepingFails() {
        DefenseTaskRequest request = request();
        when(intentService.markSubmitting(eq(1L), eq("run-1"), eq(1), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(intentService).markSubmitted(eq(1L), eq("run-1"), eq(1), any());

        coordinator.dispatchAfterCommit(request);

        verify(executor).runPreDefense(request);
        verify(taskMapper, never()).failBeforeExecution(any(), any(), any(), any(), any());
        verify(intentService, never()).abandon(any(), any(), any(), any(), any());
    }

    @Test
    void replayDoesNotInvokeExecutorWhenGpuIsBusy() {
        TaskDispatchIntent intent = intent();
        when(intentService.readRequest(intent)).thenReturn(request());
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.set("datasetFingerprint", objectMapper.createObjectNode().put("available", true));
        runtime.set("algorithmFingerprint", objectMapper.createObjectNode().put("available", true));
        when(fingerprintService.buildRuntimeSnapshot(any())).thenReturn(runtime);
        when(fingerprintService.sha256(any())).thenReturn(intent.getRuntimeHash());
        when(intentService.markSubmitting(eq(1L), eq("run-1"), eq(1), any())).thenReturn(true);
        when(gpuLeaseService.acquire(eq(1L), eq("run-1"), eq(1)))
                .thenThrow(new TaskCommandException("GPU_BUSY: 占用"));

        assertFalse(coordinator.replayPending(intent, task()));

        verify(executor, never()).runPreDefense(any());
        verify(taskMapper).failBeforeExecution(eq(1L), eq("run-1"), eq(1), any(), any());
        verify(intentService).abandon(eq(1L), eq("run-1"), eq(1), eq("GPU_BUSY"), any());
    }

    @Test
    void replayVersionsTheLeaseBeforeDispatch() {
        TaskDispatchIntent intent = intent();
        DefenseTaskRequest request = request();
        when(intentService.readRequest(intent)).thenReturn(request);
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.set("datasetFingerprint", objectMapper.createObjectNode().put("available", true));
        runtime.set("algorithmFingerprint", objectMapper.createObjectNode().put("available", true));
        when(fingerprintService.buildRuntimeSnapshot(any())).thenReturn(runtime);
        when(fingerprintService.sha256(any())).thenReturn(intent.getRuntimeHash());
        when(intentService.markSubmitting(eq(1L), eq("run-1"), eq(1), any())).thenReturn(true);
        GpuLease lease = new GpuLease();
        lease.setSlotId("default-gpu-0");
        lease.setLeaseToken("new-token");
        lease.setFencingToken(9L);
        when(gpuLeaseService.acquire(eq(1L), eq("run-1"), eq(1))).thenReturn(lease);

        assertTrue(coordinator.replayPending(intent, task()));

        verify(gpuLeaseService).releaseByRun(1L, "run-1");
        verify(executor).runPreDefense(request);
        org.junit.jupiter.api.Assertions.assertEquals("new-token", request.getGpuLeaseToken());
        verify(intentService).markSubmitted(eq(1L), eq("run-1"), eq(1), any());
    }

    @Test
    void disabledOwnerIsNotReplayed() {
        TaskDispatchIntent intent = intent();
        PreDefenseTask task = task();
        task.setOwnerUserId(7L);
        com.securitymodel.domain.user.SysUser disabled = new com.securitymodel.domain.user.SysUser();
        disabled.setEnabled(false);
        when(userMapper.selectById(7L)).thenReturn(disabled);

        assertFalse(coordinator.replayPending(intent, task));

        verify(executor, never()).runPreDefense(any());
        verify(taskMapper).failBeforeExecution(eq(1L), eq("run-1"), eq(1), any(), any());
        verify(intentService).abandon(eq(1L), eq("run-1"), eq(1),
                eq("DISPATCH_IDENTITY_MISMATCH"), any());
    }

    @Test
    void changedRuntimeFingerprintIsNotReplayed() {
        TaskDispatchIntent intent = intent();
        when(intentService.readRequest(intent)).thenReturn(request());
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.set("datasetFingerprint", objectMapper.createObjectNode().put("available", true));
        runtime.set("algorithmFingerprint", objectMapper.createObjectNode().put("available", true));
        when(fingerprintService.buildRuntimeSnapshot(any())).thenReturn(runtime);
        when(fingerprintService.sha256(any())).thenReturn("different-runtime-hash");

        assertFalse(coordinator.replayPending(intent, task()));

        verify(executor, never()).runPreDefense(any());
        verify(taskMapper).failBeforeExecution(eq(1L), eq("run-1"), eq(1), any(), any());
        verify(intentService).abandon(eq(1L), eq("run-1"), eq(1),
                eq("DISPATCH_SNAPSHOT_CHANGED"), any());
    }

    private DefenseTaskRequest request() {
        return DefenseTaskRequest.builder()
                .taskId(1L).runId("run-1").attempt(1).taskName("LDD")
                .createdBy("tester").build();
    }

    private TaskDispatchIntent intent() {
        TaskDispatchIntent intent = new TaskDispatchIntent();
        intent.setTaskId(1L);
        intent.setRunId("run-1");
        intent.setAttempt(1);
        intent.setStatus(TaskDispatchIntent.PENDING);
        intent.setRuntimeHash("runtime-hash");
        return intent;
    }

    private PreDefenseTask task() {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(1L);
        task.setStatus("running");
        task.setCurrentRunId("run-1");
        task.setCurrentAttempt(1);
        return task;
    }
}
