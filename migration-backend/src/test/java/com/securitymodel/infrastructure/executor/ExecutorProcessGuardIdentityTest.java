package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorProcessGuardIdentityTest {

    @Test
    void unverifiedRecordedPidIsNeverKilledAndSlotStaysQuarantined() throws Exception {
        Path dir = Files.createTempDirectory("guard-identity-");
        Process root = new ProcessBuilder("cmd.exe", "/c", "ping -n 2 127.0.0.1 >nul").start();
        Process unrelated = new ProcessBuilder("cmd.exe", "/c", "pause").start();
        try {
            assertTrue(unrelated.isAlive(), "test child must be alive before the guard runs");
            Files.writeString(dir.resolve("python-children.json"),
                    "{\"pids\":[" + unrelated.pid() + "]}", StandardCharsets.UTF_8);
            ExecutorProcessGuard guard = new ExecutorProcessGuard(new ObjectMapper());
            assertTrue(root.waitFor(2, TimeUnit.SECONDS), "root short process should exit first");

            assertFalse(guard.ensureGone(root, dir, 500, true));
            assertTrue(unrelated.isAlive(), "an unverified reused pid must not be force-killed");
        } finally {
            root.destroyForcibly();
            unrelated.destroyForcibly();
        }
    }

    @Test
    void missingChildrenRecordIsNotTreatedAsConfirmedExit() throws Exception {
        Path dir = Files.createTempDirectory("guard-missing-record-");
        Process root = new ProcessBuilder("cmd.exe", "/c", "exit 0").start();
        try {
            assertTrue(root.waitFor(2, TimeUnit.SECONDS));
            assertFalse(new ExecutorProcessGuard(new ObjectMapper()).ensureGone(root, dir, 100, false));
        } finally {
            root.destroyForcibly();
        }
    }

    @Test
    void corruptChildrenRecordIsNotTreatedAsConfirmedExit() throws Exception {
        Path dir = Files.createTempDirectory("guard-corrupt-record-");
        Files.writeString(dir.resolve("python-children.json"), "{broken", StandardCharsets.UTF_8);
        Process root = new ProcessBuilder("cmd.exe", "/c", "exit 0").start();
        try {
            assertTrue(root.waitFor(2, TimeUnit.SECONDS));
            assertFalse(new ExecutorProcessGuard(new ObjectMapper()).ensureGone(root, dir, 100, false));
        } finally {
            root.destroyForcibly();
        }
    }

    @Test
    void emptyChildrenRecordProvesTheAdapterChildExited() throws Exception {
        Path dir = Files.createTempDirectory("guard-empty-record-");
        Files.writeString(dir.resolve("python-children.json"), "{\"pids\":[]}", StandardCharsets.UTF_8);
        Process root = new ProcessBuilder("cmd.exe", "/c", "exit 0").start();
        try {
            assertTrue(root.waitFor(2, TimeUnit.SECONDS));
            assertTrue(new ExecutorProcessGuard(new ObjectMapper()).ensureGone(root, dir, 100, false));
        } finally {
            root.destroyForcibly();
        }
    }
}
