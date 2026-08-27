package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Explicit, one-shot bootstrap for applying the reviewed public price manifest.
 *
 * <p>The normal administrator workflow remains preview then apply. Deployments that
 * need to bring an existing database up to the bundled reviewed snapshot can start
 * once with {@code --pricing.reconciliation.bootstrap-enabled=true}. The manifest
 * verification timestamp is recorded in system_settings, so restarts are idempotent.</p>
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pricing.reconciliation.bootstrap-enabled", havingValue = "true")
public class PublicPricingBootstrapService implements ApplicationRunner {
    private static final String MARKER_PREFIX = "public_pricing_manifest_applied_";

    private final PublicPricingReconciliationService reconciliationService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        PublicPricingReconciliationService.ReconciliationReport preview = reconciliationService.preview();
        String marker = MARKER_PREFIX + normalize(preview.verifiedAt());
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key = ?",
                Integer.class,
                marker);
        if (applied != null && applied > 0) {
            log.info("Public pricing manifest {} was already applied", preview.verifiedAt());
            return;
        }
        if (preview.modelCount() == 0 || preview.exchangeRate() == null
                || preview.exchangeRate().signum() <= 0) {
            throw new IllegalStateException("Public pricing preview failed validation");
        }

        PublicPricingReconciliationService.ReconciliationReport result = reconciliationService.apply();
        jdbcTemplate.update("""
                INSERT INTO system_settings(setting_key, setting_value, description, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                """, marker, result.mode(),
                "已预览并应用公开模型价目快照 " + result.verifiedAt());
        log.info("Applied reviewed public pricing manifest {}: {} routes updated, {} routes paused",
                result.verifiedAt(), result.updatedRouteCount(), result.pausedRouteCount());
    }

    private String normalize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^0-9A-Za-z]+", "_");
    }
}
