package com.transit.service;

import com.transit.model.Token;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewaySettlementService {
    private final JdbcTemplate jdbcTemplate;

    @Value("${billing.reservation-expiry-seconds:600}")
    private long reservationExpirySeconds;

    /** Atomically reserves both API-key quota and user balance before any
     * upstream request is sent. */
    @Transactional
    public Reservation reserve(Token token, User user, int estimatedTokens, long estimatedAmount,
                               String reservationId, String model) {
        if (token == null || token.getId() == null || user == null || user.getId() == null) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "A billable API Key owner is required");
        }
        int reservedTokens = Math.max(1, estimatedTokens);
        long reservedAmount = Math.max(0, estimatedAmount);
        int quotaUpdated = jdbcTemplate.update("""
                UPDATE tokens
                SET used_quota = used_quota + ?
                WHERE id = ? AND enabled = TRUE
                  AND (expired_at IS NULL OR expired_at > CURRENT_TIMESTAMP)
                  AND (total_quota = 0 OR used_quota + ? <= total_quota)
                """, reservedTokens, token.getId(), reservedTokens);
        if (quotaUpdated != 1) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "API Key does not have enough remaining token quota for this request");
        }

        if (reservedAmount > 0) {
            int balanceUpdated = jdbcTemplate.update("""
                    UPDATE users SET balance = balance - ?
                    WHERE id = ? AND status = 'ACTIVE' AND balance >= ?
                    """, reservedAmount, user.getId(), reservedAmount);
            if (balanceUpdated != 1) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Insufficient balance for the maximum estimated request cost");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO gateway_reservations
                    (reservation_id, token_id, user_id, model, reserved_tokens, reserved_amount,
                     status, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'RESERVED', ?, ?)
                    """, reservationId, token.getId(), user.getId(), model, reservedTokens,
                    reservedAmount, now.plusSeconds(Math.max(60, reservationExpirySeconds)), now);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate request reservation");
        }
        return new Reservation(reservationId, token.getId(), user.getId(), reservedTokens, reservedAmount);
    }

    /** Reconciles the conservative reservation with provider-reported or
     * explicitly estimated actual usage. */
    @Transactional
    public void settle(Reservation reservation, int actualTokens, long actualAmount, String remark) {
        Map<String, Object> row = lock(reservation.id());
        String status = String.valueOf(row.get("status"));
        if ("SETTLED".equals(status)) return;
        if (!"RESERVED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request reservation is no longer active");
        }

        int safeTokens = Math.max(0, actualTokens);
        long safeAmount = Math.max(0, actualAmount);
        reconcileTokenQuota(reservation, safeTokens);
        reconcileBalance(reservation, safeAmount);

        int updated = jdbcTemplate.update("""
                UPDATE gateway_reservations
                SET actual_tokens = ?, actual_amount = ?, status = 'SETTLED', settled_at = ?
                WHERE reservation_id = ? AND status = 'RESERVED'
                """, safeTokens, safeAmount, LocalDateTime.now(), reservation.id());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request reservation was concurrently settled");
        }

        if (safeAmount > 0) {
            Long balanceAfter = jdbcTemplate.queryForObject(
                    "SELECT balance FROM users WHERE id = ?", Long.class, reservation.userId());
            jdbcTemplate.update("""
                    INSERT INTO wallet_transactions
                    (user_id, type, amount, balance_after, channel, remark, created_at)
                    VALUES (?, 'CONSUME', ?, ?, 'api', ?, ?)
                    """, reservation.userId(), -safeAmount, balanceAfter == null ? 0 : balanceAfter,
                    remark, LocalDateTime.now());
        }
    }

    @Transactional
    public void release(Reservation reservation, String reason) {
        releaseInternal(reservation.id(), reason);
    }

    private void releaseInternal(String reservationId, String reason) {
        Map<String, Object> row = lock(reservationId);
        if (!"RESERVED".equals(String.valueOf(row.get("status")))) return;
        long tokenId = ((Number) row.get("token_id")).longValue();
        long userId = ((Number) row.get("user_id")).longValue();
        int reservedTokens = ((Number) row.get("reserved_tokens")).intValue();
        long reservedAmount = ((Number) row.get("reserved_amount")).longValue();
        jdbcTemplate.update("""
                UPDATE tokens
                SET used_quota = CASE WHEN used_quota >= ? THEN used_quota - ? ELSE 0 END
                WHERE id = ?
                """, reservedTokens, reservedTokens, tokenId);
        if (reservedAmount > 0) {
            jdbcTemplate.update("UPDATE users SET balance = balance + ? WHERE id = ?",
                    reservedAmount, userId);
        }
        jdbcTemplate.update("""
                UPDATE gateway_reservations
                SET status = 'RELEASED', failure_reason = ?, settled_at = ?
                WHERE reservation_id = ? AND status = 'RESERVED'
                """, truncate(reason, 500), LocalDateTime.now(), reservationId);
    }

    @Scheduled(fixedDelayString = "${billing.reservation-cleanup-ms:60000}",
            initialDelayString = "${billing.reservation-cleanup-initial-delay-ms:60000}")
    @Transactional
    public void releaseExpiredReservations() {
        List<String> expired = jdbcTemplate.queryForList("""
                SELECT reservation_id FROM gateway_reservations
                WHERE status = 'RESERVED' AND expires_at < CURRENT_TIMESTAMP
                ORDER BY expires_at ASC LIMIT 100
                """, String.class);
        for (String reservationId : expired) {
            try {
                releaseInternal(reservationId, "Reservation expired before settlement");
            } catch (Exception exception) {
                log.warn("Unable to release expired gateway reservation {}", reservationId);
            }
        }
    }

    private void reconcileTokenQuota(Reservation reservation, int actualTokens) {
        int delta = actualTokens - reservation.reservedTokens();
        if (delta > 0) {
            int updated = jdbcTemplate.update("""
                    UPDATE tokens SET used_quota = used_quota + ?
                    WHERE id = ? AND (total_quota = 0 OR used_quota + ? <= total_quota)
                    """, delta, reservation.tokenId(), delta);
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Actual usage exceeded the reserved API Key quota");
            }
        } else if (delta < 0) {
            int refund = -delta;
            jdbcTemplate.update("""
                    UPDATE tokens SET used_quota = CASE WHEN used_quota >= ? THEN used_quota - ? ELSE 0 END
                    WHERE id = ?
                    """, refund, refund, reservation.tokenId());
        }
    }

    private void reconcileBalance(Reservation reservation, long actualAmount) {
        long delta = actualAmount - reservation.reservedAmount();
        if (delta > 0) {
            int updated = jdbcTemplate.update("""
                    UPDATE users SET balance = balance - ?
                    WHERE id = ? AND status = 'ACTIVE' AND balance >= ?
                    """, delta, reservation.userId(), delta);
            if (updated != 1) {
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                        "Actual usage exceeded the reserved account balance");
            }
        } else if (delta < 0) {
            jdbcTemplate.update("UPDATE users SET balance = balance + ? WHERE id = ?",
                    -delta, reservation.userId());
        }
    }

    private Map<String, Object> lock(String reservationId) {
        return jdbcTemplate.queryForList("""
                SELECT reservation_id, token_id, user_id, reserved_tokens, reserved_amount, status
                FROM gateway_reservations WHERE reservation_id = ? FOR UPDATE
                """, reservationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Request reservation not found"));
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.substring(0, Math.min(max, value.length()));
    }

    public record Reservation(String id, Long tokenId, Long userId, int reservedTokens, long reservedAmount) {
    }
}
