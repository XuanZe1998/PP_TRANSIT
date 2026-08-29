package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Keeps the legacy user balance and the user's own treasury wallet in sync.
 * Employee wallets in company organizations are allocation limits and are
 * deliberately not selected here.
 */
@Service
@RequiredArgsConstructor
public class WalletBalanceService {
    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public BalanceSnapshot credit(Long userId, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Credit amount must be positive");
        return adjust(userId, amount, "User account is unavailable");
    }

    @Transactional
    public BalanceSnapshot debit(Long userId, long amount, String insufficientMessage) {
        if (amount <= 0) throw new IllegalArgumentException("Debit amount must be positive");
        return adjust(userId, -amount, insufficientMessage);
    }

    @Transactional
    public BalanceSnapshot adjust(Long userId, long delta, String insufficientMessage) {
        if (userId == null || delta == 0 || delta == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Balance adjustment is invalid");
        }
        Map<String, Object> wallet = ownTreasuryWallet(userId);
        if (wallet == null) {
            int changed = jdbcTemplate.update("""
                    UPDATE users SET balance=balance+?
                    WHERE id=? AND status='ACTIVE' AND balance+?>=0
                    """, delta, userId, delta);
            if (changed != 1) throw unavailableOrInsufficient(userId, insufficientMessage);
            return new BalanceSnapshot(userId, null, null, userBalance(userId));
        }

        long walletId = ((Number) wallet.get("id")).longValue();
        long organizationId = ((Number) wallet.get("organization_id")).longValue();
        int changed = jdbcTemplate.update("""
                UPDATE wallet_accounts
                SET balance=balance+?,version=version+1,updated_at=?
                WHERE id=? AND status='ACTIVE' AND balance+?>=0
                """, delta, LocalDateTime.now(), walletId, delta);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, insufficientMessage);
        Long walletBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_accounts WHERE id=?", Long.class, walletId);
        long balanceAfter = walletBalance == null ? 0 : walletBalance;
        int userChanged = jdbcTemplate.update(
                "UPDATE users SET balance=? WHERE id=? AND status='ACTIVE'", balanceAfter, userId);
        if (userChanged != 1) throw unavailableOrInsufficient(userId, insufficientMessage);
        return new BalanceSnapshot(userId, organizationId, walletId, balanceAfter);
    }

    private Map<String, Object> ownTreasuryWallet(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT wa.id,wa.organization_id,wa.balance
                FROM wallet_accounts wa
                JOIN organizations o ON o.id=wa.organization_id AND o.status='ACTIVE'
                JOIN organization_members om ON om.organization_id=o.id AND om.user_id=wa.user_id
                  AND om.status='ACTIVE' AND om.member_role='OWNER'
                JOIN users u ON u.id=wa.user_id
                WHERE wa.user_id=? AND wa.status='ACTIVE' AND wa.account_type='TREASURY'
                ORDER BY CASE WHEN o.id=u.default_organization_id THEN 0
                              WHEN o.organization_type='PERSONAL' THEN 1 ELSE 2 END,
                         o.created_at
                LIMIT 1 FOR UPDATE
                """, userId);
        return rows.stream().findFirst().orElse(null);
    }

    private long userBalance(Long userId) {
        Long value = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id=?", Long.class, userId);
        return value == null ? 0 : value;
    }

    private ResponseStatusException unavailableOrInsufficient(Long userId, String message) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Long.class, userId);
        return new ResponseStatusException(count == null || count == 0 ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT,
                count == null || count == 0 ? "User not found" : message);
    }

    public record BalanceSnapshot(Long userId, Long organizationId, Long walletAccountId, long balance) {
    }
}
