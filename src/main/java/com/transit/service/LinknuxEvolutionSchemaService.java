package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;

/** Additive schema for the Linknux account-pool, distribution and operations modules. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LinknuxEvolutionSchemaService {
    private final JdbcTemplate jdbc;

    @Bean
    @Order(2)
    ApplicationRunner linknuxEvolutionSchemaRunner() {
        return args -> {
            createDistributionTables();
            createProviderAccountTables();
            createOperationsTables();
            extendProviderCredentials();
            ensureColumn("upstream_oauth_states", "oauth_client_config_version",
                    "ALTER TABLE upstream_oauth_states ADD COLUMN oauth_client_config_version BIGINT NOT NULL DEFAULT 0");
            extendServiceCommerce();
            extendOAuthLoginState();
            seedDefaults();
            backfillProviderAccounts();
            createIndexes();
        };
    }

    private void extendOAuthLoginState() {
        ensureColumn("oauth_login_states", "invite_code",
                "ALTER TABLE oauth_login_states ADD COLUMN invite_code VARCHAR(32) NULL");
    }

    private void createDistributionTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_tiers (
                    tier_code VARCHAR(32) PRIMARY KEY,
                    display_name VARCHAR(80) NOT NULL,
                    commission_bps INT NOT NULL,
                    max_customer_rebate_bps INT NOT NULL,
                    min_platform_margin_bps INT NOT NULL DEFAULT 2000,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_profiles (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    user_id BIGINT NOT NULL UNIQUE,
                    tier_code VARCHAR(32) NOT NULL DEFAULT 'BASIC',
                    invite_code VARCHAR(32) NOT NULL UNIQUE,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    requested_rebate_bps INT NOT NULL DEFAULT 0,
                    approved_at DATETIME NULL,
                    approved_by BIGINT NULL,
                    suspended_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_customer_bindings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    customer_user_id BIGINT NOT NULL UNIQUE,
                    agent_user_id BIGINT NOT NULL,
                    invite_code VARCHAR(32) NOT NULL,
                    customer_rebate_bps INT NOT NULL DEFAULT 0,
                    binding_source VARCHAR(32) NOT NULL,
                    bound_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    created_by BIGINT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_commission_events (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    business_event_id VARCHAR(96) NOT NULL UNIQUE,
                    business_type VARCHAR(32) NOT NULL,
                    customer_user_id BIGINT NOT NULL,
                    agent_user_id BIGINT NOT NULL,
                    sale_amount BIGINT NOT NULL,
                    cost_amount BIGINT NOT NULL,
                    gross_profit BIGINT NOT NULL,
                    commission_pool BIGINT NOT NULL,
                    customer_rebate_amount BIGINT NOT NULL,
                    agent_commission_amount BIGINT NOT NULL,
                    amount_scale BIGINT NOT NULL DEFAULT 10000,
                    status VARCHAR(32) NOT NULL DEFAULT 'SETTLED',
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_commission_ledger (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    event_id BIGINT NOT NULL,
                    business_event_id VARCHAR(96) NOT NULL,
                    beneficiary_user_id BIGINT NOT NULL,
                    agent_user_id BIGINT NOT NULL,
                    customer_user_id BIGINT NOT NULL,
                    entry_type VARCHAR(32) NOT NULL,
                    amount BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    available_at DATETIME NULL,
                    reversed_entry_id BIGINT NULL,
                    note VARCHAR(500) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(business_event_id, beneficiary_user_id, entry_type)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_withdrawals (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    request_no VARCHAR(64) NOT NULL UNIQUE,
                    agent_user_id BIGINT NOT NULL,
                    amount BIGINT NOT NULL,
                    destination_type VARCHAR(32) NOT NULL,
                    destination_encrypted TEXT NULL,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    audit_note VARCHAR(500) NULL,
                    reviewed_by BIGINT NULL,
                    reviewed_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void createProviderAccountTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS upstream_oauth_client_configs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    platform VARCHAR(32) NOT NULL UNIQUE,
                    encrypted_config_bundle TEXT NOT NULL,
                    client_id_preview VARCHAR(160) NULL,
                    has_client_secret BOOLEAN NOT NULL DEFAULT FALSE,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    config_version BIGINT NOT NULL DEFAULT 1,
                    last_test_status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
                    last_tested_at DATETIME NULL,
                    last_error_masked VARCHAR(500) NULL,
                    created_by BIGINT NULL,
                    updated_by BIGINT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS upstream_oauth_states (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT, flow_id VARCHAR(64) NOT NULL UNIQUE,
                    state_hash VARCHAR(64) NOT NULL UNIQUE, platform VARCHAR(32) NOT NULL,
                    encrypted_code_verifier TEXT NOT NULL, encrypted_nonce TEXT NOT NULL,
                    admin_user_id BIGINT NOT NULL, reauthorize_credential_id BIGINT NULL, upstream_proxy_id BIGINT NULL,
                    price_template_id BIGINT NOT NULL, account_group VARCHAR(80) NOT NULL DEFAULT 'default',
                    model_scope TEXT NULL, redirect_uri VARCHAR(1000) NOT NULL,
                    callback_mode VARCHAR(24) NOT NULL DEFAULT 'POPUP', expires_at DATETIME NOT NULL,
                    consumed_at DATETIME NULL, created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS provider_price_templates (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(160) NOT NULL,
                    platform VARCHAR(32) NOT NULL, model_pattern VARCHAR(160) NOT NULL,
                    priority INT NOT NULL DEFAULT 0, pricing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN',
                    official_price_json TEXT NOT NULL, cost_price_json TEXT NOT NULL,
                    sale_price_json TEXT NOT NULL, source_url VARCHAR(1000) NULL,
                    source_note VARCHAR(500) NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS upstream_proxies (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    protocol VARCHAR(16) NOT NULL,
                    host VARCHAR(255) NOT NULL,
                    port INT NOT NULL,
                    encrypted_auth TEXT NULL,
                    fallback_proxy_id BIGINT NULL,
                    direct_fallback BOOLEAN NOT NULL DEFAULT FALSE,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    expires_at DATETIME NULL,
                    latency_ms BIGINT NOT NULL DEFAULT 0,
                    health_status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
                    last_checked_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS provider_account_route_bindings (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    credential_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    model_pattern VARCHAR(160) NOT NULL DEFAULT '*',
                    protocol VARCHAR(64) NOT NULL DEFAULT '*',
                    priority INT NOT NULL DEFAULT 0,
                    weight INT NOT NULL DEFAULT 100,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(credential_id, channel_id, model_pattern, protocol)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS provider_account_quota_snapshots (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    credential_id BIGINT NOT NULL,
                    quota_type VARCHAR(32) NOT NULL,
                    used_amount BIGINT NOT NULL DEFAULT 0,
                    limit_amount BIGINT NOT NULL DEFAULT 0,
                    remaining_amount BIGINT NOT NULL DEFAULT 0,
                    period_start DATETIME NULL,
                    resets_at DATETIME NULL,
                    source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
                    captured_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS provider_account_events (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    credential_id BIGINT NOT NULL,
                    event_type VARCHAR(48) NOT NULL,
                    error_class VARCHAR(32) NULL,
                    retryable BOOLEAN NOT NULL DEFAULT FALSE,
                    detail_masked VARCHAR(1000) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void createOperationsTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ops_minute_metrics (
                    bucket_start DATETIME NOT NULL,
                    channel_id BIGINT NOT NULL DEFAULT 0,
                    credential_id BIGINT NOT NULL DEFAULT 0,
                    model VARCHAR(160) NOT NULL DEFAULT '',
                    request_count BIGINT NOT NULL DEFAULT 0,
                    success_count BIGINT NOT NULL DEFAULT 0,
                    error_count BIGINT NOT NULL DEFAULT 0,
                    input_tokens BIGINT NOT NULL DEFAULT 0,
                    output_tokens BIGINT NOT NULL DEFAULT 0,
                    latency_sum_ms BIGINT NOT NULL DEFAULT 0,
                    ttft_sum_ms BIGINT NOT NULL DEFAULT 0,
                    p50_ms BIGINT NOT NULL DEFAULT 0,
                    p95_ms BIGINT NOT NULL DEFAULT 0,
                    p99_ms BIGINT NOT NULL DEFAULT 0,
                    sale_amount BIGINT NOT NULL DEFAULT 0,
                    cost_amount BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY(bucket_start, channel_id, credential_id, model)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS task_heartbeats (
                    task_key VARCHAR(96) PRIMARY KEY,
                    status VARCHAR(32) NOT NULL,
                    last_started_at DATETIME NULL,
                    last_succeeded_at DATETIME NULL,
                    last_failed_at DATETIME NULL,
                    last_error VARCHAR(1000) NULL,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS alert_rules (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    metric_key VARCHAR(64) NOT NULL,
                    operator VARCHAR(8) NOT NULL,
                    threshold_value BIGINT NOT NULL,
                    window_minutes INT NOT NULL DEFAULT 5,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS alert_events (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    rule_id BIGINT NULL,
                    severity VARCHAR(16) NOT NULL,
                    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
                    fingerprint VARCHAR(96) NOT NULL,
                    summary VARCHAR(500) NOT NULL,
                    detail_masked VARCHAR(1000) NULL,
                    opened_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    resolved_at DATETIME NULL,
                    UNIQUE(fingerprint, status)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS channel_monitor_history (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    channel_id BIGINT NOT NULL,
                    credential_id BIGINT NULL,
                    status VARCHAR(32) NOT NULL,
                    latency_ms BIGINT NOT NULL DEFAULT 0,
                    error_class VARCHAR(32) NULL,
                    detail_masked VARCHAR(1000) NULL,
                    checked_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS announcements (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    title VARCHAR(200) NOT NULL,
                    content TEXT NOT NULL,
                    audience VARCHAR(32) NOT NULL DEFAULT 'ALL',
                    starts_at DATETIME NULL,
                    ends_at DATETIME NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_by BIGINT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS announcement_reads (
                    announcement_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    read_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(announcement_id, user_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS backup_runs (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    request_no VARCHAR(64) NOT NULL UNIQUE,
                    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
                    storage_path_masked VARCHAR(500) NULL,
                    size_bytes BIGINT NOT NULL DEFAULT 0,
                    checksum_sha256 VARCHAR(64) NULL,
                    requested_by BIGINT NULL,
                    started_at DATETIME NULL,
                    completed_at DATETIME NULL,
                    error_message VARCHAR(1000) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }

    private void extendProviderCredentials() {
        ensureColumn("provider_credentials", "platform", "ALTER TABLE provider_credentials ADD COLUMN platform VARCHAR(32) NOT NULL DEFAULT 'COMPATIBLE'");
        ensureColumn("provider_credentials", "auth_type", "ALTER TABLE provider_credentials ADD COLUMN auth_type VARCHAR(32) NOT NULL DEFAULT 'API_KEY'");
        ensureColumn("provider_credentials", "encrypted_credential_bundle", "ALTER TABLE provider_credentials ADD COLUMN encrypted_credential_bundle TEXT NULL");
        ensureColumn("provider_credentials", "oauth_expires_at", "ALTER TABLE provider_credentials ADD COLUMN oauth_expires_at DATETIME NULL");
        ensureColumn("provider_credentials", "account_group", "ALTER TABLE provider_credentials ADD COLUMN account_group VARCHAR(80) NOT NULL DEFAULT 'default'");
        ensureColumn("provider_credentials", "upstream_proxy_id", "ALTER TABLE provider_credentials ADD COLUMN upstream_proxy_id BIGINT NULL");
        ensureColumn("provider_credentials", "cost_mode", "ALTER TABLE provider_credentials ADD COLUMN cost_mode VARCHAR(32) NOT NULL DEFAULT 'MODEL_MAPPING'");
        ensureColumn("provider_credentials", "period_cost_amount", "ALTER TABLE provider_credentials ADD COLUMN period_cost_amount BIGINT NOT NULL DEFAULT 0");
        ensureColumn("provider_credentials", "cost_reliable", "ALTER TABLE provider_credentials ADD COLUMN cost_reliable BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("provider_credentials", "model_scope", "ALTER TABLE provider_credentials ADD COLUMN model_scope TEXT NULL");
        ensureColumn("provider_credentials", "temporary_unschedulable_until", "ALTER TABLE provider_credentials ADD COLUMN temporary_unschedulable_until DATETIME NULL");
        ensureColumn("provider_credentials", "last_error_class", "ALTER TABLE provider_credentials ADD COLUMN last_error_class VARCHAR(32) NULL");
        ensureColumn("provider_credentials", "last_used_at", "ALTER TABLE provider_credentials ADD COLUMN last_used_at DATETIME NULL");
        ensureColumn("provider_credentials", "external_account_id", "ALTER TABLE provider_credentials ADD COLUMN external_account_id VARCHAR(255) NULL");
        ensureColumn("provider_credentials", "email_preview", "ALTER TABLE provider_credentials ADD COLUMN email_preview VARCHAR(255) NULL");
        ensureColumn("provider_credentials", "subscription_tier", "ALTER TABLE provider_credentials ADD COLUMN subscription_tier VARCHAR(80) NULL");
        ensureColumn("provider_credentials", "authorization_scope", "ALTER TABLE provider_credentials ADD COLUMN authorization_scope TEXT NULL");
        ensureColumn("provider_credentials", "entitlement_status", "ALTER TABLE provider_credentials ADD COLUMN entitlement_status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'");
        ensureColumn("provider_credentials", "token_version", "ALTER TABLE provider_credentials ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0");
        ensureColumn("provider_credentials", "last_refreshed_at", "ALTER TABLE provider_credentials ADD COLUMN last_refreshed_at DATETIME NULL");
        ensureColumn("provider_credentials", "refresh_failure_count", "ALTER TABLE provider_credentials ADD COLUMN refresh_failure_count INT NOT NULL DEFAULT 0");
        ensureColumn("provider_credentials", "price_template_id", "ALTER TABLE provider_credentials ADD COLUMN price_template_id BIGINT NULL");
        ensureColumn("channels", "managed", "ALTER TABLE channels ADD COLUMN managed BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("channels", "managed_platform", "ALTER TABLE channels ADD COLUMN managed_platform VARCHAR(32) NULL");
        ensureColumn("channels", "managed_auth_type", "ALTER TABLE channels ADD COLUMN managed_auth_type VARCHAR(32) NULL");
        ensureColumn("upstream_oauth_states", "reauthorize_credential_id", "ALTER TABLE upstream_oauth_states ADD COLUMN reauthorize_credential_id BIGINT NULL");
    }

    private void extendServiceCommerce() {
        ensureColumn("other_services", "cost_cents", "ALTER TABLE other_services ADD COLUMN cost_cents BIGINT NULL");
        ensureColumn("other_services", "commission_refund_window_days", "ALTER TABLE other_services ADD COLUMN commission_refund_window_days INT NOT NULL DEFAULT 7");
    }

    private void seedDefaults() {
        seedTier("BASIC", "基础", 500, 200, 2000);
        seedTier("GROWTH", "成长", 800, 300, 2000);
        seedTier("PARTNER", "合作伙伴", 1200, 500, 2000);
    }

    private void backfillProviderAccounts() {
        jdbc.update("""
                UPDATE provider_credentials pc
                SET platform=UPPER(COALESCE((SELECT NULLIF(c.source_code,'') FROM channels c WHERE c.id=pc.channel_id),'COMPATIBLE')),
                    auth_type=COALESCE(NULLIF(auth_type,''),'API_KEY'),
                    account_group=COALESCE(NULLIF(account_group,''),'default'),
                    cost_mode=COALESCE(NULLIF(cost_mode,''),'MODEL_MAPPING')
                WHERE platform IS NULL OR platform='' OR platform='COMPATIBLE' OR auth_type IS NULL OR auth_type=''
                   OR account_group IS NULL OR account_group='' OR cost_mode IS NULL OR cost_mode=''
                """);
    }

    private void createIndexes() {
        ensureIndex("agent_customer_bindings", "idx_agent_bindings_agent", "CREATE INDEX idx_agent_bindings_agent ON agent_customer_bindings(agent_user_id,bound_at)");
        ensureIndex("agent_commission_ledger", "idx_agent_ledger_beneficiary", "CREATE INDEX idx_agent_ledger_beneficiary ON agent_commission_ledger(beneficiary_user_id,status,available_at)");
        ensureIndex("agent_withdrawals", "idx_agent_withdrawal_status", "CREATE INDEX idx_agent_withdrawal_status ON agent_withdrawals(status,created_at)");
        ensureIndex("provider_account_quota_snapshots", "idx_provider_quota_latest", "CREATE INDEX idx_provider_quota_latest ON provider_account_quota_snapshots(credential_id,captured_at)");
        ensureIndex("provider_account_events", "idx_provider_events_account", "CREATE INDEX idx_provider_events_account ON provider_account_events(credential_id,created_at)");
        ensureIndex("provider_credentials", "uk_provider_oauth_external", "CREATE UNIQUE INDEX uk_provider_oauth_external ON provider_credentials(platform,external_account_id)");
        ensureIndex("channels", "uk_channels_managed_platform", "CREATE UNIQUE INDEX uk_channels_managed_platform ON channels(managed_platform)");
        ensureIndex("upstream_oauth_states", "idx_upstream_oauth_expiry", "CREATE INDEX idx_upstream_oauth_expiry ON upstream_oauth_states(expires_at,consumed_at)");
        ensureIndex("channel_monitor_history", "idx_channel_monitor_time", "CREATE INDEX idx_channel_monitor_time ON channel_monitor_history(channel_id,checked_at)");
        ensureIndex("alert_events", "idx_alert_events_opened", "CREATE INDEX idx_alert_events_opened ON alert_events(status,opened_at)");
    }

    private void seedTier(String code, String name, int commission, int rebate, int margin) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_tiers WHERE tier_code=?", Integer.class, code);
        if (count == null || count == 0) jdbc.update("INSERT INTO agent_tiers(tier_code,display_name,commission_bps,max_customer_rebate_bps,min_platform_margin_bps) VALUES (?,?,?,?,?)",
                code, name, commission, rebate, margin);
    }

    private void ensureColumn(String table, String column, String ddl) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME)=LOWER(?) AND LOWER(COLUMN_NAME)=LOWER(?)",
                Integer.class, table, column);
        if (count == null || count == 0) {
            jdbc.execute(ddl);
            log.info("Linknux schema added {}.{}", table, column);
        }
    }

    private void ensureIndex(String table, String index, String ddl) {
        Boolean exists = jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            for (String tableName : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
                try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), connection.getSchema(), tableName, false, false)) {
                    while (indexes.next()) {
                        String name = indexes.getString("INDEX_NAME");
                        if (name != null && name.equalsIgnoreCase(index)) return true;
                    }
                }
            }
            return false;
        });
        if (!Boolean.TRUE.equals(exists)) jdbc.execute(ddl);
    }
}
