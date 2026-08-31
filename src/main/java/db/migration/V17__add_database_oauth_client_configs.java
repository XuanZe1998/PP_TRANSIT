package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;
import java.sql.ResultSet;

/** Encrypted, administrator-managed OAuth client configuration. */
public class V17__add_database_oauth_client_configs extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("""
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
            if (!columnExists(context, "upstream_oauth_states", "oauth_client_config_version")) {
                statement.execute("ALTER TABLE upstream_oauth_states ADD COLUMN oauth_client_config_version BIGINT NOT NULL DEFAULT 0");
            }
        }
    }

    private boolean columnExists(Context context, String table, String column) throws Exception {
        try (ResultSet rows = context.getConnection().getMetaData().getColumns(
                context.getConnection().getCatalog(), null, null, null)) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }
}
