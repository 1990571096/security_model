package me.zhengjie.modules.system.service.executor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PythonProcessWaiterTest {

    private final PythonProcessWaiter waiter = new PythonProcessWaiter();

    @Test
    void returnsCompletedForNormalProcess() throws Exception {
        Process process = startProcess("exit 0", "exit 0");

        assertEquals(
                PythonProcessWaiter.Outcome.COMPLETED,
                waiter.waitFor(process, 3, () -> false));
        assertEquals(0, process.exitValue());
    }

    @Test
    void forciblyTerminatesTimedOutProcess() throws Exception {
        Process process = startProcess("ping -n 6 127.0.0.1 > nul", "sleep 6");

        assertEquals(
                PythonProcessWaiter.Outcome.TIMED_OUT,
                waiter.waitFor(process, 1, () -> false));
        assertFalse(process.isAlive());
    }

    @Test
    void observesCancellationBeforeTimeout() throws Exception {
        Process process = startProcess("ping -n 6 127.0.0.1 > nul", "sleep 6");
        AtomicBoolean canceled = new AtomicBoolean();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(250);
                canceled.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        canceller.start();

        assertEquals(
                PythonProcessWaiter.Outcome.CANCELED,
                waiter.waitFor(process, 5, canceled::get));
        canceller.join(2000);
        assertFalse(process.isAlive());
    }

    private Process startProcess(String windowsCommand, String unixCommand) throws Exception {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", windowsCommand).start();
        }
        return new ProcessBuilder("sh", "-c", unixCommand).start();
    }
}
