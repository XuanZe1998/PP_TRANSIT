package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.dto.PublicUpstream;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PublicModelPresentationService {
    private final JdbcTemplate jdbc;
    private final ModelIdentityService identities;

    public void enrich(List<PublicModel> models) {
        if (models == null || models.isEmpty()) return;
        List<String> names = models.stream().map(PublicModel::getPublicName).filter(Objects::nonNull).distinct().toList();
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mm.public_model_name,mm.channel_model_name,COALESCE(c.source_code,c.type) source_code,
                       mm.vendor mapping_vendor,mm.capability mapping_capability,mm.input_modalities mapping_inputs,
                       mm.output_modalities mapping_outputs,mm.protocols mapping_protocols,
                       a.comparison_key,a.explicit_override,i.metadata_rank
                FROM model_mappings mm JOIN channels c ON c.id=mm.channel_id
                LEFT JOIN model_identity_aliases a ON a.source_code=LOWER(COALESCE(c.source_code,c.type))
                    AND a.upstream_model_name=LOWER(mm.channel_model_name)
                LEFT JOIN model_catalog_identities i ON i.comparison_key=a.comparison_key
                WHERE mm.public_model_name IN (""" + placeholders + ") AND mm.enabled=TRUE AND c.enabled=TRUE", names.toArray());
        Map<String, List<Map<String, Object>>> byPublicName = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byPublicName.computeIfAbsent(text(row, "public_model_name", ""), ignored -> new ArrayList<>()).add(row);
        }
        Map<PublicModel, Resolution> resolutions = new LinkedHashMap<>();
        for (PublicModel model : models) {
            List<Map<String, Object>> candidates = byPublicName.getOrDefault(model.getPublicName(), List.of());
            Map<String, Object> row = candidates.stream().max(Comparator.comparingInt(candidate -> integer(candidate, "metadata_rank"))).orElse(Map.of());
            String source = text(row, "source_code", Objects.toString(model.getSource(), "other"));
            String upstream = useful(model.getUpstreamModelName()) ? model.getUpstreamModelName()
                    : text(row, "channel_model_name", model.getPublicName());
            String publisherCode = text(row, "publisher_code", "");
            if (!useful(publisherCode)) publisherCode = ModelIdentityService.publisherCode(
                    text(row, "mapping_vendor", model.getVendor()), upstream);
            boolean useMappedAlias = "aiapibank".equalsIgnoreCase(source) || bool(row, "explicit_override");
            String comparisonKey = useMappedAlias ? text(row, "comparison_key", "") : "";
            if (!useful(comparisonKey)) comparisonKey = identities.canonicalKey(
                    "aiapibank".equalsIgnoreCase(source) ? upstream : model.getPublicName(), publisherCode);
            resolutions.put(model, new Resolution(row, source, upstream, publisherCode, comparisonKey));
        }

        List<String> keys = resolutions.values().stream().map(Resolution::comparisonKey).distinct().toList();
        String identityPlaceholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<>();
        if (!keys.isEmpty()) {
            for (Map<String, Object> identity : jdbc.queryForList("""
                    SELECT comparison_key,display_name,publisher_code,publisher_name,category,capability,
                           input_modalities,output_modalities,protocols,metadata_rank
                    FROM model_catalog_identities WHERE comparison_key IN (""" + identityPlaceholders + ")", keys.toArray())) {
                metadata.put(text(identity, "comparison_key", ""), identity);
            }
        }

        for (Map.Entry<PublicModel, Resolution> entry : resolutions.entrySet()) {
            PublicModel model = entry.getKey();
            Resolution resolution = entry.getValue();
            Map<String, Object> row = resolution.row();
            Map<String, Object> identity = metadata.getOrDefault(resolution.comparisonKey(), Map.of());
            String publisherCode = text(identity, "publisher_code", resolution.publisherCode());

            model.setComparisonKey(resolution.comparisonKey());
            model.setUpstreamModelName(resolution.upstream());
            model.setDisplayName(text(identity, "display_name", "aiapibank".equalsIgnoreCase(resolution.source())
                    ? resolution.upstream() : model.getPublicName()));
            model.setPublisherCode(publisherCode);
            model.setPublisherName(text(identity, "publisher_name", ModelIdentityService.publisherName(publisherCode)));
            model.setVendor(publisherCode);
            model.setCapability(text(identity, "capability", text(row, "mapping_capability", defaultText(model.getCapability(), "text"))));
            model.setInputModalities(text(identity, "input_modalities", text(row, "mapping_inputs", defaultText(model.getInputModalities(), "text"))));
            model.setOutputModalities(text(identity, "output_modalities", text(row, "mapping_outputs", defaultText(model.getOutputModalities(), "text"))));
            model.setProtocols(text(identity, "protocols", text(row, "mapping_protocols", defaultText(model.getProtocols(), "chat-completions"))));
            model.setCategory(text(identity, "category", ModelIdentityService.category(
                    model.getCapability(), model.getInputModalities(), model.getOutputModalities())));
            applyRouteAndPlan(model);
            if (!useful(model.getVerificationStatus())) model.setVerificationStatus("AVAILABLE");
            if (!useful(model.getVerificationMessage())) model.setVerificationMessage("当前公开路由已验证可调用");
            if (!useful(model.getPricingStatus())) model.setPricingStatus(model.isBillingConfigured() ? "VERIFIED" : "PENDING");
        }
    }

    private void applyRouteAndPlan(PublicModel model) {
        List<PublicUpstream> upstreams = model.getUpstreams() == null ? List.of() : model.getUpstreams();
        if (upstreams.size() == 1) {
            model.setRouteCode(defaultText(upstreams.get(0).getCode(), PublicUpstreamMappingService.FALLBACK_CODE));
            model.setRouteName(defaultText(upstreams.get(0).getName(), PublicUpstreamMappingService.FALLBACK_NAME));
        } else if (upstreams.size() > 1) {
            model.setRouteCode("multi-route"); model.setRouteName("多个平台路由");
        } else {
            model.setRouteCode(PublicUpstreamMappingService.FALLBACK_CODE);
            model.setRouteName(PublicUpstreamMappingService.FALLBACK_NAME);
        }
        if (model.getProviderGroup() != null && useful(model.getProviderGroup().getSlug())) {
            model.setPlanCode(model.getProviderGroup().getSlug());
            model.setPlanName(defaultText(model.getProviderGroup().getName(), "标准"));
        } else if (PublicUpstreamMappingService.FALLBACK_CODE.equals(model.getRouteCode())) {
            model.setPlanCode("smart-route"); model.setPlanName("智能路由");
        } else {
            model.setPlanCode("standard"); model.setPlanName("标准");
        }
    }

    private Object value(Map<String, Object> row, String key) { for (var entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue(); return null; }
    private String text(Map<String, Object> row, String key, String fallback) { Object value=value(row,key); return value==null||value.toString().isBlank()?fallback:value.toString().trim(); }
    private int integer(Map<String, Object> row, String key) { Object value=value(row,key); return value instanceof Number n?n.intValue():0; }
    private boolean bool(Map<String, Object> row, String key) { Object value=value(row,key); return value instanceof Boolean b?b:value instanceof Number n?n.intValue()!=0:Boolean.parseBoolean(Objects.toString(value,"false")); }
    private boolean useful(String value) { return value != null && !value.isBlank(); }
    private String defaultText(String value, String fallback) { return useful(value) ? value.trim() : fallback; }
    private record Resolution(Map<String, Object> row, String source, String upstream,
                              String publisherCode, String comparisonKey) { }
}
