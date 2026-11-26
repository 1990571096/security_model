package com.securitymodel.application.assistant;

import org.springframework.http.HttpStatus;

/** Explicit assistant failure with a stable API code and HTTP status. */
public class AssistantException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public AssistantException(String code, HttpStatus status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
