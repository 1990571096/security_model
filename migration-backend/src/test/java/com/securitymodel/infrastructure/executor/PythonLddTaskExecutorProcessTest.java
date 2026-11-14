package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.task.DefenseTaskRequest;
import com.securitymodel.application.task.ExecutionFingerprintService;
import com.securitymodel.application.task.GpuLeaseService;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * R2-07: cancel/timeout must terminate the short parent+child tree and only then release
 * the lease; a surviving tree must quarantine the slot. Uses a stub Python process, never LDD.
 */
class PythonLddTaskExecutorProcessTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PreDefenseTaskMapper taskMapper = mock(PreDefenseTaskMapper.class);
    private final GpuLeaseService leaseService = mock(GpuLeaseService.class);

    @Test
    void timeoutKillsTheWholeShortProcessTreeAndReleasesOnlyAfterExit(@TempDir Path workspace) throws Exception {
        assumeTrue(pythonAvailable(), "python is not available on this host");
        PythonLddTaskExecutor executor = executor(workspace, 1);
        DefenseTaskRequest request = request(41L, "run-timeout");

        executor.runPreDefense(request);

        verify(leaseService, never()).markBlocked(any(), anyString(), anyString());
        verify(leaseService).releaseQuietly(request.getGpuLeaseToken());
        assertFalse(rootAlive(workspace, 41L), "the timed-out parent must be gone");
        assertFalse(childAlive(workspace, 41L), "the timed-out descendant must be gone");
    }

    @Test
    void cancelKillsTheWholeShortProcessTreeBeforeReleasingTheLease(@TempDir Path workspace) throws Exception {
        assumeTrue(pythonAvailable(), "python is not available on this host");
        PythonLddTaskExecutor executor = executor(workspace, 60);
        DefenseTaskRequest request = request(42L, "run-cancel");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> executor.runPreDefense(request));
            waitFor(workspace.resolve("pre_defense-42").resolve("attempt-1").resolve("root.pid"), 10);

            executor.cancelPreDefense(42L, "run-cancel");
            worker.shutdown();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));

            verify(leaseService).releaseQuietly(request.getGpuLeaseToken());
            assertFalse(rootAlive(workspace, 42L), "cancel must stop the parent");
            assertFalse(childAlive(workspace, 42L), "cancel must stop descendants too");
        } finally {
            worker.shutdownNow();
        }
    }

    private PythonLddTaskExecutor executor(Path workspace, long timeoutSeconds) throws IOException {
        Path script = workspace.resolve("stub_ldd.py");
        Files.writeString(script, String.join("\n",
                "import os, subprocess, sys, time",
                "root = os.path.dirname(sys.argv[sys.argv.index('--result') + 1])",
                "open(os.path.join(root, 'root.pid'), 'w').write(str(os.getpid()))",
                "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])",
                "open(os.path.join(root, 'child.pid'), 'w').write(str(child.pid))",
                "time.sleep(60)"), StandardCharsets.UTF_8);
        ExecutionFingerprintService fingerprint =
                new ExecutionFingerprintService(objectMapper, workspace.toString(), "python", script.toString(),
                        false, 1024);
        return new PythonLddTaskExecutor(taskMapper, objectMapper, fingerprint, leaseService,
                new ExecutorArtifactPolicy(objectMapper, 0),
                new AttemptProcessRegistry(objectMapper, workspace.toString()),
                new ExecutorProcessGuard(objectMapper), mock(TaskRuntimeMetrics.class),
                "python", script.toString(), timeoutSeconds);
    }

    private DefenseTaskRequest request(long taskId, String runId) {
        return DefenseTaskRequest.builder()
                .taskId(taskId)
                .runId(runId)
                .attempt(1)
                .stage("pre_defense")
                .gpuLeaseToken("token-" + taskId)
                .build();
    }

    private void waitFor(Path file, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("stub process did not start: " + file);
    }

    private boolean rootAlive(Path workspace, long taskId) throws IOException {
        return pidAlive(workspace.resolve("pre_defense-" + taskId).resolve("attempt-1").resolve("root.pid"));
    }

    private boolean childAlive(Path workspace, long taskId) throws IOException {
        return pidAlive(workspace.resolve("pre_defense-" + taskId).resolve("attempt-1").resolve("child.pid"));
    }

    private boolean pidAlive(Path pidFile) throws IOException {
        if (!Files.isRegularFile(pidFile)) {
            return false;
        }
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private boolean pythonAvailable() {
        try {
            return new ProcessBuilder("python", "-c", "print(1)").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }
}
