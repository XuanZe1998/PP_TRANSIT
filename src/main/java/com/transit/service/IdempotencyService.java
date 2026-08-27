package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IdempotencyService {
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,160}");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public Claim claim(String actorScope, Object actorId, String operationScope,
                       String key, Object request, boolean required) {
        if (key == null || key.isBlank()) {
            if (required) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
            }
            return Claim.untracked();
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is invalid");
        }
        String actor = String.valueOf(actorId);
        String hash = hash(request);
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO idempotency_records
                    (actor_scope, actor_id, operation_scope, idempotency_key, request_hash,
                     status, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?)
                    """, actorScope, actor, operationScope, key, hash, now.plusHours(24), now, now);
            Long id = jdbcTemplate.queryForObject("""
                    SELECT id FROM idempotency_records
                    WHERE actor_scope=? AND actor_id=? AND operation_scope=? AND idempotency_key=?
                    """, Long.class, actorScope, actor, operationScope, key);
            return new Claim(id, true, false, null, null);
        } catch (DuplicateKeyException duplicate) {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT id, request_hash, status, http_status, response_body
                    FROM idempotency_records
                    WHERE actor_scope=? AND actor_id=? AND operation_scope=? AND idempotency_key=?
                    FOR UPDATE
                    """, actorScope, actor, operationScope, key);
            if (!hash.equals(row.get("request_hash"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Idempotency-Key was already used with a different request");
            }
            if ("COMPLETED".equals(row.get("status"))) {
                return new Claim(((Number) row.get("id")).longValue(), true, true,
                        row.get("http_status") == null ? 200 : ((Number) row.get("http_status")).intValue(),
                        parse((String) row.get("response_body")));
            }
            ResponseStatusException processing = new ResponseStatusException(HttpStatus.CONFLICT,
                    "An operation with this Idempotency-Key is still processing");
            processing.getHeaders().set("Retry-After", "2");
            throw processing;
        }
    }

    public void complete(Claim claim, int httpStatus, Object response, String resourceType, Object resourceId) {
        if (claim == null || !claim.tracked() || claim.replay()) return;
        jdbcTemplate.update("""
                UPDATE idempotency_records
                SET status='COMPLETED', http_status=?, response_body=?, resource_type=?, resource_id=?, updated_at=?
                WHERE id=? AND status='PROCESSING'
                """, httpStatus, json(response), resourceType,
                resourceId == null ? null : String.valueOf(resourceId), LocalDateTime.now(), claim.id());
    }

    public void fail(Claim claim, Throwable error) {
        if (claim == null || !claim.tracked() || claim.replay()) return;
        String message = error == null ? "Operation failed" : String.valueOf(error.getMessage());
        jdbcTemplate.update("""
                UPDATE idempotency_records SET status='FAILED', error_message=?, updated_at=?
                WHERE id=? AND status='PROCESSING'
                """, message.substring(0, Math.min(1000, message.length())), LocalDateTime.now(), claim.id());
    }

    public void unknown(Claim claim, Throwable error, String resourceType, Object resourceId) {
        if (claim == null || !claim.tracked() || claim.replay()) return;
        String message = error == null ? "Upstream result is unknown" : String.valueOf(error.getMessage());
        jdbcTemplate.update("""
                UPDATE idempotency_records SET status='UNKNOWN', error_message=?,resource_type=?,resource_id=?,updated_at=?
                WHERE id=? AND status='PROCESSING'
                """, message.substring(0, Math.min(1000, message.length())), resourceType,
                resourceId == null ? null : String.valueOf(resourceId), LocalDateTime.now(), claim.id());
    }

    @Scheduled(initialDelayString = "${gateway.idempotency.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${gateway.idempotency.cleanup-ms:3600000}")
    public void cleanup() {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE expires_at < CURRENT_TIMESTAMP");
    }

    private String hash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request cannot be canonicalized");
        }
    }

    private JsonNode parse(String value) {
        if (value == null) return objectMapper.nullNode();
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored idempotent response is invalid", exception);
        }
    }

    public record Claim(Long id, boolean tracked, boolean replay, Integer httpStatus, JsonNode response) {
        static Claim untracked() { return new Claim(null, false, false, null, null); }
    }
}
