package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ModelPriceTierMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiApiBankCatalogService {
    public static final String SOURCE_CODE = "aiapibank";
    public static final String SOURCE_NAME = "AiAPIBank";
    public static final String CATALOG_URL = "https://aiapibank.com/api/v1/model-plaza";
    private static final BigDecimal PER_MILLION = new BigDecimal("1000000");
    private static final Map<Long, String> FIXED_SLUGS = Map.ofEntries(
            Map.entry(37L, "grok-low"), Map.entry(32L, "gpt-low"),
            Map.entry(18L, "gpt-plus-stable"), Map.entry(5L, "gpt-plus-pro"),
            Map.entry(4L, "gpt-pro"), Map.entry(2L, "claude-special"),
            Map.entry(65L, "claude-cursor"), Map.entry(6L, "claude-max20"),
            Map.entry(64L, "deepseek"), Map.entry(63L, "glm"), Map.entry(62L, "kimi"));
    private static final long IMAGE_1K_ID = -1001L;
    private static final long IMAGE_ALL_ID = -1002L;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ChannelMapper channelMapper;
    private final ModelMappingMapper mappingMapper;
    private final ModelPriceTierMapper tierMapper;
    private final ChannelSecretService secrets;
    @Autowired(required = false) private ModelIdentityService modelIdentityService;

    @Value("${aiapibank.enabled:true}") private boolean enabled;
    @Value("${aiapibank.base-url:https://aiapibank.com}") private String baseUrl;
    @Value("${aiapibank.sale-markup:1.10}") private BigDecimal saleMarkup;
    @Value("${aiapibank.request-timeout-seconds:30}") private int timeoutSeconds;

    @Scheduled(cron = "${aiapibank.sync-cron:0 20 3 * * *}", zone = "${aiapibank.sync-zone:Asia/Tokyo}")
    public void scheduledSync() {
        if (!enabled) return;
        try {
            SyncResult result = sync(false);
            log.info("AiAPIBank scheduled catalog sync completed: groups={}, models={}, errors={}",
                    result.groupsApplied(), result.modelsApplied(), result.errors().size());
        } catch (RuntimeException error) {
            log.error("AiAPIBank scheduled catalog sync failed without replacing the last valid catalog: {}",
                    safe(error));
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void syncAfterCredentialConfigured(AiApiBankCredentialConfiguredEvent event) {
        if (!enabled || event == null || event.channelId() == null) return;
        try {
            ChannelSyncResult result = syncChannel(event.channelId());
            log.info("AiAPIBank channel catalog auto-sync completed: channelId={}, group={}, models={}",
                    event.channelId(), result.groupSlug(), result.modelsApplied());
        } catch (RuntimeException error) {
            log.error("AiAPIBank channel catalog auto-sync failed: channelId={}, error={}",
                    event.channelId(), safe(error));
        }
    }

    /**
     * Repairs the first-run workflow after a deployment. The initial catalog
     * import creates disabled group placeholders; administrators then attach
     * their encrypted keys. Only configured groups that still have no valid
     * catalog are synchronized here, so normal restarts do not refresh all
     * AiAPIBank routes repeatedly.
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncConfiguredPendingGroupsAfterStartup() {
        if (!enabled) return;
        List<Long> channelIds = jdbc.queryForList("""
                SELECT DISTINCT c.id FROM channels c
                JOIN aiapibank_provider_groups g ON g.channel_id=c.id OR (
                    g.group_slug=c.group_name AND LOWER(c.name) LIKE ? AND LOWER(c.base_url) LIKE ?
                )
                WHERE c.api_key IS NOT NULL AND c.api_key<>''
                  AND (g.id IS NULL OR g.credential_status='CREDENTIAL_MISSING'
                       OR g.sync_status<>'SUCCESS' OR COALESCE(g.model_count,0)=0)
                ORDER BY c.id
                """, Long.class, "aiapibank%", "%aiapibank.com%");
        if (channelIds.isEmpty()) return;
        List<GroupSnapshot> groups;
        try {
            groups = catalogGroups();
        } catch (RuntimeException error) {
            log.error("AiAPIBank pending channel startup sync could not load the catalog: {}", safe(error));
            return;
        }
        int modelsApplied = 0;
        int failures = 0;
        for (Long channelId : channelIds) {
            try {
                Channel channel = requireAiApiBankChannel(channelId);
                modelsApplied += syncChannel(channel, groups).modelsApplied();
            } catch (RuntimeException error) {
                failures++;
                log.error("AiAPIBank pending channel startup sync failed: channelId={}, error={}",
                        channelId, safe(error));
            }
        }
        log.info("AiAPIBank pending channel startup sync completed: channels={}, models={}, failures={}",
                channelIds.size(), modelsApplied, failures);
    }

    public ChannelSyncResult syncChannel(Long channelId) {
        if (!enabled) throw new IllegalStateException("AiAPIBank catalog synchronization is disabled");
        Channel channel = requireAiApiBankChannel(channelId);
        return syncChannel(channel, catalogGroups());
    }

    public SyncResult sync(boolean dryRun) {
        LocalDateTime started = LocalDateTime.now();
        Long runId = dryRun ? null : insertRun(started, false);
        List<String> errors = new ArrayList<>();
        int groupsApplied = 0;
        int modelsSeen = 0;
        int modelsApplied = 0;
        int credentialsMissing = 0;
        int disabledRoutes = 0;
        List<GroupSnapshot> groups;
        try {
            groups = catalogGroups();
        } catch (RuntimeException invalidCatalog) {
            if (runId != null) finishRun(runId, "FAILED", 0, 0, 0, 0, 0, 0,
                    List.of(safe(invalidCatalog)));
            throw invalidCatalog;
        }
        Set<Long> seenGroups = groups.stream().map(GroupSnapshot::externalId).collect(Collectors.toSet());
        for (GroupSnapshot group : groups) {
            Channel channel = findChannel(group.slug());
            boolean hasCredential = channel != null && channel.getApiKey() != null && !channel.getApiKey().isBlank();
            if (!hasCredential) {
                credentialsMissing++;
                if (!dryRun) applyCredentialMissing(group, channel);
                continue;
            }
            CredentialSnapshot credential;
            try {
                credential = inspectCredential(channel, group);
            } catch (RuntimeException groupFailure) {
                errors.add(group.slug() + ": " + safe(groupFailure));
                if (!dryRun) recordGroupFailure(group, safe(groupFailure));
                continue;
            }
            List<JsonNode> available = availableModels(group, credential.modelNames());
            if (available.isEmpty()) {
                errors.add(group.slug() + ": /v1/models 未返回该分组可同步模型");
                if (!dryRun) recordGroupFailure(group, "模型校验结果为空，已保留上一份有效目录");
                continue;
            }
            modelsSeen += available.size();
            if (!dryRun) {
                ApplyResult applied = transactions.execute(status -> applyGroup(group, channel, credential, available));
                if (applied != null) {
                    groupsApplied++;
                    modelsApplied += applied.modelsApplied();
                    disabledRoutes += applied.disabledRoutes();
                }
            }
        }
        if (!dryRun) disabledRoutes += markMissingGroups(seenGroups);
        SyncResult result = new SyncResult(dryRun, groups.size(), groupsApplied, modelsSeen, modelsApplied,
                credentialsMissing, disabledRoutes, List.copyOf(errors), started, LocalDateTime.now());
        if (runId != null) finishRun(runId, errors.isEmpty() ? "SUCCESS" : "PARTIAL",
                groups.size(), groupsApplied, modelsSeen, modelsApplied, credentialsMissing, disabledRoutes, errors);
        return result;
    }

    private List<GroupSnapshot> catalogGroups() {
        List<GroupSnapshot> groups = new ArrayList<>(parseCatalog(fetchCatalog()));
        groups.add(imageGroup(IMAGE_1K_ID, "gpt-image2-1k",
                "GPT-Image-2 分组 特价0.05一张（只支持1K）", "生图1K分组"));
        groups.add(imageGroup(IMAGE_ALL_ID, "image2-all-res",
                "Image-2 生图1K/2K/4K", "3种分辨率全支持 1K/2K-0.1一张 4K-0.2一张"));
        return groups;
    }

    private Channel requireAiApiBankChannel(Long channelId) {
        Channel channel = channelId == null ? null : channelMapper.selectById(channelId);
        if (channel == null) throw new IllegalArgumentException("AiAPIBank channel not found");
        Integer groupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE channel_id=?", Integer.class, channelId);
        if (!SOURCE_CODE.equalsIgnoreCase(channel.getSourceCode())
                && (groupCount == null || groupCount == 0)
                && !isLegacyAiApiBankChannel(channel)) {
            throw new IllegalArgumentException("Channel does not belong to AiAPIBank");
        }
        if (channel.getApiKey() == null || channel.getApiKey().isBlank()) {
            throw new IllegalStateException("AiAPIBank channel credential is missing");
        }
        return channel;
    }

    private ChannelSyncResult syncChannel(Channel channel, List<GroupSnapshot> groups) {
        String slug = Objects.toString(channel.getGroupName(), "").trim();
        GroupSnapshot group = groups.stream().filter(candidate -> candidate.slug().equals(slug))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "AiAPIBank catalog no longer contains group " + slug));
        CredentialSnapshot credential;
        try {
            credential = inspectCredential(channel, group);
        } catch (RuntimeException error) {
            recordGroupFailure(group, safe(error));
            throw error;
        }
        List<JsonNode> available = availableModels(group, credential.modelNames());
        if (available.isEmpty()) {
            String message = "/v1/models 未返回该分组可同步模型";
            recordGroupFailure(group, message);
            throw new IllegalStateException(message);
        }
        ApplyResult applied = transactions.execute(status -> applyGroup(group, channel, credential, available));
        if (applied == null) throw new IllegalStateException("AiAPIBank channel synchronization did not commit");
        return new ChannelSyncResult(channel.getId(), group.slug(), available.size(),
                applied.modelsApplied(), applied.disabledRoutes(), LocalDateTime.now());
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> groups = jdbc.queryForList("""
                SELECT g.external_group_id,g.group_slug,g.group_name,g.platform,g.base_rate_multiplier,
                       g.group_rate_multiplier,g.user_rate_multiplier,g.resolved_rate_multiplier,
                       g.peak_rate_enabled,g.peak_start,g.peak_end,g.peak_rate_multiplier,g.billing_timezone,
                       g.credential_status,g.sync_status,g.model_count,g.missing_sync_count,g.last_error,
                       g.last_synced_at,c.id channel_id,c.enabled channel_enabled,c.health_status
                FROM aiapibank_provider_groups g LEFT JOIN channels c ON c.id=g.channel_id
                ORDER BY g.external_group_id
                """);
        result.put("channel", SOURCE_NAME);
        result.put("saleMarkup", saleMarkup);
        result.put("groups", groups);
        result.put("groupCount", groups.size());
        result.put("credentialsMissing", groups.stream().filter(row ->
                "CREDENTIAL_MISSING".equalsIgnoreCase(Objects.toString(value(row, "credential_status"), ""))).count());
        result.put("disabledModels", jdbc.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_model_offers WHERE enabled=FALSE", Long.class));
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT * FROM aiapibank_sync_runs ORDER BY id DESC LIMIT 1");
        result.put("lastRun", runs.isEmpty() ? null : runs.get(0));
        return result;
    }

    JsonNode fetchCatalog() {
        return webClient.get().uri(catalogUrl()).retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(Math.max(3, timeoutSeconds)));
    }

    private String catalogUrl() {
        String root = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return root.endsWith("/api/v1/model-plaza") ? root : root + "/api/v1/model-plaza";
    }

    List<GroupSnapshot> parseCatalog(JsonNode root) {
        if (root == null || root.isNull()) throw new IllegalStateException("AiAPIBank 模型广场响应为空");
        if (root.has("code") && root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("AiAPIBank 模型广场返回错误: " + root.path("message").asText("unknown"));
        }
        JsonNode groups = root.path("data").path("groups");
        if (!groups.isArray() || groups.isEmpty()) {
            throw new IllegalStateException("AiAPIBank 模型广场没有有效分组；拒绝覆盖现有目录");
        }
        List<GroupSnapshot> result = new ArrayList<>();
        for (JsonNode node : groups) {
            long id = node.path("id").asLong(Long.MIN_VALUE);
            String name = node.path("name").asText("").trim();
            String platform = node.path("platform").asText("").trim().toLowerCase(Locale.ROOT);
            if (id == Long.MIN_VALUE || name.isBlank() || platform.isBlank() || !node.path("models").isArray()) {
                throw new IllegalStateException("AiAPIBank 分组字段不完整；拒绝覆盖现有目录");
            }
            result.add(new GroupSnapshot(id, groupSlug(id), name,
                    node.path("description").asText(""), platform,
                    node.path("subscription_type").asText("standard"), decimal(node, "rate_multiplier", BigDecimal.ONE),
                    node.path("peak_rate_enabled").asBoolean(false), node.path("peak_start").asText(""),
                    node.path("peak_end").asText(""), decimal(node, "peak_rate_multiplier", BigDecimal.ONE),
                    node.path("is_exclusive").asBoolean(false), node.path("image_rate_independent").asBoolean(false),
                    decimal(node, "image_rate_multiplier", BigDecimal.ONE),
                    node.path("long_context_pricing_enabled").asBoolean(false), false,
                    copyModels(node.path("models")), node.deepCopy()));
        }
        return result;
    }

    private List<JsonNode> copyModels(JsonNode models) {
        List<JsonNode> result = new ArrayList<>();
        models.forEach(model -> result.add(model.deepCopy()));
        return result;
    }

    private GroupSnapshot imageGroup(long id, String slug, String name, String description) {
        return new GroupSnapshot(id, slug, name, description, "openai", "standard", BigDecimal.ONE,
                false, "", "", BigDecimal.ONE, true, true, BigDecimal.ONE,
                false, true, List.of(), JsonNodeFactory.instance.objectNode());
    }

    private CredentialSnapshot inspectCredential(Channel stored, GroupSnapshot group) {
        String key = secrets.decrypt(stored.getApiKey());
        JsonNode models = authorizedGet(key, "/v1/models");
        JsonNode rows = models.path("data");
        if (!rows.isArray() || rows.isEmpty()) throw new IllegalStateException("模型校验响应为空");
        Set<String> names = new HashSet<>();
        rows.forEach(row -> {
            String name = row.path("id").asText(row.path("name").asText("")).trim();
            if (!name.isBlank()) names.add(name);
        });
        if (names.isEmpty()) throw new IllegalStateException("模型校验响应不含模型 ID");
        JsonNode billing = authorizedGet(key, "/v1/sub2api/billing");
        JsonNode data = billing.has("data") ? billing.path("data") : billing;
        BigDecimal groupRate = decimal(data, "group_rate_multiplier", group.baseRate());
        BigDecimal userRate = decimal(data, "user_rate_multiplier", BigDecimal.ONE);
        BigDecimal resolved = decimal(data, "resolved_rate_multiplier", groupRate.multiply(userRate));
        return new CredentialSnapshot(Set.copyOf(names), positive(groupRate, group.baseRate()),
                positive(userRate, BigDecimal.ONE), positive(resolved, group.baseRate()),
                data.path("peak_rate_enabled").asBoolean(group.peakEnabled()),
                data.path("peak_start").asText(group.peakStart()), data.path("peak_end").asText(group.peakEnd()),
                positive(decimal(data, "peak_rate_multiplier", group.peakRate()), group.peakRate()),
                data.path("timezone").asText(data.path("billing_timezone").asText("Asia/Shanghai")),
                billing.deepCopy());
    }

    private JsonNode authorizedGet(String key, String path) {
        String normalizedBase = baseUrl.replaceAll("/+$", "");
        return webClient.get().uri(normalizedBase + path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(Math.max(3, timeoutSeconds)));
    }

    private List<JsonNode> availableModels(GroupSnapshot group, Set<String> actual) {
        if (group.imageOnly()) {
            List<String> imageNames = actual.stream().filter(name -> name.toLowerCase(Locale.ROOT).contains("image"))
                    .sorted().toList();
            if (imageNames.isEmpty() && actual.contains("gpt-image-2")) imageNames = List.of("gpt-image-2");
            List<JsonNode> nodes = new ArrayList<>();
            for (String name : imageNames) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("name", name); node.put("platform", "openai");
                node.putObject("pricing").put("billing_mode", "request");
                node.set("official_pricing", objectMapper.createObjectNode());
                nodes.add(node);
            }
            return nodes;
        }
        return group.models().stream().filter(model -> actual.contains(model.path("name").asText())).toList();
    }

    private ApplyResult applyGroup(GroupSnapshot group, Channel existing, CredentialSnapshot credential,
                                   List<JsonNode> available) {
        Channel channel = upsertChannel(group, existing, available);
        upsertPublicAlias(channel.getId());
        long groupId = upsertGroup(group, channel.getId(), credential, available.size(), "SUCCESS", null);
        Set<String> seen = new HashSet<>();
        int applied = 0;
        for (JsonNode model : available) {
            String upstream = model.path("name").asText("").trim();
            if (upstream.isBlank()) continue;
            seen.add(upstream);
            upsertOfferAndMapping(groupId, group, channel, credential, model);
            applied++;
        }
        int disabled = incrementMissingOffers(groupId, seen);
        return new ApplyResult(applied, disabled);
    }

    private Channel upsertChannel(GroupSnapshot group, Channel existing, List<JsonNode> models) {
        Channel channel = existing == null ? new Channel() : existing;
        channel.setName(SOURCE_NAME + " · " + group.name());
        channel.setType("anthropic".equals(group.platform()) ? "anthropic" : "openai-compatible");
        channel.setSourceCode(SOURCE_CODE);
        channel.setSourceName(SOURCE_NAME);
        channel.setProtocolType(group.imageOnly() ? "images" : "anthropic".equals(group.platform())
                ? "anthropic-messages" : "openai-compatible");
        channel.setBaseUrl(baseUrl.replaceAll("/+$", ""));
        channel.setModels(models.stream().map(model -> model.path("name").asText()).filter(v -> !v.isBlank())
                .distinct().collect(Collectors.joining(",")));
        channel.setGroupName(group.slug());
        channel.setEnabled(true);
        channel.setManaged(false);
        channel.setWeight(100);
        channel.setHealthStatus("HEALTHY");
        channel.setLastError(null);
        channel.setLastTestedAt(LocalDateTime.now());
        channel.setLastSuccessAt(LocalDateTime.now());
        if (existing == null) {
            channel.setAutoDisable(true); channel.setFailureThreshold(3); channel.setCooldownSeconds(60);
            channelMapper.insert(channel);
        } else channelMapper.updateById(channel);
        return channel;
    }

    private void upsertPublicAlias(Long channelId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_display_mappings WHERE channel_id=?",
                Integer.class, channelId);
        if (count != null && count > 0) {
            jdbc.update("UPDATE upstream_display_mappings SET public_code=?,public_name=?,badge_text=?,badge_color=?,enabled=TRUE,updated_at=? WHERE channel_id=?",
                    SOURCE_CODE, SOURCE_NAME, SOURCE_NAME, "#6d5dfc", LocalDateTime.now(), channelId);
        } else {
            jdbc.update("INSERT INTO upstream_display_mappings(channel_id,public_code,public_name,badge_text,badge_color,sort_order,enabled,created_at,updated_at) VALUES (?,?,?,?,?,10,TRUE,?,?)",
                    channelId, SOURCE_CODE, SOURCE_NAME, SOURCE_NAME, "#6d5dfc", LocalDateTime.now(), LocalDateTime.now());
        }
    }

    private long upsertGroup(GroupSnapshot group, Long channelId, CredentialSnapshot credential, int modelCount,
                             String status, String error) {
        int updated = jdbc.update("""
                UPDATE aiapibank_provider_groups SET channel_id=?,group_slug=?,group_name=?,description=?,platform=?,
                subscription_type=?,base_rate_multiplier=?,group_rate_multiplier=?,user_rate_multiplier=?,
                resolved_rate_multiplier=?,peak_rate_enabled=?,peak_start=?,peak_end=?,peak_rate_multiplier=?,
                billing_timezone=?,exclusive_group=?,image_rate_independent=?,image_rate_multiplier=?,
                long_context_pricing_enabled=?,credential_status=?,sync_status=?,model_count=?,
                missing_sync_count=0,last_error=?,raw_json=?,last_synced_at=?,updated_at=? WHERE external_group_id=?
                """, channelId, group.slug(), group.name(), group.description(), group.platform(), group.subscriptionType(),
                group.baseRate(), credential.groupRate(), credential.userRate(), credential.resolvedRate(),
                credential.peakEnabled(), credential.peakStart(), credential.peakEnd(), credential.peakRate(),
                credential.timezone(), group.exclusive(), group.imageRateIndependent(), group.imageRate(),
                group.longContext(), credentialStatus(status), status, modelCount, error, json(group.raw()), LocalDateTime.now(), LocalDateTime.now(),
                group.externalId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO aiapibank_provider_groups(external_group_id,channel_id,group_slug,group_name,description,
                    platform,subscription_type,base_rate_multiplier,group_rate_multiplier,user_rate_multiplier,
                    resolved_rate_multiplier,peak_rate_enabled,peak_start,peak_end,peak_rate_multiplier,billing_timezone,
                    exclusive_group,image_rate_independent,image_rate_multiplier,long_context_pricing_enabled,
                    credential_status,sync_status,model_count,raw_json,last_synced_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, group.externalId(), channelId, group.slug(), group.name(), group.description(), group.platform(),
                    group.subscriptionType(), group.baseRate(), credential.groupRate(), credential.userRate(),
                    credential.resolvedRate(), credential.peakEnabled(), credential.peakStart(), credential.peakEnd(),
                    credential.peakRate(), credential.timezone(), group.exclusive(), group.imageRateIndependent(),
                    group.imageRate(), group.longContext(), credentialStatus(status), status, modelCount, json(group.raw()), LocalDateTime.now(),
                    LocalDateTime.now(), LocalDateTime.now());
        }
        return jdbc.queryForObject("SELECT id FROM aiapibank_provider_groups WHERE external_group_id=?", Long.class,
                group.externalId());
    }

    private void upsertOfferAndMapping(long groupId, GroupSnapshot group, Channel channel,
                                       CredentialSnapshot credential, JsonNode model) {
        String upstream = model.path("name").asText().trim();
        String publicName = publicModelId(group.slug(), upstream);
        ModelMapping mapping = mappingMapper.selectOne(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getChannelId, channel.getId())
                .eq(ModelMapping::getChannelModelName, upstream).last("LIMIT 1"));
        if (mapping == null) mapping = new ModelMapping();
        JsonNode pricing = model.path("pricing");
        PriceSet primary = group.imageOnly() ? imagePrimary(group) : tokenPrices(pricing, credential.resolvedRate());
        mapping.setPublicModelName(publicName); mapping.setChannelModelName(upstream); mapping.setChannelId(channel.getId());
        mapping.setPriority(100); mapping.setEnabled(true); mapping.setBillingEnabled(true); mapping.setTrafficPercent(100);
        mapping.setPriceRatio(saleMarkup);
        mapping.setVendor(ModelIdentityService.publisherCode(group.platform(), upstream));
        mapping.setCapability(group.imageOnly() ? "image" : capability(upstream));
        mapping.setInputModalities(group.imageOnly() ? "text,image" : "text,image");
        mapping.setOutputModalities(group.imageOnly() ? "image" : "text");
        mapping.setProtocols(protocols(group, upstream));
        mapping.setPricingUnit(group.imageOnly() ? "IMAGE" : "TOKEN");
        mapping.setBillingMode("PAID"); mapping.setPricingStatus("VERIFIED");
        mapping.setPricingMessage("AiAPIBank 折后采购价 × " + saleMarkup.stripTrailingZeros().toPlainString());
        mapping.setPricingSourceUrl(CATALOG_URL); mapping.setPricingVerifiedAt(LocalDateTime.now());
        // Some upstream image models expose output pricing through
        // image_output_price while leaving output_price null. Treat that as
        // the billable output-token dimension so the model remains routable
        // through /v1/images/generations and the shared token ledger.
        BigDecimal outputCost = primary.costOutput().signum() > 0 ? primary.costOutput() : primary.costImageOutput();
        BigDecimal outputSale = primary.saleOutput().signum() > 0 ? primary.saleOutput() : primary.saleImageOutput();
        mapping.setInputCostPerMillion(primary.costInput()); mapping.setOutputCostPerMillion(outputCost);
        mapping.setCachedCostPerMillion(primary.costCacheRead()); mapping.setCostPerMillion(primary.costInput());
        mapping.setInputPricePerMillion(primary.saleInput()); mapping.setOutputPricePerMillion(outputSale);
        mapping.setCachedPricePerMillion(primary.saleCacheRead());
        mapping.setOfficialUnitPrice(primary.officialPerRequest()); mapping.setCostUnitPrice(primary.costPerRequest());
        mapping.setSaleUnitPrice(primary.salePerRequest());
        if (mapping.getId() == null) mappingMapper.insert(mapping); else mappingMapper.updateById(mapping);

        int updated = jdbc.update("""
                UPDATE aiapibank_model_offers SET model_mapping_id=?,public_model_name=?,platform=?,billing_mode=?,
                enabled=TRUE,missing_sync_count=0,pricing_json=?,official_pricing_json=?,raw_json=?,last_seen_at=?,
                last_synced_at=?,updated_at=? WHERE provider_group_id=? AND upstream_model_name=?
                """, mapping.getId(), publicName, model.path("platform").asText(group.platform()),
                pricing.path("billing_mode").asText(group.imageOnly() ? "request" : "token"), json(pricing),
                json(model.path("official_pricing")), json(model), LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), groupId, upstream);
        if (updated == 0) jdbc.update("""
                INSERT INTO aiapibank_model_offers(provider_group_id,model_mapping_id,upstream_model_name,
                public_model_name,platform,billing_mode,enabled,pricing_json,official_pricing_json,raw_json,
                last_seen_at,last_synced_at,created_at,updated_at) VALUES (?,?,?,?,?,?,TRUE,?,?,?,?,?,?,?)
                """, groupId, mapping.getId(), upstream, publicName, model.path("platform").asText(group.platform()),
                pricing.path("billing_mode").asText(group.imageOnly() ? "request" : "token"), json(pricing),
                json(model.path("official_pricing")), json(model), LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now());
        long offerId = jdbc.queryForObject("SELECT id FROM aiapibank_model_offers WHERE provider_group_id=? AND upstream_model_name=?",
                Long.class, groupId, upstream);
        if (group.imageOnly()) synchronizeImageVariants(offerId, group);
        synchronizeTiers(mapping, model, credential, group);
        if (modelIdentityService != null) {
            modelIdentityService.register(channel, mapping, group.platform(), ModelIdentityService.RANK_PROVIDER_CATALOG);
        }
    }

    private void synchronizeTiers(ModelMapping mapping, JsonNode model, CredentialSnapshot credential,
                                  GroupSnapshot group) {
        tierMapper.delete(new LambdaQueryWrapper<ModelPriceTier>().eq(ModelPriceTier::getModelMappingId, mapping.getId()));
        if (group.imageOnly()) return;
        JsonNode pricing = model.path("pricing");
        JsonNode official = model.path("official_pricing");
        JsonNode intervals = pricing.path("intervals");
        JsonNode officialIntervals = official.path("intervals");
        List<JsonNode> tiers = intervals.isArray() && !intervals.isEmpty() ? copyModels(intervals) : List.of(pricing);
        for (int i = 0; i < tiers.size(); i++) {
            JsonNode base = tiers.get(i);
            JsonNode officialBase = officialIntervals.isArray() && i < officialIntervals.size()
                    ? officialIntervals.get(i) : official;
            PriceSet prices = tokenPrices(base, credential.resolvedRate());
            ModelPriceTier tier = ModelPriceTier.builder().modelMappingId(mapping.getId())
                    .tierName(base.path("tier_label").asText(tiers.size() == 1 ? "默认挡位" : "挡位 " + (i + 1)))
                    .maxContextTokens(base.path("max_tokens").isNumber() ? base.path("max_tokens").asInt() : null)
                    .sortOrder(i).officialGroupName("AiAPIBank 官方参考价")
                    .officialInputPrice(perMillion(officialBase, "input_price"))
                    .officialOutputPrice(perMillion(officialBase, "output_price"))
                    .officialCacheReadPrice(perMillion(officialBase, "cache_read_price"))
                    .officialCacheWritePrice(perMillion(officialBase, "cache_write_price"))
                    .officialCacheWrite1hPrice(perMillion(officialBase, "cache_write_1h_price"))
                    .officialImageInputPrice(perMillion(officialBase, "image_input_price"))
                    .officialImageOutputPrice(perMillion(officialBase, "image_output_price"))
                    .officialPerRequestPrice(decimal(officialBase, "per_request_price", BigDecimal.ZERO))
                    .officialPriceUnit("M").officialPriceSuffix("USD / 1M Token")
                    .costGroupName("AiAPIBank 折后采购价")
                    .costInputPrice(prices.costInput()).costOutputPrice(prices.costOutput())
                    .costCacheReadPrice(prices.costCacheRead()).costCacheWritePrice(prices.costCacheWrite())
                    .costCacheWrite1hPrice(prices.costCacheWrite1h()).costImageInputPrice(prices.costImageInput())
                    .costImageOutputPrice(prices.costImageOutput()).costPerRequestPrice(prices.costPerRequest())
                    .costPriceUnit("M").costPriceSuffix("USD / 1M Token")
                    .saleGroupName("本站售价（成本 × " + saleMarkup.stripTrailingZeros().toPlainString() + "）")
                    .saleInputPrice(prices.saleInput()).saleOutputPrice(prices.saleOutput())
                    .saleCacheReadPrice(prices.saleCacheRead()).saleCacheWritePrice(prices.saleCacheWrite())
                    .saleCacheWrite1hPrice(prices.saleCacheWrite1h()).saleImageInputPrice(prices.saleImageInput())
                    .saleImageOutputPrice(prices.saleImageOutput()).salePerRequestPrice(prices.salePerRequest())
                    .salePriceUnit("M").salePriceSuffix("USD / 1M Token")
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
            tierMapper.insert(tier);
        }
    }

    private PriceSet tokenPrices(JsonNode pricing, BigDecimal multiplier) {
        BigDecimal costInput = money(perMillion(pricing, "input_price").multiply(multiplier));
        BigDecimal costOutput = money(perMillion(pricing, "output_price").multiply(multiplier));
        BigDecimal costRead = money(perMillion(pricing, "cache_read_price").multiply(multiplier));
        BigDecimal costWrite = money(perMillion(pricing, "cache_write_price").multiply(multiplier));
        BigDecimal costWrite1h = money(perMillion(pricing, "cache_write_1h_price").multiply(multiplier));
        BigDecimal costImageInput = money(perMillion(pricing, "image_input_price").multiply(multiplier));
        BigDecimal costImageOutput = money(perMillion(pricing, "image_output_price").multiply(multiplier));
        BigDecimal costRequest = money(decimal(pricing, "per_request_price", BigDecimal.ZERO).multiply(multiplier));
        return new PriceSet(BigDecimal.ZERO, costInput, costOutput, costRead, costWrite, costWrite1h,
                costImageInput, costImageOutput, costRequest, markup(costInput), markup(costOutput),
                markup(costRead), markup(costWrite), markup(costWrite1h), markup(costImageInput),
                markup(costImageOutput), markup(costRequest));
    }

    private PriceSet imagePrimary(GroupSnapshot group) {
        BigDecimal cost = group.externalId() == IMAGE_1K_ID ? new BigDecimal("0.05") : new BigDecimal("0.10");
        return new PriceSet(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cost, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, markup(cost));
    }

    private void synchronizeImageVariants(long offerId, GroupSnapshot group) {
        jdbc.update("DELETE FROM aiapibank_image_price_variants WHERE model_offer_id=?", offerId);
        if (group.externalId() == IMAGE_1K_ID) {
            insertVariant(offerId, "1K", 1024, new BigDecimal("0.05"));
        } else {
            insertVariant(offerId, "1K", 1024, new BigDecimal("0.10"));
            insertVariant(offerId, "2K", 2048, new BigDecimal("0.10"));
            insertVariant(offerId, "4K", 4096, new BigDecimal("0.20"));
        }
    }

    private void insertVariant(long offerId, String resolution, int maxEdge, BigDecimal cost) {
        jdbc.update("""
                INSERT INTO aiapibank_image_price_variants(model_offer_id,resolution_tier,max_edge_pixels,unit,
                official_unit_price,source_unit_price,sale_unit_price,created_at,updated_at)
                VALUES (?,?,?,'IMAGE',0,?,?,?,?)
                """, offerId, resolution, maxEdge, cost, markup(cost), LocalDateTime.now(), LocalDateTime.now());
    }

    private int incrementMissingOffers(long groupId, Set<String> seen) {
        List<Map<String, Object>> offers = jdbc.queryForList(
                "SELECT id,model_mapping_id,upstream_model_name,missing_sync_count FROM aiapibank_model_offers WHERE provider_group_id=?",
                groupId);
        int disabled = 0;
        for (Map<String, Object> offer : offers) {
            String name = Objects.toString(value(offer, "upstream_model_name"), "");
            if (seen.contains(name)) continue;
            int missing = ((Number) value(offer, "missing_sync_count")).intValue() + 1;
            boolean disable = missing >= 3;
            jdbc.update("UPDATE aiapibank_model_offers SET missing_sync_count=?,enabled=?,updated_at=? WHERE id=?",
                    missing, !disable, LocalDateTime.now(), value(offer, "id"));
            if (disable) {
                jdbc.update("UPDATE model_mappings SET enabled=FALSE,pricing_message=? WHERE id=?",
                        "AiAPIBank 连续三次同步未发现该模型", value(offer, "model_mapping_id"));
                disabled++;
            }
        }
        return disabled;
    }

    private int markMissingGroups(Set<Long> seenGroups) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,external_group_id,channel_id,missing_sync_count FROM aiapibank_provider_groups");
        int disabled = 0;
        for (Map<String, Object> row : rows) {
            long externalId = ((Number) value(row, "external_group_id")).longValue();
            if (seenGroups.contains(externalId)) continue;
            int missing = ((Number) value(row, "missing_sync_count")).intValue() + 1;
            jdbc.update("UPDATE aiapibank_provider_groups SET missing_sync_count=?,sync_status=?,updated_at=? WHERE id=?",
                    missing, missing >= 3 ? "DISABLED_MISSING" : "MISSING", LocalDateTime.now(), value(row, "id"));
            if (missing >= 3) {
                Long channelId = ((Number) value(row, "channel_id")).longValue();
                disabled += jdbc.update("UPDATE model_mappings SET enabled=FALSE WHERE channel_id=? AND enabled=TRUE", channelId);
                jdbc.update("UPDATE channels SET enabled=FALSE,health_status='DISABLED' WHERE id=?", channelId);
            }
        }
        return disabled;
    }

    private void applyCredentialMissing(GroupSnapshot group, Channel channel) {
        transactions.executeWithoutResult(status -> {
            Channel target = channel;
            if (target == null) {
                target = new Channel(); target.setName(SOURCE_NAME + " · " + group.name());
                target.setType("anthropic".equals(group.platform()) ? "anthropic" : "openai-compatible");
                target.setSourceCode(SOURCE_CODE); target.setSourceName(SOURCE_NAME);
                target.setProtocolType(group.imageOnly() ? "images" : "anthropic".equals(group.platform())
                        ? "anthropic-messages" : "openai-compatible");
                target.setBaseUrl(baseUrl.replaceAll("/+$", "")); target.setGroupName(group.slug());
                target.setModels(""); target.setEnabled(false); target.setManaged(false); target.setWeight(100);
                target.setHealthStatus("CREDENTIAL_MISSING"); target.setAutoDisable(true);
                target.setFailureThreshold(3); target.setCooldownSeconds(60); channelMapper.insert(target);
            } else {
                target.setEnabled(false); target.setHealthStatus("CREDENTIAL_MISSING"); channelMapper.updateById(target);
            }
            CredentialSnapshot empty = new CredentialSnapshot(Set.of(), group.baseRate(), BigDecimal.ONE,
                    group.baseRate(), group.peakEnabled(), group.peakStart(), group.peakEnd(), group.peakRate(),
                    "Asia/Shanghai", JsonNodeFactory.instance.objectNode());
            upsertGroup(group, target.getId(), empty, 0, "CREDENTIAL_MISSING", "分组渠道尚未配置加密 API Key");
        });
    }

    private void recordGroupFailure(GroupSnapshot group, String error) {
        jdbc.update("UPDATE aiapibank_provider_groups SET sync_status='ERROR',last_error=?,updated_at=? WHERE external_group_id=?",
                error, LocalDateTime.now(), group.externalId());
    }

    private String credentialStatus(String syncStatus) {
        return "CREDENTIAL_MISSING".equalsIgnoreCase(syncStatus) ? "CREDENTIAL_MISSING" : "READY";
    }

    private Channel findChannel(String slug) {
        List<Long> boundChannelIds = jdbc.queryForList("""
                SELECT channel_id FROM aiapibank_provider_groups
                WHERE group_slug=? AND channel_id IS NOT NULL
                ORDER BY id LIMIT 1
                """, Long.class, slug);
        Channel bound = null;
        if (!boundChannelIds.isEmpty()) {
            bound = channelMapper.selectById(boundChannelIds.get(0));
            if (bound != null && bound.getApiKey() != null && !bound.getApiKey().isBlank()) return bound;
        }
        List<Long> configuredChannelIds = jdbc.queryForList("""
                SELECT id FROM channels
                WHERE group_name=? AND api_key IS NOT NULL AND api_key<>''
                  AND LOWER(name) LIKE ? AND LOWER(base_url) LIKE ?
                ORDER BY id LIMIT 1
                """, Long.class, slug, "aiapibank%", "%aiapibank.com%");
        if (!configuredChannelIds.isEmpty()) {
            Channel configured = channelMapper.selectById(configuredChannelIds.get(0));
            if (configured != null) return configured;
        }
        if (bound != null) return bound;
        return channelMapper.selectOne(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getSourceCode, SOURCE_CODE).eq(Channel::getGroupName, slug).last("LIMIT 1"));
    }

    private boolean isLegacyAiApiBankChannel(Channel channel) {
        String name = Objects.toString(channel.getName(), "").toLowerCase(Locale.ROOT);
        String url = Objects.toString(channel.getBaseUrl(), "").toLowerCase(Locale.ROOT);
        if (!name.startsWith("aiapibank") || !url.contains("aiapibank.com")) return false;
        Integer groupCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aiapibank_provider_groups WHERE group_slug=?",
                Integer.class, channel.getGroupName());
        return groupCount != null && groupCount > 0;
    }

    public static String publicModelId(String slug, String upstream) {
        String safeSlug = Objects.requireNonNullElse(slug, "").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-").replaceAll("-+", "-").replaceAll("(^-|-$)", "");
        String safeModel = Objects.requireNonNullElse(upstream, "").trim().replaceAll("[^A-Za-z0-9._:/-]", "-");
        if (safeSlug.isBlank() || safeModel.isBlank()) throw new IllegalArgumentException("AiAPIBank group/model identifier is blank");
        return SOURCE_CODE + "/" + safeSlug + "/" + safeModel;
    }

    public static String groupSlug(long externalId) {
        if (externalId == IMAGE_1K_ID) return "gpt-image2-1k";
        if (externalId == IMAGE_ALL_ID) return "image2-all-res";
        return FIXED_SLUGS.getOrDefault(externalId, "group-" + externalId);
    }

    private String capability(String model) {
        String name = model.toLowerCase(Locale.ROOT);
        if (name.contains("image")) return "image";
        if (name.contains("embed")) return "embedding";
        if (name.contains("rerank")) return "rerank";
        return name.contains("reason") || name.contains("thinking") ? "reasoning" : "text";
    }

    private String protocols(GroupSnapshot group, String model) {
        if (group.imageOnly()) return "images";
        if ("anthropic".equals(group.platform())) return "messages,chat-completions,count-tokens";
        if (model.toLowerCase(Locale.ROOT).contains("image")) return "images,responses,chat-completions";
        return "chat-completions,responses";
    }

    private BigDecimal perMillion(JsonNode node, String field) {
        return money(decimal(node, field, BigDecimal.ZERO).multiply(PER_MILLION));
    }

    private BigDecimal markup(BigDecimal cost) {
        return salePrice(cost);
    }

    BigDecimal salePrice(BigDecimal cost) {
        return cost == null || cost.signum() == 0 ? BigDecimal.ZERO : money(cost.multiply(saleMarkup));
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isNumber() ? fallback : value.decimalValue();
    }

    private BigDecimal positive(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }

    private String json(JsonNode value) {
        return value == null || value.isMissingNode() ? null : value.toString();
    }

    private Object value(Map<String, Object> row, String key) {
        if (row.containsKey(key)) return row.get(key);
        for (Map.Entry<String, Object> entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        return null;
    }

    private String safe(Throwable error) {
        String text = Objects.toString(error == null ? null : error.getMessage(), error == null ? "unknown" : error.getClass().getSimpleName());
        return text.substring(0, Math.min(900, text.length()));
    }

    private Long insertRun(LocalDateTime started, boolean dryRun) {
        jdbc.update("INSERT INTO aiapibank_sync_runs(dry_run,status,started_at) VALUES (?,'RUNNING',?)", dryRun, started);
        return jdbc.queryForObject("SELECT MAX(id) FROM aiapibank_sync_runs", Long.class);
    }

    private void finishRun(Long id, String status, int groupsSeen, int groupsApplied, int modelsSeen,
                           int modelsApplied, int credentialsMissing, int disabledRoutes, List<String> errors) {
        try {
            jdbc.update("""
                    UPDATE aiapibank_sync_runs SET status=?,groups_seen=?,groups_applied=?,models_seen=?,models_applied=?,
                    credentials_missing=?,disabled_routes=?,error_count=?,summary_json=?,finished_at=? WHERE id=?
                    """, status, groupsSeen, groupsApplied, modelsSeen, modelsApplied, credentialsMissing,
                    disabledRoutes, errors.size(), objectMapper.writeValueAsString(Map.of("errors", errors)), LocalDateTime.now(), id);
        } catch (Exception failure) {
            log.warn("Unable to persist AiAPIBank sync result: {}", safe(failure));
        }
    }

    public record SyncResult(boolean dryRun, int groupsSeen, int groupsApplied, int modelsSeen, int modelsApplied,
                             int credentialsMissing, int disabledRoutes, List<String> errors,
                             LocalDateTime startedAt, LocalDateTime finishedAt) {}
    public record ChannelSyncResult(Long channelId, String groupSlug, int modelsSeen, int modelsApplied,
                                    int disabledRoutes, LocalDateTime finishedAt) {}
    record GroupSnapshot(long externalId, String slug, String name, String description, String platform,
                         String subscriptionType, BigDecimal baseRate, boolean peakEnabled, String peakStart,
                         String peakEnd, BigDecimal peakRate, boolean exclusive, boolean imageRateIndependent,
                         BigDecimal imageRate, boolean longContext, boolean imageOnly, List<JsonNode> models,
                         JsonNode raw) {}
    private record CredentialSnapshot(Set<String> modelNames, BigDecimal groupRate, BigDecimal userRate,
                                      BigDecimal resolvedRate, boolean peakEnabled, String peakStart, String peakEnd,
                                      BigDecimal peakRate, String timezone, JsonNode raw) {}
    private record PriceSet(BigDecimal officialPerRequest, BigDecimal costInput, BigDecimal costOutput,
                            BigDecimal costCacheRead, BigDecimal costCacheWrite, BigDecimal costCacheWrite1h,
                            BigDecimal costImageInput, BigDecimal costImageOutput, BigDecimal costPerRequest,
                            BigDecimal saleInput, BigDecimal saleOutput, BigDecimal saleCacheRead,
                            BigDecimal saleCacheWrite, BigDecimal saleCacheWrite1h, BigDecimal saleImageInput,
                            BigDecimal saleImageOutput, BigDecimal salePerRequest) {}
    private record ApplyResult(int modelsApplied, int disabledRoutes) {}
}
