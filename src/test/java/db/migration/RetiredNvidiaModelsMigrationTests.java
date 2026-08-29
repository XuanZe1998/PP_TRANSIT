package db.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetiredNvidiaModelsMigrationTests {
    @Test
    void migrationDeletesOnlyRetiredRoutesPricesVerificationsAndGrantsAndKeepsLogs() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:retired_model_migration;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            createSchema(connection);
            seedRows(connection);
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            V14__exclude_retired_nvidia_models migration = new V14__exclude_retired_nvidia_models();
            migration.migrate(context);
            migration.migrate(context);

            assertThat(count(connection, "provider_model_exclusions")).isEqualTo(3);
            assertThat(count(connection, "provider_models WHERE source_code='nvidia'")).isZero();
            assertThat(count(connection, "provider_models WHERE source_code='haoee'")).isEqualTo(1);
            assertThat(count(connection, "model_mappings WHERE channel_id=1")).isZero();
            assertThat(count(connection, "model_mappings WHERE channel_id=2")).isEqualTo(1);
            assertThat(count(connection, "model_price_tiers")).isEqualTo(1);
            assertThat(count(connection, "provider_model_verifications")).isEqualTo(1);
            assertThat(count(connection, "api_key_models WHERE model_name LIKE '%nemotron%' OR model_name LIKE '%step-3.7%' OR model_name LIKE '%topic-control%'"))
                    .isZero();
            assertThat(count(connection, "api_key_models WHERE model_name='safe-model'")).isEqualTo(1);
            assertThat(count(connection, "logs")).isEqualTo(3);
        }
    }

    private void createSchema(Connection connection) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE provider_model_exclusions (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_code VARCHAR(80), upstream_model_name VARCHAR(160), public_model_name VARCHAR(160), reason VARCHAR(500), excluded_at TIMESTAMP, UNIQUE(source_code,upstream_model_name))");
            sql.execute("CREATE TABLE channels (id BIGINT PRIMARY KEY, source_code VARCHAR(80), type VARCHAR(80))");
            sql.execute("CREATE TABLE model_mappings (id BIGINT PRIMARY KEY, channel_id BIGINT, channel_model_name VARCHAR(160))");
            sql.execute("CREATE TABLE model_price_tiers (id BIGINT PRIMARY KEY, model_mapping_id BIGINT)");
            sql.execute("CREATE TABLE provider_models (id BIGINT PRIMARY KEY, source_code VARCHAR(80), upstream_model_name VARCHAR(160))");
            sql.execute("CREATE TABLE provider_model_verifications (id BIGINT PRIMARY KEY, provider_model_id BIGINT)");
            sql.execute("CREATE TABLE api_key_models (token_id BIGINT, model_name VARCHAR(160))");
            sql.execute("CREATE TABLE logs (id BIGINT PRIMARY KEY, model VARCHAR(160))");
        }
    }

    private void seedRows(Connection connection) throws Exception {
        try (Statement sql = connection.createStatement()) {
            sql.execute("INSERT INTO channels VALUES (1,'nvidia','openai'),(2,'haoee','haoee')");
            long id = 10;
            for (String model : V14__exclude_retired_nvidia_models.RETIRED) {
                sql.execute("INSERT INTO model_mappings VALUES (" + id + ",1,'" + model + "')");
                sql.execute("INSERT INTO model_price_tiers VALUES (" + id + "," + id + ")");
                sql.execute("INSERT INTO provider_models VALUES (" + id + ",'nvidia','" + model + "')");
                sql.execute("INSERT INTO provider_model_verifications VALUES (" + id + "," + id + ")");
                sql.execute("INSERT INTO api_key_models VALUES (1,'" + model + "')");
                sql.execute("INSERT INTO logs VALUES (" + id + ",'" + model + "')");
                id++;
            }
            sql.execute("INSERT INTO model_mappings VALUES (20,2,'mistralai/mistral-nemotron')");
            sql.execute("INSERT INTO model_price_tiers VALUES (20,20)");
            sql.execute("INSERT INTO provider_models VALUES (20,'haoee','mistralai/mistral-nemotron')");
            sql.execute("INSERT INTO provider_model_verifications VALUES (20,20)");
            sql.execute("INSERT INTO api_key_models VALUES (1,'safe-model')");
        }
    }

    private int count(Connection connection, String tableAndWhere) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + tableAndWhere)) {
            result.next();
            return result.getInt(1);
        }
    }
}
