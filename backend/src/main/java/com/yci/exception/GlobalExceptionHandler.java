package com.yci.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", status.value());
        m.put("error", error);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> badRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, "Validation Error", msg);
    }

    @ExceptionHandler(YouTubeApiException.class)
    public ResponseEntity<Map<String, Object>> youtube(YouTubeApiException ex) {
        log.warn("YouTube API error: {}", ex.getMessage());
        return body(HttpStatus.BAD_GATEWAY, "YouTube API Error", ex.getMessage());
    }

    @ExceptionHandler(MlServiceException.class)
    public ResponseEntity<Map<String, Object>> ml(MlServiceException ex) {
        log.warn("ML service error: {}", ex.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "ML Service Error", ex.getMessage());
    }

    @ExceptionHandler(LlmServiceException.class)
    public ResponseEntity<Map<String, Object>> llm(LlmServiceException ex) {
        log.warn("LLM service error: {}", ex.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "LLM Service Error", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception ex) {
        log.error("Unhandled error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", ex.getMessage());
    }
}
