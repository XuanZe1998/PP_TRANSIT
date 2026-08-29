package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.dto.PublicModel;
import com.transit.dto.PublicModelPricing;
import com.transit.dto.MoneyAmount;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.OtherService;
import com.transit.service.OtherServiceCatalogService;
import com.transit.service.OtherServiceImageStorageService;
import com.transit.service.ModelContextPricingService;
import com.transit.service.PublicUpstreamMappingService;
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
                                                  @RequestParam(value = "sort", required = false, defaultValue = "name") String sort) {
        if (page < 1 || page > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be between 1 and 1000000");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        }
        final String normalizedQuery = normalizeBlank(query);
        // `type` is retained for older clients. New clients should use the
        // explicit source parameter because upstream and protocol are separate concepts.
        final String normalizedType = normalizeBlank(source) != null ? normalizeBlank(source) : normalizeBlank(type);
        // Unverified and failed provider rows are operational data and are never public.
        if (normalizedQuery != null && normalizedQuery.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is too long");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
        }

        if (normalizedType != null || normalizeBlank(capability) != null || normalizeBlank(vendor) != null) {
            List<PublicModel> all = modelMappingMapper.findPublicModels();
            publicUpstreamMappingService.sanitize(all);
            modelContextPricingService.enrich(all);
            List<PublicModel> filtered = all.stream()
                    .filter(item -> normalizedQuery == null || item.getPublicName().toLowerCase(Locale.ROOT).contains(normalizedQuery.toLowerCase(Locale.ROOT)))
                    .filter(item -> normalizedType == null || normalizedType.equalsIgnoreCase(item.getSource())
                            || (item.getSources() != null && List.of(item.getSources().split(",")).stream().anyMatch(normalizedType::equalsIgnoreCase)))
                    .filter(item -> normalizeBlank(capability) == null || normalizeBlank(capability).equalsIgnoreCase(item.getCapability()))
                    .filter(item -> normalizeBlank(vendor) == null || normalizeBlank(vendor).equalsIgnoreCase(item.getVendor()))
                    .sorted(java.util.Comparator.comparing(PublicModel::getPublicName, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            int from = Math.min(offset, filtered.size());
            int to = Math.min(from + size, filtered.size());
            List<PublicModel> items = filtered.subList(from, to);
            items.forEach(this::applyMoney);
            PageResponse<PublicModel> catalog = new PageResponse<>();
            catalog.setPage(page); catalog.setSize(size); catalog.setTotal((long) filtered.size()); catalog.setItems(items);
            return Mono.just(catalog);
        }

        Long total = modelMappingMapper.countPublicModels(normalizedQuery, normalizedType);
        List<PublicModel> items;
        if ("hot".equalsIgnoreCase(sort)) {
            items = modelMappingMapper.findPublicModelsPagedHot(normalizedQuery, normalizedType, size, offset);
        } else if ("recent".equalsIgnoreCase(sort)) {
            items = modelMappingMapper.findPublicModelsPagedRecent(normalizedQuery, normalizedType, size, offset);
        } else {
            items = modelMappingMapper.findPublicModelsPagedByName(normalizedQuery, normalizedType, size, offset);
        }

        if (items == null) {
            items = List.of();
        }
        for (PublicModel item : items) {
            applyMoney(item);
        }
        publicUpstreamMappingService.sanitize(items);
        modelContextPricingService.enrich(items);

        PageResponse<PublicModel> resp = new PageResponse<>();
        resp.setTotal(total == null ? 0 : total);
        resp.setPage(page);
        resp.setSize(size);
        resp.setItems(items);
        return Mono.just(resp);
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

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
