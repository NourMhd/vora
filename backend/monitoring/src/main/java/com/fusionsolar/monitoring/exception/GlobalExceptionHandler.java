package com.fusionsolar.monitoring.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, DateTimeParseException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex, HttpServletRequest request) {
        String message;
        if (ex instanceof MissingServletRequestParameterException missing) {
            message = "Missing required parameter: " + missing.getParameterName();
        } else if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "Invalid parameter type: " + mismatch.getName();
        } else if (ex instanceof DateTimeParseException) {
            message = "Invalid date format";
        } else {
            message = ex.getMessage() != null ? ex.getMessage() : "Invalid request";
        }
        return buildResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(UpstreamRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(UpstreamRateLimitException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request);
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<Map<String, Object>> handleUpstream(UpstreamServiceException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_GATEWAY, ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "Forbidden", request);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex, HttpServletRequest request) {
        HttpStatus status = resolveRuntimeStatus(ex);
        String message = ex.getMessage() != null ? ex.getMessage() : "Runtime error";
        if (status.is5xxServerError()) {
            log.error("Handled runtime exception [{}] on {} : {}", status.value(), request.getRequestURI(), message, ex);
        } else {
            log.warn("Handled runtime exception [{}] on {} : {}", status.value(), request.getRequestURI(), message);
        }
        return buildResponse(status, message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private HttpStatus resolveRuntimeStatus(RuntimeException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
        if (msg.contains("not found") || msg.contains("no sites available")) return HttpStatus.NOT_FOUND;
        if (msg.contains("invalid") || msg.contains("date cannot") || msg.contains("must not")) return HttpStatus.BAD_REQUEST;
        if (msg.contains("rate limit") || msg.contains("407") || msg.contains("429")) return HttpStatus.TOO_MANY_REQUESTS;
        if (msg.contains("fusionsolar") || msg.contains("huawei") || msg.contains("unable to load") || msg.contains("upstream")) return HttpStatus.BAD_GATEWAY;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String error, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", error);
        body.put("path", request.getRequestURI());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
