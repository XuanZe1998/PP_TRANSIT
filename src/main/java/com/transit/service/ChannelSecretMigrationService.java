package com.transit.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class ChannelSecretMigrationService implements ApplicationRunner {
    private final JdbcTemplate jdbcTemplate;
    private final ChannelSecretService secretService;

    @Override
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, api_key FROM channels WHERE api_key IS NOT NULL AND api_key <> ''");
        if (!secretService.isConfigured()) {
            if (!rows.isEmpty()) {
                log.warn("{} channel credential(s) remain unavailable for encrypted migration; configure security.data-encryption-key", rows.size());
            }
            return;
        }
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            String stored = String.valueOf(row.get("api_key"));
            if (!secretService.isEncrypted(stored)) {
                jdbcTemplate.update("UPDATE channels SET api_key = ? WHERE id = ? AND api_key = ?",
                        secretService.encrypt(stored), row.get("id"), stored);
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("Encrypted {} legacy channel credential(s) in place", migrated);
        }
    }
}
