package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Additive enterprise-v2 schema migration.  The legacy schema bootstrap is
 * intentionally kept during the rolling-upgrade window; this runner only adds
 * new tables/columns and performs repeatable, lossless backfills.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class EnterpriseSchemaService {
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;

    @Bean
    @Order(1)
    ApplicationRunner enterpriseSchemaRunner() {
        return args -> {
            createTables();
            addColumns();
            backfillMultiUnitPricing();
            createIndexes();
            backfillOrganizations();
            backfillApiKeyModels();
        };
    }

    private void backfillMultiUnitPricing() {
        jdbcTemplate.update("""
                UPDATE model_mappings
                SET billing_mode=CASE WHEN billing_enabled=TRUE THEN 'PAID' ELSE 'DISABLED' END,
                    pricing_status=CASE WHEN billing_enabled=TRUE AND UPPER(COALESCE(pricing_unit,'TOKEN'))='TOKEN'
                                             AND COALESCE(input_price_per_million,0)>0 THEN 'VERIFIED' ELSE 'PENDING' END,
                    pricing_message=CASE WHEN UPPER(COALESCE(pricing_unit,'TOKEN'))<>'TOKEN'
                                         THEN CONCAT('尚未设置按',UPPER(pricing_unit),'计费的销售单价')
                                         WHEN billing_enabled=TRUE AND COALESCE(input_price_per_million,0)>0
                                         THEN COALESCE(pricing_message,'价格已由现有 Token 定价迁移')
                                         ELSE COALESCE(pricing_message,'缺少关键销售价格') END
                WHERE billing_mode IS NULL OR pricing_status IS NULL
                   OR (pricing_status='PENDING' AND pricing_message IS NULL)
                """);
        jdbcTemplate.update("""
                UPDATE model_mappings
                SET billing_enabled=TRUE,billing_mode='FREE_PREVIEW',pricing_status='FREE_PREVIEW',
                    pricing_message='免费开发预览 · 非生产服务，不承诺生产 SLA',
                    pricing_source_url='https://docs.api.nvidia.com/nim/docs/run-anywhere',
                    pricing_verified_at=COALESCE(pricing_verified_at,CURRENT_TIMESTAMP),
                    input_price_per_million=0,output_price_per_million=0,cached_price_per_million=0,
                    input_cost_per_million=0,output_cost_per_million=0,cached_cost_per_million=0
                WHERE enabled=TRUE AND channel_id IN (
                    SELECT id FROM channels WHERE LOWER(COALESCE(source_code,type))='nvidia'
                )
                """);
    }

    private void createTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organizations (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    organization_type VARCHAR(24) NOT NULL DEFAULT 'PERSONAL',
                    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    created_by BIGINT NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_members (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    organization_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    member_role VARCHAR(24) NOT NULL,
                    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    joined_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(organization_id, user_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS organization_invitations (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    organization_id BIGINT NOT NULL,
                    email VARCHAR(255) NOT NULL,
                    member_role VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
                    token_hash VARCHAR(255) NOT NULL UNIQUE,
                    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
                    invited_by BIGINT NOT NULL,
                    expires_at DATETIME NOT NULL,
                    accepted_by BIGINT NULL,
                    accepted_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS wallet_accounts (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    organization_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    account_type VARCHAR(24) NOT NULL DEFAULT 'MEMBER',
                    balance BIGINT NOT NULL DEFAULT 0,
                    held_balance BIGINT NOT NULL DEFAULT 0,
                    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(organization_id, user_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS wallet_ledger_entries (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    transaction_id VARCHAR(96) NOT NULL,
                    wallet_account_id BIGINT NOT NULL,
                    organization_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    entry_type VARCHAR(40) NOT NULL,
                    direction VARCHAR(8) NOT NULL,
                    amount BIGINT NOT NULL,
                    balance_after BIGINT NOT NULL,
                    reference_type VARCHAR(40) NULL,
                    reference_id VARCHAR(120) NULL,
                    remark VARCHAR(500) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(transaction_id, wallet_account_id, direction)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS provider_credentials (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    channel_id BIGINT NOT NULL,
                    name VARCHAR(160) NOT NULL,
                    encrypted_secret VARCHAR(1200) NOT NULL,
                    secret_preview VARCHAR(32) NULL,
                    priority INT NOT NULL DEFAULT 0,
                    weight INT NOT NULL DEFAULT 100,
                    rpm_limit INT NOT NULL DEFAULT 0,
                    tpm_limit INT NOT NULL DEFAULT 0,
                    concurrency_limit INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    health_status VARCHAR(24) NOT NULL DEFAULT 'UNTESTED',
                    cooldown_until DATETIME NULL,
                    consecutive_failures INT NOT NULL DEFAULT 0,
                    total_successes BIGINT NOT NULL DEFAULT 0,
                    total_failures BIGINT NOT NULL DEFAULT 0,
                    average_latency_ms BIGINT NOT NULL DEFAULT 0,
                    last_error VARCHAR(1000) NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS api_key_models (
                    token_id BIGINT NOT NULL,
                    model_name VARCHAR(160) NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY(token_id, model_name)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS idempotency_records (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    actor_scope VARCHAR(40) NOT NULL,
                    actor_id VARCHAR(96) NOT NULL,
                    operation_scope VARCHAR(120) NOT NULL,
                    idempotency_key VARCHAR(160) NOT NULL,
                    request_hash VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    http_status INT NULL,
                    resource_type VARCHAR(40) NULL,
                    resource_id VARCHAR(120) NULL,
                    response_body TEXT NULL,
                    error_message VARCHAR(1000) NULL,
                    expires_at DATETIME NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(actor_scope, actor_id, operation_scope, idempotency_key)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_tasks (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    task_id VARCHAR(96) NOT NULL UNIQUE,
                    user_id BIGINT NOT NULL,
                    token_id BIGINT NOT NULL,
                    organization_id BIGINT NULL,
                    model VARCHAR(160) NOT NULL,
                    capability VARCHAR(40) NOT NULL,
                    channel_id BIGINT NOT NULL,
                    credential_id BIGINT NULL,
                    upstream_task_id VARCHAR(255) NULL,
                    status VARCHAR(24) NOT NULL,
                    request_json TEXT NOT NULL,
                    response_json TEXT NULL,
                    error_message VARCHAR(1000) NULL,
                    reserved_amount BIGINT NOT NULL DEFAULT 0,
                    actual_amount BIGINT NOT NULL DEFAULT 0,
                    reservation_id VARCHAR(96) NULL,
                    next_poll_at DATETIME NULL,
                    lease_owner VARCHAR(120) NULL,
                    lease_expires_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    completed_at DATETIME NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS usage_hourly (
                    bucket_start DATETIME NOT NULL,
                    organization_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    token_id BIGINT NOT NULL,
                    source_code VARCHAR(80) NOT NULL,
                    model VARCHAR(160) NOT NULL,
                    request_count BIGINT NOT NULL DEFAULT 0,
                    success_count BIGINT NOT NULL DEFAULT 0,
                    input_tokens BIGINT NOT NULL DEFAULT 0,
                    output_tokens BIGINT NOT NULL DEFAULT 0,
                    cache_hit_tokens BIGINT NOT NULL DEFAULT 0,
                    cache_write_tokens BIGINT NOT NULL DEFAULT 0,
                    cache_miss_tokens BIGINT NOT NULL DEFAULT 0,
                    sale_amount BIGINT NOT NULL DEFAULT 0,
                    cost_amount BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY(bucket_start, organization_id, user_id, token_id, source_code, model)
                )
                """);
    }

    private void addColumns() {
        ensureColumn("users", "default_organization_id",
                "ALTER TABLE users ADD COLUMN default_organization_id BIGINT NULL");
        ensureColumn("tokens", "allow_all_models",
                "ALTER TABLE tokens ADD COLUMN allow_all_models BOOLEAN NOT NULL DEFAULT FALSE");
        ensureColumn("tokens", "organization_id",
                "ALTER TABLE tokens ADD COLUMN organization_id BIGINT NULL");
        ensureColumn("channels", "source_code",
                "ALTER TABLE channels ADD COLUMN source_code VARCHAR(80) NOT NULL DEFAULT 'other'");
        ensureColumn("channels", "source_name",
                "ALTER TABLE channels ADD COLUMN source_name VARCHAR(160) NOT NULL DEFAULT '其他第三方中转站'");
        ensureColumn("channels", "protocol_type",
                "ALTER TABLE channels ADD COLUMN protocol_type VARCHAR(80) NOT NULL DEFAULT 'openai-chat'");
        ensureColumn("model_mappings", "vendor",
                "ALTER TABLE model_mappings ADD COLUMN vendor VARCHAR(80) NOT NULL DEFAULT 'unknown'");
        ensureColumn("model_mappings", "capability",
                "ALTER TABLE model_mappings ADD COLUMN capability VARCHAR(40) NOT NULL DEFAULT 'text'");
        ensureColumn("model_mappings", "input_modalities",
                "ALTER TABLE model_mappings ADD COLUMN input_modalities VARCHAR(255) NOT NULL DEFAULT 'text'");
        ensureColumn("model_mappings", "output_modalities",
                "ALTER TABLE model_mappings ADD COLUMN output_modalities VARCHAR(255) NOT NULL DEFAULT 'text'");
        ensureColumn("model_mappings", "protocols",
                "ALTER TABLE model_mappings ADD COLUMN protocols VARCHAR(255) NOT NULL DEFAULT 'chat-completions'");
        ensureColumn("model_mappings", "pricing_unit",
                "ALTER TABLE model_mappings ADD COLUMN pricing_unit VARCHAR(40) NOT NULL DEFAULT 'TOKEN'");
        ensureColumn("model_mappings", "endpoint_path",
                "ALTER TABLE model_mappings ADD COLUMN endpoint_path VARCHAR(500) NULL");
        ensureColumn("model_mappings", "task_query_path",
                "ALTER TABLE model_mappings ADD COLUMN task_query_path VARCHAR(500) NULL");
        ensureColumn("model_mappings", "task_query_method",
                "ALTER TABLE model_mappings ADD COLUMN task_query_method VARCHAR(8) NOT NULL DEFAULT 'POST'");
        ensureColumn("model_mappings", "billing_mode",
                "ALTER TABLE model_mappings ADD COLUMN billing_mode VARCHAR(24) NOT NULL DEFAULT 'PAID'");
        ensureColumn("model_mappings", "pricing_status",
                "ALTER TABLE model_mappings ADD COLUMN pricing_status VARCHAR(24) NOT NULL DEFAULT 'PENDING'");
        ensureColumn("model_mappings", "pricing_message",
                "ALTER TABLE model_mappings ADD COLUMN pricing_message VARCHAR(500) NULL");
        ensureColumn("model_mappings", "pricing_source_url",
                "ALTER TABLE model_mappings ADD COLUMN pricing_source_url VARCHAR(1000) NULL");
        ensureColumn("model_mappings", "pricing_verified_at",
                "ALTER TABLE model_mappings ADD COLUMN pricing_verified_at DATETIME NULL");
        ensureColumn("model_mappings", "official_unit_price",
                "ALTER TABLE model_mappings ADD COLUMN official_unit_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "cost_unit_price",
                "ALTER TABLE model_mappings ADD COLUMN cost_unit_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_mappings", "sale_unit_price",
                "ALTER TABLE model_mappings ADD COLUMN sale_unit_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("logs", "organization_id",
                "ALTER TABLE logs ADD COLUMN organization_id BIGINT NULL");
        ensureColumn("logs", "credential_id",
                "ALTER TABLE logs ADD COLUMN credential_id BIGINT NULL");
        ensureColumn("logs", "source_code",
                "ALTER TABLE logs ADD COLUMN source_code VARCHAR(80) NULL");
        ensureColumn("logs", "cache_miss_tokens",
                "ALTER TABLE logs ADD COLUMN cache_miss_tokens INT NOT NULL DEFAULT 0");
        ensureColumn("gateway_reservations", "wallet_account_id",
                "ALTER TABLE gateway_reservations ADD COLUMN wallet_account_id BIGINT NULL");
        ensureColumn("logs", "billing_unit", "ALTER TABLE logs ADD COLUMN billing_unit VARCHAR(40) NOT NULL DEFAULT 'TOKEN'");
        ensureColumn("logs", "billable_quantity", "ALTER TABLE logs ADD COLUMN billable_quantity DECIMAL(24,6) NOT NULL DEFAULT 0");
        ensureColumn("logs", "unit_sale_price", "ALTER TABLE logs ADD COLUMN unit_sale_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("logs", "unit_cost_price", "ALTER TABLE logs ADD COLUMN unit_cost_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_tasks", "billing_unit", "ALTER TABLE model_tasks ADD COLUMN billing_unit VARCHAR(40) NOT NULL DEFAULT 'TASK'");
        ensureColumn("model_tasks", "billable_quantity", "ALTER TABLE model_tasks ADD COLUMN billable_quantity DECIMAL(24,6) NOT NULL DEFAULT 1");
        ensureColumn("model_tasks", "unit_sale_price", "ALTER TABLE model_tasks ADD COLUMN unit_sale_price DECIMAL(18,6) NOT NULL DEFAULT 0");
        ensureColumn("model_tasks", "unit_cost_price", "ALTER TABLE model_tasks ADD COLUMN unit_cost_price DECIMAL(18,6) NOT NULL DEFAULT 0");
    }

    private void createIndexes() {
        ensureIndex("provider_credentials", "idx_provider_credential_route",
                "CREATE INDEX idx_provider_credential_route ON provider_credentials(channel_id, enabled, health_status, priority)");
        ensureIndex("organization_members", "idx_org_member_user",
                "CREATE INDEX idx_org_member_user ON organization_members(user_id, status)");
        ensureIndex("wallet_ledger_entries", "idx_wallet_ledger_org_created",
                "CREATE INDEX idx_wallet_ledger_org_created ON wallet_ledger_entries(organization_id, created_at)");
        ensureIndex("idempotency_records", "idx_idempotency_expiry",
                "CREATE INDEX idx_idempotency_expiry ON idempotency_records(status, expires_at)");
        ensureIndex("model_tasks", "idx_model_task_claim",
                "CREATE INDEX idx_model_task_claim ON model_tasks(status, next_poll_at, lease_expires_at)");
        ensureIndex("logs", "idx_logs_org_created",
                "CREATE INDEX idx_logs_org_created ON logs(organization_id, created_at)");
    }

    private void backfillOrganizations() {
        List<Map<String, Object>> users = jdbcTemplate.queryForList(
                "SELECT id, username, balance, default_organization_id FROM users ORDER BY id");
        for (Map<String, Object> user : users) {
            if (user.get("default_organization_id") != null) continue;
            transactions.executeWithoutResult(status -> {
                long userId = ((Number) user.get("id")).longValue();
                List<Long> existing = jdbcTemplate.queryForList("""
                        SELECT om.organization_id FROM organization_members om
                        WHERE om.user_id = ? AND om.member_role = 'OWNER' ORDER BY om.id LIMIT 1
                        """, Long.class, userId);
                long organizationId;
                if (existing.isEmpty()) {
                    jdbcTemplate.update("""
                            INSERT INTO organizations(name, organization_type, status, created_by, created_at, updated_at)
                            VALUES (?, 'PERSONAL', 'ACTIVE', ?, ?, ?)
                            """, String.valueOf(user.get("username")) + " 的个人组织", userId,
                            LocalDateTime.now(), LocalDateTime.now());
                    organizationId = jdbcTemplate.queryForObject(
                            "SELECT MAX(id) FROM organizations WHERE created_by = ?", Long.class, userId);
                    jdbcTemplate.update("""
                            INSERT INTO organization_members(organization_id, user_id, member_role, status, joined_at)
                            VALUES (?, ?, 'OWNER', 'ACTIVE', ?)
                            """, organizationId, userId, LocalDateTime.now());
                } else {
                    organizationId = existing.get(0);
                }
                Integer walletCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM wallet_accounts WHERE organization_id = ? AND user_id = ?",
                        Integer.class, organizationId, userId);
                if (walletCount == null || walletCount == 0) {
                    long balance = user.get("balance") == null ? 0 : ((Number) user.get("balance")).longValue();
                    jdbcTemplate.update("""
                            INSERT INTO wallet_accounts(organization_id, user_id, account_type, balance, status, created_at, updated_at)
                            VALUES (?, ?, 'TREASURY', ?, 'ACTIVE', ?, ?)
                            """, organizationId, userId, balance, LocalDateTime.now(), LocalDateTime.now());
                }
                jdbcTemplate.update("UPDATE users SET default_organization_id = ? WHERE id = ?",
                        organizationId, userId);
            });
        }
        jdbcTemplate.update("""
                UPDATE tokens SET organization_id=(SELECT u.default_organization_id FROM users u WHERE u.id=tokens.user_id)
                WHERE organization_id IS NULL
                """);
    }

    private void backfillApiKeyModels() {
        List<Map<String, Object>> tokens = jdbcTemplate.queryForList(
                "SELECT id, allowed_models, allow_all_models FROM tokens");
        for (Map<String, Object> token : tokens) {
            long tokenId = ((Number) token.get("id")).longValue();
            String allowed = token.get("allowed_models") == null ? "" : token.get("allowed_models").toString();
            if (allowed.isBlank() || "*".equals(allowed.trim())) {
                jdbcTemplate.update("UPDATE tokens SET allow_all_models = TRUE WHERE id = ?", tokenId);
                continue;
            }
            Arrays.stream(allowed.split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).distinct()
                    .forEach(model -> {
                        Integer count = jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM api_key_models WHERE token_id = ? AND LOWER(model_name) = ?",
                                Integer.class, tokenId, model.toLowerCase(Locale.ROOT));
                        if (count == null || count == 0) {
                            jdbcTemplate.update("INSERT INTO api_key_models(token_id, model_name) VALUES (?, ?)",
                                    tokenId, model);
                        }
                    });
        }
    }

    private void ensureColumn(String table, String column, String sql) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE LOWER(TABLE_NAME)=LOWER(?) AND LOWER(COLUMN_NAME)=LOWER(?)
                """, Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute(sql);
            log.info("Enterprise schema added {}.{}", table, column);
        }
    }

    private void ensureIndex(String table, String index, String sql) {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            for (String tableName : List.of(table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT))) {
                try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                        connection.getCatalog(), connection.getSchema(), tableName, false, false)) {
                    while (indexes.next()) {
                        String existingName = indexes.getString("INDEX_NAME");
                        if (existingName != null && existingName.equalsIgnoreCase(index)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
        if (!Boolean.TRUE.equals(exists)) jdbcTemplate.execute(sql);
    }
}
