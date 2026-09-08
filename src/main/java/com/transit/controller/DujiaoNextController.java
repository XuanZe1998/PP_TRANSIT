package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.service.CurrentUserService;
import com.transit.service.DujiaoNextApiException;
import com.transit.service.DujiaoNextClient;
import com.transit.service.DujiaoNextProcurementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class DujiaoNextController {
    private static final int MAX_CALLBACK_BYTES = 256 * 1024;

    private final DujiaoNextClient client;
    private final DujiaoNextProcurementService procurementService;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    @GetMapping("/admin/api/dujiao-next/configuration")
    public Map<String, Object> configuration(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        currentUserService.requireAdmin(authorization);
        return client.configuration();
    }

    @PostMapping("/admin/api/dujiao-next/ping")
    public JsonNode ping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        currentUserService.requireAdmin(authorization);
        return client.ping();
    }

    @GetMapping("/admin/api/dujiao-next/products")
    public JsonNode products(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                             @RequestParam(defaultValue = "1") int page,
                             @RequestParam(name = "page_size", defaultValue = "100") int pageSize) {
        currentUserService.requireAdmin(authorization);
        return client.products(page, pageSize);
    }

    @GetMapping("/admin/api/dujiao-next/products/{id}")
    public JsonNode product(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                            @PathVariable long id) {
        currentUserService.requireAdmin(authorization);
        return client.product(id);
    }

    @PostMapping("/api/v1/upstream/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestHeader(name = "Dujiao-Next-Api-Key", required = false) String apiKey,
            @RequestHeader(name = "Dujiao-Next-Timestamp", required = false) String timestamp,
            @RequestHeader(name = "Dujiao-Next-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > MAX_CALLBACK_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("ok", false, "message", "callback body is too large"));
        }
        try {
            client.verifyCallback(apiKey, timestamp, signature, payload);
            JsonNode json = objectMapper.readTree(payload);
            procurementService.handleCallback(json);
            return ResponseEntity.ok(Map.of("ok", true, "message", "received"));
        } catch (DujiaoNextApiException exception) {
            int status = exception.getHttpStatus() >= 400 && exception.getHttpStatus() <= 599
                    ? exception.getHttpStatus() : 401;
            return ResponseEntity.status(status).body(Map.of("ok", false, "message", exception.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException exception) {
            return ResponseEntity.status(exception.getStatusCode())
                    .body(Map.of("ok", false, "message", exception.getReason() == null ? "callback rejected" : exception.getReason()));
        } catch (Exception exception) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "invalid callback body"));
        }
    }
}
