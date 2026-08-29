package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletBalanceReconciliationService {
    static final String MARKER = "wallet.balance.reconciliation.v2";

    private final JdbcTemplate jdbcTemplate;

    /**
     * One-time conversion from transferred employee funds to independent
     * employee limits backed by a company treasury. Personal accounts use the
     * legacy user balance as the authoritative value for this conversion.
     */
    @Transactional
    public boolean reconcileOnce() {
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key=?", Integer.class, MARKER);
        if (completed != null && completed > 0) return false;

        List<Map<String, Object>> personal = jdbcTemplate.queryForList("""
                SELECT wa.id wallet_id,wa.balance wallet_balance,u.id user_id,u.balance user_balance,
                       u.default_organization_id,
                       default_o.organization_type default_organization_type,
                       default_m.member_role default_member_role,
                       default_wa.balance default_wallet_balance
                FROM wallet_accounts wa
                JOIN organizations o ON o.id=wa.organization_id AND o.organization_type='PERSONAL'
                JOIN users u ON u.id=wa.user_id
                LEFT JOIN organizations default_o ON default_o.id=u.default_organization_id
                LEFT JOIN organization_members default_m ON default_m.organization_id=default_o.id
                  AND default_m.user_id=u.id AND default_m.status='ACTIVE'
                LEFT JOIN wallet_accounts default_wa ON default_wa.organization_id=default_o.id
                  AND default_wa.user_id=u.id AND default_wa.status='ACTIVE'
                WHERE wa.account_type='TREASURY' AND wa.status='ACTIVE'
                """);
        for (Map<String, Object> row : personal) {
            long target = ((Number) row.get("user_balance")).longValue();
            if ("COMPANY".equalsIgnoreCase(String.valueOf(row.get("default_organization_type")))
                    && !"OWNER".equalsIgnoreCase(String.valueOf(row.get("default_member_role")))
                    && row.get("default_wallet_balance") != null) {
                long personalBefore = ((Number) row.get("wallet_balance")).longValue();
                long memberMirror = ((Number) row.get("default_wallet_balance")).longValue();
                target = Math.max(0, Math.addExact(personalBefore, target - memberMirror));
            }
            jdbcTemplate.update("""
                    UPDATE wallet_accounts SET balance=?,version=version+1,updated_at=? WHERE id=?
                    """, target, LocalDateTime.now(), row.get("wallet_id"));
        }

        List<Map<String, Object>> companies = jdbcTemplate.queryForList("""
                SELECT o.id organization_id,owner_wa.id owner_wallet_id,owner_wa.user_id owner_user_id,
                       owner_wa.balance owner_wallet_balance,u.balance owner_legacy_balance,
                       u.default_organization_id,
                       COALESCE(SUM(CASE WHEN member_wa.id<>owner_wa.id AND member_wa.status='ACTIVE'
                                         THEN member_wa.balance ELSE 0 END),0) member_allocations
                FROM organizations o
                JOIN organization_members owner_m ON owner_m.organization_id=o.id
                  AND owner_m.member_role='OWNER' AND owner_m.status='ACTIVE'
                JOIN wallet_accounts owner_wa ON owner_wa.organization_id=o.id
                  AND owner_wa.user_id=owner_m.user_id AND owner_wa.status='ACTIVE'
                JOIN users u ON u.id=owner_m.user_id
                LEFT JOIN wallet_accounts member_wa ON member_wa.organization_id=o.id
                WHERE o.organization_type='COMPANY' AND o.status='ACTIVE'
                GROUP BY o.id,owner_wa.id,owner_wa.user_id,owner_wa.balance,u.balance,u.default_organization_id
                """);
        for (Map<String, Object> company : companies) {
            long legacyTreasury = ((Number) (company.get("default_organization_id") != null
                    && ((Number) company.get("default_organization_id")).longValue()
                    == ((Number) company.get("organization_id")).longValue()
                    ? company.get("owner_legacy_balance") : company.get("owner_wallet_balance"))).longValue();
            long memberAllocations = ((Number) company.get("member_allocations")).longValue();
            long treasury = Math.addExact(Math.max(0, legacyTreasury), Math.max(0, memberAllocations));
            jdbcTemplate.update("""
                    UPDATE wallet_accounts SET balance=?,version=version+1,updated_at=? WHERE id=?
                    """, treasury, LocalDateTime.now(), company.get("owner_wallet_id"));
            jdbcTemplate.update("UPDATE users SET balance=? WHERE id=?",
                    treasury, company.get("owner_user_id"));
        }

        List<Map<String, Object>> activeEnterpriseReservations = jdbcTemplate.queryForList("""
                SELECT gr.reservation_id,gr.reserved_amount,owner_wa.id funding_wallet_id
                FROM gateway_reservations gr
                JOIN wallet_accounts member_wa ON member_wa.id=gr.wallet_account_id
                JOIN organizations o ON o.id=member_wa.organization_id
                  AND o.organization_type='COMPANY' AND o.status='ACTIVE'
                JOIN organization_members member_m ON member_m.organization_id=o.id
                  AND member_m.user_id=member_wa.user_id AND member_m.status='ACTIVE'
                  AND member_m.member_role<>'OWNER'
                JOIN organization_members owner_m ON owner_m.organization_id=o.id
                  AND owner_m.status='ACTIVE' AND owner_m.member_role='OWNER'
                JOIN wallet_accounts owner_wa ON owner_wa.organization_id=o.id
                  AND owner_wa.user_id=owner_m.user_id AND owner_wa.status='ACTIVE'
                  AND owner_wa.account_type='TREASURY'
                WHERE gr.status='RESERVED' AND gr.funding_wallet_account_id IS NULL
                """);
        for (Map<String, Object> reservation : activeEnterpriseReservations) {
            long held = ((Number) reservation.get("reserved_amount")).longValue();
            long fundingWalletId = ((Number) reservation.get("funding_wallet_id")).longValue();
            jdbcTemplate.update("""
                    UPDATE wallet_accounts SET held_balance=held_balance+?,version=version+1,updated_at=?
                    WHERE id=?
                    """, held, LocalDateTime.now(), fundingWalletId);
            jdbcTemplate.update("""
                    UPDATE gateway_reservations SET funding_wallet_account_id=? WHERE reservation_id=?
                    """, fundingWalletId, reservation.get("reservation_id"));
        }

        jdbcTemplate.update("""
                UPDATE users u SET balance=(
                  SELECT wa.balance FROM wallet_accounts wa
                  JOIN organizations o ON o.id=wa.organization_id
                  JOIN organization_members om ON om.organization_id=o.id AND om.user_id=u.id
                  WHERE wa.user_id=u.id AND wa.status='ACTIVE' AND wa.account_type='TREASURY'
                    AND om.status='ACTIVE' AND om.member_role='OWNER'
                  ORDER BY CASE WHEN o.id=u.default_organization_id THEN 0
                                WHEN o.organization_type='PERSONAL' THEN 1 ELSE 2 END,
                           o.created_at LIMIT 1
                )
                WHERE EXISTS (
                  SELECT 1 FROM wallet_accounts wa
                  JOIN organizations o ON o.id=wa.organization_id
                  JOIN organization_members om ON om.organization_id=o.id AND om.user_id=u.id
                  WHERE wa.user_id=u.id AND wa.status='ACTIVE' AND wa.account_type='TREASURY'
                    AND om.status='ACTIVE' AND om.member_role='OWNER'
                )
                """);

        jdbcTemplate.update("""
                INSERT INTO system_settings(setting_key,setting_value,description,updated_at)
                VALUES (?,?,'个人组织余额同步及企业员工双额度模型一次性校准',CURRENT_TIMESTAMP)
                """, MARKER, "personal=" + personal.size() + ",companies=" + companies.size()
                        + ",activeReservations=" + activeEnterpriseReservations.size());
        log.warn("Wallet balance reconciliation v2 completed: {} personal wallets, {} company treasuries, "
                        + "{} active enterprise reservations",
                personal.size(), companies.size(), activeEnterpriseReservations.size());
        return true;
    }
}
