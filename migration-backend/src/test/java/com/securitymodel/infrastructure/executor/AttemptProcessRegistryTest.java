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
    void missingRecordIsUnknownBecauseItCannotProveTheChildNeverStarted(@TempDir Path workspace) {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(1L, 1));
    }

    @Test
    void legacyRecordWithoutSchemaVersionIsUnknown(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-1").resolve("attempt-1"));
        Files.writeString(attemptDir.resolve("process.json"), "{\"state\":\"running\",\"pid\":12345}",
                StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(1L, 1));
    }

    @Test
    void startingMarkerWithoutPidIsUnknown(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-2").resolve("attempt-1"));
        Files.writeString(attemptDir.resolve("process.json"),
                "{\"schema_version\":2,\"state\":\"starting\"}", StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(2L, 1));
    }

    @Test
    void exitedProcessIsReportedAsExited(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-3").resolve("attempt-1"));
        Process process = exitProcess();
        registry.prepare(attemptDir);
        registry.record(attemptDir, process);
        Files.writeString(attemptDir.resolve("python-children.json"), "{\"pids\":[]}", StandardCharsets.UTF_8);
        process.waitFor();

        assertEquals(AttemptProcessRegistry.ReapOutcome.EXITED, registry.reapIfOrphaned(3L, 1));
    }

    @Test
    void missingStartTimeIsUnknownAndNotKilled(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-4").resolve("attempt-1"));
        Process process = sleepingProcess();
        Files.writeString(attemptDir.resolve("process.json"),
                "{\"schema_version\":2,\"state\":\"running\",\"pid\":" + process.pid() + "}",
                StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(4L, 1));
        assertEquals(true, process.isAlive(), "unknown identity must never be killed");
        process.destroyForcibly();
    }

    @Test
    void liveProcessIsKilledWhenIdentityMatches(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-5").resolve("attempt-1"));
        Process process = sleepingProcess();
        registry.prepare(attemptDir);
        registry.record(attemptDir, process);
        Files.writeString(attemptDir.resolve("python-children.json"), "{\"pids\":[]}", StandardCharsets.UTF_8);

        assertEquals(AttemptProcessRegistry.ReapOutcome.REAPED, registry.reapIfOrphaned(5L, 1));
        assertEquals(false, process.isAlive());
    }

    @Test
    void exitedParentWithLiveRecordedChildIsUnknown(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-6").resolve("attempt-1"));
        Process parent = exitProcess();
        Process child = sleepingProcess();
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        registry.record(attemptDir, parent);
        Files.writeString(attemptDir.resolve("python-children.json"),
                "{\"pids\":[" + child.pid() + "]}", StandardCharsets.UTF_8);
        parent.waitFor();

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(6L, 1));
        assertEquals(true, child.isAlive(), "a parent exit alone does not release the slot");
        child.destroyForcibly();
    }

    @Test
    void reusedPidIsNotKilledAndIsReportedExited(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-7").resolve("attempt-1"));
        Process unrelated = sleepingProcess();
        Files.writeString(attemptDir.resolve("process.json"),
                "{\"schema_version\":2,\"state\":\"running\",\"pid\":" + unrelated.pid()
                        + ",\"started_at\":\"2000-01-01T00:00:00Z\"}", StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Files.writeString(attemptDir.resolve("python-children.json"), "{\"pids\":[]}", StandardCharsets.UTF_8);

        assertEquals(AttemptProcessRegistry.ReapOutcome.EXITED, registry.reapIfOrphaned(7L, 1));
        assertEquals(true, unrelated.isAlive(), "a reused pid must never be killed");
        unrelated.destroyForcibly();
    }

    @Test
    void corruptedRecordIsUnknown(@TempDir Path workspace) throws Exception {
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-8").resolve("attempt-1"));
        Files.writeString(attemptDir.resolve("process.json"), "{not-json", StandardCharsets.UTF_8);
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(8L, 1));
    }

    @Test
    void missingChildrenRecordIsUnknownEvenWhenRootIsExited(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-12").resolve("attempt-1"));
        Process process = exitProcess();
        registry.prepare(attemptDir);
        registry.record(attemptDir, process);
        process.waitFor();

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(12L, 1));
    }

    @Test
    void corruptedChildrenRecordDoesNotKillUnverifiedPid(@TempDir Path workspace) throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path attemptDir = Files.createDirectories(workspace.resolve("pre_defense-13").resolve("attempt-1"));
        Process unrelated = sleepingProcess();
        registry.prepare(attemptDir);
        registry.record(attemptDir, unrelated);
        Files.writeString(attemptDir.resolve("python-children.json"), "{broken", StandardCharsets.UTF_8);

        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(13L, 1));
        assertEquals(true, unrelated.isAlive());
        unrelated.destroyForcibly();
    }

    @Test
    void refusesToPrepareOutsideTheConfiguredWorkspace(@TempDir Path workspace, @TempDir Path outside)
            throws Exception {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace.toString());
        Path foreignDir = Files.createDirectories(outside.resolve("pre_defense-11").resolve("attempt-1"));

        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> registry.prepare(foreignDir));
    }

    @Test
    void blankWorkspaceNeverFallsBackToTheRepositoryRoot() {
        AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, "");
        assertEquals(AttemptProcessRegistry.ReapOutcome.UNKNOWN, registry.reapIfOrphaned(9L, 1));
    }

    private static Process exitProcess() throws java.io.IOException {
        if (isWindows()) {
            return new ProcessBuilder("cmd", "/c", "exit", "0").start();
        }
        return new ProcessBuilder("sh", "-c", "exit 0").start();
    }

    private static Process sleepingProcess() throws java.io.IOException {
        if (isWindows()) {
            return new ProcessBuilder("ping", "-n", "20", "127.0.0.1")
                    .redirectErrorStream(true).start();
        }
        return new ProcessBuilder("sh", "-c", "sleep 20")
                .redirectErrorStream(true).start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
