package com.securitymodel.application.task;

/**
 * Business validation failure for task commands. {@code code} is optional; when present
 * the web layer returns it verbatim so callers get a stable machine-readable error code
 * instead of having to parse the human-readable message.
 */
public class TaskCommandException extends RuntimeException {

    private final String code;

    public TaskCommandException(String message) {
        super(message);
        this.code = null;
    }

    public TaskCommandException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
