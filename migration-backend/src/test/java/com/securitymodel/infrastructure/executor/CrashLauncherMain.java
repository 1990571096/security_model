package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * Separate JVM used by the crash drill. It owns the Python parent/child, records their identity,
 * then sleeps until the drill kills this JVM. Recovery runs in the test JVM afterwards.
 */
public final class CrashLauncherMain {

    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]);
        Path taskDir = Path.of(args[1]);
        String python = args[2];
        String script = args[3];
        AttemptProcessRegistry registry = new AttemptProcessRegistry(new ObjectMapper(), workspace.toString());
        registry.prepare(taskDir);
        Process process = new ProcessBuilder(python, script, taskDir.toString())
                .redirectErrorStream(true)
                .start();
        registry.record(taskDir, process);
        Path childPidFile = taskDir.resolve("child.pid");
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!java.nio.file.Files.isRegularFile(childPidFile)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        if (java.nio.file.Files.isRegularFile(childPidFile)) {
            long childPid = Long.parseLong(java.nio.file.Files.readString(childPidFile).trim());
            registry.recordChildren(taskDir, java.util.List.of(childPid));
        }
        System.out.println("READY " + process.pid());
        System.out.flush();
        Thread.sleep(120_000);
    }
}
