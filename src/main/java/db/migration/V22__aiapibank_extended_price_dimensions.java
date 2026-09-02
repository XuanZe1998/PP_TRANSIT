package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Adds the optional AiAPIBank price dimensions when the generic price-tier
 * table is present. Some minimal test/bootstrap databases create that table
 * later through SchemaRepairService, so this migration is intentionally
 * metadata-aware and a no-op when it is absent.
 */
public class V22__aiapibank_extended_price_dimensions extends BaseJavaMigration {
    private static final String[] COLUMNS = {
            "official_cache_write_1h_price",
            "official_image_input_price",
            "official_image_output_price",
            "official_per_request_price",
            "cost_cache_write_1h_price",
            "cost_image_input_price",
            "cost_image_output_price",
            "cost_per_request_price",
            "sale_cache_write_1h_price",
            "sale_image_input_price",
            "sale_image_output_price",
            "sale_per_request_price"
    };

    @Override
    public void migrate(Context context) throws Exception {
        if (!tableExists(context, "model_price_tiers")) return;
        try (Statement statement = context.getConnection().createStatement()) {
            for (String column : COLUMNS) {
                if (!columnExists(context, "model_price_tiers", column)) {
                    statement.execute("ALTER TABLE model_price_tiers ADD COLUMN " + column
                            + " DECIMAL(24,10) NOT NULL DEFAULT 0");
                }
            }
        }
    }

    private boolean tableExists(Context context, String table) throws Exception {
        try (ResultSet rows = context.getConnection().getMetaData().getTables(
                context.getConnection().getCatalog(), null, null, new String[]{"TABLE"})) {
            while (rows.next()) if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
        }
        return false;
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
