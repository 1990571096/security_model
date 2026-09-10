package me.zhengjie.modules.system.service.executor;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Waits for an external Python process while allowing cancellation to be
 * observed before the full timeout elapses.
 */
public class PythonProcessWaiter {

    private static final long POLL_MILLIS = 200L;

    public Outcome waitFor(Process process, long timeoutSeconds, BooleanSupplier canceled)
            throws InterruptedException {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        long timeoutNanos = TimeUnit.SECONDS.toNanos(Math.max(0L, timeoutSeconds));
        long deadline = System.nanoTime() + timeoutNanos;

        while (process.isAlive()) {
            if (canceled != null && canceled.getAsBoolean()) {
                process.destroyForcibly();
                return Outcome.CANCELED;
            }

            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                process.destroyForcibly();
                return Outcome.TIMED_OUT;
            }

            long waitMillis = Math.min(POLL_MILLIS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            process.waitFor(waitMillis, TimeUnit.MILLISECONDS);
        }
        return Outcome.COMPLETED;
    }

    public enum Outcome {
        COMPLETED,
        CANCELED,
        TIMED_OUT
    }
}
