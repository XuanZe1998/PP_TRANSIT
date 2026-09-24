package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MaPayLegacyPaymentMigration {
    private static final String MARKER = "mapay.legacy.payment.migration.v1";
    private final JdbcTemplate jdbcTemplate;
    private final ServiceCommerceService serviceCommerceService;
    private final TransactionTemplate transactionTemplate;

    @Bean
    @Order(3)
    ApplicationRunner maPayLegacyPaymentMigrationRunner() {
        return args -> migrate();
    }

    public void migrate() {
        transactionTemplate.executeWithoutResult(ignored -> migrateAtomically());
    }

    private void migrateAtomically() {
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key=?", Integer.class, MARKER);
        if (completed != null && completed > 0) return;

        List<Map<String, Object>> legacy = jdbcTemplate.queryForList("""
                SELECT id,business_type,business_id
                  FROM payment_intents
                 WHERE status IN ('PENDING','PREPARING')
                   AND COALESCE(payment_provider,'LEGACY') <> 'MAPAY'
                """);
        for (Map<String, Object> row : legacy) {
            long businessId = ((Number) row.get("business_id")).longValue();
            String businessType = String.valueOf(row.get("business_type"));
            if (PaymentBusinessSettlementService.SERVICE_ORDER.equals(businessType)) {
                serviceCommerceService.cancelPendingOrderForProviderMigration(businessId);
            } else if (PaymentBusinessSettlementService.WALLET_RECHARGE.equals(businessType)) {
                jdbcTemplate.update("""
                        UPDATE wallet_recharge_orders
                           SET status='CANCELLED',updated_at=?
                         WHERE id=? AND status IN ('PENDING','EXPIRED')
                        """, LocalDateTime.now(), businessId);
            }
            jdbcTemplate.update("""
                    UPDATE payment_intents
                       SET status='CANCELLED',
                           last_error='Legacy provider retired during MaPay migration',
                           updated_at=?
                     WHERE id=? AND status IN ('PENDING','PREPARING')
                    """, LocalDateTime.now(), row.get("id"));
        }
        jdbcTemplate.update("""
                INSERT INTO system_settings(setting_key,setting_value,description,updated_at)
                VALUES(?, 'true', 'Legacy pending payment attempts cancelled for MaPay migration', ?)
                """, MARKER, LocalDateTime.now());
        if (!legacy.isEmpty()) {
            log.warn("Cancelled {} legacy pending payment intent(s) during MaPay migration", legacy.size());
        }
    }
}
