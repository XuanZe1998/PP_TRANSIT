package com.transit.service;

import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Atomic funding and settlement for the asynchronous auto-movie pipeline. */
@Service
@RequiredArgsConstructor
public class CreativeBillingService {
    private final JdbcTemplate jdbc;

    @Transactional
    public void reserve(User user, long projectId, String stage, long amount) {
        if (amount <= 0) return;
        if (user == null || user.getId() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        List<String> statuses = jdbc.queryForList("SELECT status FROM users WHERE id=?", String.class, user.getId());
        if (statuses.isEmpty() || !"ACTIVE".equals(statuses.get(0))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is unavailable");
        }
        BillingWallets wallets = billingWallets(user);
        if (wallets.accountId() == null) {
            int updated = jdbc.update("UPDATE users SET balance=balance-? WHERE id=? AND status='ACTIVE' AND balance>=?",
                    amount, user.getId(), amount);
            if (updated != 1) throw insufficient();
        } else {
            reserveWallet(wallets.accountId(), amount);
            if (wallets.fundingId() != null) reserveWallet(wallets.fundingId(), amount);
            syncOwnerBalance(wallets.fundingId() == null ? wallets.accountId() : wallets.fundingId());
        }
        jdbc.update("""
                INSERT INTO creative_billing_reservations
                (project_id,user_id,stage,estimated_amount,reserved_amount,wallet_account_id,funding_wallet_account_id,status,created_at)
                VALUES (?,?,?,?,?,?,?,'RESERVED',?)
                """, projectId, user.getId(), stage, amount, amount, wallets.accountId(), wallets.fundingId(), LocalDateTime.now());
    }

    @Transactional
    public void settle(long projectId, String stage, long actual) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM creative_billing_reservations
                WHERE project_id=? AND stage=? AND status='RESERVED' ORDER BY id FOR UPDATE
                """, projectId, stage);
        long remaining = Math.max(0, actual);
        for (Map<String, Object> row : rows) {
            long reserved = number(row.get("reserved_amount"));
            long charge = Math.min(reserved, remaining);
            refund(row, reserved - charge, reserved);
            int updated = jdbc.update("UPDATE creative_billing_reservations SET actual_amount=?,status='SETTLED',settled_at=? WHERE id=? AND status='RESERVED'",
                    charge, LocalDateTime.now(), row.get("id"));
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Billing reservation changed");
            remaining -= charge;
        }
    }

    @Transactional
    public void releaseOpenReservations(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM creative_billing_reservations
                WHERE project_id=? AND status='RESERVED' ORDER BY id FOR UPDATE
                """, projectId);
        for (Map<String, Object> row : rows) {
            long reserved = number(row.get("reserved_amount"));
            refund(row, reserved, reserved);
            int updated = jdbc.update("UPDATE creative_billing_reservations SET actual_amount=0,status='RELEASED',settled_at=? WHERE id=? AND status='RESERVED'",
                    LocalDateTime.now(), row.get("id"));
            if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Billing reservation changed");
        }
    }

    private BillingWallets billingWallets(User user) {
        Long organizationId = jdbc.queryForObject("SELECT default_organization_id FROM users WHERE id=?", Long.class, user.getId());
        if (organizationId == null) {
            organizationId = jdbc.queryForList("""
                    SELECT wa.organization_id FROM wallet_accounts wa
                    JOIN organizations o ON o.id=wa.organization_id AND o.status='ACTIVE'
                    JOIN organization_members om ON om.organization_id=o.id AND om.user_id=wa.user_id
                      AND om.member_role='OWNER' AND om.status='ACTIVE'
                    WHERE wa.user_id=? AND wa.account_type='TREASURY' AND wa.status='ACTIVE'
                    ORDER BY CASE WHEN o.organization_type='PERSONAL' THEN 0 ELSE 1 END,o.created_at LIMIT 1
                    """, Long.class, user.getId()).stream().findFirst().orElse(null);
        }
        if (organizationId == null) return new BillingWallets(null, null);
        List<Map<String, Object>> accounts = jdbc.queryForList("""
                SELECT wa.id,o.organization_type,om.member_role FROM wallet_accounts wa
                JOIN organizations o ON o.id=wa.organization_id AND o.status='ACTIVE'
                JOIN organization_members om ON om.organization_id=wa.organization_id AND om.user_id=wa.user_id AND om.status='ACTIVE'
                WHERE wa.organization_id=? AND wa.user_id=? AND wa.status='ACTIVE'
                """, organizationId, user.getId());
        if (accounts.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization permission denied");
        Map<String, Object> account = accounts.get(0);
        long accountId = number(account.get("id"));
        if (!"COMPANY".equals(account.get("organization_type")) || "OWNER".equals(account.get("member_role"))) {
            return new BillingWallets(accountId, null);
        }
        Long treasuryId = jdbc.queryForList("""
                SELECT wa.id FROM wallet_accounts wa
                JOIN organization_members om ON om.organization_id=wa.organization_id AND om.user_id=wa.user_id
                  AND om.member_role='OWNER' AND om.status='ACTIVE'
                WHERE wa.organization_id=? AND wa.account_type='TREASURY' AND wa.status='ACTIVE'
                ORDER BY wa.id LIMIT 1
                """, Long.class, organizationId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Enterprise treasury wallet is unavailable"));
        return new BillingWallets(accountId, treasuryId);
    }

    private void reserveWallet(long walletId, long amount) {
        Map<String, Object> wallet = jdbc.queryForMap(
                "SELECT user_id,organization_id,account_type,balance FROM wallet_accounts WHERE id=? FOR UPDATE", walletId);
        if (mirrorsUserBalance(walletId, wallet)) {
            Long balance = jdbc.queryForObject("SELECT balance FROM users WHERE id=? FOR UPDATE", Long.class, wallet.get("user_id"));
            if (!Objects.equals(balance, number(wallet.get("balance")))) {
                // Do not overwrite historical users-only charges with a stale treasury balance.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet balance requires reconciliation");
            }
        }
        int updated = jdbc.update("""
                UPDATE wallet_accounts SET balance=balance-?,held_balance=held_balance+?,version=version+1,updated_at=?
                WHERE id=? AND status='ACTIVE' AND balance>=?
                """, amount, amount, LocalDateTime.now(), walletId, amount);
        if (updated != 1) throw insufficient();
    }

    private void refund(Map<String, Object> row, long refund, long reserved) {
        long userId = number(row.get("user_id"));
        if (row.get("wallet_account_id") == null) {
            // Existing reservations before the wallet-aware schema were debited from users.balance only.
            if (refund > 0) jdbc.update("UPDATE users SET balance=balance+? WHERE id=?", refund, userId);
            return;
        }
        long accountId = number(row.get("wallet_account_id"));
        refundWallet(accountId, refund, reserved);
        if (row.get("funding_wallet_account_id") != null && number(row.get("funding_wallet_account_id")) != accountId) {
            long fundingId = number(row.get("funding_wallet_account_id"));
            refundWallet(fundingId, refund, reserved);
            syncOwnerBalance(fundingId);
        } else {
            syncOwnerBalance(accountId);
        }
    }

    private void refundWallet(long walletId, long refund, long reserved) {
        int updated = jdbc.update("""
                UPDATE wallet_accounts
                SET balance=balance+?,held_balance=held_balance-?,version=version+1,updated_at=?
                WHERE id=? AND held_balance>=?
                """, refund, reserved, LocalDateTime.now(), walletId, reserved);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet reservation hold is unavailable");
    }

    private void syncOwnerBalance(long walletId) {
        Map<String, Object> wallet = jdbc.queryForMap("SELECT user_id,organization_id,account_type FROM wallet_accounts WHERE id=?", walletId);
        long userId = number(wallet.get("user_id"));
        if (!mirrorsUserBalance(walletId, wallet)) return;
        jdbc.update("UPDATE users SET balance=(SELECT balance FROM wallet_accounts WHERE id=?) WHERE id=?", walletId, userId);
    }

    private boolean mirrorsUserBalance(long walletId, Map<String, Object> wallet) {
        // Member wallets are allocation limits, not a user's personal balance mirror.
        if (!"TREASURY".equals(wallet.get("account_type"))) return false;
        long userId = number(wallet.get("user_id"));
        Long defaultOrgId = jdbc.queryForObject("SELECT default_organization_id FROM users WHERE id=?", Long.class, userId);
        // Preserve original-source refunds even if this default treasury is suspended.
        if (Objects.equals(defaultOrgId, number(wallet.get("organization_id")))) return true;
        // Inspect preference without locking an unrelated wallet: funding locks stay wallet -> user.
        List<Long> preferred = jdbc.queryForList("""
                SELECT wa.id FROM wallet_accounts wa
                JOIN organizations o ON o.id=wa.organization_id AND o.status='ACTIVE'
                JOIN organization_members om ON om.organization_id=o.id AND om.user_id=wa.user_id
                  AND om.status='ACTIVE' AND om.member_role='OWNER'
                JOIN users u ON u.id=wa.user_id
                WHERE wa.user_id=? AND wa.status='ACTIVE' AND wa.account_type='TREASURY'
                ORDER BY CASE WHEN o.id=u.default_organization_id THEN 0
                              WHEN o.organization_type='PERSONAL' THEN 1 ELSE 2 END,o.created_at
                LIMIT 1
                """, Long.class, userId);
        return !preferred.isEmpty() && preferred.get(0) == walletId;
    }

    private long number(Object value) { return ((Number) value).longValue(); }
    private ResponseStatusException insufficient() {
        return new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "余额不足，无法冻结本阶段预估费用");
    }
    private record BillingWallets(Long accountId, Long fundingId) { }
}
