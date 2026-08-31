package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ModelMarketDisplayService {
    private final JdbcTemplate jdbc;

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT names.public_model_name publicName,
                       COALESCE(settings.display_priority, 0) displayPriority
                  FROM (SELECT DISTINCT public_model_name FROM model_mappings) names
                  LEFT JOIN model_market_display_settings settings
                    ON settings.public_model_name = names.public_model_name
                 ORDER BY displayPriority DESC, names.public_model_name ASC
                """);
    }

    @Transactional
    public Map<String, Object> update(Map<String, Object> request) {
        Object rawItems = request == null ? null : request.get("items");
        if (!(rawItems instanceof List<?> items) || items.size() > 5000) {
            throw bad("items must be an array with at most 5000 entries");
        }
        List<Map.Entry<String, Integer>> values = new ArrayList<>();
        for (Object rawItem : items) {
            if (!(rawItem instanceof Map<?, ?> item)) throw bad("display setting entry is invalid");
            Object rawPublicName = item.get("publicName");
            String publicName = rawPublicName == null ? "" : String.valueOf(rawPublicName).trim();
            if (publicName.isBlank() || publicName.length() > 160) throw bad("publicName is invalid");
            Object rawPriority = item.get("displayPriority");
            int priority;
            try { priority = rawPriority instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(rawPriority)); }
            catch (RuntimeException error) { throw bad("displayPriority must be an integer"); }
            if (priority < 0 || priority > 1_000_000) throw bad("displayPriority must be between 0 and 1000000");
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE public_model_name=?", Integer.class, publicName);
            if (exists == null || exists == 0) throw bad("Unknown public model: " + publicName);
            values.add(Map.entry(publicName, priority));
        }
        for (Map.Entry<String, Integer> value : values) {
            int changed = jdbc.update("""
                    UPDATE model_market_display_settings
                       SET display_priority=?,updated_at=CURRENT_TIMESTAMP
                     WHERE public_model_name=?
                    """, value.getValue(), value.getKey());
            if (changed == 0) {
                try {
                    jdbc.update("""
                            INSERT INTO model_market_display_settings(public_model_name,display_priority,created_at,updated_at)
                            VALUES (?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                            """, value.getKey(), value.getValue());
                } catch (DuplicateKeyException concurrentInsert) {
                    jdbc.update("""
                            UPDATE model_market_display_settings
                               SET display_priority=?,updated_at=CURRENT_TIMESTAMP
                             WHERE public_model_name=?
                            """, value.getValue(), value.getKey());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updated", values.size());
        result.put("items", list());
        return result;
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
