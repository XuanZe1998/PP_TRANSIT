package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.dto.PublicModel;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.OtherService;
import com.transit.service.OtherServiceCatalogService;
import com.transit.service.OtherServiceImageStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final ModelMappingMapper modelMappingMapper;
    private final OtherServiceCatalogService otherServiceCatalogService;
    private final OtherServiceImageStorageService otherServiceImageStorageService;

    @Value("${billing.currency:CNY}")
    private String billingCurrency;

    @Value("${billing.amount-scale:10000}")
    private long amountScale;

    @GetMapping("/models")
    public Mono<PageResponse<PublicModel>> models(@RequestParam(value = "page", required = false, defaultValue = "1") int page,
                                                  @RequestParam(value = "size", required = false, defaultValue = "12") int size,
                                                  @RequestParam(value = "query", required = false) String query,
                                                  @RequestParam(value = "type", required = false) String type,
                                                  @RequestParam(value = "sort", required = false, defaultValue = "name") String sort) {
        if (page < 1 || page > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be between 1 and 1000000");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        }
        final String normalizedQuery = normalizeBlank(query);
        final String normalizedType = normalizeBlank(type);
        if (normalizedQuery != null && normalizedQuery.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is too long");
        }
        int offset;
        try {
            offset = Math.multiplyExact(page - 1, size);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page offset is too large");
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
            item.setCurrency(billingCurrency.toUpperCase(Locale.ROOT));
            item.setAmountScale(Math.max(1, amountScale));
            item.setPriceUnit("currency_per_1m_tokens");
            item.setPriceVariesByRoute(differs(item.getMinInputPricePerMillion(), item.getMaxInputPricePerMillion())
                    || differs(item.getMinOutputPricePerMillion(), item.getMaxOutputPricePerMillion())
                    || differs(item.getMinCachedPricePerMillion(), item.getMaxCachedPricePerMillion()));
        }

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

    private String normalizeBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
