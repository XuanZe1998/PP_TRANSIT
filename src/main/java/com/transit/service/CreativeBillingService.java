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

/** Atomically moves creative-job holds and refunds through the shared treasury balance. */
@Service
@RequiredArgsConstructor
public class CreativeBillingService {
    private final JdbcTemplate jdbc;
    private final WalletBalanceService balances;

    @Transactional
    public void reserve(long userId, long projectId, String stage, long amount) {
        if (amount <= 0) return;
        long available = balances.lockTransferableBalance(userId);
        if (available < amount) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "余额不足，无法冻结本阶段预估费用");
        }
        balances.debit(userId, amount, "余额不足，无法冻结本阶段预估费用");
        jdbc.update("""
                INSERT INTO creative_billing_reservations
                (project_id,user_id,stage,estimated_amount,reserved_amount,status,created_at)
                VALUES (?,?,?,?,?,'RESERVED',?)
                """, projectId, userId, stage, amount, amount, LocalDateTime.now());
    }

    @Transactional
    public void settle(long projectId, String stage, long actual) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,user_id,reserved_amount FROM creative_billing_reservations
                WHERE project_id=? AND stage=? AND status='RESERVED' ORDER BY id FOR UPDATE
                """, projectId, stage);
        long remaining = Math.max(0, actual);
        for (Map<String, Object> row : rows) {
            long reserved = ((Number) row.get("reserved_amount")).longValue();
            long charge = Math.min(reserved, remaining);
            long refund = reserved - charge;
            if (refund > 0) balances.credit(((Number) row.get("user_id")).longValue(), refund);
            jdbc.update("""
                    UPDATE creative_billing_reservations
                    SET actual_amount=?,status='SETTLED',settled_at=? WHERE id=? AND status='RESERVED'
                    """, charge, LocalDateTime.now(), row.get("id"));
            remaining -= charge;
        }
    }

    @Transactional
    public void releaseOpen(long projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id,user_id,reserved_amount FROM creative_billing_reservations
                WHERE project_id=? AND status='RESERVED' ORDER BY id FOR UPDATE
                """, projectId);
        for (Map<String, Object> row : rows) {
            long refund = ((Number) row.get("reserved_amount")).longValue();
            if (refund > 0) balances.credit(((Number) row.get("user_id")).longValue(), refund);
            jdbc.update("""
                    UPDATE creative_billing_reservations
                    SET status='RELEASED',actual_amount=0,settled_at=? WHERE id=? AND status='RESERVED'
                    """, LocalDateTime.now(), row.get("id"));
        }
    }
}
