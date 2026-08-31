package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Persistent, fail-closed storage for administrator-owned upstream OAuth accounts. */
public class V16__add_upstream_oauth_pool extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        execute(connection, """
                CREATE TABLE IF NOT EXISTS upstream_oauth_states (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    flow_id VARCHAR(64) NOT NULL UNIQUE,
                    state_hash VARCHAR(64) NOT NULL UNIQUE,
                    platform VARCHAR(32) NOT NULL,
                    encrypted_code_verifier TEXT NOT NULL,
                    encrypted_nonce TEXT NOT NULL,
                    admin_user_id BIGINT NOT NULL,
                    reauthorize_credential_id BIGINT NULL,
                    upstream_proxy_id BIGINT NULL,
                    price_template_id BIGINT NOT NULL,
                    account_group VARCHAR(80) NOT NULL DEFAULT 'default',
                    model_scope TEXT NULL,
                    redirect_uri VARCHAR(1000) NOT NULL,
                    callback_mode VARCHAR(24) NOT NULL DEFAULT 'POPUP',
                    expires_at DATETIME NOT NULL,
                    consumed_at DATETIME NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        execute(connection, """
                CREATE TABLE IF NOT EXISTS provider_price_templates (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    name VARCHAR(160) NOT NULL,
                    platform VARCHAR(32) NOT NULL,
                    model_pattern VARCHAR(160) NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    pricing_unit VARCHAR(32) NOT NULL DEFAULT 'TOKEN',
                    official_price_json TEXT NOT NULL,
                    cost_price_json TEXT NOT NULL,
                    sale_price_json TEXT NOT NULL,
                    source_url VARCHAR(1000) NULL,
                    source_note VARCHAR(500) NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
        if (tableExists(connection, "provider_credentials")) {
            addColumn(connection, "provider_credentials", "external_account_id", "VARCHAR(255) NULL");
            addColumn(connection, "provider_credentials", "email_preview", "VARCHAR(255) NULL");
            addColumn(connection, "provider_credentials", "subscription_tier", "VARCHAR(80) NULL");
            addColumn(connection, "provider_credentials", "authorization_scope", "TEXT NULL");
            addColumn(connection, "provider_credentials", "entitlement_status", "VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'");
            addColumn(connection, "provider_credentials", "token_version", "BIGINT NOT NULL DEFAULT 0");
            addColumn(connection, "provider_credentials", "last_refreshed_at", "DATETIME NULL");
            addColumn(connection, "provider_credentials", "refresh_failure_count", "INT NOT NULL DEFAULT 0");
            addColumn(connection, "provider_credentials", "price_template_id", "BIGINT NULL");
            if (!indexExists(connection, "provider_credentials", "uk_provider_oauth_external"))
                execute(connection, "CREATE UNIQUE INDEX uk_provider_oauth_external ON provider_credentials(platform,external_account_id)");
            makeSecretNullable(connection);
        }
        if (tableExists(connection, "channels")) {
            addColumn(connection, "channels", "managed", "BOOLEAN NOT NULL DEFAULT FALSE");
            addColumn(connection, "channels", "managed_platform", "VARCHAR(32) NULL");
            addColumn(connection, "channels", "managed_auth_type", "VARCHAR(32) NULL");
            if (!indexExists(connection, "channels", "uk_channels_managed_platform"))
                execute(connection, "CREATE UNIQUE INDEX uk_channels_managed_platform ON channels(managed_platform)");
        }
    }

    private void makeSecretNullable(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
        execute(connection, product.contains("h2")
                ? "ALTER TABLE provider_credentials ALTER COLUMN encrypted_secret VARCHAR(1200) NULL"
                : "ALTER TABLE provider_credentials MODIFY COLUMN encrypted_secret VARCHAR(1200) NULL");
    }

    private void addColumn(Connection connection, String table, String column, String definition) throws SQLException {
        if (!columnExists(connection, table, column)) execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getTables(connection.getCatalog(), null, null, new String[]{"TABLE"})) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
        }
        return false;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (ResultSet rows = connection.getMetaData().getColumns(connection.getCatalog(), null, null, null)) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME")) && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
        }
        return false;
    }

    private boolean indexExists(Connection connection, String table, String index) throws SQLException {
        for (String name : new String[]{table, table.toUpperCase(), table.toLowerCase()}) try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, name, false, false)) {
            while (rows.next()) if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
        }
        return false;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
