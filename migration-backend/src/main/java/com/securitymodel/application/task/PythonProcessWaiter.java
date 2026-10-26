package com.securitymodel.application.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class PythonProcessWaiter {
    public Outcome waitFor(Process process, long timeoutSeconds, BooleanSupplier canceled)
            throws InterruptedException {
        return waitFor(process, timeoutSeconds, canceled, null, null);
    }

    public Outcome waitFor(Process process, long timeoutSeconds, BooleanSupplier canceled,
                           Path progressFile, Consumer<Path> progressListener)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0L, timeoutSeconds));
        long lastModified = Long.MIN_VALUE;
        long lastSize = Long.MIN_VALUE;
        while (process.isAlive()) {
            if (progressFile != null && progressListener != null && Files.isRegularFile(progressFile)) {
                try {
                    long modified = Files.getLastModifiedTime(progressFile).toMillis();
                    long size = Files.size(progressFile);
                    if (modified != lastModified || size != lastSize) {
                        progressListener.accept(progressFile);
                        lastModified = modified;
                        lastSize = size;
                    }
                } catch (Exception ignored) {
                    // A progress snapshot is supplementary; execution must continue
                    // when the file is being atomically replaced or temporarily unreadable.
                }
            }
            if (canceled != null && canceled.getAsBoolean()) {
                destroyProcessTree(process);
                return Outcome.CANCELED;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                destroyProcessTree(process);
                return Outcome.TIMED_OUT;
            }
            process.waitFor(Math.min(200L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))),
                    TimeUnit.MILLISECONDS);
        }
        if (progressFile != null && progressListener != null && Files.isRegularFile(progressFile)) {
            try {
                progressListener.accept(progressFile);
            } catch (Exception ignored) {
                // The final result remains the source of truth if the last snapshot is unavailable.
            }
        }
        return Outcome.COMPLETED;
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    public enum Outcome {
        COMPLETED, CANCELED, TIMED_OUT
    }
}
