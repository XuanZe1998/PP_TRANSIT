package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.dto.PageResponse;
import com.transit.dto.PublicModel;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ProviderModelMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ProviderModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Catalog of discovered provider models. Catalog visibility is deliberately separate from route availability. */
@Slf4j
@Service
@Order(3)
@RequiredArgsConstructor
public class ProviderModelCatalogService implements ApplicationRunner {
    public static final Set<String> STATUSES = Set.of(
            "DISCOVERED", "VERIFYING", "AVAILABLE", "FAILED", "UNSUPPORTED", "RETIRED");
    private static final String HAOEE_RESOURCE = "catalog/haoee-models.yaml";
    private static final String NVIDIA_RESOURCE = "catalog/nvidia-models.yaml";
    private static final String MODEL_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}";

    private final ProviderModelMapper providerModelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final ChannelMapper channelMapper;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService channelSecretService;
    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now();
        for (ModelMapping mapping : modelMappingMapper.selectList(null)) {
            Channel channel = channelMapper.selectById(mapping.getChannelId());
            if (channel == null || mapping.getChannelModelName() == null) continue;
            String source = Objects.toString(channel.getSourceCode(), channel.getType()).toLowerCase(Locale.ROOT);
            CatalogSeed seed = new CatalogSeed(mapping.getChannelModelName(), mapping.getVendor(),
                    mapping.getCapability(), mapping.getProtocols(), mapping.getPricingUnit(),
                    mapping.getEndpointPath(), mapping.getTaskQueryPath(), mapping.getTaskQueryMethod());
            upsert(source, Objects.toString(channel.getSourceName(), channel.getName()), channel.getId(), seed, now, null);
        }
    }

    @Transactional
    public int synchronizeHaoee(long channelId) {
        List<CatalogSeed> seeds = loadHaoeeManifest();
        Channel channel = channelMapper.selectById(channelId);
        boolean initializeChannelSelection = channel != null && channel.getModels() == null;
        LocalDateTime now = LocalDateTime.now();
        for (CatalogSeed seed : seeds) {
            upsert("haoee", "好易智算", channelId, seed, now, null);
        }
        markMissing("haoee", seeds.stream().map(CatalogSeed::name).collect(java.util.stream.Collectors.toSet()), now);
        if (initializeChannelSelection) {
            List<String> routedModels = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                            .eq(ModelMapping::getChannelId, channelId)
                            .orderByAsc(ModelMapping::getChannelModelName))
                    .stream().map(ModelMapping::getChannelModelName)
                    .filter(Objects::nonNull).distinct().toList();
            jdbcTemplate.update("UPDATE channels SET models=? WHERE id=?",
                    String.join("\n", routedModels), channelId);
        }
        log.info("Haoee provider catalog synchronized from versioned manifest: {} models", seeds.size());
        return seeds.size();
    }

    public int synchronizeConfiguredHaoee() {
        Channel channel = managedChannel("haoee");
        if (channel == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "Haoee channel is not configured");
        return synchronizeHaoee(channel.getId());
    }

    /**
     * Seeds a first deployment from the last reviewed NVIDIA response. It never marks rows
     * available and never replaces the live /v1/models synchronization as the authority.
     */
    @Transactional
    public int ensureNvidiaBootstrapSnapshot(long channelId) {
        List<CatalogSeed> seeds = loadNvidiaSnapshot();
        long existingCount = providerModelMapper.selectCount(new LambdaQueryWrapper<ProviderModel>()
                .eq(ProviderModel::getSourceCode, "nvidia"));
        if (existingCount >= seeds.size()) return Math.toIntExact(existingCount);
        LocalDateTime now = LocalDateTime.now();
        for (CatalogSeed seed : seeds) {
            upsert("nvidia", "NVIDIA", channelId, seed, now, "{\"bootstrapSnapshot\":true}");
        }
        log.info("NVIDIA bootstrap catalog seeded from versioned snapshot: {} models", seeds.size());
        return seeds.size();
    }

    @Transactional
    public void invalidateSourceAvailability(String source, String message) {
        for (ProviderModel model : listBySource(source)) {
            if (!"AVAILABLE".equals(model.getVerificationStatus())) continue;
            model.setVerificationStatus("DISCOVERED");
            model.setVerificationMessage(limit(message, 1000));
            model.setUpdatedAt(LocalDateTime.now());
            providerModelMapper.updateById(model);
        }
    }

    @Transactional
    public List<ProviderModel> synchronizeNvidia(long channelId, String plainApiKey) {
        JsonNode payload = webClient.get().uri("https://integrate.api.nvidia.com/v1/models")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + plainApiKey)
                .retrieve().bodyToMono(JsonNode.class).timeout(Duration.ofSeconds(30)).block();
        if (payload == null || !payload.path("data").isArray()) {
            throw new IllegalStateException("NVIDIA returned an invalid model catalog");
        }
        List<CatalogSeed> seeds = new ArrayList<>();
        Map<String, String> rawMetadata = new LinkedHashMap<>();
        payload.path("data").forEach(node -> {
            String id = node.path("id").asText("").trim();
            if (!id.matches(MODEL_PATTERN)) return;
            seeds.add(inferNvidia(id));
            rawMetadata.put(id, node.toString());
        });
        if (seeds.isEmpty()) throw new IllegalStateException("NVIDIA returned an empty model catalog");
        seeds.sort(Comparator.comparing(CatalogSeed::name));
        LocalDateTime now = LocalDateTime.now();
        for (CatalogSeed seed : seeds) {
            upsert("nvidia", "NVIDIA", channelId, seed, now, rawMetadata.get(seed.name()));
        }
        markMissing("nvidia", seeds.stream().map(CatalogSeed::name).collect(java.util.stream.Collectors.toSet()), now);
        log.info("NVIDIA provider catalog synchronized from /v1/models: {} models", seeds.size());
        return listBySource("nvidia");
    }

    /** Scheduled refresh is fail-safe: a provider failure never retires the cached catalog. */
    @Scheduled(cron = "${nvidia.catalog-sync-cron:0 17 */6 * * *}")
    public void scheduledNvidiaSync() {
        Channel channel = managedChannel("nvidia");
        if (channel == null || !channel.isEnabled()) return;
        try {
            channelSecretService.reveal(channel);
            synchronizeNvidia(channel.getId(), channel.getApiKey());
        } catch (RuntimeException error) {
            log.warn("NVIDIA catalog refresh failed; retained previous catalog: {}", error.getMessage());
        }
    }

    public List<ProviderModel> list(String source, String status) {
        LambdaQueryWrapper<ProviderModel> query = new LambdaQueryWrapper<ProviderModel>()
                .orderByAsc(ProviderModel::getSourceCode)
                .orderByAsc(ProviderModel::getPublicModelName);
        if (source != null && !source.isBlank()) query.eq(ProviderModel::getSourceCode, source.trim().toLowerCase(Locale.ROOT));
        if (status != null && !status.isBlank()) query.eq(ProviderModel::getVerificationStatus, normalizeStatus(status));
        return providerModelMapper.selectList(query);
    }

    public List<ProviderModel> listBySource(String source) {
        return list(source, null);
    }

    public List<Map<String, Object>> exclusions(String source) {
        if (source == null || source.isBlank()) {
            return jdbcTemplate.queryForList("""
                    SELECT id,source_code,upstream_model_name,public_model_name,reason,excluded_by,excluded_at
                    FROM provider_model_exclusions ORDER BY excluded_at DESC,id DESC
                    """);
        }
        return jdbcTemplate.queryForList("""
                SELECT id,source_code,upstream_model_name,public_model_name,reason,excluded_by,excluded_at
                FROM provider_model_exclusions WHERE source_code=? ORDER BY excluded_at DESC,id DESC
                """, source.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Removes failed catalog rows and their disabled routes while retaining a tombstone.
     * The tombstone is checked by every catalog synchronization, so deletion is durable.
     */
    @Transactional
    public int purgeFailed(String source, Long adminId) {
        List<ProviderModel> failed = list(source, "FAILED");
        for (ProviderModel model : failed) {
            jdbcTemplate.update("""
                    INSERT INTO provider_model_exclusions
                    (source_code,upstream_model_name,public_model_name,reason,excluded_by,excluded_at)
                    SELECT ?,?,?,?,?,? WHERE NOT EXISTS (
                        SELECT 1 FROM provider_model_exclusions WHERE source_code=? AND upstream_model_name=?
                    )
                    """, model.getSourceCode(), model.getUpstreamModelName(), model.getPublicModelName(),
                    limit(model.getVerificationMessage(), 500), adminId, LocalDateTime.now(),
                    model.getSourceCode(), model.getUpstreamModelName());
            List<Long> mappingIds = jdbcTemplate.queryForList("""
                    SELECT mm.id FROM model_mappings mm
                    INNER JOIN channels c ON c.id=mm.channel_id
                    WHERE LOWER(COALESCE(c.source_code,c.type))=? AND mm.channel_model_name=?
                    """, Long.class, model.getSourceCode().toLowerCase(Locale.ROOT), model.getUpstreamModelName());
            for (Long mappingId : mappingIds) {
                jdbcTemplate.update("DELETE FROM model_price_tiers WHERE model_mapping_id=?", mappingId);
                jdbcTemplate.update("DELETE FROM model_mappings WHERE id=?", mappingId);
            }
            jdbcTemplate.update("DELETE FROM provider_model_verifications WHERE provider_model_id=?", model.getId());
            providerModelMapper.deleteById(model.getId());
        }
        return failed.size();
    }

    @Transactional
    public Map<String, Object> restoreExclusion(long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id,source_code,upstream_model_name,public_model_name,reason,excluded_by,excluded_at
                FROM provider_model_exclusions WHERE id=?
                """, id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Provider model exclusion not found");
        jdbcTemplate.update("DELETE FROM provider_model_exclusions WHERE id=?", id);
        return rows.get(0);
    }

    public ProviderModel require(long id) {
        ProviderModel model = providerModelMapper.selectById(id);
        if (model == null) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Provider model not found");
        return model;
    }

    public Map<String, Long> statusCounts(String source) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String status : STATUSES) {
            long count = providerModelMapper.selectCount(new LambdaQueryWrapper<ProviderModel>()
                    .eq(source != null && !source.isBlank(), ProviderModel::getSourceCode, source)
                    .eq(ProviderModel::getVerificationStatus, status));
            result.put(status, count);
        }
        return result;
    }

    public PageResponse<PublicModel> publicCatalog(int page, int size, String query, String source,
                                                    String capability, String vendor, String availability) {
        String normalizedAvailability = availability == null ? "available" : availability.trim().toLowerCase(Locale.ROOT);
        List<ProviderModel> rows = providerModelMapper.selectList(new LambdaQueryWrapper<ProviderModel>()
                .orderByAsc(ProviderModel::getPublicModelName));
        Map<String, List<ProviderModel>> grouped = new LinkedHashMap<>();
        for (ProviderModel row : rows) {
            if (!matches(row, query, source, capability, vendor, normalizedAvailability)) continue;
            grouped.computeIfAbsent(row.getPublicModelName(), ignored -> new ArrayList<>()).add(row);
        }
        List<PublicModel> models = grouped.values().stream().map(this::toPublicModel).toList();
        int from = Math.min((page - 1) * size, models.size());
        int to = Math.min(from + size, models.size());
        PageResponse<PublicModel> response = new PageResponse<>();
        response.setPage(page);
        response.setSize(size);
        response.setTotal((long) models.size());
        response.setItems(models.subList(from, to));
        return response;
    }

    @Transactional
    public void beginVerification(long id) {
        ProviderModel model = require(id);
        model.setVerificationStatus("VERIFYING");
        model.setVerificationMessage("验证请求已进入队列");
        model.setUpdatedAt(LocalDateTime.now());
        providerModelMapper.updateById(model);
        jdbcTemplate.update("""
                INSERT INTO provider_model_verifications
                (provider_model_id,source_code,upstream_model_name,status,message,started_at)
                VALUES (?,?,?,?,?,?)
                """, model.getId(), model.getSourceCode(), model.getUpstreamModelName(),
                "VERIFYING", "验证请求已进入队列", LocalDateTime.now());
    }

    @Transactional
    public void completeVerification(long id, boolean success, String message) {
        ProviderModel model = require(id);
        model.setVerificationStatus(success ? "AVAILABLE" : "FAILED");
        model.setVerificationMessage(limit(message, 1000));
        model.setVerifiedAt(success ? LocalDateTime.now() : model.getVerifiedAt());
        model.setUpdatedAt(LocalDateTime.now());
        providerModelMapper.updateById(model);
        List<Long> attempts = jdbcTemplate.queryForList("""
                SELECT id FROM provider_model_verifications
                WHERE provider_model_id=? AND status='VERIFYING'
                ORDER BY id DESC LIMIT 1
                """, Long.class, id);
        if (attempts.isEmpty()) {
            jdbcTemplate.update("""
                    INSERT INTO provider_model_verifications
                    (provider_model_id,source_code,upstream_model_name,status,message,started_at,completed_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, model.getId(), model.getSourceCode(), model.getUpstreamModelName(),
                    model.getVerificationStatus(), model.getVerificationMessage(), LocalDateTime.now(), LocalDateTime.now());
        } else {
            jdbcTemplate.update("""
                    UPDATE provider_model_verifications
                    SET status=?,message=?,completed_at=? WHERE id=?
                    """, model.getVerificationStatus(), model.getVerificationMessage(), LocalDateTime.now(), attempts.get(0));
        }
        modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelModelName, model.getUpstreamModelName()))
                .stream().filter(mapping -> {
                    Channel channel = channelMapper.selectById(mapping.getChannelId());
                    return channel != null && model.getSourceCode().equalsIgnoreCase(channel.getSourceCode());
                }).forEach(mapping -> {
                    mapping.setEnabled(success);
                    modelMappingMapper.updateById(mapping);
                });
    }

    public List<Map<String, Object>> verificationHistory(Long modelId, String source, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        StringBuilder sql = new StringBuilder("""
                SELECT id,provider_model_id,source_code,upstream_model_name,status,message,started_at,completed_at
                FROM provider_model_verifications WHERE 1=1
                """);
        List<Object> parameters = new ArrayList<>();
        if (modelId != null) {
            sql.append(" AND provider_model_id=?");
            parameters.add(modelId);
        }
        if (source != null && !source.isBlank()) {
            sql.append(" AND source_code=?");
            parameters.add(source.trim().toLowerCase(Locale.ROOT));
        }
        sql.append(" ORDER BY id DESC LIMIT ").append(safeLimit);
        return jdbcTemplate.queryForList(sql.toString(), parameters.toArray());
    }

    private void upsert(String source, String sourceName, long channelId, CatalogSeed seed,
                        LocalDateTime now, String rawMetadata) {
        if (isExcluded(source, seed.name())) {
            log.debug("Skipped permanently excluded provider model: {}/{}", source, seed.name());
            return;
        }
        ProviderModel existing = providerModelMapper.selectOne(new LambdaQueryWrapper<ProviderModel>()
                .eq(ProviderModel::getSourceCode, source)
                .eq(ProviderModel::getUpstreamModelName, seed.name()).last("LIMIT 1"));
        boolean routeAvailable = hasEnabledRoute(channelId, seed.name());
        String status = routeAvailable ? "AVAILABLE" : existing == null || "RETIRED".equals(existing.getVerificationStatus())
                ? "DISCOVERED" : existing.getVerificationStatus();
        if (!supported(seed.protocols())) status = "UNSUPPORTED";
        if (existing == null) {
            existing = ProviderModel.builder().sourceCode(source).sourceName(sourceName)
                    .upstreamModelName(seed.name()).publicModelName(seed.name())
                    .createdAt(now).build();
        }
        existing.setSourceName(sourceName);
        existing.setPublicModelName(seed.name());
        existing.setVendor(seed.vendor());
        existing.setCapability(seed.capability());
        existing.setInputModalities(inputModalities(seed.capability()));
        existing.setOutputModalities(outputModalities(seed.capability()));
        existing.setProtocols(seed.protocols());
        existing.setPricingUnit(seed.pricingUnit());
        existing.setEndpointPath(seed.endpointPath());
        existing.setTaskQueryPath(seed.taskQueryPath());
        existing.setTaskQueryMethod(seed.taskQueryMethod());
        existing.setVerificationStatus(status);
        existing.setVerificationMessage("UNSUPPORTED".equals(status) ? "当前网关尚未实现该协议" : existing.getVerificationMessage());
        existing.setVerifiedAt(routeAvailable && existing.getVerifiedAt() == null ? now : existing.getVerifiedAt());
        existing.setLastSeenAt(now);
        existing.setMissingSyncCount(0);
        existing.setRawMetadata(rawMetadata);
        existing.setUpdatedAt(now);
        if (existing.getId() == null) providerModelMapper.insert(existing); else providerModelMapper.updateById(existing);
        if (supported(seed.protocols())) ensureMapping(channelId, seed, routeAvailable);
    }

    private boolean isExcluded(String source, String upstreamModelName) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM provider_model_exclusions
                WHERE source_code=? AND upstream_model_name=?
                """, Integer.class, source.toLowerCase(Locale.ROOT), upstreamModelName);
        return count != null && count > 0;
    }

    private void ensureMapping(long channelId, CatalogSeed seed, boolean enabled) {
        Channel channel = channelMapper.selectById(channelId);
        if (channel != null && channel.getModels() != null
                && !configuredModels(channel.getModels()).contains(seed.name())) {
            return;
        }
        ModelMapping mapping = modelMappingMapper.selectOne(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channelId)
                .eq(ModelMapping::getChannelModelName, seed.name()).last("LIMIT 1"));
        boolean freePreview = channel != null && "nvidia".equalsIgnoreCase(channel.getSourceCode());
        if (mapping != null) {
            mapping.setVendor(seed.vendor());
            mapping.setCapability(seed.capability());
            mapping.setInputModalities(inputModalities(seed.capability()));
            mapping.setOutputModalities(outputModalities(seed.capability()));
            mapping.setProtocols(seed.protocols());
            mapping.setPricingUnit(seed.pricingUnit());
            mapping.setEndpointPath(seed.endpointPath());
            mapping.setTaskQueryPath(seed.taskQueryPath());
            mapping.setTaskQueryMethod(seed.taskQueryMethod());
            if (freePreview && enabled) {
                mapping.setEnabled(true); mapping.setBillingEnabled(true); mapping.setBillingMode("FREE_PREVIEW");
                mapping.setPricingStatus("FREE_PREVIEW");
                mapping.setPricingMessage("免费开发预览 · 非生产服务，不承诺生产 SLA");
                mapping.setPricingSourceUrl("https://docs.api.nvidia.com/nim/docs/run-anywhere");
                mapping.setPricingVerifiedAt(now());
            }
            modelMappingMapper.updateById(mapping);
            return;
        }
        mapping = ModelMapping.builder().publicModelName(seed.name()).channelModelName(seed.name())
                .channelId(channelId).priority(100).enabled(enabled)
                .priceRatio(BigDecimal.ONE).costPerMillion(BigDecimal.ZERO)
                .inputPricePerMillion(BigDecimal.ZERO).outputPricePerMillion(BigDecimal.ZERO)
                .cachedPricePerMillion(BigDecimal.ZERO).inputCostPerMillion(BigDecimal.ZERO)
                .outputCostPerMillion(BigDecimal.ZERO).cachedCostPerMillion(BigDecimal.ZERO)
                .billingEnabled(freePreview && enabled).billingMode(freePreview && enabled ? "FREE_PREVIEW" : "DISABLED")
                .pricingStatus(freePreview && enabled ? "FREE_PREVIEW" : "PENDING")
                .pricingMessage(freePreview && enabled ? "免费开发预览 · 非生产服务，不承诺生产 SLA" : "缺少关键销售价格")
                .pricingSourceUrl(freePreview ? "https://docs.api.nvidia.com/nim/docs/run-anywhere" : null)
                .pricingVerifiedAt(freePreview && enabled ? java.time.LocalDateTime.now() : null)
                .officialUnitPrice(BigDecimal.ZERO).costUnitPrice(BigDecimal.ZERO).saleUnitPrice(BigDecimal.ZERO)
                .trafficPercent(100)
                .capabilityTags("catalog," + seed.capability()).vendor(seed.vendor())
                .capability(seed.capability()).inputModalities(inputModalities(seed.capability()))
                .outputModalities(outputModalities(seed.capability())).protocols(seed.protocols())
                .pricingUnit(seed.pricingUnit()).endpointPath(seed.endpointPath())
                .taskQueryPath(seed.taskQueryPath()).taskQueryMethod(seed.taskQueryMethod()).build();
        modelMappingMapper.insert(mapping);
    }

    private LocalDateTime now() { return LocalDateTime.now(); }

    private Set<String> configuredModels(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> models = new HashSet<>();
        for (String item : value.split("[,，、\\r\\n]+")) {
            String model = item.trim();
            if (!model.isBlank()) models.add(model);
        }
        return models;
    }

    private boolean hasEnabledRoute(long channelId, String model) {
        return modelMappingMapper.selectCount(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channelId).eq(ModelMapping::getChannelModelName, model)
                .eq(ModelMapping::isEnabled, true)) > 0;
    }

    private void markMissing(String source, Set<String> seen, LocalDateTime now) {
        for (ProviderModel model : listBySource(source)) {
            if (seen.contains(model.getUpstreamModelName())) continue;
            model.setMissingSyncCount(model.getMissingSyncCount() + 1);
            model.setUpdatedAt(now);
            if (model.getMissingSyncCount() >= 3) {
                model.setVerificationStatus("RETIRED");
                model.setVerificationMessage("连续三次未在上游目录中出现");
            }
            providerModelMapper.updateById(model);
        }
    }

    @SuppressWarnings("unchecked")
    private List<CatalogSeed> loadHaoeeManifest() {
        try (InputStream input = new ClassPathResource(HAOEE_RESOURCE).getInputStream()) {
            Map<String, Object> document = new Yaml().load(input);
            List<Map<String, Object>> models = (List<Map<String, Object>>) document.getOrDefault("models", List.of());
            List<CatalogSeed> result = new ArrayList<>();
            for (Map<String, Object> row : models) {
                String name = Objects.toString(row.get("name"), "").trim();
                if (!name.matches(MODEL_PATTERN)) throw new IllegalStateException("Invalid Haoee model name: " + name);
                result.add(new CatalogSeed(name, string(row, "vendor", "unknown"),
                        string(row, "capability", "text"), string(row, "protocols", "chat-completions"),
                        string(row, "pricingUnit", "TOKEN"), nullable(row.get("endpointPath")),
                        nullable(row.get("taskQueryPath")), string(row, "taskQueryMethod", "POST")));
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load Haoee catalog manifest", error);
        }
    }

    @SuppressWarnings("unchecked")
    private List<CatalogSeed> loadNvidiaSnapshot() {
        try (InputStream input = new ClassPathResource(NVIDIA_RESOURCE).getInputStream()) {
            Map<String, Object> document = new Yaml().load(input);
            List<Object> models = (List<Object>) document.getOrDefault("models", List.of());
            List<CatalogSeed> result = new ArrayList<>();
            for (Object value : models) {
                String name = Objects.toString(value, "").trim();
                if (!name.matches(MODEL_PATTERN)) {
                    throw new IllegalStateException("Invalid NVIDIA snapshot model name: " + name);
                }
                result.add(inferNvidia(name));
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load NVIDIA bootstrap catalog", error);
        }
    }

    private CatalogSeed inferNvidia(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        String capability = lower.contains("embed") || lower.contains("bge-") ? "embedding"
                : lower.contains("rerank") ? "rerank"
                : lower.contains("vision") || lower.contains("-vl") || lower.contains("vl-") ? "vision"
                : lower.contains("reason") || lower.contains("deepseek") || lower.contains("nemotron") ? "reasoning" : "text";
        String protocol = capability.equals("embedding") ? "embeddings" : capability.equals("rerank") ? "reranks" : "chat-completions";
        int slash = id.indexOf('/');
        return new CatalogSeed(id, slash > 0 ? id.substring(0, slash) : "nvidia", capability, protocol,
                "TOKEN", null, null, "POST");
    }

    private boolean supported(String protocols) {
        return List.of("chat-completions", "responses", "embeddings", "reranks", "images", "tasks",
                        "messages", "gemini", "audio-speech", "audio-transcriptions")
                .stream().anyMatch(protocols::contains);
    }

    private boolean matches(ProviderModel row, String query, String source, String capability,
                            String vendor, String availability) {
        if (query != null && !query.isBlank() && !row.getPublicModelName().toLowerCase(Locale.ROOT)
                .contains(query.trim().toLowerCase(Locale.ROOT))) return false;
        if (source != null && !source.isBlank()) {
            String requested = source.trim().toLowerCase(Locale.ROOT);
            if ("other".equals(requested)) {
                if (Set.of("haoee", "nvidia").contains(row.getSourceCode())) return false;
            } else if (!requested.equals(row.getSourceCode())) return false;
        }
        if (capability != null && !capability.isBlank() && !capability.equalsIgnoreCase(row.getCapability())) return false;
        if (vendor != null && !vendor.isBlank() && !vendor.equalsIgnoreCase(row.getVendor())) return false;
        boolean available = "AVAILABLE".equals(row.getVerificationStatus());
        return "all".equals(availability) || ("available".equals(availability) && available)
                || ("unavailable".equals(availability) && !available);
    }

    private PublicModel toPublicModel(List<ProviderModel> routes) {
        ProviderModel primary = routes.stream().filter(route -> "AVAILABLE".equals(route.getVerificationStatus()))
                .findFirst().orElse(routes.get(0));
        PublicModel result = new PublicModel();
        result.setPublicName(primary.getPublicModelName());
        result.setType(primary.getSourceCode());
        result.setSource(routes.size() > 1 ? "multi" : primary.getSourceCode());
        result.setSourceName(routes.size() > 1 ? "多个来源" : primary.getSourceName());
        result.setSources(routes.stream().map(ProviderModel::getSourceCode).distinct().sorted().collect(java.util.stream.Collectors.joining(",")));
        result.setVendor(primary.getVendor());
        result.setCapability(primary.getCapability());
        result.setInputModalities(primary.getInputModalities());
        result.setOutputModalities(primary.getOutputModalities());
        result.setProtocols(primary.getProtocols());
        result.setPricingUnit(primary.getPricingUnit());
        result.setAvailable(routes.stream().anyMatch(route -> "AVAILABLE".equals(route.getVerificationStatus())));
        result.setVerificationStatus(result.isAvailable() ? "AVAILABLE" : primary.getVerificationStatus());
        result.setVerificationMessage(primary.getVerificationMessage());
        result.setVerifiedAt(primary.getVerifiedAt());
        result.setLastSeenAt(routes.stream().map(ProviderModel::getLastSeenAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
        result.setRouteCount(routes.size());
        result.setProviderCount(routes.size());
        result.setMinInputPricePerMillion(BigDecimal.ZERO); result.setMaxInputPricePerMillion(BigDecimal.ZERO);
        result.setMinOutputPricePerMillion(BigDecimal.ZERO); result.setMaxOutputPricePerMillion(BigDecimal.ZERO);
        result.setMinCachedPricePerMillion(BigDecimal.ZERO); result.setMaxCachedPricePerMillion(BigDecimal.ZERO);
        result.setMinCacheReadPricePerMillion(BigDecimal.ZERO); result.setMaxCacheReadPricePerMillion(BigDecimal.ZERO);
        result.setMinCacheWritePricePerMillion(BigDecimal.ZERO); result.setMaxCacheWritePricePerMillion(BigDecimal.ZERO);
        return result;
    }

    private Channel managedChannel(String source) {
        return channelMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Channel>()
                .eq(Channel::getSourceCode, source).orderByAsc(Channel::getId).last("LIMIT 1"));
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid verification status");
        return normalized;
    }

    private String string(Map<String, Object> row, String key, String fallback) {
        String value = Objects.toString(row.get(key), "").trim();
        return value.isEmpty() ? fallback : value;
    }
    private String nullable(Object value) { String text = Objects.toString(value, "").trim(); return text.isEmpty() ? null : text; }
    private String limit(String value, int max) { if (value == null) return null; return value.substring(0, Math.min(max, value.length())); }
    private String inputModalities(String capability) { return Set.of("vision", "image", "video").contains(capability) ? "text,image" : capability.equals("transcription") ? "audio" : "text"; }
    private String outputModalities(String capability) { return switch (capability) { case "image", "video", "music", "speech" -> capability.equals("speech") ? "audio" : capability; case "embedding" -> "vector"; default -> "text"; }; }

    public record CatalogSeed(String name, String vendor, String capability, String protocols,
                              String pricingUnit, String endpointPath, String taskQueryPath,
                              String taskQueryMethod) { }
}
