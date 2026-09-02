package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Lossless, repeatable consolidation for managed providers that use a credential pool. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ManagedChannelConsolidationService {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final ChannelSecretService secrets;

    @Bean
    @Order(2)
    ApplicationRunner managedChannelConsolidationRunner() {
        return args -> transactions.executeWithoutResult(ignored -> consolidate("haoee", "maas.haoee.com", "好易智算 MaaS", "好易智算"));
    }

    int consolidate(String sourceCode, String hostFragment, String canonicalName, String sourceName) {
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                SELECT * FROM channels
                WHERE LOWER(COALESCE(source_code,''))=? OR LOWER(COALESCE(base_url,'')) LIKE ?
                ORDER BY created_at ASC, id ASC
                """, sourceCode.toLowerCase(Locale.ROOT), "%" + hostFragment.toLowerCase(Locale.ROOT) + "%");
        if (candidates.isEmpty()) return 0;
        long canonicalId = ((Number) candidates.get(0).get("id")).longValue();
        jdbcTemplate.update("""
                UPDATE channels SET name=?,type=?,source_code=?,source_name=?,group_name=?,base_url=TRIM(TRAILING '/' FROM base_url)
                WHERE id=?
                """, canonicalName, sourceCode, sourceCode, sourceName, sourceCode, canonicalId);

        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (Map<String, Object> candidate : candidates) {
            splitModels(Objects.toString(candidate.get("models"), "")).forEach(models::add);
        }

        int removed = 0;
        for (int index = 1; index < candidates.size(); index++) {
            long duplicateId = ((Number) candidates.get(index).get("id")).longValue();
            migrateLegacyKeyIfNeeded(duplicateId);
            jdbcTemplate.update("UPDATE provider_credentials SET channel_id=?,name=CONCAT(name,' · migrated ',?) WHERE channel_id=?",
                    canonicalId, duplicateId, duplicateId);
            mergeMappings(canonicalId, duplicateId);
            jdbcTemplate.update("UPDATE channel_test_logs SET channel_id=? WHERE channel_id=?", canonicalId, duplicateId);
            jdbcTemplate.update("UPDATE channel_health_checks SET channel_id=? WHERE channel_id=?", canonicalId, duplicateId);
            jdbcTemplate.update("UPDATE logs SET channel_id=? WHERE channel_id=?", canonicalId, duplicateId);
            jdbcTemplate.update("UPDATE model_tasks SET channel_id=? WHERE channel_id=?", canonicalId, duplicateId);
            jdbcTemplate.update("DELETE FROM channels WHERE id=?", duplicateId);
            removed++;
        }
        migrateLegacyKeyIfNeeded(canonicalId);
        deduplicateCredentials(canonicalId);
        jdbcTemplate.update("UPDATE channels SET models=? WHERE id=?", String.join("\n", models), canonicalId);
        if (removed > 0) log.warn("Consolidated {} duplicate {} channels into channel {}", removed, sourceCode, canonicalId);
        return removed;
    }

    /**
     * A managed channel may already have received the same key through catalog
     * bootstrap and through its former legacy channel field. Compare decrypted
     * values (ciphertexts use random nonces) and keep only one pool row per key.
     */
    private void deduplicateCredentials(long channelId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,encrypted_secret FROM provider_credentials
                WHERE channel_id=? ORDER BY id
                """, channelId);
        Map<String, Long> keepers = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            String stored = Objects.toString(row.get("encrypted_secret"), "");
            String plain;
            try {
                plain = secrets.decrypt(stored);
            } catch (Exception e) {
                log.warn("Skipping credential id={} on channel {}: unable to decrypt (key mismatch?)", id, channelId);
                continue;
            }
            Long keeper = keepers.putIfAbsent(plain, id);
            if (keeper == null) continue;
            jdbcTemplate.update("UPDATE logs SET credential_id=? WHERE credential_id=?", keeper, id);
            jdbcTemplate.update("UPDATE model_tasks SET credential_id=? WHERE credential_id=?", keeper, id);
            jdbcTemplate.update("DELETE FROM provider_credentials WHERE id=?", id);
        }
    }

    private void migrateLegacyKeyIfNeeded(long channelId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT api_key FROM channels WHERE id=?", channelId);
        if (rows.isEmpty()) return;
        String encrypted = Objects.toString(rows.get(0).get("api_key"), "").trim();
        if (encrypted.isBlank()) return;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM provider_credentials WHERE channel_id=? AND encrypted_secret=?",
                Integer.class, channelId, encrypted);
        if (count != null && count > 0) return;
        jdbcTemplate.update("""
                INSERT INTO provider_credentials(channel_id,name,encrypted_secret,secret_preview,priority,weight,
                  rpm_limit,tpm_limit,concurrency_limit,enabled,health_status,created_at,updated_at)
                VALUES (?,CONCAT('Legacy channel key ',? ),?,'****',0,100,0,0,0,TRUE,'UNTESTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, channelId, channelId, encrypted);
    }

    private void mergeMappings(long canonicalId, long duplicateId) {
        List<Map<String, Object>> duplicateMappings = jdbcTemplate.queryForList(
                "SELECT id,channel_model_name FROM model_mappings WHERE channel_id=? ORDER BY id", duplicateId);
        for (Map<String, Object> duplicate : duplicateMappings) {
            long duplicateMappingId = ((Number) duplicate.get("id")).longValue();
            String model = Objects.toString(duplicate.get("channel_model_name"), "");
            List<Long> canonical = jdbcTemplate.queryForList(
                    "SELECT id FROM model_mappings WHERE channel_id=? AND channel_model_name=? ORDER BY id LIMIT 1",
                    Long.class, canonicalId, model);
            if (canonical.isEmpty()) {
                jdbcTemplate.update("UPDATE model_mappings SET channel_id=? WHERE id=?", canonicalId, duplicateMappingId);
                continue;
            }
            long targetId = canonical.get(0);
            jdbcTemplate.update("""
                    UPDATE model_mappings target JOIN model_mappings source ON source.id=?
                    SET target.vendor=CASE WHEN target.vendor IS NULL OR target.vendor='unknown' THEN source.vendor ELSE target.vendor END,
                        target.capability=CASE WHEN target.capability IS NULL OR target.capability='text' THEN source.capability ELSE target.capability END,
                        target.input_modalities=COALESCE(NULLIF(target.input_modalities,''),source.input_modalities),
                        target.output_modalities=COALESCE(NULLIF(target.output_modalities,''),source.output_modalities),
                        target.protocols=COALESCE(NULLIF(target.protocols,''),source.protocols),
                        target.pricing_unit=COALESCE(NULLIF(target.pricing_unit,''),source.pricing_unit),
                        target.endpoint_path=COALESCE(target.endpoint_path,source.endpoint_path),
                        target.task_query_path=COALESCE(target.task_query_path,source.task_query_path),
                        target.enabled=(target.enabled OR source.enabled),
                        target.billing_enabled=(target.billing_enabled OR source.billing_enabled),
                        target.billing_mode=CASE WHEN target.billing_mode='DISABLED' THEN source.billing_mode ELSE target.billing_mode END,
                        target.pricing_status=CASE WHEN target.pricing_status='PENDING' THEN source.pricing_status ELSE target.pricing_status END,
                        target.pricing_message=COALESCE(target.pricing_message,source.pricing_message),
                        target.pricing_source_url=COALESCE(target.pricing_source_url,source.pricing_source_url),
                        target.pricing_verified_at=COALESCE(target.pricing_verified_at,source.pricing_verified_at),
                        target.official_unit_price=GREATEST(target.official_unit_price,source.official_unit_price),
                        target.cost_unit_price=GREATEST(target.cost_unit_price,source.cost_unit_price),
                        target.sale_unit_price=GREATEST(target.sale_unit_price,source.sale_unit_price),
                        target.input_cost_per_million=GREATEST(target.input_cost_per_million,source.input_cost_per_million),
                        target.output_cost_per_million=GREATEST(target.output_cost_per_million,source.output_cost_per_million),
                        target.cached_cost_per_million=GREATEST(target.cached_cost_per_million,source.cached_cost_per_million),
                        target.input_price_per_million=GREATEST(target.input_price_per_million,source.input_price_per_million),
                        target.output_price_per_million=GREATEST(target.output_price_per_million,source.output_price_per_million),
                        target.cached_price_per_million=GREATEST(target.cached_price_per_million,source.cached_price_per_million)
                    WHERE target.id=?
                    """, duplicateMappingId, targetId);
            Integer tiers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM model_price_tiers WHERE model_mapping_id=?", Integer.class, targetId);
            if (tiers == null || tiers == 0) {
                jdbcTemplate.update("UPDATE model_price_tiers SET model_mapping_id=? WHERE model_mapping_id=?", targetId, duplicateMappingId);
            } else {
                jdbcTemplate.update("DELETE FROM model_price_tiers WHERE model_mapping_id=?", duplicateMappingId);
            }
            jdbcTemplate.update("DELETE FROM model_mappings WHERE id=?", duplicateMappingId);
        }
    }

    private List<String> splitModels(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.split("[,，、\\r\\n]+")) {
            String model = item.trim();
            if (!model.isBlank()) result.add(model);
        }
        return result;
    }
}
