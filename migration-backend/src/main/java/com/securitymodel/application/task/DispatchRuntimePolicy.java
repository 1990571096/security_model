package com.securitymodel.application.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Single source of truth for dispatch mode/role. Components ask this policy instead of
 * scattering {@code if (rabbit)} checks. The invalid {@code db/worker} combination fails
 * fast at startup so a Worker never starts a second direct dispatch path in db mode.
 */
@Component
public class DispatchRuntimePolicy {

    public enum Mode { DB, RABBIT }

    public enum Role { API, WORKER, ALL }

    private final Mode mode;
    private final Role role;

    public DispatchRuntimePolicy() {
        this("db", "api");
    }

    @Autowired
    public DispatchRuntimePolicy(
            @Value("${security-model.dispatch.mode:db}") String mode,
            @Value("${security-model.dispatch.role:api}") String role) {
        this.mode = parseMode(mode);
        this.role = parseRole(role);
        if (this.mode == Mode.DB && this.role == Role.WORKER) {
            throw new IllegalStateException(
                    "db 模式只允许 role=api|all；独立 Worker 必须使用 dispatch.mode=rabbit");
        }
    }

    public Mode mode() {
        return mode;
    }

    public Role role() {
        return role;
    }

    public boolean rabbitMode() {
        return mode == Mode.RABBIT;
    }

    public boolean apiRole() {
        return role == Role.API || role == Role.ALL;
    }

    public boolean workerRole() {
        return role == Role.WORKER || role == Role.ALL;
    }

    /** db mode keeps the R3 after-commit dispatch; rabbit API never starts Python. */
    public boolean directDispatchOwnedByApi() {
        return mode == Mode.DB && apiRole();
    }

    /** rabbit API writes Outbox in the command transaction; db mode does not. */
    public boolean writesOutbox() {
        return mode == Mode.RABBIT && apiRole();
    }

    /** Only the Worker recovers in rabbit mode; API restart must not judge Worker tasks. */
    public boolean runsRecovery() {
        return mode == Mode.DB ? apiRole() : workerRole();
    }

    /** Relay is an API-process component in this single-machine design. */
    public boolean runsRelay() {
        return mode == Mode.RABBIT && apiRole();
    }

    public boolean runsConsumer() {
        return mode == Mode.RABBIT && workerRole();
    }

    public boolean workerAcquiresLease() {
        return mode == Mode.RABBIT && workerRole();
    }

    private Mode parseMode(String value) {
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("security-model.dispatch.mode 只能是 db 或 rabbit");
        }
    }

    private Role parseRole(String value) {
        try {
            return Role.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("security-model.dispatch.role 只能是 api、worker 或 all");
        }
    }
}
