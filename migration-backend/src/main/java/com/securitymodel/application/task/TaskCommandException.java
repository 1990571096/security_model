package com.securitymodel.application.task;

/**
 * Business validation failure for task commands.
 */
public class TaskCommandException extends RuntimeException {

    public TaskCommandException(String message) {
        super(message);
    }
}
