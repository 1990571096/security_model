package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R2 crash drill without a GPU: a separate JVM starts a short Python parent plus child, records
 * their identity, then is killed. Recovery in this JVM must kill the whole tree and prove that
 * every recorded process is gone before any lease could be released.
 */
class OrphanProcessCrashDrillTest {

    @Test
    void recoveryKillsTheTreeLeftByAKilledLauncherJvm(@TempDir Path workspace) throws Exception {
        assumeTrue(pythonAvailable(), "python is not available on this host");
        String python = "python";
        Path script = workspace.resolve("drill.py");
        Files.writeString(script, String.join("\n",
                "import os, subprocess, sys, time",
                "task_dir = sys.argv[1]",
                "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)'])",
                "open(os.path.join(task_dir, 'child.pid'), 'w').write(str(child.pid))",
                "time.sleep(120)"), StandardCharsets.UTF_8);
        Path taskDir = Files.createDirectories(workspace.resolve("pre_defense-51").resolve("attempt-1"));

        Process launcher = new ProcessBuilder(javaExecutable(), "-cp", childClasspath(),
                "com.securitymodel.infrastructure.executor.CrashLauncherMain",
                workspace.toString(), taskDir.toString(), python, script.toString())
                .redirectErrorStream(true)
                .start();
        try {
            String ready = waitForReady(launcher, 20);
            assertTrue(ready.startsWith("READY"), ready);
            long rootPid = Long.parseLong(ready.substring("READY ".length()).trim());
            waitFor(taskDir.resolve("child.pid"), 10);
            long childPid = Long.parseLong(Files.readString(taskDir.resolve("child.pid")).trim());

            // Simulate the owning JVM crash: the launcher dies, Python stays alive.
            launcher.destroyForcibly();
            launcher.waitFor(10, TimeUnit.SECONDS);
            assertTrue(ProcessHandle.of(rootPid).map(ProcessHandle::isAlive).orElse(false),
                    "drill precondition: the Python parent outlives the launcher");

            AttemptProcessRegistry registry =
                    new AttemptProcessRegistry(new ObjectMapper(), workspace.toString());
            assertEquals(AttemptProcessRegistry.ReapOutcome.REAPED, registry.reapIfOrphaned(51L, 1));
            assertFalse(ProcessHandle.of(rootPid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            launcher.destroyForcibly();
        }
    }

    private String waitForReady(Process launcher, int seconds) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        StringBuilder output = new StringBuilder();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                launcher.getInputStream(), StandardCharsets.UTF_8));
        while (System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            output.append(line).append('\n');
            if (line.startsWith("READY")) {
                return line;
            }
        }
        throw new IllegalStateException("launcher did not report READY: " + output);
    }

    private void waitFor(Path file, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(file)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("drill child pid file missing: " + file);
    }

    private String childClasspath() {
        String property = System.getProperty("surefire.test.class.path");
        return property == null || property.isBlank() ? System.getProperty("java.class.path") : property;
    }

    private String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
    }

    private boolean pythonAvailable() {
        try {
            return new ProcessBuilder("python", "-c", "print(1)").redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }
}
