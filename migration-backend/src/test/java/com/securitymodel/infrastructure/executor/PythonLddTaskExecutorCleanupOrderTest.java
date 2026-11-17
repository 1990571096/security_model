package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.task.DefenseTaskRequest;
import com.securitymodel.application.task.ExecutionFingerprintService;
import com.securitymodel.application.task.GpuLeaseService;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The accepted marker is the consumer barrier. It must be cleared only after the process
 * tree, artifact and GPU-slot decisions complete; a cleanup exception must keep it set.
 */
class PythonLddTaskExecutorCleanupOrderTest {

    @TempDir
    Path tempDir;

    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final ExecutionFingerprintService fingerprints = mock(ExecutionFingerprintService.class);
    private final GpuLeaseService gpuLeases = mock(GpuLeaseService.class);
    private final ExecutorArtifactPolicy artifacts = mock(ExecutorArtifactPolicy.class);
    private final AttemptProcessRegistry registry = mock(AttemptProcessRegistry.class);
    private final ExecutorProcessGuard processGuard = mock(ExecutorProcessGuard.class);
    private final TaskRuntimeMetrics metrics = mock(TaskRuntimeMetrics.class);

    private final PythonLddTaskExecutor executor = new PythonLddTaskExecutor(
            taskMapper, new ObjectMapper(), fingerprints, gpuLeases, artifacts, registry,
            processGuard, metrics, "python", "", 30);
    private final PythonLddTaskExecutor configured = new PythonLddTaskExecutor(
            taskMapper, new ObjectMapper(), fingerprints, gpuLeases, artifacts, registry,
            processGuard, metrics, "python", "stub-ldd.py", 30);

    @Test
    void clearsAcceptedOnlyAfterProcessArtifactAndLeaseDecision() throws Exception {
        when(fingerprints.getWorkspace()).thenReturn(tempDir.toString());
        when(processGuard.ensureGone(any(), any(), anyLong(), eq(true))).thenReturn(true);

        configured.executeAndWait(request());

        InOrder ordered = inOrder(processGuard, artifacts, gpuLeases, registry);
        ordered.verify(processGuard).ensureGone(any(), any(), anyLong(), eq(true));
        ordered.verify(artifacts).finalizeAttempt(any());
        ordered.verify(gpuLeases).releaseQuietly("lease-token");
        ordered.verify(registry).clearAccepted(7L, "run-1", 1);
    }

    @Test
    void unconfiguredExecutorReleasesLeaseWithoutQuarantining() {
        when(fingerprints.getWorkspace()).thenReturn(tempDir.toString());

        executor.executeAndWait(request());

        // No script means invoke() fails before any child can be started. Probing a missing
        // process record would only yield UNKNOWN and keep the slot blocked forever.
        verify(processGuard, never()).ensureGone(any(), any(), anyLong(), anyBoolean());
        verify(gpuLeases).releaseQuietly("lease-token");
        verify(gpuLeases, never()).markBlocked(any(), any(), any());
        verify(registry).clearAccepted(7L, "run-1", 1);
    }

    @Test
    void cleanupFailureKeepsAcceptedMarkerAndDoesNotReleaseSlot() {
        when(fingerprints.getWorkspace()).thenReturn(tempDir.toString());
        when(processGuard.ensureGone(any(), any(), anyLong(), eq(true)))
                .thenThrow(new IllegalStateException("process tree check failed"));

        assertThrows(IllegalStateException.class, () -> configured.executeAndWait(request()));

        verify(registry, never()).clearAccepted(any(), any(), any());
        verify(gpuLeases, never()).releaseQuietly(any());
        verify(gpuLeases, never()).markBlocked(any(), any(), any());
    }

    @Test
    void quarantinesSlotBeforeClearingAcceptedWhenTreeIsStillAlive() {
        when(fingerprints.getWorkspace()).thenReturn(tempDir.toString());
        when(processGuard.ensureGone(any(), any(), anyLong(), eq(true))).thenReturn(false);

        configured.executeAndWait(request());

        InOrder ordered = inOrder(processGuard, gpuLeases, registry);
        ordered.verify(processGuard).ensureGone(any(), any(), anyLong(), eq(true));
        ordered.verify(gpuLeases).markBlocked(7L, "run-1", "EXECUTOR_TREE_ALIVE");
        ordered.verify(registry).clearAccepted(7L, "run-1", 1);
        verify(gpuLeases, never()).releaseQuietly(any());
    }

    private DefenseTaskRequest request() {
        return DefenseTaskRequest.builder()
                .taskId(7L)
                .attempt(1)
                .runId("run-1")
                .taskName("cleanup-order")
                .stage("pre_defense")
                .gpuLeaseToken("lease-token")
                .build();
    }
}
