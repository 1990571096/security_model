package com.securitymodel.application.resource;

public class ResourceConflictException extends RuntimeException {
    public ResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
