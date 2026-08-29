package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Permanently retires three repeatedly failing NVIDIA models. This is a Java
 * migration because older installations have the routing/grant tables while a
 * clean installation creates those runtime-compatible tables after Flyway.
 */
public class V14__exclude_retired_nvidia_models extends BaseJavaMigration {
    static final List<String> RETIRED = List.of(
            "mistralai/mistral-nemotron",
            "stepfun-ai/step-3.7-flash",
            "nvidia/llama-3.1-nemoguard-8b-topic-control"
    );

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        insertTombstones(connection);
        deleteStructuredGrants(connection);
        deleteRoutesAndPrices(connection);
        deleteProviderCatalogRows(connection);
    }

    private void insertTombstones(Connection connection) throws SQLException {
        for (String model : RETIRED) {
            if (exists(connection, """
                    SELECT 1 FROM provider_model_exclusions
                    WHERE LOWER(source_code)='nvidia' AND upstream_model_name=?
                    """, model)) continue;
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO provider_model_exclusions
                    (source_code,upstream_model_name,public_model_name,reason,excluded_at)
                    VALUES ('nvidia',?,?,?,?)
                    """)) {
                insert.setString(1, model);
                insert.setString(2, model);
                insert.setString(3, "Permanently retired after repeated upstream failures");
                insert.setTimestamp(4, Timestamp.from(Instant.now()));
                insert.executeUpdate();
            }
        }
    }

    private void deleteStructuredGrants(Connection connection) throws SQLException {
        if (!tableExists(connection, "api_key_models")) return;
        for (String model : RETIRED) {
            execute(connection, "DELETE FROM api_key_models WHERE model_name=?", model);
        }
    }

    private void deleteRoutesAndPrices(Connection connection) throws SQLException {
        if (!tableExists(connection, "channels") || !tableExists(connection, "model_mappings")) return;
        List<Long> mappingIds = new ArrayList<>();
        for (String model : RETIRED) {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT mapping.id
                    FROM model_mappings mapping
                    INNER JOIN channels channel ON channel.id=mapping.channel_id
                    WHERE LOWER(COALESCE(channel.source_code,channel.type))='nvidia'
                      AND mapping.channel_model_name=?
                    """)) {
                select.setString(1, model);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) mappingIds.add(rows.getLong(1));
                }
            }
        }
        for (Long mappingId : mappingIds) {
            if (tableExists(connection, "model_price_tiers")) {
                execute(connection, "DELETE FROM model_price_tiers WHERE model_mapping_id=?", mappingId);
            }
            execute(connection, "DELETE FROM model_mappings WHERE id=?", mappingId);
        }
    }

    private void deleteProviderCatalogRows(Connection connection) throws SQLException {
        if (!tableExists(connection, "provider_models")) return;
        List<Long> providerModelIds = new ArrayList<>();
        for (String model : RETIRED) {
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id FROM provider_models
                    WHERE LOWER(source_code)='nvidia' AND upstream_model_name=?
                    """)) {
                select.setString(1, model);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) providerModelIds.add(rows.getLong(1));
                }
            }
        }
        for (Long providerModelId : providerModelIds) {
            if (tableExists(connection, "provider_model_verifications")) {
                execute(connection, "DELETE FROM provider_model_verifications WHERE provider_model_id=?", providerModelId);
            }
            execute(connection, "DELETE FROM provider_models WHERE id=?", providerModelId);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, null, new String[]{"TABLE"})) {
            while (tables.next()) {
                if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    private boolean exists(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void execute(Connection connection, String sql, Object value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            statement.executeUpdate();
        }
    }
}
