package com.securitymodel.application.task;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gate that keeps new task launches out until startup recovery has finished probing orphaned
 * processes and leases. Read-only endpoints stay available; the flag is only consulted when a
 * command would start a Python process.
 */
@Component
public class SchedulingReadinessGate {

    private final AtomicBoolean ready = new AtomicBoolean(false);

    public void markReady() {
        ready.set(true);
    }

    /** Used by tests and by recovery failures: launching stays blocked until recovery succeeds. */
    public void markNotReady() {
        ready.set(false);
    }

    public boolean isReady() {
        return ready.get();
    }
}
