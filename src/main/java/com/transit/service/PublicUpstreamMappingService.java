package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.dto.PublicUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collection;

@Service
@RequiredArgsConstructor
public class PublicUpstreamMappingService {
    public static final String FALLBACK_CODE = "platform-route";
    public static final String FALLBACK_NAME = "平台智能路由";
    private final JdbcTemplate jdbcTemplate;

    public Map<Long, PublicUpstream> forChannels(Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) return Map.of();
        List<Long> ids = channelIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "SELECT channel_id,public_code,public_name,badge_text,badge_color FROM upstream_display_mappings "
                        + "WHERE enabled=TRUE AND channel_id IN (" + placeholders + ")", ids.toArray());
        Map<Long, PublicUpstream> result = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            Object rawId = value(row, "channel_id");
            if (!(rawId instanceof Number number)) continue;
            String code = safeCode(value(row, "public_code"));
            result.put(number.longValue(), new PublicUpstream(code,
                    text(value(row, "public_name"), FALLBACK_NAME),
                    text(value(row, "badge_text"), "智能路由"),
                    text(value(row, "badge_color"), "#2563eb")));
        }
        return result;
    }

    public void sanitize(List<PublicModel> models) {
        if (models == null || models.isEmpty()) return;
        List<String> names = models.stream().map(PublicModel::getPublicName).filter(v -> v != null && !v.isBlank()).distinct().toList();
        if (names.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        String sql = "SELECT mm.public_model_name, COALESCE(NULLIF(udm.public_code,''), ?) public_code, "
                + "COALESCE(NULLIF(udm.public_name,''), ?) public_name, "
                + "COALESCE(NULLIF(udm.badge_text,''), '智能路由') badge_text, "
                + "COALESCE(NULLIF(udm.badge_color,''), '#2563eb') badge_color, "
                + "COALESCE(udm.sort_order, 100) sort_order "
                + "FROM model_mappings mm "
                + "LEFT JOIN upstream_display_mappings udm ON udm.channel_id=mm.channel_id AND udm.enabled=TRUE "
                + "WHERE mm.public_model_name IN (" + placeholders + ") AND mm.enabled=TRUE "
                + "ORDER BY sort_order, mm.channel_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, prepend(names, FALLBACK_CODE, FALLBACK_NAME));
        Map<String, LinkedHashMap<String, PublicUpstream>> byModel = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String model = String.valueOf(value(row, "public_model_name"));
            String code = safeCode(value(row, "public_code"));
            byModel.computeIfAbsent(model, ignored -> new LinkedHashMap<>()).putIfAbsent(code,
                    new PublicUpstream(code, text(value(row, "public_name"), FALLBACK_NAME),
                            text(value(row, "badge_text"), "智能路由"), text(value(row, "badge_color"), "#2563eb")));
        }
        for (PublicModel model : models) {
            List<PublicUpstream> upstreams = new ArrayList<>(byModel.getOrDefault(model.getPublicName(), fallback()).values());
            if (upstreams.isEmpty()) upstreams = new ArrayList<>(fallback().values());
            model.setUpstreams(upstreams);
            String codes = String.join(",", upstreams.stream().map(PublicUpstream::getCode).toList());
            model.setSources(codes);
            model.setSource(upstreams.size() == 1 ? upstreams.get(0).getCode() : "multi-route");
            model.setType(model.getSource());
            model.setSourceName(upstreams.size() == 1 ? upstreams.get(0).getName() : "多个平台路由");
        }
    }

    private Object[] prepend(List<String> names, Object... prefix) {
        List<Object> args = new ArrayList<>(List.of(prefix)); args.addAll(names); return args.toArray();
    }
    private LinkedHashMap<String, PublicUpstream> fallback() {
        LinkedHashMap<String, PublicUpstream> map = new LinkedHashMap<>();
        map.put(FALLBACK_CODE, new PublicUpstream(FALLBACK_CODE, FALLBACK_NAME, "智能路由", "#2563eb"));
        return map;
    }
    private String safeCode(Object value) {
        String code = text(value, FALLBACK_CODE).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        return code.isBlank() ? FALLBACK_CODE : code;
    }
    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) return row.get(key);
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }
    private String text(Object value, String fallback) { return value == null || value.toString().isBlank() ? fallback : value.toString().trim(); }
}
