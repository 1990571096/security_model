package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttemptProcessRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingRecordMeansNothingToReap(@TempDir Path workspace) {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.NO_RECORD, registry.reapIfOrphaned(1L, 1));
    }

    @Test
    void exitedProcessIsReportedAsExited(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-2").resolve("attempt-1"));
        Process process = new ProcessBuilder("cmd", "/c", "exit", "0").start();
        registry.record(attemptDir, process);
        process.waitFor();

        assertEquals(AttemptProcessRegistry.ReapOutcome.EXITED, registry.reapIfOrphaned(2L, 1));
    }

    @Test
    void liveProcessIsKilledBeforeLeaseRelease(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-3").resolve("attempt-1"));
        Process process = new ProcessBuilder("ping", "-n", "20", "127.0.0.1")
                .redirectErrorStream(true)
                .start();
        registry.record(attemptDir, process);

        assertEquals(AttemptProcessRegistry.ReapOutcome.REAPED, registry.reapIfOrphaned(3L, 1));
        assertEquals(false, process.isAlive());
    }
}
