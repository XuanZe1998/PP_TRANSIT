package com.transit.controller;

import com.transit.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> statusError(ResponseStatusException exception,
                                                     HttpServletRequest request) {
        String message = exception.getReason() == null ? "Request failed" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .headers(exception.getHeaders())
                .body(error(message, String.valueOf(exception.getStatusCode().value()),
                        "request_error", request));
    }

    @ExceptionHandler({WebClientResponseException.class, WebClientRequestException.class})
    ResponseEntity<Map<String, Object>> gatewayError(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        if (exception instanceof WebClientResponseException response) {
            String upstreamPath = response.getRequest() == null ? "unknown"
                    : response.getRequest().getURI().getPath();
            log.warn("Upstream response failure requestId={} path={} status={} body={}", requestId,
                    upstreamPath, response.getStatusCode().value(), safeUpstreamBody(response.getResponseBodyAsString()));
        } else {
            log.warn("Upstream connection failure requestId={} path={} error={}", requestId,
                    request.getRequestURI(), safeUpstreamBody(exception.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(error("Upstream provider request failed", "upstream_error",
                        "upstream_error", request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(error("Invalid request", "invalid_request", "request_error", request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException exception,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(error("The request conflicts with existing data", "conflict",
                        "request_error", request));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, Object>> uploadTooLarge(MaxUploadSizeExceededException exception,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(error("Uploaded image is too large", "payload_too_large",
                        "request_error", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException exception,
                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("Resource not found", "not_found", "request_error", request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> internalError(Exception exception, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("Unhandled API error for request {}", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("Internal server error", "internal_error", "api_error", request));
    }

    private Map<String, Object> error(String message, String code, String type,
                                      HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("requestId", requestId(request));
        payload.put("path", request.getRequestURI());
        payload.put("error", Map.of(
                "message", message,
                "type", type,
                "code", code));
        return payload;
    }

    private String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value == null ? "unknown" : value.toString();
    }

    private String safeUpstreamBody(String value) {
        if (value == null || value.isBlank()) return "(empty)";
        String sanitized = value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)sk-[A-Za-z0-9_-]+", "sk-[REDACTED]")
                .replaceAll("[\\r\\n\\t]+", " ");
        return sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000) + "…";
    }
}
