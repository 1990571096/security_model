package com.securitymodel.infrastructure.executor;

import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutorArtifactRetentionJobTest {

    @Test
    void deletesExpiredNonCurrentAttemptOfTerminalTask(@TempDir Path workspace) throws Exception {
        Path taskDir = workspace.resolve("pre_defense-7");
        Path oldAttempt = Files.createDirectories(taskDir.resolve("attempt-1"));
        Files.writeString(oldAttempt.resolve("stdout.log"), "old\n");
        Files.createDirectories(taskDir.resolve("attempt-2"));
        Files.setLastModifiedTime(oldAttempt, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(7L);
        task.setStatus("success");
        task.setCurrentAttempt(2);
        when(mapper.selectById(7L)).thenReturn(task);

        new ExecutorArtifactRetentionJob(mapper, workspace.toString(), 3).cleanup();

        assertFalse(Files.exists(oldAttempt));
        assertTrue(Files.exists(taskDir.resolve("attempt-2")));
    }

    @Test
    void keepsAttemptsOfRunningTask(@TempDir Path workspace) throws Exception {
        Path oldAttempt = Files.createDirectories(workspace.resolve("pre_defense-8").resolve("attempt-1"));
        Files.setLastModifiedTime(oldAttempt, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(8L);
        task.setStatus("running");
        task.setCurrentAttempt(1);
        when(mapper.selectById(8L)).thenReturn(task);

        new ExecutorArtifactRetentionJob(mapper, workspace.toString(), 3).cleanup();

        assertTrue(Files.exists(oldAttempt));
    }

    @Test
    void disabledRetentionDeletesNothing(@TempDir Path workspace) throws Exception {
        Path oldAttempt = Files.createDirectories(workspace.resolve("pre_defense-9").resolve("attempt-1"));
        Files.setLastModifiedTime(oldAttempt, FileTime.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
        new ExecutorArtifactRetentionJob(mapper, workspace.toString(), 0).cleanup();

        assertTrue(Files.exists(oldAttempt));
    }

    @Test
    void rejectsAComputedPathThatEscapesTheWorkspace(@TempDir Path workspace, @TempDir Path outside)
            throws Exception {
        ExecutorArtifactRetentionJob job = new ExecutorArtifactRetentionJob(
                mock(PreDefenseTaskMapper.class), workspace.toString(), 3);

        org.junit.jupiter.api.Assertions.assertNull(
                job.resolveSafe(outside.resolve("attempt-1")),
                "an outside directory must never be accepted for deletion");
        org.junit.jupiter.api.Assertions.assertNull(
                job.resolveSafe(workspace.resolve("..").resolve("escape").resolve("attempt-1")));
        assertTrue(job.resolveSafe(Files.createDirectories(workspace.resolve("pre_defense-12")
                .resolve("attempt-1"))) != null, "an in-workspace attempt is accepted");
    }

    @Test
    void refusesToDeleteThroughASymlinkOrJunction(@TempDir Path workspace, @TempDir Path outside) throws Exception {
        Path outsideAttempt = Files.createDirectories(outside.resolve("attempt-1"));
        Files.writeString(outsideAttempt.resolve("stdout.log"), "must stay\n");
        Path link = workspace.resolve("pre_defense-10");
        boolean linked = false;
        String linkError = "";
        try {
            Files.createSymbolicLink(link, outside);
            linked = true;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException exception) {
            linkError = exception.toString();
            try {
                Files.deleteIfExists(link);
            } catch (java.io.IOException ignored) {
                // best effort; mklink below reports the real problem
            }
            try {
                java.nio.file.Path cmd = java.nio.file.Path.of(
                        System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "cmd.exe");
                Process junction = new ProcessBuilder(cmd.toString(), "/c", "mklink", "/J",
                        link.toString(), outside.toString()).redirectErrorStream(true).start();
                String output = new String(junction.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                linkError = linkError + " | junction exit=" + junction.waitFor() + " output=" + output;
                linked = junction.exitValue() == 0;
            } catch (java.io.IOException ignored) {
                linked = false;
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(linked,
                "symlink/junction creation is not permitted on this host: " + linkError);
        Files.setLastModifiedTime(outsideAttempt, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));

        PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(10L);
        task.setStatus("success");
        task.setCurrentAttempt(2);
        when(mapper.selectById(10L)).thenReturn(task);

        new ExecutorArtifactRetentionJob(mapper, workspace.toString(), 1).cleanup();

        assertTrue(Files.exists(outsideAttempt), "a link must never lead deletion outside the workspace");
    }

    @Test
    void skipsUnknownTaskDirectories(@TempDir Path workspace) throws Exception {
        Path stray = Files.createDirectories(workspace.resolve("not-a-task").resolve("attempt-1"));
        PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);

        new ExecutorArtifactRetentionJob(mapper, workspace.toString(), 1).cleanup();

        assertTrue(Files.exists(stray));
    }
}
