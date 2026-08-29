package com.transit.service;

import com.transit.model.Token;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    @Autowired(required = false)
    private AgentDistributionService agentDistributionService;

    @Value("${billing.reservation-expiry-seconds:600}")
    private long reservationExpirySeconds;

    /** Atomically reserves both API-key quota and user balance before any
     * upstream request is sent. */
    @Transactional
    public Reservation reserve(Token token, User user, int estimatedTokens, long estimatedAmount,
                               String reservationId, String model) {
        return reserve(token, user, estimatedTokens, estimatedAmount, 0, 10000,
                java.math.BigDecimal.ONE, reservationId, model);
    }

    /** Adds the source-currency quote to a reservation created through the
     * stable reserve contract. Keeping this separate preserves compatibility
     * for other gateway callers while still persisting the FX snapshot used
     * by USD-priced model requests. */
    @Transactional
    public void captureSourceSnapshot(String reservationId, long sourceAmount, long sourceScale,
                                      java.math.BigDecimal exchangeRate) {
        int updated = jdbcTemplate.update("""
                UPDATE gateway_reservations
                SET source_amount=?, source_currency='USD', source_scale=?,
                    settlement_currency='CNY', exchange_rate=?
                WHERE reservation_id=? AND status='RESERVED'
                """, Math.max(0, sourceAmount), Math.max(1, sourceScale), exchangeRate, reservationId);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Request reservation is no longer available for pricing snapshot");
        }
    }

    @Transactional
    public Reservation reserve(Token token, User user, int estimatedTokens, long estimatedAmount,
                               long sourceAmount, long sourceScale, java.math.BigDecimal exchangeRate,
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

        BillingWallets wallets = billingWallets(token, user);
        reserveFunds(wallets, user.getId(), reservedAmount);

        LocalDateTime now = LocalDateTime.now();
        try {
            jdbcTemplate.update("""
                    INSERT INTO gateway_reservations
                    (reservation_id, token_id, user_id, wallet_account_id, funding_wallet_account_id,
                     model, reserved_tokens, reserved_amount,
                     source_amount,source_currency,source_scale,settlement_currency,exchange_rate,
                     status, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'USD', ?, 'CNY', ?, 'RESERVED', ?, ?)
                    """, reservationId, token.getId(), user.getId(), wallets.accountWalletId(),
                    wallets.fundingWalletId(), model, reservedTokens,
                    reservedAmount, Math.max(0, sourceAmount), Math.max(1, sourceScale), exchangeRate,
                    now.plusSeconds(Math.max(60, reservationExpirySeconds)), now);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate request reservation");
        }
        return new Reservation(reservationId, token.getId(), user.getId(), wallets.accountWalletId(),
                wallets.fundingWalletId(), reservedTokens, reservedAmount);
    }

    /** Reconciles the conservative reservation with provider-reported or
     * explicitly estimated actual usage. */
    @Transactional
    public void settle(Reservation reservation, int actualTokens, long actualAmount, String remark) {
        settleInternal(reservation, actualTokens, actualAmount, null, remark);
    }

    /** Settles with a reliable cost snapshot and triggers idempotent gross-profit distribution. */
    @Transactional
    public void settle(Reservation reservation, int actualTokens, long actualAmount, long actualCostAmount, String remark) {
        settleInternal(reservation, actualTokens, actualAmount, Math.max(0, actualCostAmount), remark);
    }

    private void settleInternal(Reservation reservation, int actualTokens, long actualAmount, Long actualCostAmount, String remark) {
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
            long balanceAfter = reservation.walletAccountId() == null
                    ? userBalance(reservation.userId()) : walletBalance(reservation.walletAccountId());
            jdbcTemplate.update("""
                    INSERT INTO wallet_transactions
                    (user_id, type, amount, balance_after, channel, remark, created_at)
                    VALUES (?, 'CONSUME', ?, ?, 'api', ?, ?)
                    """, reservation.userId(), -safeAmount, balanceAfter,
                    remark, LocalDateTime.now());
        }
        if (actualCostAmount != null && agentDistributionService != null) {
            agentDistributionService.settleApiUsage(reservation.id(), reservation.userId(), safeAmount, actualCostAmount);
        }
    }

    @Transactional
    public void release(Reservation reservation, String reason) {
        releaseInternal(reservation.id(), reason);
    }

    /** Keeps funds held when the upstream may have accepted the request. */
    @Transactional
    public void markUnknown(Reservation reservation, String reason) {
        jdbcTemplate.update("""
                UPDATE gateway_reservations
                SET status='UNKNOWN', failure_reason=?, settled_at=?
                WHERE reservation_id=? AND status='RESERVED'
                """, truncate(reason, 500), LocalDateTime.now(), reservation.id());
    }

    private void releaseInternal(String reservationId, String reason) {
        Map<String, Object> row = lock(reservationId);
        if (!"RESERVED".equals(String.valueOf(row.get("status")))) return;
        long tokenId = ((Number) row.get("token_id")).longValue();
        long userId = ((Number) row.get("user_id")).longValue();
        int reservedTokens = ((Number) row.get("reserved_tokens")).intValue();
        long reservedAmount = ((Number) row.get("reserved_amount")).longValue();
        Long walletAccountId = row.get("wallet_account_id") == null ? null
                : ((Number) row.get("wallet_account_id")).longValue();
        Long fundingWalletAccountId = row.get("funding_wallet_account_id") == null ? null
                : ((Number) row.get("funding_wallet_account_id")).longValue();
        jdbcTemplate.update("""
                UPDATE tokens
                SET used_quota = CASE WHEN used_quota >= ? THEN used_quota - ? ELSE 0 END
                WHERE id = ?
                """, reservedTokens, reservedTokens, tokenId);
        if (reservedAmount > 0) {
            if (walletAccountId == null) {
                jdbcTemplate.update("UPDATE users SET balance = balance + ? WHERE id = ?", reservedAmount, userId);
            } else {
                releaseWalletReservation(walletAccountId, reservedAmount);
                if (fundingWalletAccountId != null && !fundingWalletAccountId.equals(walletAccountId)) {
                    releaseWalletReservation(fundingWalletAccountId, reservedAmount);
                    syncWalletOwnerBalance(fundingWalletAccountId);
                } else {
                    syncWalletOwnerBalance(walletAccountId);
                }
            }
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
        if (reservation.walletAccountId() != null) {
            reconcileWallet(reservation.walletAccountId(), reservation.reservedAmount(), delta,
                    "Actual usage exceeded the reserved employee/account balance");
            if (reservation.fundingWalletAccountId() != null
                    && !reservation.fundingWalletAccountId().equals(reservation.walletAccountId())) {
                reconcileWallet(reservation.fundingWalletAccountId(), reservation.reservedAmount(), delta,
                        "Actual usage exceeded the enterprise treasury balance");
                syncWalletOwnerBalance(reservation.fundingWalletAccountId());
            } else {
                syncWalletOwnerBalance(reservation.walletAccountId());
            }
            return;
        }
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
                SELECT reservation_id, token_id, user_id, wallet_account_id, funding_wallet_account_id,
                       reserved_tokens, reserved_amount, status
                FROM gateway_reservations WHERE reservation_id = ? FOR UPDATE
                """, reservationId).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Request reservation not found"));
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.substring(0, Math.min(max, value.length()));
    }

    private BillingWallets billingWallets(Token token, User user) {
        Long organizationId = token.getOrganizationId() != null
                ? token.getOrganizationId() : user.getDefaultOrganizationId();
        if (organizationId == null) return new BillingWallets(null, null);
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList("""
                SELECT wa.id,o.organization_type,om.member_role
                FROM wallet_accounts wa
                JOIN organizations o ON o.id=wa.organization_id AND o.status='ACTIVE'
                JOIN organization_members om ON om.organization_id=wa.organization_id
                  AND om.user_id=wa.user_id AND om.status='ACTIVE'
                WHERE wa.organization_id=? AND wa.user_id=? AND wa.status='ACTIVE'
                """, organizationId, user.getId());
        if (accounts.isEmpty()) return new BillingWallets(null, null);
        Map<String, Object> account = accounts.get(0);
        long accountWalletId = ((Number) account.get("id")).longValue();
        if (!"COMPANY".equalsIgnoreCase(String.valueOf(account.get("organization_type")))
                || "OWNER".equalsIgnoreCase(String.valueOf(account.get("member_role")))) {
            return new BillingWallets(accountWalletId, null);
        }
        Long fundingWalletId = jdbcTemplate.queryForList("""
                SELECT wa.id FROM wallet_accounts wa
                JOIN organization_members om ON om.organization_id=wa.organization_id
                  AND om.user_id=wa.user_id AND om.status='ACTIVE' AND om.member_role='OWNER'
                WHERE wa.organization_id=? AND wa.status='ACTIVE' AND wa.account_type='TREASURY'
                ORDER BY wa.id LIMIT 1
                """, Long.class, organizationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Enterprise treasury wallet is unavailable"));
        return new BillingWallets(accountWalletId, fundingWalletId);
    }

    public Reservation restore(String reservationId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT reservation_id, token_id, user_id, wallet_account_id, funding_wallet_account_id,
                       reserved_tokens, reserved_amount
                FROM gateway_reservations WHERE reservation_id=?
                """, reservationId);
        return new Reservation(String.valueOf(row.get("reservation_id")),
                ((Number) row.get("token_id")).longValue(), ((Number) row.get("user_id")).longValue(),
                row.get("wallet_account_id") == null ? null : ((Number) row.get("wallet_account_id")).longValue(),
                row.get("funding_wallet_account_id") == null ? null
                        : ((Number) row.get("funding_wallet_account_id")).longValue(),
                ((Number) row.get("reserved_tokens")).intValue(), ((Number) row.get("reserved_amount")).longValue());
    }

    public record Reservation(String id, Long tokenId, Long userId, Long walletAccountId,
                              Long fundingWalletAccountId,
                              int reservedTokens, long reservedAmount) {
        public Reservation(String id, Long tokenId, Long userId, int reservedTokens, long reservedAmount) {
            this(id, tokenId, userId, null, null, reservedTokens, reservedAmount);
        }

        public Reservation(String id, Long tokenId, Long userId, Long walletAccountId,
                           int reservedTokens, long reservedAmount) {
            this(id, tokenId, userId, walletAccountId, null, reservedTokens, reservedAmount);
        }
    }

    private void reserveFunds(BillingWallets wallets, Long userId, long amount) {
        if (amount <= 0) return;
        if (wallets.accountWalletId() == null) {
            int updated = jdbcTemplate.update("""
                    UPDATE users SET balance=balance-?
                    WHERE id=? AND status='ACTIVE' AND balance>=?
                    """, amount, userId, amount);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Insufficient balance for the maximum estimated request cost");
            return;
        }
        reserveWallet(wallets.accountWalletId(), amount,
                wallets.fundingWalletId() == null
                        ? "Insufficient balance for the maximum estimated request cost"
                        : "Employee allocation is insufficient for the maximum estimated request cost");
        if (wallets.fundingWalletId() != null
                && !wallets.fundingWalletId().equals(wallets.accountWalletId())) {
            reserveWallet(wallets.fundingWalletId(), amount,
                    "Enterprise treasury balance is insufficient for the maximum estimated request cost");
            syncWalletOwnerBalance(wallets.fundingWalletId());
        } else {
            syncWalletOwnerBalance(wallets.accountWalletId());
        }
    }

    private void reserveWallet(Long walletId, long amount, String message) {
        int updated = jdbcTemplate.update("""
                UPDATE wallet_accounts
                SET balance=balance-?,held_balance=held_balance+?,version=version+1,updated_at=?
                WHERE id=? AND status='ACTIVE' AND balance>=?
                """, amount, amount, LocalDateTime.now(), walletId, amount);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, message);
    }

    private void releaseWalletReservation(Long walletId, long amount) {
        jdbcTemplate.update("""
                UPDATE wallet_accounts
                SET balance=balance+?,held_balance=CASE WHEN held_balance>=? THEN held_balance-? ELSE 0 END,
                    version=version+1,updated_at=? WHERE id=?
                """, amount, amount, amount, LocalDateTime.now(), walletId);
    }

    private void reconcileWallet(Long walletId, long reservedAmount, long delta, String insufficientMessage) {
        if (delta > 0) {
            int updated = jdbcTemplate.update("""
                    UPDATE wallet_accounts
                    SET balance=balance-?,held_balance=CASE WHEN held_balance>=? THEN held_balance-? ELSE 0 END,
                        version=version+1,updated_at=?
                    WHERE id=? AND status='ACTIVE' AND balance>=?
                    """, delta, reservedAmount, reservedAmount, LocalDateTime.now(), walletId, delta);
            if (updated != 1) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, insufficientMessage);
        } else {
            long refund = -delta;
            jdbcTemplate.update("""
                    UPDATE wallet_accounts
                    SET balance=balance+?,held_balance=CASE WHEN held_balance>=? THEN held_balance-? ELSE 0 END,
                        version=version+1,updated_at=? WHERE id=?
                    """, refund, reservedAmount, reservedAmount, LocalDateTime.now(), walletId);
        }
    }

    private void syncWalletOwnerBalance(Long walletId) {
        jdbcTemplate.update("""
                UPDATE users SET balance=(SELECT balance FROM wallet_accounts WHERE id=?)
                WHERE id=(SELECT user_id FROM wallet_accounts WHERE id=? )
                """, walletId, walletId);
    }

    private long walletBalance(Long walletId) {
        Long value = jdbcTemplate.queryForObject("SELECT balance FROM wallet_accounts WHERE id=?", Long.class, walletId);
        return value == null ? 0 : value;
    }

    private long userBalance(Long userId) {
        Long value = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, userId);
        return value == null ? 0 : value;
    }

    private record BillingWallets(Long accountWalletId, Long fundingWalletId) {
    }
}
