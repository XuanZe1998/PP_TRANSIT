package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.dto.PublicModel;
import com.transit.dto.PublicModelComparison;
import com.transit.dto.PublicModelFacetOption;
import com.transit.dto.PublicModelPricing;
import com.transit.dto.MoneyAmount;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.OtherService;
import com.transit.service.OtherServiceCatalogService;
import com.transit.service.OtherServiceImageStorageService;
import com.transit.service.ModelContextPricingService;
import com.transit.service.PublicUpstreamMappingService;
import com.transit.service.AiApiBankPublicCatalogService;
import com.transit.service.PublicModelMarketplaceService;
import com.transit.service.PublicModelPresentationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.time.Duration;

@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicController {
    private static final String SITE_DESCRIPTION = "面向开发者与团队的一站式 AI 能力平台，统一连接主流模型，提供智能路由、用量治理、创作工具与企业协作。";
    private final ModelMappingMapper modelMappingMapper;
    private final OtherServiceCatalogService otherServiceCatalogService;
    private final OtherServiceImageStorageService otherServiceImageStorageService;
    private final PublicUpstreamMappingService publicUpstreamMappingService;
    private final ModelContextPricingService modelContextPricingService;
    private final AiApiBankPublicCatalogService aiApiBankPublicCatalogService;
    private final PublicModelPresentationService publicModelPresentationService;
    private final PublicModelMarketplaceService publicModelMarketplaceService;

    @Value("${billing.model-currency:USD}")
    private String billingCurrency;

    @Value("${billing.amount-scale:10000}")
    private long amountScale;

    @Value("${site.name:Linknux}")
    private String siteName;

    @Value("${site.description:" + SITE_DESCRIPTION + "}")
    private String siteDescription;

    @GetMapping("/site-config")
    public Mono<java.util.Map<String, String>> siteConfig() {
        return Mono.just(java.util.Map.of(
                "name", siteName,
                "description", siteDescription,
                "logoUrl", "/brand/linknux-mark-192.png",
                "faviconUrl", "/favicon.png"));
    }

    @GetMapping("/models")
    public Mono<PageResponse<PublicModel>> models(@RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                  @RequestParam(value = "size", required = false, defaultValue = "12") int size,
                                                  @RequestParam(value = "query", required = false) String query,
                                                  @RequestParam(value = "type", required = false) String type,
                                                  @RequestParam(value = "source", required = false) String source,
                                                  @RequestParam(value = "availability", required = false, defaultValue = "available") String availability,
                                                  @RequestParam(value = "capability", required = false) String capability,
                                                  @RequestParam(value = "vendor", required = false) String vendor,
                                                  @RequestParam(value = "routes", required = false) String routes,
                                                  @RequestParam(value = "publishers", required = false) String publishers,
                                                  @RequestParam(value = "categories", required = false) String categories,
                                                  @RequestParam(value = "capabilities", required = false) String capabilities,
                                                  @RequestParam(value = "inputModalities", required = false) String inputModalities,
                                                  @RequestParam(value = "outputModalities", required = false) String outputModalities,
                                                  @RequestParam(value = "protocols", required = false) String protocols,
                                                  @RequestParam(value = "pricingUnits", required = false) String pricingUnits,
                                                  @RequestParam(value = "plans", required = false) String plans,
                                                  @RequestParam(value = "priceStatuses", required = false) String priceStatuses,
                                                  @RequestParam(value = "sort", required = false, defaultValue = "priority") String sort) {
        validatePage(page, size, query);
        var filters = filters(query, first(routes, source, type), first(publishers, vendor), categories,
                first(capabilities, capability), inputModalities, outputModalities, protocols, pricingUnits, plans, priceStatuses);
        List<PublicModel> filtered = publicModelMarketplaceService.sort(
                publicModelMarketplaceService.filter(loadPublicModels(), filters), sort);
        int offset = Math.multiplyExact(page - 1, size);
        int from = Math.min(offset, filtered.size());
        int to = Math.min(from + size, filtered.size());
        PageResponse<PublicModel> response = new PageResponse<>();
        response.setPage(page); response.setSize(size); response.setTotal(filtered.size());
        response.setItems(filtered.subList(from, to));
        return Mono.just(response);
    }

    @GetMapping("/models/facets")
    public Mono<Map<String, List<PublicModelFacetOption>>> modelFacets(
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "routes", required = false) String routes,
            @RequestParam(value = "publishers", required = false) String publishers,
            @RequestParam(value = "categories", required = false) String categories,
            @RequestParam(value = "capabilities", required = false) String capabilities,
            @RequestParam(value = "inputModalities", required = false) String inputModalities,
            @RequestParam(value = "outputModalities", required = false) String outputModalities,
            @RequestParam(value = "protocols", required = false) String protocols,
            @RequestParam(value = "pricingUnits", required = false) String pricingUnits,
            @RequestParam(value = "plans", required = false) String plans,
            @RequestParam(value = "priceStatuses", required = false) String priceStatuses) {
        validateQuery(query);
        var filters = filters(query, routes, publishers, categories, capabilities, inputModalities,
                outputModalities, protocols, pricingUnits, plans, priceStatuses);
        return Mono.just(publicModelMarketplaceService.facets(loadPublicModels(), filters));
    }

    @GetMapping("/model-comparisons")
    public Mono<PublicModelComparison> modelComparison(@RequestParam("model") String publicName) {
        if (publicName == null || publicName.isBlank() || publicName.length() > 320) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model must be between 1 and 320 characters");
        }
        return Mono.just(publicModelMarketplaceService.comparison(loadPublicModels(), publicName.trim()));
    }

    @GetMapping("/other-services")
    public Flux<OtherService> otherServices() {
        return Flux.fromIterable(otherServiceCatalogService.listPublicServices());
    }

    @GetMapping("/other-services/{id}/redeem")
    public ResponseEntity<Void> redirectToCardRedemption(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(otherServiceCatalogService.requireRedemptionDestination(id))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .build();
    }

    @GetMapping("/other-service-images/{fileName:.+}")
    public ResponseEntity<Resource> otherServiceImage(@PathVariable String fileName) {
        OtherServiceImageStorageService.StoredImage image = otherServiceImageStorageService.load(fileName);
        return ResponseEntity.ok()
                .contentType(image.mediaType())
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(image.resource());
    }

    private boolean differs(java.math.BigDecimal left, java.math.BigDecimal right) {
        return left != null && right != null && left.compareTo(right) != 0;
    }

    private List<PublicModel> loadPublicModels() {
        List<PublicModel> models = modelMappingMapper.findPublicModels();
        if (models == null) models = List.of();
        publicUpstreamMappingService.sanitize(models);
        modelContextPricingService.enrich(models);
        aiApiBankPublicCatalogService.enrich(models);
        publicModelPresentationService.enrich(models);
        models.forEach(this::applyMoney);
        return models;
    }

    private PublicModelMarketplaceService.Filters filters(String query, String routes, String publishers,
            String categories, String capabilities, String inputModalities, String outputModalities,
            String protocols, String pricingUnits, String plans, String priceStatuses) {
        return PublicModelMarketplaceService.criteria(query, routes, publishers, categories, capabilities,
                inputModalities, outputModalities, protocols, pricingUnits, plans, priceStatuses);
    }

    private void validatePage(int page, int size, String query) {
        if (page < 1 || page > 1_000_000) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "page must be between 1 and 1000000");
        if (size < 1 || size > 100) throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        validateQuery(query);
    }

    private void validateQuery(String query) {
        if (query != null && query.trim().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is too long");
        }
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private void applyMoney(PublicModel item) {
        item.applyMoney(billingCurrency.toUpperCase(Locale.ROOT), Math.max(1, amountScale));
        String unit = item.getPricingUnit() == null ? "TOKEN" : item.getPricingUnit().toUpperCase(Locale.ROOT);
        item.setPriceUnit(unit.equals("TOKEN") ? "currency_per_1m_tokens" : "currency_per_" + unit.toLowerCase(Locale.ROOT));
        item.setPriceVariesByRoute(differs(item.getMinInputPricePerMillion(), item.getMaxInputPricePerMillion())
                || differs(item.getMinOutputPricePerMillion(), item.getMaxOutputPricePerMillion())
                || differs(item.getMinCachedPricePerMillion(), item.getMaxCachedPricePerMillion())
                || differs(item.getMinCacheWritePricePerMillion(), item.getMaxCacheWritePricePerMillion()));
        java.math.BigDecimal unitSale = item.getSaleUnitPrice() == null ? java.math.BigDecimal.ZERO : item.getSaleUnitPrice();
        String mode = item.getBillingMode() == null ? "PAID" : item.getBillingMode().toUpperCase(Locale.ROOT);
        String status = item.getPricingStatus() == null ? "PENDING" : item.getPricingStatus().toUpperCase(Locale.ROOT);
        item.setPricing(PublicModelPricing.builder()
                .billingMode(mode).status(status).message(item.getPricingMessage())
                .sourceUrl(item.getPricingSourceUrl()).verifiedAt(item.getPricingVerifiedAt())
                .unit(unit).unitLabel(unitLabel(unit)).currency(billingCurrency.toUpperCase(Locale.ROOT))
                .saleUnitPrice(unitSale)
                .saleUnitPriceMoney(new MoneyAmount(unitSale.multiply(java.math.BigDecimal.valueOf(Math.max(1, amountScale)))
                        .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact(),
                        billingCurrency.toUpperCase(Locale.ROOT), Math.max(1, amountScale)))
                .build());
    }

    private String unitLabel(String unit) {
        return switch (unit) {
            case "SECOND" -> "USD / 秒";
            case "IMAGE" -> "USD / 张";
            case "MINUTE" -> "USD / 分钟";
            case "CHARACTER" -> "USD / 千字符";
            case "TASK" -> "USD / 次";
            default -> "USD / 1M Token";
        };
    }

}
