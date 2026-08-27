package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SchemaRepairService {

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._:/-]{1,160}");

    private final JdbcTemplate jdbcTemplate;

    @Value("${app.seed-demo-catalog:false}")
    private boolean seedDemoCatalog;
    @Bean
    @Order(0)
    ApplicationRunner schemaRepairRunner() {
        return args -> {
            ensureCoreTables();
            ensureOAuthTables();
            ensurePlatformTables();
            ensureAdminOperationsTables();
            ensureAccountPresentationTables();
            removeLegacyPlusAndService07();
            ensureColumns();
            normalizeAndValidateContacts();
            ensureIndexes();
            purgeExistingFailedProviderModels();
            seedDefaults();
            backfillPaymentIntents();
            backfillChannelModelMappings();
            backfillModelPriceTiers();
        };
    }

    private void removeLegacyPlusAndService07() {
        Integer migrated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key = 'legacy_plus_service07_removed_v1'",
                Integer.class);
        if (migrated != null && migrated > 0) return;

        log.warn("Removing legacy commercial order and Service 07 data");
        if (tableExists("payment_intents")) {
            jdbcTemplate.update("DELETE FROM payment_intents WHERE business_type = 'SERVICE_ORDER'");
        }
        if (tableExists("service_orders")) jdbcTemplate.update("DELETE FROM service_orders");
        if (tableExists("service_inventory_items")) {
            jdbcTemplate.update("""
                    UPDATE service_inventory_items
                       SET status='AVAILABLE', reserved_order_id=NULL, reserved_until=NULL
                     WHERE status='RESERVED'
                    """);
            jdbcTemplate.update("DELETE FROM service_inventory_items WHERE status='DELIVERED'");
            jdbcTemplate.update("DELETE FROM service_inventory_items WHERE service_id=7");
        }
        if (tableExists("service_coupons") && columnExists("service_coupons", "reserved_uses")) {
            jdbcTemplate.update("""
                    UPDATE service_coupons
                       SET remaining_uses = CASE WHEN remaining_uses IS NULL THEN NULL
                                                ELSE remaining_uses + COALESCE(reserved_uses, 0) END,
                           reserved_uses = 0
                    """);
        }
        if (tableExists("other_services") && columnExists("other_services", "manual_reserved")) {
            jdbcTemplate.update("UPDATE other_services SET manual_reserved=0");
        }
        if (tableExists("service_coupon_services")) {
            jdbcTemplate.update("DELETE FROM service_coupon_services WHERE service_id=7");
        }
        if (tableExists("other_services")) {
            jdbcTemplate.update("DELETE FROM other_services WHERE id=7 AND name='Chat GPT Plus'");
        }

        executeIgnoring("DROP TABLE IF EXISTS service07_fulfillments");
        executeIgnoring("DROP TABLE IF EXISTS plus_orders");
        executeIgnoring("DROP TABLE IF EXISTS plus_products");
        executeIgnoring("DROP INDEX uq_other_services_plus_product ON other_services");
        executeIgnoring("DROP INDEX uq_other_services_plus_product");
        if (columnExists("other_services", "linked_product_id")) {
            executeIgnoring("ALTER TABLE other_services DROP COLUMN linked_product_id");
        }
        if (columnExists("other_services", "service_type")) {
            executeIgnoring("ALTER TABLE other_services DROP COLUMN service_type");
        }
        jdbcTemplate.update("""
                INSERT INTO system_settings(setting_key, setting_value, description, updated_at)
                VALUES ('legacy_plus_service07_removed_v1', 'true',
                        'Legacy commercial order and Service 07 data removed', CURRENT_TIMESTAMP)
                """);
    }

    private void purgeExistingFailedProviderModels() {
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key='provider_models.failed_purge_v1'", Integer.class);
        if (completed != null && completed > 0) return;
        List<Map<String, Object>> failed = jdbcTemplate.queryForList("""
                SELECT id,source_code,upstream_model_name,public_model_name,verification_message
                FROM provider_models WHERE verification_status='FAILED'
                """);
        for (Map<String, Object> model : failed) {
            long modelId = ((Number) model.get("id")).longValue();
            String source = String.valueOf(model.get("source_code"));
            String upstream = String.valueOf(model.get("upstream_model_name"));
            jdbcTemplate.update("""
                    INSERT INTO provider_model_exclusions
                    (source_code,upstream_model_name,public_model_name,reason,excluded_at)
                    SELECT ?,?,?,?,CURRENT_TIMESTAMP WHERE NOT EXISTS (
                      SELECT 1 FROM provider_model_exclusions WHERE source_code=? AND upstream_model_name=?
                    )
                    """, source, upstream, String.valueOf(model.get("public_model_name")),
                    model.get("verification_message"), source, upstream);
            List<Long> mappingIds = jdbcTemplate.queryForList("""
                    SELECT mm.id FROM model_mappings mm JOIN channels c ON c.id=mm.channel_id
                    WHERE LOWER(COALESCE(c.source_code,c.type))=LOWER(?) AND mm.channel_model_name=?
                    """, Long.class, source, upstream);
            for (Long mappingId : mappingIds) {
                jdbcTemplate.update("DELETE FROM model_price_tiers WHERE model_mapping_id=?", mappingId);
                jdbcTemplate.update("DELETE FROM model_mappings WHERE id=?", mappingId);
            }
            jdbcTemplate.update("DELETE FROM provider_model_verifications WHERE provider_model_id=?", modelId);
            jdbcTemplate.update("DELETE FROM provider_models WHERE id=?", modelId);
        }
        jdbcTemplate.update("""
                INSERT INTO system_settings(setting_key,setting_value,description,updated_at)
                VALUES ('provider_models.failed_purge_v1',?,'已将历史验证失败模型迁移到永久排除清单',CURRENT_TIMESTAMP)
                """, String.valueOf(failed.size()));
        if (!failed.isEmpty()) log.warn("Permanently excluded {} previously failed provider models", failed.size());
    }

    private void executeIgnoring(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException ignored) {
            log.debug("Optional legacy cleanup statement was not applicable: {}", sql);
        }
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME)=UPPER(?)",
                Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                 WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }

    int backfillPaymentIntents() {
        int created = jdbcTemplate.update("""
                INSERT INTO payment_intents(
                    order_no, user_id, business_type, business_id, description,
                    source_amount, source_currency, source_scale,
                    settlement_amount_cents, settlement_currency, exchange_rate,
                    payment_method, status, payment_provider, provider_trade_no,
                    payment_type, payment_url, paid_at, created_at, updated_at
                )
                SELECT o.order_no, o.user_id, 'SERVICE_ORDER', o.id,
                       COALESCE(o.product_name, 'Service order'),
                       o.amount_cents, COALESCE(o.currency, 'CNY'), 100,
                       COALESCE(o.payment_amount_cents, o.amount_cents),
                       COALESCE(o.payment_currency, 'CNY'), COALESCE(o.exchange_rate, 1),
                       COALESCE(o.payment_method, 'alipay'),
                       CASE WHEN o.status IN ('PAID', 'FULFILLED') THEN 'PAID'
                            WHEN o.status = 'EXPIRED' THEN 'PENDING'
                            WHEN o.status IN ('FAILED', 'CANCELLED') THEN 'FAILED'
                            ELSE 'PENDING' END,
                       o.payment_provider, o.provider_trade_no, o.payment_type,
                       o.payment_url, o.paid_at, o.created_at, o.updated_at
                  FROM service_orders o
                 WHERE o.order_no IS NOT NULL AND o.user_id IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM payment_intents p
                        WHERE p.business_type = 'SERVICE_ORDER' AND p.business_id = o.id
                   )
                """);
        if (created > 0) log.info("Backfilled {} service payment intent(s)", created);
        jdbcTemplate.update("UPDATE payment_intents SET refund_status='NONE' WHERE refund_status IS NULL");
        jdbcTemplate.update("UPDATE payment_intents SET status='PENDING' WHERE business_type='SERVICE_ORDER' AND status='EXPIRED'");
        return created;
    }

    /**
     * Repairs channels created before model mappings became channel-owned. The
     * migration is deliberately additive: it creates only missing same-name
     * mappings and never changes existing aliases, prices, costs or publication
     * settings.
     */
    int backfillChannelModelMappings() {
        List<Map<String, Object>> channels = jdbcTemplate.queryForList("""
                SELECT id, models FROM channels
                WHERE models IS NOT NULL AND TRIM(models) <> ''
                """);
        int created = 0;
        for (Map<String, Object> channel : channels) {
            long channelId = ((Number) channel.get("id")).longValue();
            Set<String> models = new LinkedHashSet<>();
            for (String item : String.valueOf(channel.get("models")).split("[,，、\\r\\n]+")) {
                String model = item.trim();
                if (model.isBlank()) continue;
                if (!MODEL_NAME.matcher(model).matches()) {
                    log.warn("Skipped invalid legacy model name while repairing channel {}", channelId);
                    continue;
                }
                models.add(model);
            }
            for (String model : models) {
                created += jdbcTemplate.update("""
                        INSERT INTO model_mappings(
                            public_model_name, channel_model_name, channel_id,
                            priority, enabled, price_ratio, cost_per_million,
                            input_price_per_million, output_price_per_million, cached_price_per_million,
                            input_cost_per_million, output_cost_per_million, cached_cost_per_million,
                            billing_enabled, traffic_percent, created_at
                        )
                        SELECT ?, ?, c.id,
                               10, TRUE, 1, 0,
                               1, 1, 0,
                               0, 0, 0,
                               TRUE, 100, CURRENT_TIMESTAMP
                        FROM channels c
                        WHERE c.id = ?
                          AND NOT EXISTS (
                              SELECT 1 FROM model_mappings mm
                              WHERE mm.channel_id = c.id AND mm.channel_model_name = ?
                          )
                        """, model, model, channelId, model);
            }
        }
        if (created > 0) {
            log.info("Backfilled {} channel-owned model mapping(s)", created);
        }
        return created;
    }

    int backfillModelPriceTiers() {
        int created = jdbcTemplate.update("""
                INSERT INTO model_price_tiers(
                    model_mapping_id, tier_name, max_context_tokens, sort_order,
                    official_group_name, official_input_price, official_output_price,
                    official_cache_read_price, official_cache_write_price,
                    cost_group_name, cost_input_price, cost_output_price,
                    cost_cache_read_price, cost_cache_write_price,
                    sale_group_name, sale_input_price, sale_output_price,
                    sale_cache_read_price, sale_cache_write_price, created_at, updated_at
                )
                SELECT mm.id, '默认挡位', NULL, 0,
                       '官网价格（待补充）', 0, 0, 0, 0,
                       '采购成本', COALESCE(mm.input_cost_per_million, mm.cost_per_million, 0),
                       COALESCE(mm.output_cost_per_million, mm.cost_per_million, 0),
                       COALESCE(mm.cached_cost_per_million, 0), 0,
                       '本站售价', COALESCE(mm.input_price_per_million, mm.price_ratio, 1),
                       COALESCE(mm.output_price_per_million, mm.price_ratio, 1),
                       COALESCE(mm.cached_price_per_million, 0), 0,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                FROM model_mappings mm
                WHERE NOT EXISTS (
                    SELECT 1 FROM model_price_tiers tier WHERE tier.model_mapping_id = mm.id
                )
                """);
        if (created > 0) {
            log.info("Backfilled {} default model price tier(s)", created);
        }
        return created;
    }

    private void ensureCoreTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS admins (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    username VARCHAR(120) NOT NULL UNIQUE,
                    password VARCHAR(255) NOT NULL,
                    display_name VARCHAR(120) NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    access_token VARCHAR(255) NULL,
                    token_expires_at DATETIME NULL,
                    last_login_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    username VARCHAR(120) NOT NULL UNIQUE,
                    password VARCHAR(255) NULL,
                    email VARCHAR(255) NULL,
                    phone VARCHAR(40) NULL,
                    auth_provider VARCHAR(40) NOT NULL DEFAULT 'local',
                    role VARCHAR(40) NOT NULL DEFAULT 'USER',
                    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
                    group_id BIGINT NULL,
                    balance BIGINT NOT NULL DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS channels (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    type VARCHAR(80) NOT NULL,
                    base_url VARCHAR(500) NULL,
                    api_key VARCHAR(1000) NULL,
                    models VARCHAR(2000) NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    group_name VARCHAR(80) NOT NULL DEFAULT 'default',
                    weight INT NOT NULL DEFAULT 100,
                    rpm_limit INT NOT NULL DEFAULT 0,
                    tpm_limit INT NOT NULL DEFAULT 0,
                    health_status VARCHAR(40) NOT NULL DEFAULT 'UNTESTED',
                    cooldown_until DATETIME NULL,
                    auto_disable BOOLEAN NOT NULL DEFAULT TRUE,
                    failure_threshold INT NOT NULL DEFAULT 3,
                    cooldown_seconds INT NOT NULL DEFAULT 60,
                    consecutive_failures INT NOT NULL DEFAULT 0,
                    total_successes BIGINT NOT NULL DEFAULT 0,
                    total_failures BIGINT NOT NULL DEFAULT 0,
                    average_latency_ms BIGINT NOT NULL DEFAULT 0,
                    last_error VARCHAR(1000) NULL,
                    last_tested_at DATETIME NULL,
                    last_success_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_mappings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    public_model_name VARCHAR(160) NOT NULL,
                    channel_model_name VARCHAR(160) NOT NULL,
                    channel_id BIGINT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    price_ratio DECIMAL(10,4) NOT NULL DEFAULT 1,
                    cost_per_million DECIMAL(12,6) NOT NULL DEFAULT 0,
                    input_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 1,
                    output_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 1,
                    cached_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 0,
                    input_cost_per_million DECIMAL(18,6) NOT NULL DEFAULT 0,
                    output_cost_per_million DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cached_cost_per_million DECIMAL(18,6) NOT NULL DEFAULT 0,
                    billing_enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    traffic_percent INT NOT NULL DEFAULT 100,
                    capability_tags VARCHAR(1000) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_price_tiers (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    model_mapping_id BIGINT NOT NULL,
                    tier_name VARCHAR(120) NOT NULL,
                    max_context_tokens INT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    official_group_name VARCHAR(120) NOT NULL DEFAULT '官网价格',
                    official_input_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    official_output_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    official_cache_read_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    official_cache_write_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    official_price_unit VARCHAR(8) NOT NULL DEFAULT 'M',
                    official_price_suffix VARCHAR(120) NOT NULL DEFAULT 'USD / 1M Token',
                    cost_group_name VARCHAR(120) NOT NULL DEFAULT '采购成本',
                    cost_input_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_output_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_cache_read_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_cache_write_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_price_unit VARCHAR(8) NOT NULL DEFAULT 'M',
                    cost_price_suffix VARCHAR(120) NOT NULL DEFAULT 'USD / 1M Token',
                    sale_group_name VARCHAR(120) NOT NULL DEFAULT '本站售价',
                    sale_input_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_output_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_cache_read_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_cache_write_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_price_unit VARCHAR(8) NOT NULL DEFAULT 'M',
                    sale_price_suffix VARCHAR(120) NOT NULL DEFAULT 'USD / 1M Token',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tokens (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    `key` VARCHAR(255) NOT NULL UNIQUE,
                    key_prefix VARCHAR(32) NULL,
                    user_id BIGINT NULL,
                    name VARCHAR(160) NULL,
                    used_quota BIGINT NOT NULL DEFAULT 0,
                    total_quota BIGINT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    expired_at DATETIME NULL,
                    allowed_models VARCHAR(1000) NULL,
                    ip_whitelist VARCHAR(1000) NULL,
                    description VARCHAR(500) NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS logs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NULL,
                    token_key VARCHAR(255) NULL,
                    token_id BIGINT NULL,
                    model VARCHAR(160) NULL,
                    channel_id BIGINT NULL,
                    prompt_tokens INT NOT NULL DEFAULT 0,
                    completion_tokens INT NOT NULL DEFAULT 0,
                    total_tokens INT NOT NULL DEFAULT 0,
                    cached_tokens INT NOT NULL DEFAULT 0,
                    cache_read_tokens INT NOT NULL DEFAULT 0,
                    cache_write_tokens INT NOT NULL DEFAULT 0,
                    cost BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(40) NOT NULL DEFAULT 'SUCCESS',
                    latency_ms BIGINT NOT NULL DEFAULT 0,
                    trace_id VARCHAR(80) NULL,
                    error_message VARCHAR(1000) NULL,
                    sale_amount BIGINT NOT NULL DEFAULT 0,
                    cost_amount BIGINT NOT NULL DEFAULT 0,
                    input_amount BIGINT NOT NULL DEFAULT 0,
                    output_amount BIGINT NOT NULL DEFAULT 0,
                    cached_amount BIGINT NOT NULL DEFAULT 0,
                    cache_read_amount BIGINT NOT NULL DEFAULT 0,
                    cache_write_amount BIGINT NOT NULL DEFAULT 0,
                    total_amount BIGINT NOT NULL DEFAULT 0,
                    input_cost_amount BIGINT NOT NULL DEFAULT 0,
                    output_cost_amount BIGINT NOT NULL DEFAULT 0,
                    cached_cost_amount BIGINT NOT NULL DEFAULT 0,
                    cache_read_cost_amount BIGINT NOT NULL DEFAULT 0,
                    cache_write_cost_amount BIGINT NOT NULL DEFAULT 0,
                    gross_profit BIGINT NOT NULL DEFAULT 0,
                    model_currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                    model_amount_scale BIGINT NOT NULL DEFAULT 10000,
                    settlement_amount BIGINT NOT NULL DEFAULT 0,
                    settlement_currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
                    exchange_rate DECIMAL(18,8) NOT NULL DEFAULT 1,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS service_orders (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    order_no VARCHAR(80) NULL,
                    user_id BIGINT NULL,
                    service_id BIGINT NULL,
                    product_name VARCHAR(160) NULL,
                    quantity INT NOT NULL DEFAULT 1,
                    fulfillment_mode VARCHAR(32) NULL,
                    unit_price_cents BIGINT NULL,
                    effective_unit_price_cents BIGINT NULL,
                    merchandise_subtotal_cents BIGINT NULL,
                    wholesale_discount_cents BIGINT NOT NULL DEFAULT 0,
                    coupon_id BIGINT NULL,
                    coupon_code VARCHAR(80) NULL,
                    coupon_discount_cents BIGINT NOT NULL DEFAULT 0,
                    coupon_reservation_active BOOLEAN NOT NULL DEFAULT FALSE,
                    refund_resources_released BOOLEAN NOT NULL DEFAULT FALSE,
                    service_fee_cents BIGINT NULL,
                    amount_cents BIGINT NOT NULL DEFAULT 0,
                    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                    payment_amount_cents BIGINT NULL,
                    payment_currency VARCHAR(3) NULL,
                    exchange_rate DECIMAL(18,8) NULL,
                    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
                    contact_email VARCHAR(255) NULL,
                    contact_note VARCHAR(1000) NULL,
                    invoice_number VARCHAR(80) NULL,
                    receipt_number VARCHAR(14) NULL,
                    billing_name VARCHAR(160) NULL,
                    billing_address_line_1 VARCHAR(255) NULL,
                    billing_district VARCHAR(120) NULL,
                    billing_city VARCHAR(120) NULL,
                    billing_province VARCHAR(120) NULL,
                    billing_postal_code VARCHAR(20) NULL,
                    billing_country VARCHAR(120) NULL,
                    payment_method VARCHAR(20) NULL,
                    custom_input_json TEXT NULL,
                    purchase_prompt VARCHAR(1000) NULL,
                    supplier_quote_json TEXT NULL,
                    reservation_expires_at DATETIME NULL,
                    fulfillment_status VARCHAR(32) NULL,
                    delivery_content_encrypted TEXT NULL,
                    fulfillment_note VARCHAR(1000) NULL,
                    payment_reference VARCHAR(255) NULL,
                    payment_provider VARCHAR(40) NULL,
                    provider_trade_no VARCHAR(120) NULL,
                    payment_type VARCHAR(40) NULL,
                    payment_url VARCHAR(2000) NULL,
                    fulfillment_reference VARCHAR(255) NULL,
                    paid_at DATETIME NULL,
                    fulfilled_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL,
                    downloaded_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS other_services (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    description VARCHAR(1000) NULL,
                    image_url VARCHAR(1000) NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    action_label VARCHAR(40) NULL,
                    price_cents BIGINT NOT NULL DEFAULT 0,
                    service_fee_cents BIGINT NOT NULL DEFAULT 0,
                    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
                    purchase_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    product_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
                    fulfillment_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL_PROCESSING',
                    purchase_prompt VARCHAR(1000) NULL,
                    max_purchase_quantity INT NOT NULL DEFAULT 1,
                    manual_stock INT NULL,
                    manual_reserved INT NOT NULL DEFAULT 0,
                    wholesale_tiers_json TEXT NULL,
                    input_schema_json TEXT NULL,
                    redemption_url VARCHAR(2000) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS payment_intents (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    order_no VARCHAR(80) NOT NULL,
                    user_id BIGINT NOT NULL,
                    business_type VARCHAR(40) NOT NULL,
                    business_id BIGINT NOT NULL,
                    description VARCHAR(255) NOT NULL,
                    source_amount BIGINT NOT NULL,
                    source_currency VARCHAR(3) NOT NULL,
                    source_scale BIGINT NOT NULL,
                    settlement_amount_cents BIGINT NOT NULL,
                    settlement_currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
                    exchange_rate DECIMAL(18,8) NOT NULL,
                    payment_method VARCHAR(20) NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    payment_provider VARCHAR(40) NULL,
                    provider_trade_no VARCHAR(120) NULL,
                    payment_type VARCHAR(40) NULL,
                    payment_url VARCHAR(2000) NULL,
                    expires_at DATETIME NULL,
                    paid_at DATETIME NULL,
                    refund_status VARCHAR(32) NULL,
                    refund_no VARCHAR(80) NULL,
                    provider_refund_no VARCHAR(120) NULL,
                    refund_reason VARCHAR(500) NULL,
                    refunded_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS wallet_recharge_orders (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    order_no VARCHAR(80) NOT NULL,
                    user_id BIGINT NOT NULL,
                    plan_id BIGINT NOT NULL,
                    plan_name VARCHAR(120) NOT NULL,
                    payment_amount_units BIGINT NOT NULL,
                    base_credit_units BIGINT NOT NULL,
                    bonus_percent INT NOT NULL,
                    bonus_credit_units BIGINT NOT NULL,
                    total_credit_units BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    payment_method VARCHAR(20) NOT NULL,
                    invoice_number VARCHAR(80) NOT NULL,
                    invoice_requested BOOLEAN NOT NULL DEFAULT FALSE,
                    receipt_number VARCHAR(14) NOT NULL,
                    contact_email VARCHAR(255) NOT NULL,
                    billing_name VARCHAR(160) NOT NULL,
                    billing_address_line_1 VARCHAR(255) NOT NULL,
                    billing_district VARCHAR(120) NOT NULL,
                    billing_city VARCHAR(120) NOT NULL,
                    billing_province VARCHAR(120) NOT NULL,
                    billing_postal_code VARCHAR(20) NOT NULL,
                    billing_country VARCHAR(120) NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL,
                    paid_at DATETIME NULL,
                    refunded_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS service_inventory_items (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    service_id BIGINT NOT NULL,
                    content_encrypted TEXT NOT NULL,
                    content_fingerprint VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL DEFAULT 'AVAILABLE',
                    reserved_order_id BIGINT NULL,
                    reserved_until DATETIME NULL,
                    delivered_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS service_coupons (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    code VARCHAR(80) NOT NULL,
                    discount_cents BIGINT NOT NULL,
                    remaining_uses INT NOT NULL,
                    reserved_uses INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS service_coupon_services (
                    coupon_id BIGINT NOT NULL,
                    service_id BIGINT NOT NULL,
                    PRIMARY KEY (coupon_id, service_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vmcard_webhook_events (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    event_type VARCHAR(40) NOT NULL,
                    external_id VARCHAR(128) NOT NULL,
                    encrypted_payload TEXT NOT NULL,
                    received_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vmcard_saved_cards (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    card_id VARCHAR(128) NOT NULL,
                    environment VARCHAR(20) NOT NULL DEFAULT 'sandbox',
                    label VARCHAR(160) NULL,
                    product_code VARCHAR(120) NULL,
                    email VARCHAR(254) NULL,
                    card_created_at DATETIME NULL,
                    disabled_or_frozen_at DATETIME NULL,
                    encrypted_payload MEDIUMTEXT NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS vmcard_product_codes (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    environment VARCHAR(20) NOT NULL DEFAULT 'sandbox',
                    bin VARCHAR(32) NULL,
                    product_code VARCHAR(120) NOT NULL,
                    type VARCHAR(40) NULL,
                    network VARCHAR(40) NULL,
                    media VARCHAR(80) NULL,
                    issuing_area VARCHAR(120) NULL,
                    remaining_open_card_num INT NOT NULL DEFAULT 0,
                    available BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
    }

    private void ensurePlatformTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_provider_configs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    provider_key VARCHAR(80) NOT NULL DEFAULT 'seedance',
                    display_name VARCHAR(160) NOT NULL,
                    base_url VARCHAR(1000) NOT NULL,
                    api_key VARCHAR(2000) NOT NULL,
                    model_ids_json TEXT NOT NULL,
                    default_model VARCHAR(160) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_platform_connections (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    capability VARCHAR(20) NOT NULL,
                    provider_key VARCHAR(80) NOT NULL,
                    display_name VARCHAR(160) NOT NULL,
                    base_url VARCHAR(1000) NOT NULL,
                    api_key MEDIUMTEXT NULL,
                    model_ids_json TEXT NOT NULL,
                    default_model VARCHAR(160) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    is_default BOOLEAN NOT NULL DEFAULT FALSE,
                    default_slot VARCHAR(20) NULL,
                    version INT NOT NULL DEFAULT 1,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_runtime_settings (
                    id BIGINT PRIMARY KEY,
                    auto_movie_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    script_price BIGINT NOT NULL DEFAULT 10000,
                    image_price BIGINT NOT NULL DEFAULT 5000,
                    video_second_price BIGINT NOT NULL DEFAULT 2000,
                    worker_concurrency INT NOT NULL DEFAULT 2,
                    video_concurrency INT NOT NULL DEFAULT 3,
                    max_retries INT NOT NULL DEFAULT 3,
                    poll_interval_ms INT NOT NULL DEFAULT 5000,
                    max_source_bytes BIGINT NOT NULL DEFAULT 1048576,
                    max_source_characters INT NOT NULL DEFAULT 200000,
                    max_image_bytes BIGINT NOT NULL DEFAULT 10485760,
                    max_characters INT NOT NULL DEFAULT 8,
                    max_scenes INT NOT NULL DEFAULT 8,
                    max_shots INT NOT NULL DEFAULT 12,
                    min_duration INT NOT NULL DEFAULT 30,
                    max_duration INT NOT NULL DEFAULT 90,
                    version INT NOT NULL DEFAULT 1,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_storage_configs (
                    id BIGINT PRIMARY KEY,
                    storage_type VARCHAR(20) NOT NULL DEFAULT 'S3',
                    endpoint VARCHAR(1000) NULL,
                    region_name VARCHAR(120) NULL,
                    bucket_name VARCHAR(255) NULL,
                    public_base_url VARCHAR(1000) NULL,
                    access_key MEDIUMTEXT NULL,
                    secret_key MEDIUMTEXT NULL,
                    path_style BOOLEAN NOT NULL DEFAULT FALSE,
                    signed_url_seconds INT NOT NULL DEFAULT 3600,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    version INT NOT NULL DEFAULT 1,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_config_audit (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    admin_user_id BIGINT NULL,
                    action_name VARCHAR(80) NOT NULL,
                    target_type VARCHAR(80) NOT NULL,
                    target_id VARCHAR(120) NULL,
                    changed_fields_json TEXT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO creative_runtime_settings(id)
                SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM creative_runtime_settings WHERE id=1)
                """);
        jdbcTemplate.update("""
                INSERT INTO creative_storage_configs(id)
                SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM creative_storage_configs WHERE id=1)
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_tasks (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    provider_key VARCHAR(80) NOT NULL,
                    provider_config_id BIGINT NULL,
                    model_key VARCHAR(160) NOT NULL,
                    mode VARCHAR(60) NOT NULL,
                    project_name VARCHAR(160) NULL,
                    prompt TEXT NOT NULL,
                    first_frame_url VARCHAR(2000) NULL,
                    input_last_frame_url VARCHAR(2000) NULL,
                    reference_urls_json TEXT NULL,
                    options_json TEXT NULL,
                    provider_task_id VARCHAR(200) NULL,
                    status VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
                    video_url TEXT NULL,
                    thumbnail_url TEXT NULL,
                    output_last_frame_url TEXT NULL,
                    error_message TEXT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL,
                    completed_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_projects (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    title VARCHAR(160) NOT NULL,
                    source_text LONGTEXT NOT NULL,
                    target_duration INT NOT NULL DEFAULT 60,
                    ratio VARCHAR(20) NOT NULL DEFAULT '16:9',
                    resolution VARCHAR(20) NOT NULL DEFAULT '720p',
                    style VARCHAR(160) NULL,
                    language VARCHAR(40) NOT NULL DEFAULT 'zh-CN',
                    generate_audio BOOLEAN NOT NULL DEFAULT TRUE,
                    text_connection_id BIGINT NULL,
                    image_connection_id BIGINT NULL,
                    video_connection_id BIGINT NULL,
                    text_model VARCHAR(160) NULL,
                    image_model VARCHAR(160) NULL,
                    video_model VARCHAR(160) NULL,
                    stage VARCHAR(40) NOT NULL DEFAULT 'SOURCE',
                    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
                    version INT NOT NULL DEFAULT 1,
                    final_video_url TEXT NULL,
                    cover_url TEXT NULL,
                    error_message TEXT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL,
                    completed_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_scripts (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    project_id BIGINT NOT NULL,
                    version INT NOT NULL,
                    summary TEXT NULL,
                    script_json LONGTEXT NOT NULL,
                    approved BOOLEAN NOT NULL DEFAULT FALSE,
                    model_key VARCHAR(160) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_assets (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    project_id BIGINT NOT NULL,
                    script_version INT NOT NULL,
                    asset_type VARCHAR(20) NOT NULL,
                    temp_ref VARCHAR(80) NOT NULL,
                    name VARCHAR(160) NOT NULL,
                    description TEXT NULL,
                    prompt TEXT NULL,
                    image_url TEXT NULL,
                    source VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
                    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
                    error_message TEXT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_shots (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    project_id BIGINT NOT NULL,
                    script_version INT NOT NULL,
                    shot_order INT NOT NULL,
                    duration INT NOT NULL,
                    dialogue TEXT NULL,
                    narration TEXT NULL,
                    video_prompt TEXT NOT NULL,
                    character_refs_json TEXT NULL,
                    scene_ref VARCHAR(80) NULL,
                    reference_urls_json TEXT NULL,
                    creative_task_id BIGINT NULL,
                    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
                    video_url TEXT NULL,
                    thumbnail_url TEXT NULL,
                    error_message TEXT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_jobs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    project_id BIGINT NOT NULL,
                    job_type VARCHAR(40) NOT NULL,
                    payload_json TEXT NULL,
                    status VARCHAR(40) NOT NULL DEFAULT 'QUEUED',
                    attempts INT NOT NULL DEFAULT 0,
                    next_run_at DATETIME NULL,
                    lease_owner VARCHAR(120) NULL,
                    lease_expires_at DATETIME NULL,
                    error_message TEXT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS creative_billing_reservations (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    project_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    stage VARCHAR(40) NOT NULL,
                    estimated_amount BIGINT NOT NULL DEFAULT 0,
                    reserved_amount BIGINT NOT NULL DEFAULT 0,
                    actual_amount BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(40) NOT NULL DEFAULT 'RESERVED',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    settled_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_groups (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(80) NOT NULL UNIQUE,
                    display_name VARCHAR(120) NOT NULL,
                    price_ratio DECIMAL(10,4) NOT NULL DEFAULT 1,
                    monthly_quota BIGINT NOT NULL DEFAULT 0,
                    description VARCHAR(500) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS wallet_transactions (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    type VARCHAR(40) NOT NULL,
                    amount BIGINT NOT NULL,
                    balance_after BIGINT NOT NULL,
                    channel VARCHAR(80) NULL,
                    remark VARCHAR(500) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS redeem_codes (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    code VARCHAR(80) NOT NULL UNIQUE,
                    code_prefix VARCHAR(24) NULL,
                    amount BIGINT NOT NULL,
                    max_uses INT NOT NULL DEFAULT 1,
                    used_count INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    expires_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS system_settings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    setting_key VARCHAR(120) NOT NULL UNIQUE,
                    setting_value VARCHAR(2000) NULL,
                    description VARCHAR(500) NULL,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS recharge_plans (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(120) NOT NULL UNIQUE,
                    amount BIGINT NOT NULL,
                    bonus_percent INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    sort_order INT NOT NULL DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS security_policies (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    scope VARCHAR(120) NOT NULL,
                    action VARCHAR(80) NOT NULL,
                    threshold_value VARCHAR(160) NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS integration_exports (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    client_type VARCHAR(80) NOT NULL,
                    config_text VARCHAR(4000) NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void ensureOAuthTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS oauth_clients (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    client_id VARCHAR(255) NOT NULL UNIQUE,
                    client_secret VARCHAR(255) NOT NULL,
                    provider VARCHAR(80) NULL,
                    redirect_uri VARCHAR(1000) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS oauth_codes (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    code VARCHAR(255) NOT NULL UNIQUE,
                    client_id VARCHAR(255) NOT NULL,
                    user_id BIGINT NOT NULL,
                    redirect_uri VARCHAR(1000) NULL,
                    scope VARCHAR(1000) NULL,
                    expires_at DATETIME NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS oauth_tokens (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    access_token VARCHAR(255) NOT NULL UNIQUE,
                    refresh_token VARCHAR(255) NOT NULL UNIQUE,
                    token_type VARCHAR(40) NOT NULL DEFAULT 'Bearer',
                    user_id BIGINT NOT NULL,
                    client_id VARCHAR(255) NULL,
                    scope VARCHAR(1000) NULL,
                    access_expires_at DATETIME NOT NULL,
                    expires_at DATETIME NOT NULL,
                    revoked BOOLEAN NOT NULL DEFAULT FALSE,
                    revoked_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS oauth_user_bindings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    provider VARCHAR(80) NOT NULL,
                    provider_user_id VARCHAR(255) NOT NULL,
                    access_token VARCHAR(2000) NULL,
                    refresh_token VARCHAR(2000) NULL,
                    expires_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(provider, provider_user_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS oauth_login_states (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    state_hash VARCHAR(255) NOT NULL UNIQUE,
                    provider VARCHAR(40) NOT NULL,
                    expires_at DATETIME NOT NULL,
                    consumed_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void ensureAdminOperationsTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS provider_model_exclusions (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    source_code VARCHAR(80) NOT NULL,
                    upstream_model_name VARCHAR(160) NOT NULL,
                    public_model_name VARCHAR(160) NOT NULL,
                    reason VARCHAR(500) NULL,
                    excluded_by BIGINT NULL,
                    excluded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(source_code, upstream_model_name)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS admin_audit_logs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    admin_id BIGINT NULL,
                    admin_name VARCHAR(120) NULL,
                    action VARCHAR(120) NOT NULL,
                    target_type VARCHAR(80) NOT NULL,
                    target_id VARCHAR(80) NULL,
                    before_data VARCHAR(4000) NULL,
                    after_data VARCHAR(4000) NULL,
                    ip_address VARCHAR(80) NULL,
                    result VARCHAR(40) NOT NULL DEFAULT 'SUCCESS',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS channel_health_checks (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    channel_id BIGINT NOT NULL,
                    status VARCHAR(40) NOT NULL,
                    latency_ms BIGINT NOT NULL DEFAULT 0,
                    message VARCHAR(1000) NULL,
                    checked_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS channel_test_logs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    channel_id BIGINT NOT NULL,
                    model_name VARCHAR(160) NULL,
                    provider_type VARCHAR(80) NULL,
                    exit_code INT NOT NULL DEFAULT 0,
                    status VARCHAR(40) NOT NULL,
                    latency_ms BIGINT NOT NULL DEFAULT 0,
                    prompt_tokens INT NOT NULL DEFAULT 0,
                    completion_tokens INT NOT NULL DEFAULT 0,
                    cached_tokens INT NOT NULL DEFAULT 0,
                    estimated_cost_amount BIGINT NOT NULL DEFAULT 0,
                    response_summary VARCHAR(2000) NULL,
                    error_message VARCHAR(2000) NULL,
                    tested_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_adjustments (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    admin_id BIGINT NULL,
                    amount BIGINT NOT NULL,
                    reason VARCHAR(500) NULL,
                    balance_after BIGINT NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS gateway_reservations (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    reservation_id VARCHAR(96) NOT NULL UNIQUE,
                    token_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    model VARCHAR(160) NOT NULL,
                    reserved_tokens INT NOT NULL,
                    reserved_amount BIGINT NOT NULL,
                    actual_tokens INT NULL,
                    actual_amount BIGINT NULL,
                    source_amount BIGINT NOT NULL DEFAULT 0,
                    source_currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                    source_scale BIGINT NOT NULL DEFAULT 10000,
                    settlement_currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
                    exchange_rate DECIMAL(18,8) NOT NULL DEFAULT 1,
                    status VARCHAR(40) NOT NULL,
                    failure_reason VARCHAR(500) NULL,
                    expires_at DATETIME NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    settled_at DATETIME NULL
                )
                """);
    }

    private void ensureAccountPresentationTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_verification_codes (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, recipient VARCHAR(255) NOT NULL,
                  channel VARCHAR(20) NOT NULL, purpose VARCHAR(40) NOT NULL, code_hash VARCHAR(128) NOT NULL,
                  status VARCHAR(24) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
                  expires_at DATETIME NOT NULL, consumed_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS upstream_display_mappings (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, channel_id BIGINT NOT NULL, public_code VARCHAR(80) NOT NULL,
                  public_name VARCHAR(120) NOT NULL, badge_text VARCHAR(40) NULL, badge_color VARCHAR(16) NULL,
                  sort_order INT NOT NULL DEFAULT 100, enabled BOOLEAN NOT NULL DEFAULT TRUE,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_context_pricing_policies (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, public_model_name VARCHAR(160) NOT NULL,
                  enabled BOOLEAN NOT NULL DEFAULT FALSE, threshold_tokens INT NOT NULL,
                  multiplier DECIMAL(10,6) NOT NULL DEFAULT 2.000000, verification_note VARCHAR(500) NULL,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS payment_refund_jobs (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT, payment_intent_id BIGINT NOT NULL, service_order_id BIGINT NOT NULL,
                  reason VARCHAR(500) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
                  next_attempt_at DATETIME NOT NULL, last_error VARCHAR(1000) NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void ensureColumns() {
        ensureColumn("oauth_login_states", "target_user_id", "ALTER TABLE oauth_login_states ADD COLUMN target_user_id BIGINT NULL");
        ensureColumn("oauth_login_states", "flow_type", "ALTER TABLE oauth_login_states ADD COLUMN flow_type VARCHAR(16) NOT NULL DEFAULT 'LOGIN'");
        ensureColumn("creative_platform_connections", "default_slot",
                "ALTER TABLE creative_platform_connections ADD COLUMN default_slot VARCHAR(20) NULL");
        jdbcTemplate.update("UPDATE creative_platform_connections SET default_slot=capability WHERE is_default=TRUE AND default_slot IS NULL");
        ensureColumn("users", "phone", "ALTER TABLE users ADD COLUMN phone VARCHAR(40) NULL");
        ensureColumn("users", "auth_provider", "ALTER TABLE users ADD COLUMN auth_provider VARCHAR(40) NOT NULL DEFAULT 'local'");
        ensureColumn("users", "status", "ALTER TABLE users ADD COLUMN status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("users", "group_id", "ALTER TABLE users ADD COLUMN group_id BIGINT NULL");
        ensureColumn("users", "display_name", "ALTER TABLE users ADD COLUMN display_name VARCHAR(120) NULL");
        ensureColumn("users", "avatar_path", "ALTER TABLE users ADD COLUMN avatar_path VARCHAR(500) NULL");
        ensureColumn("users", "email_verified_at", "ALTER TABLE users ADD COLUMN email_verified_at DATETIME NULL");
        ensureColumn("users", "phone_verified_at", "ALTER TABLE users ADD COLUMN phone_verified_at DATETIME NULL");
        ensureColumn("users", "locale", "ALTER TABLE users ADD COLUMN locale VARCHAR(20) NOT NULL DEFAULT 'zh-CN'");
        ensureColumn("users", "timezone", "ALTER TABLE users ADD COLUMN timezone VARCHAR(80) NOT NULL DEFAULT 'Asia/Shanghai'");
        ensureColumn("users", "last_login_at", "ALTER TABLE users ADD COLUMN last_login_at DATETIME NULL");
        ensureColumn("oauth_tokens", "access_expires_at", "ALTER TABLE oauth_tokens ADD COLUMN access_expires_at DATETIME NULL");
        ensureColumn("oauth_tokens", "device_name", "ALTER TABLE oauth_tokens ADD COLUMN device_name VARCHAR(160) NULL");
        ensureColumn("oauth_tokens", "ip_digest", "ALTER TABLE oauth_tokens ADD COLUMN ip_digest VARCHAR(128) NULL");
        ensureColumn("oauth_tokens", "last_active_at", "ALTER TABLE oauth_tokens ADD COLUMN last_active_at DATETIME NULL");
        jdbcTemplate.update("""
                UPDATE oauth_tokens
                SET access_expires_at = COALESCE(created_at, CURRENT_TIMESTAMP)
                WHERE access_expires_at IS NULL
                """);
        ensureColumn("tokens", "user_id", "ALTER TABLE tokens ADD COLUMN user_id BIGINT NULL");
        ensureColumn("vmcard_saved_cards", "email", "ALTER TABLE vmcard_saved_cards ADD COLUMN email VARCHAR(254) NULL");
        ensureColumn("vmcard_saved_cards", "card_created_at", "ALTER TABLE vmcard_saved_cards ADD COLUMN card_created_at DATETIME NULL");
        ensureColumn("vmcard_saved_cards", "disabled_or_frozen_at", "ALTER TABLE vmcard_saved_cards ADD COLUMN disabled_or_frozen_at DATETIME NULL");
        ensureColumn("tokens", "key_prefix", "ALTER TABLE tokens ADD COLUMN key_prefix VARCHAR(32) NULL");
        ensureColumn("tokens", "allowed_models", "ALTER TABLE tokens ADD COLUMN allowed_models VARCHAR(1000) NULL");
        ensureColumn("tokens", "ip_whitelist", "ALTER TABLE tokens ADD COLUMN ip_whitelist VARCHAR(1000) NULL");
        ensureColumn("tokens", "description", "ALTER TABLE tokens ADD COLUMN description VARCHAR(500) NULL");
        ensureColumn("channels", "group_name", "ALTER TABLE channels ADD COLUMN group_name VARCHAR(80) NOT NULL DEFAULT 'default'");
        ensureColumn("channels", "weight", "ALTER TABLE channels ADD COLUMN weight INT NOT NULL DEFAULT 100");
        ensureColumn("channels", "rpm_limit", "ALTER TABLE channels ADD COLUMN rpm_limit INT NOT NULL DEFAULT 0");
        ensureColumn("channels", "tpm_limit", "ALTER TABLE channels ADD COLUMN tpm_limit INT NOT NULL DEFAULT 0");
        ensureColumn("channels", "health_status", "ALTER TABLE channels ADD COLUMN health_status VARCHAR(40) NOT NULL DEFAULT 'HEALTHY'");
        ensureColumn("channels", "cooldown_until", "ALTER TABLE channels ADD COLUMN cooldown_until DATETIME NULL");
        ensureColumn("channels", "auto_disable", "ALTER TABLE channels ADD COLUMN auto_disable BOOLEAN NOT NULL DEFAULT TRUE");
        ensureColumn("channels", "failure_threshold", "ALTER TABLE channels ADD COLUMN failure_threshold INT NOT NULL DEFAULT 3");
        ensureColumn("channels", "cooldown_seconds", "ALTER TABLE channels ADD COLUMN cooldown_seconds INT NOT NULL DEFAULT 60");
        ensureColumn("channels", "consecutive_failures", "ALTER TABLE channels ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0");
        ensureColumn("channels", "total_successes", "ALTER TABLE channels ADD COLUMN total_successes BIGINT NOT NULL DEFAULT 0");
        ensureColumn("channels", "total_failures", "ALTER TABLE channels ADD COLUMN total_failures BIGINT NOT NULL DEFAULT 0");
        ensureColumn("channels", "average_latency_ms", "ALTER TABLE channels ADD COLUMN average_latency_ms BIGINT NOT NULL DEFAULT 0");
        ensureColumn("channels", "last_error", "ALTER TABLE channels ADD COLUMN last_error VARCHAR(1000) NULL");
        ensureColumn("channels", "last_tested_at", "ALTER TABLE channels ADD COLUMN last_tested_at DATETIME NULL");
        ensureColumn("channels", "last_success_at", "ALTER TABLE channels ADD COLUMN last_success_at DATETIME NULL");
        ensureColumn("model_mappings", "price_ratio", "ALTER TABLE model_mappings ADD COLUMN price_ratio DECIMAL(10,4) NOT NULL DEFAULT 1");
        ensureColumn("model_mappings", "cost_per_million", "ALTER TABLE model_mappings ADD COLUMN cost_per_million DECIMAL(12,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "input_price_per_million", "ALTER TABLE model_mappings ADD COLUMN input_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 1");
        ensureColumn("model_mappings", "output_price_per_million", "ALTER TABLE model_mappings ADD COLUMN output_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 1");
        ensureColumn("model_mappings", "cached_price_per_million", "ALTER TABLE model_mappings ADD COLUMN cached_price_per_million DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "input_cost_per_million", "ALTER TABLE model_mappings ADD COLUMN input_cost_per_million DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "output_cost_per_million", "ALTER TABLE model_mappings ADD COLUMN output_cost_per_million DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "cached_cost_per_million", "ALTER TABLE model_mappings ADD COLUMN cached_cost_per_million DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "billing_enabled", "ALTER TABLE model_mappings ADD COLUMN billing_enabled BOOLEAN NOT NULL DEFAULT TRUE");
        ensureColumn("model_mappings", "traffic_percent", "ALTER TABLE model_mappings ADD COLUMN traffic_percent INT NOT NULL DEFAULT 100");
        ensureColumn("model_mappings", "capability_tags", "ALTER TABLE model_mappings ADD COLUMN capability_tags VARCHAR(1000) NULL");
        ensureColumn("model_mappings", "created_at", "ALTER TABLE model_mappings ADD COLUMN created_at DATETIME DEFAULT CURRENT_TIMESTAMP");
        ensureColumn("model_mappings", "billing_mode", "ALTER TABLE model_mappings ADD COLUMN billing_mode VARCHAR(24) NOT NULL DEFAULT 'PAID'");
        ensureColumn("model_mappings", "pricing_status", "ALTER TABLE model_mappings ADD COLUMN pricing_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'");
        ensureColumn("model_mappings", "pricing_message", "ALTER TABLE model_mappings ADD COLUMN pricing_message VARCHAR(500) NULL");
        ensureColumn("model_mappings", "pricing_source_url", "ALTER TABLE model_mappings ADD COLUMN pricing_source_url VARCHAR(1000) NULL");
        ensureColumn("model_mappings", "pricing_verified_at", "ALTER TABLE model_mappings ADD COLUMN pricing_verified_at DATETIME NULL");
        ensureColumn("model_mappings", "official_unit_price", "ALTER TABLE model_mappings ADD COLUMN official_unit_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "cost_unit_price", "ALTER TABLE model_mappings ADD COLUMN cost_unit_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "sale_unit_price", "ALTER TABLE model_mappings ADD COLUMN sale_unit_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_price_tiers", "official_price_unit", "ALTER TABLE model_price_tiers ADD COLUMN official_price_unit VARCHAR(8) NOT NULL DEFAULT 'M'");
        ensureColumn("model_price_tiers", "official_price_suffix", "ALTER TABLE model_price_tiers ADD COLUMN official_price_suffix VARCHAR(120) NOT NULL DEFAULT 'USD / 1M Token'");
        ensureColumn("model_price_tiers", "cost_price_unit", "ALTER TABLE model_price_tiers ADD COLUMN cost_price_unit VARCHAR(8) NOT NULL DEFAULT 'M'");
        ensureColumn("model_price_tiers", "cost_price_suffix", "ALTER TABLE model_price_tiers ADD COLUMN cost_price_suffix VARCHAR(120) NOT NULL DEFAULT 'USD / 1M Token'");
        ensureColumn("model_price_tiers", "sale_price_unit", "ALTER TABLE model_price_tiers ADD COLUMN sale_price_unit VARCHAR(8) NOT NULL DEFAULT 'M'");
        ensureColumn("model_price_tiers", "sale_price_suffix", "ALTER TABLE model_price_tiers ADD COLUMN sale_price_suffix VARCHAR(120) NOT NULL DEFAULT 'USD / 1M Token'");
        ensureColumn("logs", "channel_id", "ALTER TABLE logs ADD COLUMN channel_id BIGINT NULL");
        ensureColumn("logs", "token_id", "ALTER TABLE logs ADD COLUMN token_id BIGINT NULL");
        ensureColumn("logs", "latency_ms", "ALTER TABLE logs ADD COLUMN latency_ms BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "trace_id", "ALTER TABLE logs ADD COLUMN trace_id VARCHAR(80) NULL");
        ensureColumn("logs", "error_message", "ALTER TABLE logs ADD COLUMN error_message VARCHAR(1000) NULL");
        ensureColumn("logs", "sale_amount", "ALTER TABLE logs ADD COLUMN sale_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cost_amount", "ALTER TABLE logs ADD COLUMN cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cached_tokens", "ALTER TABLE logs ADD COLUMN cached_tokens INT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cache_read_tokens", "ALTER TABLE logs ADD COLUMN cache_read_tokens INT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cache_write_tokens", "ALTER TABLE logs ADD COLUMN cache_write_tokens INT NOT NULL DEFAULT 0");
        ensureColumn("logs", "input_amount", "ALTER TABLE logs ADD COLUMN input_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "output_amount", "ALTER TABLE logs ADD COLUMN output_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cached_amount", "ALTER TABLE logs ADD COLUMN cached_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cache_read_amount", "ALTER TABLE logs ADD COLUMN cache_read_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cache_write_amount", "ALTER TABLE logs ADD COLUMN cache_write_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "total_amount", "ALTER TABLE logs ADD COLUMN total_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "input_cost_amount", "ALTER TABLE logs ADD COLUMN input_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "output_cost_amount", "ALTER TABLE logs ADD COLUMN output_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cached_cost_amount", "ALTER TABLE logs ADD COLUMN cached_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cache_read_cost_amount", "ALTER TABLE logs ADD COLUMN cache_read_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "cache_write_cost_amount", "ALTER TABLE logs ADD COLUMN cache_write_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "gross_profit", "ALTER TABLE logs ADD COLUMN gross_profit BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "model_currency", "ALTER TABLE logs ADD COLUMN model_currency VARCHAR(3) NOT NULL DEFAULT 'USD'");
        ensureColumn("logs", "model_amount_scale", "ALTER TABLE logs ADD COLUMN model_amount_scale BIGINT NOT NULL DEFAULT 10000");
        ensureColumn("logs", "settlement_amount", "ALTER TABLE logs ADD COLUMN settlement_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("logs", "settlement_currency", "ALTER TABLE logs ADD COLUMN settlement_currency VARCHAR(3) NOT NULL DEFAULT 'CNY'");
        ensureColumn("logs", "exchange_rate", "ALTER TABLE logs ADD COLUMN exchange_rate DECIMAL(18,8) NOT NULL DEFAULT 1");
        ensureColumn("logs", "billing_unit", "ALTER TABLE logs ADD COLUMN billing_unit VARCHAR(40) NOT NULL DEFAULT 'TOKEN'");
        ensureColumn("logs", "billable_quantity", "ALTER TABLE logs ADD COLUMN billable_quantity DECIMAL(24,6) NOT NULL DEFAULT 0");
        ensureColumn("logs", "unit_sale_price", "ALTER TABLE logs ADD COLUMN unit_sale_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("logs", "unit_cost_price", "ALTER TABLE logs ADD COLUMN unit_cost_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("logs", "pricing_tier", "ALTER TABLE logs ADD COLUMN pricing_tier VARCHAR(80) NULL");
        ensureColumn("logs", "context_threshold_tokens", "ALTER TABLE logs ADD COLUMN context_threshold_tokens INT NULL");
        ensureColumn("logs", "input_unit_sale_price", "ALTER TABLE logs ADD COLUMN input_unit_sale_price DECIMAL(18,6) NULL");
        ensureColumn("logs", "output_unit_sale_price", "ALTER TABLE logs ADD COLUMN output_unit_sale_price DECIMAL(18,6) NULL");
        ensureColumn("gateway_reservations", "source_amount", "ALTER TABLE gateway_reservations ADD COLUMN source_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("gateway_reservations", "source_currency", "ALTER TABLE gateway_reservations ADD COLUMN source_currency VARCHAR(3) NOT NULL DEFAULT 'USD'");
        ensureColumn("gateway_reservations", "source_scale", "ALTER TABLE gateway_reservations ADD COLUMN source_scale BIGINT NOT NULL DEFAULT 10000");
        ensureColumn("gateway_reservations", "settlement_currency", "ALTER TABLE gateway_reservations ADD COLUMN settlement_currency VARCHAR(3) NOT NULL DEFAULT 'CNY'");
        ensureColumn("gateway_reservations", "exchange_rate", "ALTER TABLE gateway_reservations ADD COLUMN exchange_rate DECIMAL(18,8) NOT NULL DEFAULT 1");
        ensureColumn("channel_test_logs", "estimated_cost_amount", "ALTER TABLE channel_test_logs ADD COLUMN estimated_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("creative_tasks", "provider_config_id", "ALTER TABLE creative_tasks ADD COLUMN provider_config_id BIGINT NULL");
        ensureColumn("creative_tasks", "project_id", "ALTER TABLE creative_tasks ADD COLUMN project_id BIGINT NULL");
        ensureColumn("creative_tasks", "shot_id", "ALTER TABLE creative_tasks ADD COLUMN shot_id BIGINT NULL");
        ensureColumn("creative_tasks", "stage", "ALTER TABLE creative_tasks ADD COLUMN stage VARCHAR(40) NULL");
        ensureColumn("creative_tasks", "billing_unit", "ALTER TABLE creative_tasks ADD COLUMN billing_unit VARCHAR(40) NULL");
        ensureColumn("creative_tasks", "billable_quantity", "ALTER TABLE creative_tasks ADD COLUMN billable_quantity DECIMAL(24,6) NULL");
        ensureColumn("creative_tasks", "unit_sale_price", "ALTER TABLE creative_tasks ADD COLUMN unit_sale_price DECIMAL(18,6) NULL");
        ensureColumn("creative_tasks", "unit_cost_price", "ALTER TABLE creative_tasks ADD COLUMN unit_cost_price DECIMAL(18,6) NULL");
        ensureColumn("service_orders", "order_no", "ALTER TABLE service_orders ADD COLUMN order_no VARCHAR(80) NULL");
        ensureColumn("service_orders", "service_id", "ALTER TABLE service_orders ADD COLUMN service_id BIGINT NULL");
        ensureColumn("service_orders", "quantity", "ALTER TABLE service_orders ADD COLUMN quantity INT NOT NULL DEFAULT 1");
        ensureColumn("service_orders", "fulfillment_mode", "ALTER TABLE service_orders ADD COLUMN fulfillment_mode VARCHAR(32) NULL");
        ensureColumn("service_orders", "unit_price_cents", "ALTER TABLE service_orders ADD COLUMN unit_price_cents BIGINT NULL");
        ensureColumn("service_orders", "effective_unit_price_cents", "ALTER TABLE service_orders ADD COLUMN effective_unit_price_cents BIGINT NULL");
        ensureColumn("service_orders", "merchandise_subtotal_cents", "ALTER TABLE service_orders ADD COLUMN merchandise_subtotal_cents BIGINT NULL");
        ensureColumn("service_orders", "wholesale_discount_cents", "ALTER TABLE service_orders ADD COLUMN wholesale_discount_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("service_orders", "coupon_id", "ALTER TABLE service_orders ADD COLUMN coupon_id BIGINT NULL");
        ensureColumn("service_orders", "coupon_code", "ALTER TABLE service_orders ADD COLUMN coupon_code VARCHAR(80) NULL");
        ensureColumn("service_orders", "coupon_discount_cents", "ALTER TABLE service_orders ADD COLUMN coupon_discount_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("service_orders", "coupon_reservation_active", "ALTER TABLE service_orders ADD COLUMN coupon_reservation_active BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("service_orders", "refund_resources_released", "ALTER TABLE service_orders ADD COLUMN refund_resources_released BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("service_orders", "supplier_quote_json", "ALTER TABLE service_orders ADD COLUMN supplier_quote_json TEXT NULL");
        ensureColumn("service_orders", "service_fee_cents", "ALTER TABLE service_orders ADD COLUMN service_fee_cents BIGINT NULL");
        ensureColumn("service_orders", "contact_email", "ALTER TABLE service_orders ADD COLUMN contact_email VARCHAR(255) NULL");
        ensureColumn("service_orders", "contact_note", "ALTER TABLE service_orders ADD COLUMN contact_note VARCHAR(1000) NULL");
        ensureColumn("service_orders", "invoice_number", "ALTER TABLE service_orders ADD COLUMN invoice_number VARCHAR(80) NULL");
        ensureColumn("service_orders", "receipt_number", "ALTER TABLE service_orders ADD COLUMN receipt_number VARCHAR(14) NULL");
        ensureColumn("service_orders", "billing_name", "ALTER TABLE service_orders ADD COLUMN billing_name VARCHAR(160) NULL");
        ensureColumn("service_orders", "billing_address_line_1", "ALTER TABLE service_orders ADD COLUMN billing_address_line_1 VARCHAR(255) NULL");
        ensureColumn("service_orders", "billing_district", "ALTER TABLE service_orders ADD COLUMN billing_district VARCHAR(120) NULL");
        ensureColumn("service_orders", "billing_city", "ALTER TABLE service_orders ADD COLUMN billing_city VARCHAR(120) NULL");
        ensureColumn("service_orders", "billing_province", "ALTER TABLE service_orders ADD COLUMN billing_province VARCHAR(120) NULL");
        ensureColumn("service_orders", "billing_postal_code", "ALTER TABLE service_orders ADD COLUMN billing_postal_code VARCHAR(20) NULL");
        ensureColumn("service_orders", "billing_country", "ALTER TABLE service_orders ADD COLUMN billing_country VARCHAR(120) NULL");
        ensureColumn("service_orders", "payment_method", "ALTER TABLE service_orders ADD COLUMN payment_method VARCHAR(20) NULL");
        ensureColumn("service_orders", "custom_input_json", "ALTER TABLE service_orders ADD COLUMN custom_input_json TEXT NULL");
        ensureColumn("service_orders", "purchase_prompt", "ALTER TABLE service_orders ADD COLUMN purchase_prompt VARCHAR(1000) NULL");
        ensureColumn("service_orders", "reservation_expires_at", "ALTER TABLE service_orders ADD COLUMN reservation_expires_at DATETIME NULL");
        ensureColumn("service_orders", "fulfillment_status", "ALTER TABLE service_orders ADD COLUMN fulfillment_status VARCHAR(32) NULL");
        ensureColumn("service_orders", "delivery_content_encrypted", "ALTER TABLE service_orders ADD COLUMN delivery_content_encrypted TEXT NULL");
        ensureColumn("service_orders", "updated_at", "ALTER TABLE service_orders ADD COLUMN updated_at DATETIME NULL");
        ensureColumn("service_orders", "downloaded_at", "ALTER TABLE service_orders ADD COLUMN downloaded_at DATETIME NULL");
        ensureColumn("service_orders", "payment_reference", "ALTER TABLE service_orders ADD COLUMN payment_reference VARCHAR(255) NULL");
        ensureColumn("service_orders", "payment_provider", "ALTER TABLE service_orders ADD COLUMN payment_provider VARCHAR(40) NULL");
        ensureColumn("service_orders", "provider_trade_no", "ALTER TABLE service_orders ADD COLUMN provider_trade_no VARCHAR(120) NULL");
        ensureColumn("service_orders", "payment_type", "ALTER TABLE service_orders ADD COLUMN payment_type VARCHAR(40) NULL");
        ensureColumn("service_orders", "payment_url", "ALTER TABLE service_orders ADD COLUMN payment_url VARCHAR(2000) NULL");
        ensureColumn("service_orders", "fulfillment_reference", "ALTER TABLE service_orders ADD COLUMN fulfillment_reference VARCHAR(255) NULL");
        ensureColumn("service_orders", "paid_at", "ALTER TABLE service_orders ADD COLUMN paid_at DATETIME NULL");
        ensureColumn("service_orders", "fulfilled_at", "ALTER TABLE service_orders ADD COLUMN fulfilled_at DATETIME NULL");
        ensureColumn("service_orders", "currency", "ALTER TABLE service_orders ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD'");
        ensureColumn("wallet_recharge_orders", "invoice_requested", "ALTER TABLE wallet_recharge_orders ADD COLUMN invoice_requested BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("service_orders", "payment_amount_cents", "ALTER TABLE service_orders ADD COLUMN payment_amount_cents BIGINT NULL");
        ensureColumn("service_orders", "payment_currency", "ALTER TABLE service_orders ADD COLUMN payment_currency VARCHAR(3) NULL");
        ensureColumn("service_orders", "exchange_rate", "ALTER TABLE service_orders ADD COLUMN exchange_rate DECIMAL(18,8) NULL");
        ensureColumn("other_services", "action_label", "ALTER TABLE other_services ADD COLUMN action_label VARCHAR(40) NULL");
        ensureColumn("other_services", "price_cents", "ALTER TABLE other_services ADD COLUMN price_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("other_services", "service_fee_cents", "ALTER TABLE other_services ADD COLUMN service_fee_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("other_services", "currency", "ALTER TABLE other_services ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'CNY'");
        ensureColumn("other_services", "purchase_enabled", "ALTER TABLE other_services ADD COLUMN purchase_enabled BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("other_services", "product_type", "ALTER TABLE other_services ADD COLUMN product_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD'");
        ensureColumn("other_services", "fulfillment_mode", "ALTER TABLE other_services ADD COLUMN fulfillment_mode VARCHAR(32) NOT NULL DEFAULT 'MANUAL_PROCESSING'");
        ensureColumn("other_services", "purchase_prompt", "ALTER TABLE other_services ADD COLUMN purchase_prompt VARCHAR(1000) NULL");
        ensureColumn("other_services", "max_purchase_quantity", "ALTER TABLE other_services ADD COLUMN max_purchase_quantity INT NOT NULL DEFAULT 1");
        ensureColumn("other_services", "manual_stock", "ALTER TABLE other_services ADD COLUMN manual_stock INT NULL");
        ensureColumn("other_services", "manual_reserved", "ALTER TABLE other_services ADD COLUMN manual_reserved INT NOT NULL DEFAULT 0");
        ensureColumn("other_services", "wholesale_tiers_json", "ALTER TABLE other_services ADD COLUMN wholesale_tiers_json TEXT NULL");
        ensureColumn("other_services", "input_schema_json", "ALTER TABLE other_services ADD COLUMN input_schema_json TEXT NULL");
        ensureColumn("other_services", "redemption_url", "ALTER TABLE other_services ADD COLUMN redemption_url VARCHAR(2000) NULL");
        ensureColumn("redeem_codes", "code_prefix", "ALTER TABLE redeem_codes ADD COLUMN code_prefix VARCHAR(24) NULL");
        ensureColumn("wallet_transactions", "reference_type", "ALTER TABLE wallet_transactions ADD COLUMN reference_type VARCHAR(40) NULL");
        ensureColumn("wallet_transactions", "reference_id", "ALTER TABLE wallet_transactions ADD COLUMN reference_id BIGINT NULL");
    }

    private void normalizeAndValidateContacts() {
        jdbcTemplate.update("UPDATE users SET email=LOWER(TRIM(email)) WHERE email IS NOT NULL");
        jdbcTemplate.update("UPDATE users SET phone=CONCAT('+86', TRIM(phone)) WHERE phone IS NOT NULL AND TRIM(phone) REGEXP '^1[3-9][0-9]{9}$'");
        for (String field : List.of("email", "phone")) {
            List<String> duplicates = jdbcTemplate.queryForList(
                    "SELECT " + field + " FROM users WHERE " + field + " IS NOT NULL AND TRIM(" + field + ")<>'' GROUP BY " + field + " HAVING COUNT(*)>1",
                    String.class);
            if (!duplicates.isEmpty()) {
                List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM users WHERE " + field + "=? ORDER BY id", Long.class, duplicates.get(0));
                throw new IllegalStateException("Duplicate user " + field + " prevents safe migration; conflicting user IDs: " + ids);
            }
        }
    }

    private void ensureIndexes() {
        ensureIndex("users", "uk_users_email", "CREATE UNIQUE INDEX uk_users_email ON users(email)");
        ensureIndex("users", "uk_users_phone", "CREATE UNIQUE INDEX uk_users_phone ON users(phone)");
        ensureIndex("user_verification_codes", "idx_verification_recipient_purpose", "CREATE INDEX idx_verification_recipient_purpose ON user_verification_codes(recipient,purpose,status,created_at)");
        ensureIndex("user_verification_codes", "idx_verification_expiry", "CREATE INDEX idx_verification_expiry ON user_verification_codes(expires_at,status)");
        ensureIndex("oauth_login_states", "idx_oauth_login_state_target", "CREATE INDEX idx_oauth_login_state_target ON oauth_login_states(target_user_id,flow_type,expires_at)");
        ensureIndex("upstream_display_mappings", "uk_upstream_display_channel", "CREATE UNIQUE INDEX uk_upstream_display_channel ON upstream_display_mappings(channel_id)");
        ensureIndex("upstream_display_mappings", "idx_upstream_display_public", "CREATE INDEX idx_upstream_display_public ON upstream_display_mappings(public_code,enabled,sort_order)");
        ensureIndex("model_context_pricing_policies", "uk_context_pricing_model", "CREATE UNIQUE INDEX uk_context_pricing_model ON model_context_pricing_policies(public_model_name)");
        ensureIndex("payment_refund_jobs", "uk_refund_job_intent", "CREATE UNIQUE INDEX uk_refund_job_intent ON payment_refund_jobs(payment_intent_id)");
        ensureIndex("payment_refund_jobs", "idx_refund_job_due", "CREATE INDEX idx_refund_job_due ON payment_refund_jobs(status,next_attempt_at)");
        ensureIndex("creative_platform_connections", "idx_creative_platform_capability",
                "CREATE INDEX idx_creative_platform_capability ON creative_platform_connections(capability, enabled, is_default)");
        ensureIndex("creative_platform_connections", "uq_creative_platform_default",
                "CREATE UNIQUE INDEX uq_creative_platform_default ON creative_platform_connections(default_slot)");
        ensureIndex("creative_provider_configs", "idx_creative_provider_config_user",
                "CREATE INDEX idx_creative_provider_config_user ON creative_provider_configs(user_id, enabled)");
        ensureIndex("creative_tasks", "idx_creative_task_user_created",
                "CREATE INDEX idx_creative_task_user_created ON creative_tasks(user_id, created_at)");
        ensureIndex("creative_tasks", "idx_creative_task_provider_config",
                "CREATE INDEX idx_creative_task_provider_config ON creative_tasks(provider_config_id, status)");
        ensureIndex("creative_tasks", "idx_creative_task_provider_status",
                "CREATE INDEX idx_creative_task_provider_status ON creative_tasks(provider_key, status)");
        ensureIndex("creative_projects", "idx_creative_project_user_updated",
                "CREATE INDEX idx_creative_project_user_updated ON creative_projects(user_id, updated_at)");
        ensureIndex("creative_scripts", "uq_creative_script_project_version",
                "CREATE UNIQUE INDEX uq_creative_script_project_version ON creative_scripts(project_id, version)");
        ensureIndex("creative_assets", "idx_creative_asset_project",
                "CREATE INDEX idx_creative_asset_project ON creative_assets(project_id, status)");
        ensureIndex("creative_shots", "uq_creative_shot_order",
                "CREATE UNIQUE INDEX uq_creative_shot_order ON creative_shots(project_id, script_version, shot_order)");
        ensureIndex("creative_jobs", "idx_creative_job_claim",
                "CREATE INDEX idx_creative_job_claim ON creative_jobs(status, next_run_at, lease_expires_at)");
        ensureIndex("service_orders", "uq_service_orders_order_no",
                "CREATE UNIQUE INDEX uq_service_orders_order_no ON service_orders(order_no)");
        ensureIndex("service_orders", "uq_service_orders_receipt_number",
                "CREATE UNIQUE INDEX uq_service_orders_receipt_number ON service_orders(receipt_number)");
        ensureIndex("service_orders", "idx_service_orders_provider_trade_no",
                "CREATE INDEX idx_service_orders_provider_trade_no ON service_orders(provider_trade_no)");
        ensureIndex("service_orders", "idx_service_orders_reservation_expiry",
                "CREATE INDEX idx_service_orders_reservation_expiry ON service_orders(status, reservation_expires_at)");
        ensureIndex("service_inventory_items", "uq_service_inventory_fingerprint",
                "CREATE UNIQUE INDEX uq_service_inventory_fingerprint ON service_inventory_items(service_id, content_fingerprint)");
        ensureIndex("service_inventory_items", "idx_service_inventory_claim",
                "CREATE INDEX idx_service_inventory_claim ON service_inventory_items(service_id, status, id)");
        ensureIndex("service_inventory_items", "uq_service_inventory_order_item",
                "CREATE UNIQUE INDEX uq_service_inventory_order_item ON service_inventory_items(reserved_order_id, id)");
        ensureIndex("service_coupons", "uq_service_coupon_code",
                "CREATE UNIQUE INDEX uq_service_coupon_code ON service_coupons(code)");
        ensureIndex("payment_intents", "uq_payment_intent_order_no",
                "CREATE UNIQUE INDEX uq_payment_intent_order_no ON payment_intents(order_no)");
        ensureIndex("payment_intents", "uq_payment_intent_business",
                "CREATE UNIQUE INDEX uq_payment_intent_business ON payment_intents(business_type, business_id)");
        ensureIndex("payment_intents", "uq_payment_intent_provider_trade",
                "CREATE UNIQUE INDEX uq_payment_intent_provider_trade ON payment_intents(provider_trade_no)");
        ensureIndex("payment_intents", "uq_payment_intent_refund_no",
                "CREATE UNIQUE INDEX uq_payment_intent_refund_no ON payment_intents(refund_no)");
        ensureIndex("wallet_recharge_orders", "uq_wallet_recharge_order_no",
                "CREATE UNIQUE INDEX uq_wallet_recharge_order_no ON wallet_recharge_orders(order_no)");
        ensureIndex("wallet_recharge_orders", "uq_wallet_recharge_receipt",
                "CREATE UNIQUE INDEX uq_wallet_recharge_receipt ON wallet_recharge_orders(receipt_number)");
        ensureIndex("wallet_recharge_orders", "idx_wallet_recharge_user_created",
                "CREATE INDEX idx_wallet_recharge_user_created ON wallet_recharge_orders(user_id, created_at)");
        ensureIndex("wallet_transactions", "uq_wallet_transaction_reference",
                "CREATE UNIQUE INDEX uq_wallet_transaction_reference ON wallet_transactions(reference_type, reference_id)");
        ensureIndex("logs", "idx_logs_user_created",
                "CREATE INDEX idx_logs_user_created ON logs(user_id, created_at)");
        ensureIndex("logs", "idx_logs_token_created",
                "CREATE INDEX idx_logs_token_created ON logs(token_id, created_at)");
        ensureIndex("logs", "idx_logs_model_created",
                "CREATE INDEX idx_logs_model_created ON logs(model, created_at)");
        ensureIndex("model_mappings", "idx_mapping_public_route",
                "CREATE INDEX idx_mapping_public_route ON model_mappings(public_model_name, enabled, priority)");
        ensureIndex("model_price_tiers", "idx_model_price_tier_mapping_context",
                "CREATE INDEX idx_model_price_tier_mapping_context ON model_price_tiers(model_mapping_id, sort_order, max_context_tokens)");
        ensureIndex("channels", "idx_channel_route_health",
                "CREATE INDEX idx_channel_route_health ON channels(enabled, health_status, cooldown_until)");
        ensureIndex("gateway_reservations", "idx_reservation_expiry",
                "CREATE INDEX idx_reservation_expiry ON gateway_reservations(status, expires_at)");
        ensureIndex("wallet_transactions", "idx_wallet_user_created",
                "CREATE INDEX idx_wallet_user_created ON wallet_transactions(user_id, created_at)");
        ensureIndex("vmcard_webhook_events", "uq_vmcard_webhook_event",
                "CREATE UNIQUE INDEX uq_vmcard_webhook_event ON vmcard_webhook_events(event_type, external_id)");
        ensureIndex("vmcard_webhook_events", "idx_vmcard_webhook_received",
                "CREATE INDEX idx_vmcard_webhook_received ON vmcard_webhook_events(received_at)");
        ensureIndex("vmcard_saved_cards", "uq_vmcard_saved_card",
                "CREATE UNIQUE INDEX uq_vmcard_saved_card ON vmcard_saved_cards(environment, card_id)");
        ensureIndex("vmcard_saved_cards", "idx_vmcard_saved_card_created",
                "CREATE INDEX idx_vmcard_saved_card_created ON vmcard_saved_cards(created_at)");
        ensureIndex("vmcard_product_codes", "uq_vmcard_product_code",
                "CREATE UNIQUE INDEX uq_vmcard_product_code ON vmcard_product_codes(environment, product_code)");
        ensureIndex("vmcard_product_codes", "idx_vmcard_product_code_selection",
                "CREATE INDEX idx_vmcard_product_code_selection ON vmcard_product_codes(environment, available, remaining_open_card_num)");
    }

    private void seedDefaults() {
        // Earlier development builds generated route-{channelId} aliases. Those
        // identifiers reveal internal topology and are not administrator-owned
        // mappings, so remove them and let the public layer use one fixed fallback.
        jdbcTemplate.update("""
                DELETE FROM upstream_display_mappings
                WHERE public_name='平台智能路由'
                  AND public_code=CONCAT('route-',channel_id)
                  AND COALESCE(badge_text,'智能路由')='智能路由'
                """);
        insertIfMissing("user_groups", "name", "default",
                "INSERT INTO user_groups(name, display_name, price_ratio, monthly_quota, description) VALUES ('default', 'Default users', 1, 0, 'Standard billing group')");
        insertIfMissing("user_groups", "name", "premium",
                "INSERT INTO user_groups(name, display_name, price_ratio, monthly_quota, description) VALUES ('premium', 'Premium users', 0.8, 0, 'Discounted commercial group')");
        insertIfMissing("system_settings", "setting_key", "site.name",
                "INSERT INTO system_settings(setting_key, setting_value, description) VALUES ('site.name', 'API Transit Station', 'Site name')");
        insertIfMissing("system_settings", "setting_key", "register.mode",
                "INSERT INTO system_settings(setting_key, setting_value, description) VALUES ('register.mode', 'invite_or_open', 'Registration policy')");
        insertIfMissing("system_settings", "setting_key", "billing.usd_cny_rate",
                "INSERT INTO system_settings(setting_key, setting_value, description) VALUES ('billing.usd_cny_rate', '6.76693506', '模型美元费用结算到人民币钱包时使用的固定汇率')");
        insertIfMissing("security_policies", "name", "RPM limit",
                "INSERT INTO security_policies(name, scope, action, threshold_value, enabled) VALUES ('RPM limit', 'default group', 'RATE_LIMIT', '500/min', TRUE)");
        insertIfMissing("security_policies", "name", "Sensitive prompt",
                "INSERT INTO security_policies(name, scope, action, threshold_value, enabled) VALUES ('Sensitive prompt', 'global', 'BLOCK', 'keyword hit', TRUE)");
        insertIfMissing("recharge_plans", "name", "starter",
                "INSERT INTO recharge_plans(name, amount, bonus_percent, sort_order) VALUES ('starter', 500000, 0, 10)");
        insertIfMissing("recharge_plans", "name", "standard",
                "INSERT INTO recharge_plans(name, amount, bonus_percent, sort_order) VALUES ('standard', 2000000, 3, 20)");
        insertIfMissing("recharge_plans", "name", "team",
                "INSERT INTO recharge_plans(name, amount, bonus_percent, sort_order) VALUES ('team', 5000000, 8, 30)");
        seedOtherServices();
        if (seedDemoCatalog) {
            seedMarketplaceModels();
        }
    }

    private void seedOtherServices() {
        Integer seeded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key = 'other_services.initialized'",
                Integer.class
        );
        if (seeded != null && seeded > 0) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM other_services", Integer.class);
        if (count == null || count == 0) {
            for (int index = 1; index <= 6; index++) {
                jdbcTemplate.update("""
                        INSERT INTO other_services(name, description, image_url, sort_order, enabled, created_at, updated_at)
                        VALUES (?, ?, NULL, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, "服务" + index, "服务内容待完善", index * 10);
            }
        }
        jdbcTemplate.update("""
                INSERT INTO system_settings(setting_key, setting_value, description, updated_at)
                VALUES ('other_services.initialized', 'true', '其他服务默认数据已初始化', CURRENT_TIMESTAMP)
                """);
    }

    private void seedMarketplaceModels() {
        Long openai = seedChannel("OpenAI Catalog", "openai", "https://api.openai.com", "gpt-5.5,gpt-5.4-mini,gpt-5.4-nano,gpt-5-codex-mini,o4-mini", 100);
        Long anthropic = seedChannel("Anthropic Catalog", "anthropic", "https://api.anthropic.com", "claude-sonnet-5,claude-opus-4-8,claude-haiku-4-5", 95);
        Long google = seedChannel("Google Gemini Catalog", "google", "https://generativelanguage.googleapis.com", "gemini-3.5-flash,gemini-3.1-pro-preview,gemini-2.5-flash", 90);
        Long xai = seedChannel("xAI Grok Catalog", "xai", "https://api.x.ai", "grok-4.3,grok-build", 80);
        Long kimi = seedChannel("Moonshot Kimi Catalog", "kimi", "https://api.moonshot.cn", "kimi-k2-thinking", 75);
        Long glm = seedChannel("Zhipu GLM Catalog", "glm", "https://open.bigmodel.cn/api/paas", "glm-5.1", 75);

        seedMapping("gpt-5.5", openai, 100, "8.0", "reasoning,coding,agent", "1M");
        seedMapping("gpt-5.4-mini", openai, 98, "2.0", "chat,low-latency,tools", "1M");
        seedMapping("gpt-5.4-nano", openai, 96, "0.6", "low-cost,classification,batch", "1M");
        seedMapping("gpt-5-codex-mini", openai, 94, "3.0", "coding,agent,review", "400K");
        seedMapping("o4-mini", openai, 92, "1.5", "reasoning,math,coding", "200K");

        seedMapping("claude-sonnet-5", anthropic, 100, "5.0", "chat,coding,long-context", "200K");
        seedMapping("claude-opus-4-8", anthropic, 98, "10.0", "reasoning,coding,agent", "200K");
        seedMapping("claude-haiku-4-5", anthropic, 94, "2.0", "chat,low-latency,high-concurrency", "200K");

        seedMapping("gemini-3.5-flash", google, 100, "2.0", "chat,multimodal,agent", "1M");
        seedMapping("gemini-3.1-pro-preview", google, 96, "5.0", "reasoning,coding,multimodal", "1M");
        seedMapping("gemini-2.5-flash", google, 92, "1.5", "chat,structured-output,search", "1M");

        seedMapping("grok-4.3", xai, 90, "3.0", "chat,reasoning,realtime", "1M");
        seedMapping("grok-build", xai, 88, "4.0", "coding,agent,developer", "1M");

        seedMapping("kimi-k2-thinking", kimi, 86, "1.5", "reasoning,long-context,chinese", "256K");
        seedMapping("glm-5.1", glm, 84, "1.2", "chat,chinese,tools", "128K");
    }

    private Long seedChannel(String name, String type, String baseUrl, String models, int weight) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM channels WHERE name = ?", Integer.class, name);
        if (count == null || count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO channels(name, type, base_url, api_key, models, enabled, group_name, weight, rpm_limit, tpm_limit, health_status, created_at)
                    VALUES (?, ?, ?, '', ?, TRUE, ?, ?, 0, 0, 'DEGRADED', CURRENT_TIMESTAMP)
                    """, name, type, baseUrl, models, type, weight);
        }
        Number id = jdbcTemplate.queryForObject("SELECT id FROM channels WHERE name = ? ORDER BY id ASC LIMIT 1", Number.class, name);
        return id == null ? null : id.longValue();
    }

    private void seedMapping(String publicModelName, Long channelId, int priority, String priceRatio, String capabilityTags, String contextTag) {
        if (channelId == null) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM model_mappings WHERE public_model_name = ?",
                Integer.class,
                publicModelName
        );
        if (count != null && count > 0) {
            return;
        }
        String tags = capabilityTags + ",context:" + contextTag;
        jdbcTemplate.update("""
                INSERT INTO model_mappings(
                    public_model_name, channel_model_name, channel_id, priority, enabled,
                    price_ratio, cost_per_million,
                    input_price_per_million, output_price_per_million, cached_price_per_million,
                    input_cost_per_million, output_cost_per_million, cached_cost_per_million,
                    billing_enabled, traffic_percent, capability_tags, created_at
                )
                VALUES (?, ?, ?, ?, TRUE, ?, 0, ?, ?, ?, 0, 0, 0, TRUE, 100, ?, CURRENT_TIMESTAMP)
                """,
                publicModelName,
                publicModelName,
                channelId,
                priority,
                priceRatio,
                priceRatio,
                priceRatio,
                "0",
                tags);
    }

    private void insertIfMissing(String tableName, String columnName, String value, String insertSql) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE " + columnName + " = ?",
                Integer.class,
                value
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute(insertSql);
        }
    }

    private void ensureColumn(String tableName, String columnName, String alterSql) {
        List<String> columns = jdbcTemplate.query(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = UPPER(?)",
                (rs, rowNum) -> rs.getString(1),
                tableName
        );
        if (columns.isEmpty()) {
            log.info("Skipped schema patch: table {} does not exist", tableName);
            return;
        }
        boolean exists = columns.stream().anyMatch(column -> column.equalsIgnoreCase(columnName));
        if (!exists) {
            jdbcTemplate.execute(alterSql);
            log.info("Patched schema: added {}.{}", tableName, columnName);
        }
    }

    private void ensureIndex(String tableName, String indexName, String createSql) {
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            boolean exists = false;
            for (String candidate : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
                try (var indexes = connection.getMetaData().getIndexInfo(
                        connection.getCatalog(), null, candidate, false, false)) {
                    while (indexes.next()) {
                        String current = indexes.getString("INDEX_NAME");
                        if (current != null && current.equalsIgnoreCase(indexName)) {
                            exists = true;
                            break;
                        }
                    }
                }
                if (exists) break;
            }
            if (!exists) {
                try (var statement = connection.createStatement()) {
                    statement.execute(createSql);
                }
                log.info("Patched schema: added index {} on {}", indexName, tableName);
            }
            return null;
        });
    }
}
