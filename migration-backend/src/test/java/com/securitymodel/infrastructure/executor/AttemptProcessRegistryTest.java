package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttemptProcessRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingRecordProvesTheChildNeverStarted(@TempDir Path workspace) {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.NOT_STARTED, registry.reapIfOrphaned(1L, 1));
    }

    @Test
    void startingMarkerWithoutPidIsUnknown(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-2").resolve("attempt-1"));
        Files.writeString(attemptDir.resolve("process.json"), "{\"state\":\"starting\"}", StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(2L, 1));
    }

    @Test
    void exitedProcessIsReportedAsExited(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-3").resolve("attempt-1"));
        Process process = new ProcessBuilder("cmd", "/c", "exit", "0").start();
        registry.prepare(attemptDir);
        registry.record(attemptDir, process);
        process.waitFor();

        assertEquals(AttemptProcessRegistry.ReapOutcome.EXITED, registry.reapIfOrphaned(3L, 1));
    }

    @Test
    void missingStartTimeIsUnknownAndNotKilled(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-4").resolve("attempt-1"));
        Process process = new ProcessBuilder("ping", "-n", "20", "127.0.0.1")
                .redirectErrorStream(true)
                .start();
        Files.writeString(attemptDir.resolve("process.json"),
                "{\"state\":\"running\",\"pid\":" + process.pid() + "}", StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(4L, 1));
        assertEquals(true, process.isAlive(), "unknown identity must never be killed");
        process.destroyForcibly();
    }

    @Test
    void liveProcessIsKilledWhenIdentityMatches(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-5").resolve("attempt-1"));
        Process process = new ProcessBuilder("ping", "-n", "20", "127.0.0.1")
                .redirectErrorStream(true)
                .start();
        registry.prepare(attemptDir);
        registry.record(attemptDir, process);

        assertEquals(AttemptProcessRegistry.ReapOutcome.REAPED, registry.reapIfOrphaned(5L, 1));
        assertEquals(false, process.isAlive());
    }

    @Test
    void exitedParentWithLiveRecordedChildIsUnknown(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-6").resolve("attempt-1"));
        Process parent = new ProcessBuilder("cmd", "/c", "exit", "0").start();
        Process child = new ProcessBuilder("ping", "-n", "20", "127.0.0.1")
                .redirectErrorStream(true)
                .start();
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        registry.record(attemptDir, parent);
        Files.writeString(attemptDir.resolve("python-children.json"),
                "{\"pids\":[" + child.pid() + "]}", StandardCharsets.UTF_8);
        parent.waitFor();

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(6L, 1));
        assertEquals(true, child.isAlive(), "a parent exit alone does not release the slot");
        child.destroyForcibly();
    }
}
