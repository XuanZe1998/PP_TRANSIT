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
            ensureColumns();
            ensureIndexes();
            seedDefaults();
            backfillChannelModelMappings();
            backfillModelPriceTiers();
        };
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
                    official_price_suffix VARCHAR(120) NOT NULL DEFAULT 'CNY / 1M Token',
                    cost_group_name VARCHAR(120) NOT NULL DEFAULT '采购成本',
                    cost_input_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_output_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_cache_read_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_cache_write_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    cost_price_unit VARCHAR(8) NOT NULL DEFAULT 'M',
                    cost_price_suffix VARCHAR(120) NOT NULL DEFAULT 'CNY / 1M Token',
                    sale_group_name VARCHAR(120) NOT NULL DEFAULT '本站售价',
                    sale_input_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_output_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_cache_read_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_cache_write_price DECIMAL(18,6) NOT NULL DEFAULT 0,
                    sale_price_unit VARCHAR(8) NOT NULL DEFAULT 'M',
                    sale_price_suffix VARCHAR(120) NOT NULL DEFAULT 'CNY / 1M Token',
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
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS plus_products (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    description VARCHAR(1000) NULL,
                    image_url VARCHAR(1000) NULL,
                    price_cents BIGINT NOT NULL DEFAULT 0,
                    service_fee_cents BIGINT NOT NULL DEFAULT 0,
                    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS plus_orders (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    order_no VARCHAR(80) NULL,
                    user_id BIGINT NULL,
                    product_id BIGINT NULL,
                    product_name VARCHAR(160) NULL,
                    quantity INT NOT NULL DEFAULT 1,
                    unit_price_cents BIGINT NULL,
                    service_fee_cents BIGINT NULL,
                    amount_cents BIGINT NOT NULL DEFAULT 0,
                    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                    payment_amount_cents BIGINT NULL,
                    payment_currency VARCHAR(3) NULL,
                    exchange_rate DECIMAL(18,8) NULL,
                    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
                    contact_email VARCHAR(255) NULL,
                    contact_note VARCHAR(1000) NULL,
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
                    service_type VARCHAR(24) NOT NULL DEFAULT 'DISPLAY',
                    linked_product_id BIGINT NULL,
                    action_label VARCHAR(40) NULL,
                    price_cents BIGINT NOT NULL DEFAULT 0,
                    service_fee_cents BIGINT NOT NULL DEFAULT 0,
                    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
                    purchase_enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL
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
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS service07_fulfillments (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    order_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
                    vmcard_card_id VARCHAR(128) NULL,
                    attempt_count INT NOT NULL DEFAULT 0,
                    error_message VARCHAR(500) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NULL,
                    completed_at DATETIME NULL
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
                    status VARCHAR(40) NOT NULL,
                    failure_reason VARCHAR(500) NULL,
                    expires_at DATETIME NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    settled_at DATETIME NULL
                )
                """);
    }

    private void ensureColumns() {
        ensureColumn("users", "phone", "ALTER TABLE users ADD COLUMN phone VARCHAR(40) NULL");
        ensureColumn("users", "auth_provider", "ALTER TABLE users ADD COLUMN auth_provider VARCHAR(40) NOT NULL DEFAULT 'local'");
        ensureColumn("users", "status", "ALTER TABLE users ADD COLUMN status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE'");
        ensureColumn("users", "group_id", "ALTER TABLE users ADD COLUMN group_id BIGINT NULL");
        ensureColumn("oauth_tokens", "access_expires_at", "ALTER TABLE oauth_tokens ADD COLUMN access_expires_at DATETIME NULL");
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
        ensureColumn("model_price_tiers", "official_price_unit", "ALTER TABLE model_price_tiers ADD COLUMN official_price_unit VARCHAR(8) NOT NULL DEFAULT 'M'");
        ensureColumn("model_price_tiers", "official_price_suffix", "ALTER TABLE model_price_tiers ADD COLUMN official_price_suffix VARCHAR(120) NOT NULL DEFAULT 'CNY / 1M Token'");
        ensureColumn("model_price_tiers", "cost_price_unit", "ALTER TABLE model_price_tiers ADD COLUMN cost_price_unit VARCHAR(8) NOT NULL DEFAULT 'M'");
        ensureColumn("model_price_tiers", "cost_price_suffix", "ALTER TABLE model_price_tiers ADD COLUMN cost_price_suffix VARCHAR(120) NOT NULL DEFAULT 'CNY / 1M Token'");
        ensureColumn("model_price_tiers", "sale_price_unit", "ALTER TABLE model_price_tiers ADD COLUMN sale_price_unit VARCHAR(8) NOT NULL DEFAULT 'M'");
        ensureColumn("model_price_tiers", "sale_price_suffix", "ALTER TABLE model_price_tiers ADD COLUMN sale_price_suffix VARCHAR(120) NOT NULL DEFAULT 'CNY / 1M Token'");
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
        ensureColumn("channel_test_logs", "estimated_cost_amount", "ALTER TABLE channel_test_logs ADD COLUMN estimated_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("creative_tasks", "provider_config_id", "ALTER TABLE creative_tasks ADD COLUMN provider_config_id BIGINT NULL");
        ensureColumn("plus_products", "image_url", "ALTER TABLE plus_products ADD COLUMN image_url VARCHAR(1000) NULL");
        ensureColumn("plus_products", "service_fee_cents", "ALTER TABLE plus_products ADD COLUMN service_fee_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("plus_products", "enabled", "ALTER TABLE plus_products ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE");
        ensureColumn("plus_products", "currency", "ALTER TABLE plus_products ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD'");
        ensureColumn("plus_orders", "order_no", "ALTER TABLE plus_orders ADD COLUMN order_no VARCHAR(80) NULL");
        ensureColumn("plus_orders", "unit_price_cents", "ALTER TABLE plus_orders ADD COLUMN unit_price_cents BIGINT NULL");
        ensureColumn("plus_orders", "service_fee_cents", "ALTER TABLE plus_orders ADD COLUMN service_fee_cents BIGINT NULL");
        ensureColumn("plus_orders", "contact_email", "ALTER TABLE plus_orders ADD COLUMN contact_email VARCHAR(255) NULL");
        ensureColumn("plus_orders", "contact_note", "ALTER TABLE plus_orders ADD COLUMN contact_note VARCHAR(1000) NULL");
        ensureColumn("plus_orders", "updated_at", "ALTER TABLE plus_orders ADD COLUMN updated_at DATETIME NULL");
        ensureColumn("plus_orders", "downloaded_at", "ALTER TABLE plus_orders ADD COLUMN downloaded_at DATETIME NULL");
        ensureColumn("plus_orders", "payment_reference", "ALTER TABLE plus_orders ADD COLUMN payment_reference VARCHAR(255) NULL");
        ensureColumn("plus_orders", "payment_provider", "ALTER TABLE plus_orders ADD COLUMN payment_provider VARCHAR(40) NULL");
        ensureColumn("plus_orders", "provider_trade_no", "ALTER TABLE plus_orders ADD COLUMN provider_trade_no VARCHAR(120) NULL");
        ensureColumn("plus_orders", "payment_type", "ALTER TABLE plus_orders ADD COLUMN payment_type VARCHAR(40) NULL");
        ensureColumn("plus_orders", "payment_url", "ALTER TABLE plus_orders ADD COLUMN payment_url VARCHAR(2000) NULL");
        ensureColumn("plus_orders", "fulfillment_reference", "ALTER TABLE plus_orders ADD COLUMN fulfillment_reference VARCHAR(255) NULL");
        ensureColumn("plus_orders", "paid_at", "ALTER TABLE plus_orders ADD COLUMN paid_at DATETIME NULL");
        ensureColumn("plus_orders", "fulfilled_at", "ALTER TABLE plus_orders ADD COLUMN fulfilled_at DATETIME NULL");
        ensureColumn("plus_orders", "currency", "ALTER TABLE plus_orders ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD'");
        ensureColumn("plus_orders", "payment_amount_cents", "ALTER TABLE plus_orders ADD COLUMN payment_amount_cents BIGINT NULL");
        ensureColumn("plus_orders", "payment_currency", "ALTER TABLE plus_orders ADD COLUMN payment_currency VARCHAR(3) NULL");
        ensureColumn("plus_orders", "exchange_rate", "ALTER TABLE plus_orders ADD COLUMN exchange_rate DECIMAL(18,8) NULL");
        ensureColumn("other_services", "service_type", "ALTER TABLE other_services ADD COLUMN service_type VARCHAR(24) NOT NULL DEFAULT 'DISPLAY'");
        ensureColumn("other_services", "linked_product_id", "ALTER TABLE other_services ADD COLUMN linked_product_id BIGINT NULL");
        ensureColumn("other_services", "action_label", "ALTER TABLE other_services ADD COLUMN action_label VARCHAR(40) NULL");
        ensureColumn("other_services", "price_cents", "ALTER TABLE other_services ADD COLUMN price_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("other_services", "service_fee_cents", "ALTER TABLE other_services ADD COLUMN service_fee_cents BIGINT NOT NULL DEFAULT 0");
        ensureColumn("other_services", "currency", "ALTER TABLE other_services ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'CNY'");
        ensureColumn("other_services", "purchase_enabled", "ALTER TABLE other_services ADD COLUMN purchase_enabled BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("redeem_codes", "code_prefix", "ALTER TABLE redeem_codes ADD COLUMN code_prefix VARCHAR(24) NULL");
    }

    private void ensureIndexes() {
        ensureIndex("creative_provider_configs", "idx_creative_provider_config_user",
                "CREATE INDEX idx_creative_provider_config_user ON creative_provider_configs(user_id, enabled)");
        ensureIndex("creative_tasks", "idx_creative_task_user_created",
                "CREATE INDEX idx_creative_task_user_created ON creative_tasks(user_id, created_at)");
        ensureIndex("creative_tasks", "idx_creative_task_provider_config",
                "CREATE INDEX idx_creative_task_provider_config ON creative_tasks(provider_config_id, status)");
        ensureIndex("creative_tasks", "idx_creative_task_provider_status",
                "CREATE INDEX idx_creative_task_provider_status ON creative_tasks(provider_key, status)");
        ensureIndex("plus_orders", "uq_plus_orders_order_no",
                "CREATE UNIQUE INDEX uq_plus_orders_order_no ON plus_orders(order_no)");
        ensureIndex("plus_orders", "idx_plus_orders_provider_trade_no",
                "CREATE INDEX idx_plus_orders_provider_trade_no ON plus_orders(provider_trade_no)");
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
        ensureIndex("other_services", "uq_other_services_plus_product",
                "CREATE UNIQUE INDEX uq_other_services_plus_product ON other_services(service_type, linked_product_id)");
        ensureIndex("service07_fulfillments", "uq_service07_fulfillment_order",
                "CREATE UNIQUE INDEX uq_service07_fulfillment_order ON service07_fulfillments(order_id)");
    }

    private void seedDefaults() {
        insertIfMissing("user_groups", "name", "default",
                "INSERT INTO user_groups(name, display_name, price_ratio, monthly_quota, description) VALUES ('default', 'Default users', 1, 0, 'Standard billing group')");
        insertIfMissing("user_groups", "name", "premium",
                "INSERT INTO user_groups(name, display_name, price_ratio, monthly_quota, description) VALUES ('premium', 'Premium users', 0.8, 0, 'Discounted commercial group')");
        insertIfMissing("system_settings", "setting_key", "site.name",
                "INSERT INTO system_settings(setting_key, setting_value, description) VALUES ('site.name', 'API Transit Station', 'Site name')");
        insertIfMissing("system_settings", "setting_key", "register.mode",
                "INSERT INTO system_settings(setting_key, setting_value, description) VALUES ('register.mode', 'invite_or_open', 'Registration policy')");
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
        linkPlusProductsIntoOtherServices();
        migrateLegacyPlusCatalogSettings();
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

    private void linkPlusProductsIntoOtherServices() {
        jdbcTemplate.update("""
                INSERT INTO other_services(
                    name, description, image_url, sort_order, enabled,
                    service_type, linked_product_id, action_label,
                    price_cents, service_fee_cents, currency, purchase_enabled,
                    created_at, updated_at
                )
                SELECT p.name, p.description, p.image_url,
                       COALESCE((SELECT MAX(s.sort_order) FROM other_services s), 0) + p.id,
                       p.enabled, 'PLUS', p.id, '立即购买',
                       p.price_cents, p.service_fee_cents, p.currency, p.enabled,
                       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                  FROM plus_products p
                 WHERE NOT EXISTS (
                       SELECT 1 FROM other_services existing
                        WHERE existing.linked_product_id = p.id
                 )
                """);
    }

    private void migrateLegacyPlusCatalogSettings() {
        jdbcTemplate.update("""
                UPDATE other_services
                   SET price_cents = COALESCE((
                           SELECT p.price_cents FROM plus_products p
                            WHERE p.id = other_services.linked_product_id
                       ), price_cents),
                       service_fee_cents = COALESCE((
                           SELECT p.service_fee_cents FROM plus_products p
                            WHERE p.id = other_services.linked_product_id
                       ), service_fee_cents),
                       currency = COALESCE((
                           SELECT p.currency FROM plus_products p
                            WHERE p.id = other_services.linked_product_id
                       ), currency),
                       purchase_enabled = COALESCE((
                           SELECT p.enabled FROM plus_products p
                            WHERE p.id = other_services.linked_product_id
                       ), purchase_enabled),
                       service_type = 'SERVICE'
                 WHERE linked_product_id IS NOT NULL
                   AND (service_type = 'PLUS'
                        OR (price_cents = 0 AND service_fee_cents = 0 AND purchase_enabled = FALSE))
                """);
    }

    private void seedMarketplaceModels() {
        Long openai = seedChannel("OpenAI Catalog", "openai", "https://api.openai.com", "gpt-5.5,gpt-5.4-mini,gpt-5.4-nano,gpt-5-codex-mini,o4-mini", 100);
        Long anthropic = seedChannel("Anthropic Catalog", "anthropic", "https://api.anthropic.com", "claude-sonnet-5,claude-opus-4-8,claude-haiku-4-5", 95);
        Long google = seedChannel("Google Gemini Catalog", "google", "https://generativelanguage.googleapis.com", "gemini-3.5-flash,gemini-3.1-pro-preview,gemini-2.5-flash", 90);
        Long deepseek = seedChannel("DeepSeek Catalog", "deepseek", "https://api.deepseek.com", "deepseek-v4-pro,deepseek-v4-flash,deepseek-reasoner", 90);
        Long xai = seedChannel("xAI Grok Catalog", "xai", "https://api.x.ai", "grok-4.3,grok-build", 80);
        Long qwen = seedChannel("Qwen Catalog", "qwen", "https://dashscope.aliyuncs.com/compatible-mode", "qwen3.7-max,qwen3.7-plus,qwen3.6-flash,qwen3-coder-plus", 85);
        Long kimi = seedChannel("Moonshot Kimi Catalog", "kimi", "https://api.moonshot.cn", "kimi-k2-thinking", 75);
        Long glm = seedChannel("Zhipu GLM Catalog", "glm", "https://open.bigmodel.cn/api/paas", "glm-5.1", 75);
        Long mistral = seedChannel("Mistral Catalog", "mistral", "https://api.mistral.ai", "mistral-large-latest,mistral-small-latest", 70);
        Long meta = seedChannel("Meta Llama Catalog", "meta", "https://openrouter.ai/api", "llama-4-scout,llama-4-maverick", 70);
        Long nvidia = seedChannel("NVIDIA Catalog", "nvidia", "https://integrate.api.nvidia.com/v1", "z-ai/glm-5.2,google/gemma-4-31b-it", 78);

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

        seedMapping("deepseek-v4-pro", deepseek, 100, "1.2", "chat,chinese,reasoning,coding", "128K");
        seedMapping("deepseek-v4-flash", deepseek, 98, "0.5", "chat,chinese,low-cost", "128K");
        seedMapping("deepseek-reasoner", deepseek, 96, "1.0", "reasoning,math,coding", "128K");

        seedMapping("grok-4.3", xai, 90, "3.0", "chat,reasoning,realtime", "1M");
        seedMapping("grok-build", xai, 88, "4.0", "coding,agent,developer", "1M");

        seedMapping("qwen3.7-max", qwen, 94, "2.0", "chat,chinese,reasoning", "1M");
        seedMapping("qwen3.7-plus", qwen, 92, "1.0", "chat,rag,structured-output", "1M");
        seedMapping("qwen3.6-flash", qwen, 90, "0.4", "chat,low-latency,low-cost", "1M");
        seedMapping("qwen3-coder-plus", qwen, 88, "1.5", "coding,completion,engineering", "256K");

        seedMapping("kimi-k2-thinking", kimi, 86, "1.5", "reasoning,long-context,chinese", "256K");
        seedMapping("glm-5.1", glm, 84, "1.2", "chat,chinese,tools", "128K");
        seedMapping("z-ai/glm-5.2", nvidia, 85, "1.8", "chat,reasoning,chinese,nvidia", "128K");
        seedMapping("google/gemma-4-31b-it", nvidia, 83, "1.4", "chat,open-weights,reasoning,nvidia", "128K");
        seedMapping("mistral-large-latest", mistral, 82, "2.0", "chat,function-calling,enterprise", "128K");
        seedMapping("mistral-small-latest", mistral, 80, "0.8", "chat,low-cost,summary", "128K");
        seedMapping("llama-4-scout", meta, 78, "0.8", "open-source,chat,self-hosting", "10M");
        seedMapping("llama-4-maverick", meta, 76, "1.2", "open-source,rag,multilingual", "1M");
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
