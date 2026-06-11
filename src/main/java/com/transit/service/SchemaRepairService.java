package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SchemaRepairService {

    private final JdbcTemplate jdbcTemplate;

    @Bean
    ApplicationRunner schemaRepairRunner() {
        return args -> {
            ensureColumn("tokens", "user_id", "ALTER TABLE tokens ADD COLUMN user_id BIGINT NULL AFTER `key`");
            ensureColumn("plus_products", "service_fee_cents",
                    "ALTER TABLE plus_products ADD COLUMN service_fee_cents BIGINT NOT NULL DEFAULT 0 AFTER price_cents");
            ensureColumn("plus_orders", "unit_price_cents",
                    "ALTER TABLE plus_orders ADD COLUMN unit_price_cents BIGINT NULL AFTER product_name");
            ensureColumn("plus_orders", "service_fee_cents",
                    "ALTER TABLE plus_orders ADD COLUMN service_fee_cents BIGINT NULL AFTER unit_price_cents");
        };
    }

    private void ensureColumn(String tableName, String columnName, String alterSql) {
        List<String> columns = jdbcTemplate.query(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                (rs, rowNum) -> rs.getString(1),
                tableName
        );
        if (columns.isEmpty()) {
            log.info("Skipped schema patch: table {} does not exist", tableName);
            return;
        }
        if (!columns.contains(columnName)) {
            jdbcTemplate.execute(alterSql);
            log.info("Patched schema: added {}.{}", tableName, columnName);
        }
    }
}
