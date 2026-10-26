package com.securitymodel.application.task;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PythonProcessWaiterTest {

    @Test
    void returnsCompletedWhenAdapterExits() throws Exception {
        Process process = childProcess(50);

        assertEquals(PythonProcessWaiter.Outcome.COMPLETED,
                new PythonProcessWaiter().waitFor(process, 2, () -> false));
        assertFalse(process.isAlive());
    }

    @Test
    void returnsCanceledAndTerminatesAdapter() throws Exception {
        Process process = childProcess(30_000);

        assertEquals(PythonProcessWaiter.Outcome.CANCELED,
                new PythonProcessWaiter().waitFor(process, 2, () -> true));
        assertFalse(process.isAlive());
    }

    @Test
    void returnsTimedOutAndReportsChangedProgressFile() throws Exception {
        Process process = childProcess(30_000);
        Path progress = Files.createTempFile("security-model-progress-", ".json");
        AtomicInteger callbacks = new AtomicInteger();
        AtomicBoolean canceled = new AtomicBoolean(false);

        try {
            assertEquals(PythonProcessWaiter.Outcome.TIMED_OUT,
                    new PythonProcessWaiter().waitFor(process, 0, canceled::get,
                            progress, ignored -> callbacks.incrementAndGet()));
            assertEquals(1, callbacks.get());
            assertFalse(process.isAlive());
        } finally {
            Files.deleteIfExists(progress);
        }
    }

    private Process childProcess(long sleepMillis) throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        return new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                getClass().getName(),
                "child",
                String.valueOf(sleepMillis)
        ).start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static void main(String[] args) throws InterruptedException {
        if (args.length == 2 && "child".equals(args[0])) {
            Thread.sleep(Long.parseLong(args[1]));
        }
    }
}
