package com.transit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProviderPriceTemplateService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public List<Map<String, Object>> list(String platform) {
        return platform == null ? jdbc.queryForList("SELECT * FROM provider_price_templates ORDER BY platform,name,id")
                : jdbc.queryForList("SELECT * FROM provider_price_templates WHERE platform=? ORDER BY name,id", normalize(platform));
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Validated value = validate(body);
        String sql = """
                INSERT INTO provider_price_templates(name,platform,model_pattern,priority,pricing_unit,official_price_json,
                cost_price_json,sale_price_json,source_url,source_note,enabled,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        LocalDateTime now = LocalDateTime.now(); GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            Object[] values = {value.name, value.platform, value.pattern, value.priority, value.unit, value.officialJson,
                    value.costJson, value.saleJson, value.sourceUrl, value.sourceNote, value.enabled, now, now};
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, keys);
        Long id = keys.getKey() == null ? null : keys.getKey().longValue();
        if (id == null) throw new IllegalStateException("Unable to read generated price template ID");
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> body) {
        get(id); Validated value = validate(body);
        jdbc.update("""
                UPDATE provider_price_templates SET name=?,platform=?,model_pattern=?,priority=?,pricing_unit=?,official_price_json=?,
                cost_price_json=?,sale_price_json=?,source_url=?,source_note=?,enabled=?,updated_at=? WHERE id=?
                """, value.name, value.platform, value.pattern, value.priority, value.unit, value.officialJson,
                value.costJson, value.saleJson, value.sourceUrl, value.sourceNote, value.enabled, LocalDateTime.now(), id);
        return get(id);
    }

    public void delete(long id) { if (jdbc.update("DELETE FROM provider_price_templates WHERE id=?", id) != 1) throw notFound(); }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM provider_price_templates WHERE id=?", id);
        if (rows.isEmpty()) throw notFound(); return rows.get(0);
    }

    public Match match(String platform, String model, Long requiredTemplateId) {
        String requiredName = requiredTemplateId == null ? null : String.valueOf(get(requiredTemplateId).get("name"));
        List<Map<String, Object>> candidates = list(platform).stream()
                .filter(row -> truth(row.get("enabled")))
                .filter(row -> requiredName == null || requiredName.equalsIgnoreCase(String.valueOf(row.get("name"))))
                .filter(row -> matches(String.valueOf(row.get("model_pattern")), model))
                .sorted(Comparator.<Map<String, Object>>comparingInt(row -> specificity(String.valueOf(row.get("model_pattern")), model)).reversed()
                        .thenComparing(Comparator.comparingInt((Map<String, Object> row) -> ((Number) row.get("priority")).intValue()).reversed()))
                .toList();
        if (candidates.isEmpty()) return null;
        Map<String, Object> row = candidates.get(0);
        return new Match(((Number) row.get("id")).longValue(), String.valueOf(row.get("pricing_unit")), parse(row.get("official_price_json")),
                parse(row.get("cost_price_json")), parse(row.get("sale_price_json")), nullable(row.get("source_url")));
    }

    public List<Map<String, Object>> preview(String platform, List<String> models, Long templateId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String model : models) { Match match = match(platform, model, templateId); result.add(Map.of("model", model, "matched", match != null, "templateId", match == null ? "" : match.templateId())); }
        return result;
    }

    private Validated validate(Map<String, Object> body) {
        String name = text(body.get("name")), platform = normalize(text(body.get("platform"))), pattern = text(body.get("modelPattern"));
        String unit = normalize(String.valueOf(body.getOrDefault("pricingUnit", "TOKEN")));
        if (name.isBlank() || platform.isBlank() || pattern.isBlank() || pattern.length() > 160) bad("价格模板名称、平台和模型匹配规则不能为空");
        if (pattern.contains("[") || pattern.contains("(") || pattern.contains("{") || pattern.contains("\\")) bad("模型规则仅支持精确名称、glob * 与 ?");
        Map<String, Object> official = map(body.get("officialPrice")), cost = map(body.get("costPrice")), sale = map(body.get("salePrice"));
        if (official.isEmpty() || !hasPositive(cost) || !hasPositive(sale)) bad("官网价、可靠成本价和售价不能为空，且成本/售价至少一项必须大于 0");
        try { return new Validated(name, platform, pattern, number(body.get("priority")), unit, json.writeValueAsString(official),
                json.writeValueAsString(cost), json.writeValueAsString(sale), nullable(body.get("sourceUrl")), nullable(body.get("sourceNote")), !Boolean.FALSE.equals(body.get("enabled"))); }
        catch (Exception exception) { throw new IllegalArgumentException(exception); }
    }

    private boolean matches(String glob, String model) { return Pattern.compile(globRegex(glob), Pattern.CASE_INSENSITIVE).matcher(model).matches(); }
    private int specificity(String pattern, String model) { return pattern.equalsIgnoreCase(model) ? 2 : "*".equals(pattern) ? 0 : 1; }
    private String globRegex(String glob) { StringBuilder out = new StringBuilder("^"); for (char c : glob.toCharArray()) { if (c == '*') out.append(".*"); else if (c == '?') out.append('.'); else out.append(Pattern.quote(String.valueOf(c))); } return out.append('$').toString(); }
    private Map<String, Object> parse(Object value) { try { return json.readValue(String.valueOf(value), new TypeReference<>() {}); } catch (Exception ignored) { return Map.of(); } }
    private Map<String, Object> map(Object value) { if (!(value instanceof Map<?, ?> raw)) return Map.of(); Map<String, Object> result = new LinkedHashMap<>(); raw.forEach((k,v) -> result.put(String.valueOf(k), v)); return result; }
    private String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String nullable(Object value) { String result = text(value); return result.isBlank() ? null : result; }
    private int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }
    private boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
    private boolean hasPositive(Map<String, Object> values) { return values.values().stream().anyMatch(value -> { try { return new java.math.BigDecimal(String.valueOf(value)).signum() > 0; } catch (Exception ignored) { return false; } }); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "价格模板不存在"); }
    private void bad(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    public record Match(long templateId, String pricingUnit, Map<String, Object> officialPrice, Map<String, Object> costPrice, Map<String, Object> salePrice, String sourceUrl) {}
    private record Validated(String name,String platform,String pattern,int priority,String unit,String officialJson,String costJson,String saleJson,String sourceUrl,String sourceNote,boolean enabled) {}
}
