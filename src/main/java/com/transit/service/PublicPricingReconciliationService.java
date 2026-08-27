package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PublicPricingReconciliationService {
    private static final String MANIFEST = "catalog/public-model-prices.yaml";

    private final ModelMappingMapper mappingMapper;
    private final ChannelMapper channelMapper;
    private final ModelPriceTierService tierService;

    @Transactional(readOnly = true)
    public ReconciliationReport preview() {
        return reconcile(false);
    }

    @Transactional
    public ReconciliationReport apply() {
        return reconcile(true);
    }

    private ReconciliationReport reconcile(boolean apply) {
        PriceManifest manifest = loadManifest();
        List<ModelMapping> mappings = mappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::isEnabled, true)
                .orderByAsc(ModelMapping::getPublicModelName)
                .orderByAsc(ModelMapping::getId));
        tierService.attach(mappings);

        Map<String, List<RoutePlan>> grouped = new LinkedHashMap<>();
        for (ModelMapping mapping : mappings) {
            Channel channel = channelMapper.selectById(mapping.getChannelId());
            if (channel == null || !channel.isEnabled() || channel.getApiKey() == null || channel.getApiKey().isBlank()) {
                continue;
            }
            RoutePlan plan = planRoute(mapping, channel, manifest);
            grouped.computeIfAbsent(mapping.getPublicModelName(), ignored -> new ArrayList<>()).add(plan);
        }

        List<ModelPriceReport> models = new ArrayList<>();
        int updatedRoutes = 0;
        int pausedRoutes = 0;
        for (Map.Entry<String, List<RoutePlan>> group : grouped.entrySet()) {
            List<RoutePlan> complete = group.getValue().stream().filter(RoutePlan::complete).toList();
            List<Integer> boundaries = mergedBoundaries(complete);
            List<UnifiedTier> unified = unify(complete, boundaries);

            List<RoutePriceReport> routes = new ArrayList<>();
            for (RoutePlan route : group.getValue()) {
                if ("NVIDIA".equals(route.provider())) {
                    updatedRoutes++;
                    if (apply) enableFreePreview(route.mapping());
                    routes.add(routeReport(route, List.of(), true));
                    continue;
                }
                if (!route.complete()) {
                    pausedRoutes++;
                    if (apply) pause(route.mapping());
                    routes.add(routeReport(route, List.of(), false));
                    continue;
                }
                List<ModelPriceTier> desired = desiredTiers(route, boundaries, unified);
                updatedRoutes++;
                if (apply) {
                    ModelMapping mapping = route.mapping();
                    mapping.setEnabled(true);
                    mapping.setBillingEnabled(true);
                    mapping.setBillingMode("PAID");
                    mapping.setPricingStatus("VERIFIED");
                    mapping.setPricingMessage("官网基准与采购成本已核验；本站售价为采购成本 +10%");
                    mapping.setPricingSourceUrl(route.source() != null && route.source().sourceUrl() != null
                            ? route.source().sourceUrl() : manifest.sourceUrl());
                    mapping.setPricingVerifiedAt(LocalDateTime.now());
                    if (route.source() != null && route.source().capability() != null) {
                        mapping.setCapability(route.source().capability());
                        mapping.setProtocols(route.source().protocols());
                    }
                    mappingMapper.updateById(mapping);
                    tierService.synchronize(mapping, desired);
                }
                routes.add(routeReport(route, unified, true));
            }
            models.add(new ModelPriceReport(group.getKey(), !complete.isEmpty(), unified, routes));
        }
        return new ReconciliationReport(apply ? "APPLIED" : "PREVIEW", manifest.verifiedAt(),
                manifest.sourceUrl(), manifest.currency(), manifest.exchangeRate(),
                models.size(), updatedRoutes, pausedRoutes, models);
    }

    private RoutePlan planRoute(ModelMapping mapping, Channel channel, PriceManifest manifest) {
        String provider = provider(channel);
        PriceSource source = manifest.models().get(mapping.getPublicModelName());
        List<ModelPriceTier> existing = existingTiers(mapping);
        String reason = null;
        List<RouteTier> routeTiers = new ArrayList<>();

        if (!"NVIDIA".equals(provider) && source == null) {
            reason = "未找到精确同名的官网基准";
        }

        for (ModelPriceTier tier : existing) {
            BigDecimal officialInput = perMillion(tier.getOfficialInputPrice(), tier.getOfficialPriceUnit());
            BigDecimal officialOutput = perMillion(tier.getOfficialOutputPrice(), tier.getOfficialPriceUnit());
            BigDecimal costInput = perMillion(tier.getCostInputPrice(), tier.getCostPriceUnit());
            BigDecimal costOutput = perMillion(tier.getCostOutputPrice(), tier.getCostPriceUnit());
            if (source != null) {
                officialInput = usd(source.officialInput(), manifest.exchangeRate());
                officialOutput = usd(source.officialOutput(), manifest.exchangeRate());
                if ("HAOEE".equals(provider) || "OTHER".equals(provider)) {
                    costInput = usd(source.costInput(), manifest.exchangeRate());
                    costOutput = usd(source.costOutput(), manifest.exchangeRate());
                }
            }
            BigDecimal costCacheRead = perMillion(tier.getCostCacheReadPrice(), tier.getCostPriceUnit());
            BigDecimal costCacheWrite = perMillion(tier.getCostCacheWritePrice(), tier.getCostPriceUnit());
            BigDecimal officialCacheRead = perMillion(tier.getOfficialCacheReadPrice(), tier.getOfficialPriceUnit());
            BigDecimal officialCacheWrite = perMillion(tier.getOfficialCacheWritePrice(), tier.getOfficialPriceUnit());
            routeTiers.add(new RouteTier(tier.getMaxContextTokens(), officialInput, officialOutput,
                    officialCacheRead, officialCacheWrite, costInput, costOutput, costCacheRead, costCacheWrite));
        }

        if ("NVIDIA".equals(provider)) {
            return new RoutePlan(mapping, provider, source, routeTiers, true, null);
        }
        boolean inputOnly = source != null && source.inputOnly();
        if (reason == null && routeTiers.stream().anyMatch(tier -> !positive(tier.officialInput())
                || !positive(tier.costInput()) || (!inputOnly
                && (!positive(tier.officialOutput()) || !positive(tier.costOutput()))))) {
            reason = "官网基准或关键采购成本不完整";
        }
        return new RoutePlan(mapping, provider, source, routeTiers, reason == null, reason);
    }

    private List<ModelPriceTier> existingTiers(ModelMapping mapping) {
        if (mapping.getPriceTiers() != null && !mapping.getPriceTiers().isEmpty()) {
            return mapping.getPriceTiers();
        }
        return List.of(ModelPriceTier.builder().tierName("默认挡位").maxContextTokens(null).sortOrder(0)
                .officialGroupName("官网基准").officialInputPrice(BigDecimal.ZERO)
                .officialOutputPrice(BigDecimal.ZERO).officialCacheReadPrice(BigDecimal.ZERO)
                .officialCacheWritePrice(BigDecimal.ZERO).officialPriceUnit("M")
                .officialPriceSuffix("USD / 1M Token").costGroupName("采购成本")
                .costInputPrice(value(mapping.getInputCostPerMillion(), mapping.getCostPerMillion()))
                .costOutputPrice(value(mapping.getOutputCostPerMillion(), mapping.getCostPerMillion()))
                .costCacheReadPrice(value(mapping.getCachedCostPerMillion(), BigDecimal.ZERO))
                .costCacheWritePrice(BigDecimal.ZERO).costPriceUnit("M").costPriceSuffix("USD / 1M Token")
                .saleGroupName("本站售价").saleInputPrice(value(mapping.getInputPricePerMillion(), BigDecimal.ZERO))
                .saleOutputPrice(value(mapping.getOutputPricePerMillion(), BigDecimal.ZERO))
                .saleCacheReadPrice(value(mapping.getCachedPricePerMillion(), BigDecimal.ZERO))
                .saleCacheWritePrice(BigDecimal.ZERO).salePriceUnit("M").salePriceSuffix("USD / 1M Token").build());
    }

    private List<Integer> mergedBoundaries(List<RoutePlan> complete) {
        Set<Integer> finite = new LinkedHashSet<>();
        complete.forEach(route -> route.tiers().stream().map(RouteTier::maxContextTokens)
                .filter(Objects::nonNull).forEach(finite::add));
        List<Integer> result = finite.stream().sorted().collect(Collectors.toCollection(ArrayList::new));
        result.add(null);
        return result;
    }

    private List<UnifiedTier> unify(List<RoutePlan> routes, List<Integer> boundaries) {
        List<UnifiedTier> result = new ArrayList<>();
        for (Integer boundary : boundaries) {
            List<RouteTier> tiers = routes.stream().map(route -> tierAt(route.tiers(), boundary)).toList();
            result.add(new UnifiedTier(boundary,
                    PublicPricingPolicy.saleFromCost(max(tiers, RouteTier::costInput)),
                    PublicPricingPolicy.saleFromCost(max(tiers, RouteTier::costOutput)),
                    PublicPricingPolicy.saleFromCost(max(tiers, RouteTier::costCacheRead)),
                    PublicPricingPolicy.saleFromCost(max(tiers, RouteTier::costCacheWrite))));
        }
        return result;
    }

    private List<ModelPriceTier> desiredTiers(RoutePlan route, List<Integer> boundaries, List<UnifiedTier> unified) {
        List<ModelPriceTier> result = new ArrayList<>();
        for (int index = 0; index < boundaries.size(); index++) {
            Integer boundary = boundaries.get(index);
            RouteTier source = tierAt(route.tiers(), boundary);
            UnifiedTier sale = unified.get(index);
            result.add(ModelPriceTier.builder().tierName(boundary == null ? "长上下文挡位" : "≤ " + boundary + " Token")
                    .maxContextTokens(boundary).sortOrder(index).officialGroupName("官网基准")
                    .officialInputPrice(source.officialInput()).officialOutputPrice(source.officialOutput())
                    .officialCacheReadPrice(source.officialCacheRead()).officialCacheWritePrice(source.officialCacheWrite())
                    .officialPriceUnit("M").officialPriceSuffix("USD / 1M Token").costGroupName("采购成本")
                    .costInputPrice(source.costInput()).costOutputPrice(source.costOutput())
                    .costCacheReadPrice(source.costCacheRead()).costCacheWritePrice(source.costCacheWrite())
                    .costPriceUnit("M").costPriceSuffix("USD / 1M Token").saleGroupName("本站售价（成本 +10%）")
                    .saleInputPrice(sale.input()).saleOutputPrice(sale.output())
                    .saleCacheReadPrice(sale.cacheRead()).saleCacheWritePrice(sale.cacheWrite())
                    .salePriceUnit("M").salePriceSuffix("USD / 1M Token")
                    .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        }
        return result;
    }

    private RouteTier tierAt(List<RouteTier> tiers, Integer boundary) {
        int point = boundary == null ? Integer.MAX_VALUE : boundary;
        return tiers.stream().sorted(Comparator.comparing(tier -> tier.maxContextTokens() == null
                        ? Integer.MAX_VALUE : tier.maxContextTokens()))
                .filter(tier -> tier.maxContextTokens() == null || point <= tier.maxContextTokens())
                .findFirst().orElse(tiers.get(tiers.size() - 1));
    }

    private BigDecimal max(List<RouteTier> tiers, java.util.function.Function<RouteTier, BigDecimal> getter) {
        return tiers.stream().map(getter).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private RoutePriceReport routeReport(RoutePlan route, List<UnifiedTier> unified, boolean published) {
        PriceSource source = route.source();
        return new RoutePriceReport(route.mapping().getId(), route.mapping().getChannelId(), route.provider(), published,
                route.reason(), source == null ? null : source.officialInput(), source == null ? null : source.officialOutput(),
                source == null ? null : source.costInput(), source == null ? null : source.costOutput(), unified);
    }

    private void pause(ModelMapping mapping) {
        mapping.setEnabled(false);
        mapping.setBillingEnabled(false);
        mapping.setBillingMode("DISABLED");
        mapping.setPricingStatus("PENDING");
        mapping.setPricingMessage("缺少可核验的关键采购价格");
        mappingMapper.updateById(mapping);
    }

    private void enableFreePreview(ModelMapping mapping) {
        mapping.setEnabled(true);
        mapping.setBillingEnabled(true);
        mapping.setBillingMode("FREE_PREVIEW");
        mapping.setPricingStatus("FREE_PREVIEW");
        mapping.setPricingMessage("免费开发预览 · 非生产服务，不承诺生产 SLA");
        mapping.setPricingSourceUrl("https://docs.api.nvidia.com/nim/docs/run-anywhere");
        mapping.setPricingVerifiedAt(LocalDateTime.now());
        mapping.setOfficialUnitPrice(BigDecimal.ZERO);
        mapping.setCostUnitPrice(BigDecimal.ZERO);
        mapping.setSaleUnitPrice(BigDecimal.ZERO);
        mapping.setInputPricePerMillion(BigDecimal.ZERO);
        mapping.setOutputPricePerMillion(BigDecimal.ZERO);
        mapping.setCachedPricePerMillion(BigDecimal.ZERO);
        mapping.setInputCostPerMillion(BigDecimal.ZERO);
        mapping.setOutputCostPerMillion(BigDecimal.ZERO);
        mapping.setCachedCostPerMillion(BigDecimal.ZERO);
        mappingMapper.updateById(mapping);
    }

    private String provider(Channel channel) {
        String baseUrl = Objects.toString(channel.getBaseUrl(), "").toLowerCase(Locale.ROOT);
        String source = Objects.toString(channel.getSourceCode(), "").toLowerCase(Locale.ROOT);
        if (baseUrl.contains("haoee.com") || source.equals("haoee")) return "HAOEE";
        if (baseUrl.contains("jwjapi")) return "JWJAPI";
        if (baseUrl.contains("nvidia.com") || source.equals("nvidia")) return "NVIDIA";
        return "OTHER";
    }

    private BigDecimal usd(BigDecimal cny, BigDecimal exchangeRate) {
        return value(cny, BigDecimal.ZERO).divide(exchangeRate, 12, RoundingMode.HALF_UP)
                .setScale(6, RoundingMode.CEILING);
    }

    private BigDecimal perMillion(BigDecimal amount, String unit) {
        BigDecimal normalized = value(amount, BigDecimal.ZERO);
        return "KB".equalsIgnoreCase(unit) ? normalized.multiply(BigDecimal.valueOf(1_000L)) : normalized;
    }

    private BigDecimal value(BigDecimal primary, BigDecimal fallback) {
        return primary != null ? primary : fallback != null ? fallback : BigDecimal.ZERO;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    @SuppressWarnings("unchecked")
    private PriceManifest loadManifest() {
        try (InputStream input = new ClassPathResource(MANIFEST).getInputStream()) {
            Map<String, Object> root = new Yaml().load(input);
            BigDecimal exchangeRate = decimal(root.get("exchangeRate"));
            Map<String, PriceSource> prices = new LinkedHashMap<>();
            for (Map<String, Object> row : (List<Map<String, Object>>) root.getOrDefault("models", List.of())) {
                PriceSource source = new PriceSource(Objects.toString(row.get("name"), "").trim(),
                        decimal(row.get("officialInput")), decimal(row.get("officialOutput")),
                        decimal(row.get("costInput")), decimal(row.get("costOutput")),
                        Boolean.parseBoolean(Objects.toString(row.get("inputOnly"), "false")),
                        nullable(row.get("capability")), nullable(row.get("protocols")),
                        nullable(row.get("sourceUrl")));
                if (source.name().isBlank() || prices.putIfAbsent(source.name(), source) != null) {
                    throw new IllegalStateException("Duplicate or empty public model price source");
                }
            }
            return new PriceManifest(Objects.toString(root.get("verifiedAt"), ""),
                    Objects.toString(root.get("sourceUrl"), ""), Objects.toString(root.get("currency"), "CNY"),
                    exchangeRate, prices);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to load public model price manifest", error);
        }
    }

    private BigDecimal decimal(Object value) {
        return new BigDecimal(Objects.toString(value, "0"));
    }

    private String nullable(Object value) {
        String text = Objects.toString(value, "").trim();
        return text.isEmpty() ? null : text;
    }

    private record PriceManifest(String verifiedAt, String sourceUrl, String currency,
                                 BigDecimal exchangeRate, Map<String, PriceSource> models) { }
    private record PriceSource(String name, BigDecimal officialInput, BigDecimal officialOutput,
                               BigDecimal costInput, BigDecimal costOutput, boolean inputOnly,
                               String capability, String protocols, String sourceUrl) { }
    private record RouteTier(Integer maxContextTokens, BigDecimal officialInput, BigDecimal officialOutput,
                             BigDecimal officialCacheRead, BigDecimal officialCacheWrite,
                             BigDecimal costInput, BigDecimal costOutput,
                             BigDecimal costCacheRead, BigDecimal costCacheWrite) { }
    private record RoutePlan(ModelMapping mapping, String provider, PriceSource source,
                             List<RouteTier> tiers, boolean complete, String reason) { }

    public record UnifiedTier(Integer maxContextTokens, BigDecimal input, BigDecimal output,
                              BigDecimal cacheRead, BigDecimal cacheWrite) { }
    public record RoutePriceReport(Long mappingId, Long channelId, String provider, boolean published,
                                   String reason, BigDecimal originalOfficialInputCny,
                                   BigDecimal originalOfficialOutputCny, BigDecimal originalCostInputCny,
                                   BigDecimal originalCostOutputCny, List<UnifiedTier> unifiedSaleUsd) { }
    public record ModelPriceReport(String model, boolean published, List<UnifiedTier> unifiedSaleUsd,
                                   List<RoutePriceReport> routes) { }
    public record ReconciliationReport(String mode, String verifiedAt, String sourceUrl, String sourceCurrency,
                                       BigDecimal exchangeRate, int modelCount, int updatedRouteCount,
                                       int pausedRouteCount, List<ModelPriceReport> models) { }
}
