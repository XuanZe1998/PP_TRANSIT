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
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PublicUpstreamMappingService {
    public static final String FALLBACK_CODE = "platform-route";
    public static final String FALLBACK_NAME = "平台智能路由";
    private final JdbcTemplate jdbcTemplate;

    public Map<Long, PublicUpstream> forChannels(Collection<Long> channelIds) {
        if (channelIds == null || channelIds.isEmpty()) return Map.of();
        List<Long> ids = channelIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        // Legacy group mappings are only a fallback for channels that have not
        // been attached to a site. A public route is a site identity; otherwise
        // every group can leak out as a separate, indistinguishable facet.
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
        List<Map<String,Object>> siteRows = jdbcTemplate.queryForList(
                "SELECT sc.channel_id,sc.site_id,s.public_code,s.public_name,s.badge_text,s.badge_color "
                        + "FROM upstream_site_channels sc JOIN upstream_sites s ON s.id=sc.site_id "
                        + "WHERE sc.channel_id IN (" + placeholders + ")", ids.toArray());
        Map<Long, PublicUpstream> sharedSiteDisplays = sharedSiteDisplays(siteRows.stream()
                .map(row -> number(value(row, "site_id"))).filter(Objects::nonNull).distinct().toList());
        for (Map<String,Object> row : siteRows) {
            Long channelId = number(value(row, "channel_id"));
            Long siteId = number(value(row, "site_id"));
            if (channelId == null || siteId == null) continue;
            PublicUpstream shared = sharedSiteDisplays.get(siteId);
            String publicCode = text(value(row, "public_code"), "site-" + siteId);
            String publicName = text(value(row, "public_name"), shared == null ? FALLBACK_NAME : shared.getName());
            String badgeText = text(value(row, "badge_text"), shared == null ? "智能路由" : shared.getBadgeText());
            String badgeColor = text(value(row, "badge_color"), shared == null ? "#2563eb" : shared.getBadgeColor());
            // Site identity intentionally overwrites any channel/group mapping.
            result.put(channelId, new PublicUpstream(safeCode(publicCode), publicName, badgeText, badgeColor));
        }
        return result;
    }

    public void sanitize(List<PublicModel> models) {
        if (models == null || models.isEmpty()) return;
        List<String> names = models.stream().map(PublicModel::getPublicName).filter(v -> v != null && !v.isBlank()).distinct().toList();
        if (names.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        String sql = "SELECT mm.public_model_name,mm.channel_id FROM model_mappings mm "
                + "WHERE mm.public_model_name IN (" + placeholders + ") AND mm.enabled=TRUE "
                + "ORDER BY mm.channel_id";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, names.toArray());
        Map<Long, PublicUpstream> channels = forChannels(rows.stream()
                .map(row -> number(value(row, "channel_id"))).filter(Objects::nonNull).toList());
        Map<String, LinkedHashMap<String, PublicUpstream>> byModel = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String model = String.valueOf(value(row, "public_model_name"));
            PublicUpstream upstream = channels.getOrDefault(number(value(row, "channel_id")), fallback().get(FALLBACK_CODE));
            byModel.computeIfAbsent(model, ignored -> new LinkedHashMap<>()).putIfAbsent(upstream.getCode(), upstream);
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

    private Map<Long, PublicUpstream> sharedSiteDisplays(List<Long> siteIds) {
        if (siteIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(siteIds.size(), "?"));
        List<Map<String,Object>> rows = jdbcTemplate.queryForList(
                "SELECT sc.site_id,MIN(d.public_name) public_name,MIN(d.badge_text) badge_text,"
                        + "MIN(d.badge_color) badge_color,COUNT(DISTINCT LOWER(TRIM(d.public_name))) public_name_count "
                        + "FROM upstream_site_channels sc JOIN upstream_display_mappings d ON d.channel_id=sc.channel_id "
                        + "AND d.enabled=TRUE WHERE sc.site_id IN (" + placeholders + ") GROUP BY sc.site_id",
                siteIds.toArray());
        Map<Long, PublicUpstream> result = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            Long siteId = number(value(row, "site_id"));
            Long nameCount = number(value(row, "public_name_count"));
            if (siteId == null || !Long.valueOf(1).equals(nameCount)) continue;
            result.put(siteId, new PublicUpstream("site-" + siteId,
                    text(value(row, "public_name"), FALLBACK_NAME),
                    text(value(row, "badge_text"), "智能路由"),
                    text(value(row, "badge_color"), "#2563eb")));
        }
        return result;
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
    private Long number(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private String text(Object value, String fallback) { return value == null || value.toString().isBlank() ? fallback : value.toString().trim(); }
}
