package com.securitymodel.interfaces.web;

import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.securitymodel.application.audit.AdminRequiredException;
import com.securitymodel.application.task.TaskCommandException;
import com.securitymodel.application.resource.ResourceConflictException;
import com.securitymodel.application.resource.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "RESOURCE_NOT_FOUND",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ResourceConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "RESOURCE_CONFLICT",
                "message", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        var error = exception.getBindingResult().getFieldError();
        return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION_FAILED",
                "message", error == null ? "请求字段无效" : error.getField() + ": " + error.getDefaultMessage()));
    }

    @ExceptionHandler(AdminRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleAdminRequired(
            AdminRequiredException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "ADMIN_REQUIRED",
                "message", exception.getMessage(),
                "path", request.getRequestURI(),
                "timestamp", OffsetDateTime.now().toString()
        ));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "code", "ACCESS_DENIED",
                "message", exception.getMessage(),
                "path", request.getRequestURI(),
                "timestamp", OffsetDateTime.now().toString()
        ));
    }

    @ExceptionHandler(TaskCommandException.class)
    public ResponseEntity<Map<String, Object>> handleTaskCommand(
            TaskCommandException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "TASK_COMMAND_INVALID",
                "message", exception.getMessage(),
                "path", request.getRequestURI(),
                "timestamp", OffsetDateTime.now().toString()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", "REQUEST_INVALID",
                "message", exception.getMessage() == null ? "请求参数无效" : exception.getMessage(),
                "path", request.getRequestURI(),
                "timestamp", OffsetDateTime.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API error: {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "code", "INTERNAL_ERROR",
                "message", "服务处理失败",
                "path", request.getRequestURI(),
                "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
