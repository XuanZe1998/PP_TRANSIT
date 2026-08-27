package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class SensitiveWordService {
    private static final List<String> MATCH_MODES = List.of("CONTAINS", "EXACT");
    private static final List<String> ACTIONS = List.of("BLOCK", "WARN", "REVIEW");
    private static final List<String> SCOPES = List.of("GLOBAL", "ORGANIZATION", "USER", "TOKEN");

    private final JdbcTemplate jdbcTemplate;
    private volatile List<Map<String, Object>> enabledCache;

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
                SELECT id,term,category,match_mode,action,scope_type,scope_id,note,enabled,created_at,updated_at
                FROM sensitive_words ORDER BY enabled DESC,category,term
                """);
    }

    public Map<String, Object> save(Map<String, Object> request) {
        Long id = nullableLong(request.get("id"));
        String term = required(request, "term", 255);
        String category = required(request, "category", 80);
        String matchMode = choice(request, "matchMode", "match_mode", "CONTAINS", MATCH_MODES);
        String action = choice(request, "action", "action", "BLOCK", ACTIONS);
        String scopeType = choice(request, "scopeType", "scope_type", "GLOBAL", SCOPES);
        Long scopeId = nullableLong(first(request, "scopeId", "scope_id"));
        if (!"GLOBAL".equals(scopeType) && scopeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A scope target is required");
        }
        if ("GLOBAL".equals(scopeType)) scopeId = null;
        String note = string(first(request, "note"));
        if (note.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note is too long");
        boolean enabled = bool(request.get("enabled"), false);
        LocalDateTime now = LocalDateTime.now();
        if (id == null) {
            KeyHolder key = new GeneratedKeyHolder();
            Long finalScopeId = scopeId;
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO sensitive_words(term,category,match_mode,action,scope_type,scope_id,note,enabled,created_at,updated_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, term); statement.setString(2, category);
                statement.setString(3, matchMode); statement.setString(4, action);
                statement.setString(5, scopeType);
                if (finalScopeId == null) statement.setNull(6, java.sql.Types.BIGINT); else statement.setLong(6, finalScopeId);
                statement.setString(7, note); statement.setBoolean(8, enabled);
                statement.setObject(9, now); statement.setObject(10, now);
                return statement;
            }, key);
            id = Objects.requireNonNull(key.getKey()).longValue();
        } else {
            int updated = jdbcTemplate.update("""
                    UPDATE sensitive_words SET term=?,category=?,match_mode=?,action=?,scope_type=?,scope_id=?,note=?,enabled=?,updated_at=?
                    WHERE id=?
                    """, term, category, matchMode, action, scopeType, scopeId, note, enabled, now, id);
            if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sensitive word not found");
        }
        enabledCache = null;
        return byId(id);
    }

    public Map<String, Object> bulk(Map<String, Object> request) {
        Collection<?> raw = request.get("terms") instanceof Collection<?> values
                ? values : List.of(string(request.get("terms")).split("[\\r\\n]+"));
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (Object value : raw) {
            String term = string(value).trim();
            if (!term.isBlank()) terms.add(term);
        }
        if (terms.isEmpty() || terms.size() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bulk import requires 1 to 200 terms");
        }
        Map<String, Object> defaults = new LinkedHashMap<>(request);
        defaults.remove("terms");
        int created = 0;
        for (String term : terms) {
            Map<String, Object> item = new LinkedHashMap<>(defaults);
            item.put("term", term);
            save(item);
            created++;
        }
        return Map.of("created", created);
    }

    public void delete(Long id) {
        if (jdbcTemplate.update("DELETE FROM sensitive_words WHERE id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sensitive word not found");
        }
        enabledCache = null;
    }

    public List<Map<String, Object>> events(int limit) {
        return jdbcTemplate.queryForList("""
                SELECT e.id,e.trace_id,e.category,e.matched_term,e.action,e.organization_id,e.user_id,
                       u.username,e.token_id,e.model,e.created_at
                FROM security_events e LEFT JOIN users u ON u.id=e.user_id
                ORDER BY e.created_at DESC LIMIT ?
                """, Math.max(10, Math.min(500, limit)));
    }

    public void scanJson(String traceId, Long organizationId, Long userId, Long tokenId,
                         String model, JsonNode request) {
        List<String> values = new ArrayList<>();
        collectText(request, null, values);
        scan(traceId, organizationId, userId, tokenId, model, values, true);
    }

    public List<Map<String, Object>> preview(Map<String, Object> request) {
        List<Map<String, Object>> matches = scan("preview", nullableLong(request.get("organizationId")),
                nullableLong(request.get("userId")), nullableLong(request.get("tokenId")),
                string(request.get("model")), List.of(string(request.get("text"))), false);
        return matches.stream().map(match -> Map.<String, Object>of(
                "id", value(match, "id"), "term", value(match, "term"),
                "category", value(match, "category"), "action", value(match, "action"))).toList();
    }

    private List<Map<String, Object>> scan(String traceId, Long organizationId, Long userId, Long tokenId,
                                            String model, Collection<String> values, boolean record) {
        List<String> normalizedValues = values.stream().map(this::normalize).filter(value -> !value.isBlank()).toList();
        if (normalizedValues.isEmpty()) return List.of();
        List<Map<String, Object>> matches = enabledWords().stream()
                .filter(word -> applies(word, organizationId, userId, tokenId))
                .filter(word -> matches(word, normalizedValues))
                .toList();
        if (record) {
            for (Map<String, Object> match : matches) {
                jdbcTemplate.update("""
                        INSERT INTO security_events(trace_id,sensitive_word_id,category,matched_term,action,organization_id,user_id,token_id,model,created_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, traceId, longValue(match, "id"), string(value(match, "category")),
                        string(value(match, "term")), string(value(match, "action")), organizationId,
                        userId, tokenId, model, LocalDateTime.now());
            }
        }
        if (record && matches.stream().anyMatch(match -> "BLOCK".equals(string(value(match, "action"))))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Request blocked by security policy");
        }
        return matches;
    }

    private List<Map<String, Object>> enabledWords() {
        List<Map<String, Object>> cached = enabledCache;
        if (cached != null) return cached;
        cached = List.copyOf(jdbcTemplate.queryForList("""
                SELECT id,term,category,match_mode,action,scope_type,scope_id
                FROM sensitive_words WHERE enabled=TRUE ORDER BY id
                """));
        enabledCache = cached;
        return cached;
    }

    private boolean applies(Map<String, Object> word, Long organizationId, Long userId, Long tokenId) {
        String scope = string(value(word, "scope_type"));
        Long target = nullableLong(value(word, "scope_id"));
        return switch (scope) {
            case "GLOBAL" -> true;
            case "ORGANIZATION" -> Objects.equals(target, organizationId);
            case "USER" -> Objects.equals(target, userId);
            case "TOKEN" -> Objects.equals(target, tokenId);
            default -> false;
        };
    }

    private boolean matches(Map<String, Object> word, List<String> values) {
        String term = normalize(string(value(word, "term")));
        boolean exact = "EXACT".equals(string(value(word, "match_mode")));
        return values.stream().anyMatch(value -> exact ? value.equals(term) : value.contains(term));
    }

    private void collectText(JsonNode node, String fieldName, List<String> values) {
        if (node == null || node.isNull() || "model".equals(fieldName)) return;
        if (node.isTextual()) {
            if (node.textValue().getBytes(StandardCharsets.UTF_8).length <= 2_097_152) values.add(node.textValue());
            return;
        }
        if (node.isArray()) node.forEach(item -> collectText(item, fieldName, values));
        if (node.isObject()) node.fields().forEachRemaining(entry -> collectText(entry.getValue(), entry.getKey(), values));
    }

    private Map<String, Object> byId(Long id) {
        return jdbcTemplate.queryForList("""
                SELECT id,term,category,match_mode,action,scope_type,scope_id,note,enabled,created_at,updated_at
                FROM sensitive_words WHERE id=?
                """, id).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Sensitive word not found"));
    }

    private String required(Map<String, Object> request, String key, int max) {
        String value = string(request.get(key)).trim();
        if (value.isBlank() || value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " is required or too long");
        }
        return value;
    }

    private String choice(Map<String, Object> request, String camelKey, String snakeKey,
                          String fallback, List<String> allowed) {
        String value = string(first(request, camelKey, snakeKey)).trim().toUpperCase();
        if (value.isBlank()) value = fallback;
        if (!allowed.contains(value)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, camelKey + " is invalid");
        return value;
    }

    private Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private Object value(Map<String, Object> map, String key) {
        if (map.containsKey(key)) return map.get(key);
        return map.get(key.toUpperCase(Locale.ROOT));
    }

    private long longValue(Map<String, Object> map, String key) {
        return Objects.requireNonNull(nullableLong(value(map, key)));
    }

    private Long nullableLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(value.toString());
    }

    private String string(Object value) { return value == null ? "" : value.toString(); }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
    }
}
