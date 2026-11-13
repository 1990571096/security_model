package com.securitymodel.application.audit;

import org.springframework.security.access.AccessDeniedException;

/** Raised when an operation requires the admin role. Mapped to HTTP 403 with code {@code ADMIN_REQUIRED}. */
public class AdminRequiredException extends AccessDeniedException {

    public AdminRequiredException(String message) {
        super(message);
    }
}
