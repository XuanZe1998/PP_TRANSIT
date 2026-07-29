package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.OtherServiceMapper;
import com.transit.mapper.PlusProductMapper;
import com.transit.model.OtherService;
import com.transit.model.PlusProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OtherServiceCatalogService {
    private static final String INTERNAL_SERVICE_TYPE = "SERVICE";
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");

    private final OtherServiceMapper otherServiceMapper;
    private final PlusProductMapper plusProductMapper;

    public List<OtherService> listPublicServices() {
        return enrich(otherServiceMapper.selectList(new LambdaQueryWrapper<OtherService>()
                .eq(OtherService::getEnabled, true)
                .orderByAsc(OtherService::getSortOrder)
                .orderByAsc(OtherService::getId)));
    }

    public List<OtherService> listAllServices() {
        return enrich(otherServiceMapper.selectList(new LambdaQueryWrapper<OtherService>()
                .orderByAsc(OtherService::getSortOrder)
                .orderByAsc(OtherService::getId)));
    }

    @Transactional
    public OtherService create(OtherService request) {
        if (request == null) {
            throw badRequest("Service body is required");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String name = requiredText(request.getName(), "name", 160);
        String description = optionalText(request.getDescription(), "description", 1000);
        String imageUrl = optionalHttpUrl(request.getImageUrl());
        long priceCents = nonNegative(request.getPriceCents(), "priceCents");
        long serviceFeeCents = nonNegative(request.getServiceFeeCents(), "serviceFeeCents");
        String currency = normalizeCurrency(request.getCurrency());
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        boolean purchaseEnabled = Boolean.TRUE.equals(request.getPurchaseEnabled());

        PlusProduct product = createBackingProduct(name, description, imageUrl, priceCents,
                serviceFeeCents, currency, enabled && purchaseEnabled, now);
        OtherService service = OtherService.builder()
                .name(name)
                .description(description)
                .imageUrl(imageUrl)
                .sortOrder(nonNegative(request.getSortOrder(), "sortOrder"))
                .enabled(enabled)
                .serviceType(INTERNAL_SERVICE_TYPE)
                .linkedProductId(product.getId())
                .actionLabel(resolveActionLabel(request.getActionLabel()))
                .priceCents(priceCents)
                .serviceFeeCents(serviceFeeCents)
                .currency(currency)
                .purchaseEnabled(purchaseEnabled)
                .createdAt(now)
                .updatedAt(now)
                .build();
        otherServiceMapper.insert(service);
        return enrich(service);
    }

    @Transactional
    public OtherService update(Long id, OtherService request) {
        OtherService service = otherServiceMapper.selectById(id);
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        if (request == null) {
            throw badRequest("Service body is required");
        }
        String name = requiredText(request.getName(), "name", 160);
        String description = optionalText(request.getDescription(), "description", 1000);
        String imageUrl = optionalHttpUrl(request.getImageUrl());
        long priceCents = nonNegative(request.getPriceCents(), "priceCents");
        long serviceFeeCents = nonNegative(request.getServiceFeeCents(), "serviceFeeCents");
        String currency = normalizeCurrency(request.getCurrency());
        boolean enabled = request.getEnabled() == null ? Boolean.TRUE.equals(service.getEnabled()) : request.getEnabled();
        boolean purchaseEnabled = Boolean.TRUE.equals(request.getPurchaseEnabled());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        PlusProduct product = upsertBackingProduct(service.getLinkedProductId(), name, description, imageUrl,
                priceCents, serviceFeeCents, currency, enabled && purchaseEnabled, now);
        service.setName(name);
        service.setDescription(description);
        service.setImageUrl(imageUrl);
        service.setSortOrder(nonNegative(request.getSortOrder(), "sortOrder"));
        service.setEnabled(enabled);
        service.setServiceType(INTERNAL_SERVICE_TYPE);
        service.setLinkedProductId(product.getId());
        service.setActionLabel(resolveActionLabel(request.getActionLabel()));
        service.setPriceCents(priceCents);
        service.setServiceFeeCents(serviceFeeCents);
        service.setCurrency(currency);
        service.setPurchaseEnabled(purchaseEnabled);
        service.setUpdatedAt(now);
        otherServiceMapper.updateById(service);
        return enrich(service);
    }

    public void delete(Long id) {
        if (otherServiceMapper.selectById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        // The internal backing product is retained because historical orders may reference it.
        otherServiceMapper.deleteById(id);
    }

    public Long resolveProductIdForOrder(Long serviceId) {
        OtherService service = otherServiceMapper.selectById(serviceId);
        if (service == null || !Boolean.TRUE.equals(service.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found or unavailable");
        }
        enrich(service);
        if (!Boolean.TRUE.equals(service.getOrderEnabled()) || service.getLinkedProductId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Service is not currently available for purchase");
        }
        return service.getLinkedProductId();
    }

    private List<OtherService> enrich(List<OtherService> services) {
        if (services == null || services.isEmpty()) return services == null ? List.of() : services;
        services.forEach(this::enrich);
        return services;
    }

    private OtherService enrich(OtherService service) {
        PlusProduct product = service.getLinkedProductId() == null
                ? null : plusProductMapper.selectById(service.getLinkedProductId());
        if (product != null) {
            if (service.getPriceCents() == null) service.setPriceCents(product.getPriceCents());
            if (service.getServiceFeeCents() == null) service.setServiceFeeCents(product.getServiceFeeCents());
            if (service.getCurrency() == null || service.getCurrency().isBlank()) service.setCurrency(product.getCurrency());
        }
        long price = service.getPriceCents() == null ? 0 : service.getPriceCents();
        long fee = service.getServiceFeeCents() == null ? 0 : service.getServiceFeeCents();
        try {
            service.setAmountCents(Math.addExact(price, fee));
        } catch (ArithmeticException exception) {
            service.setAmountCents(null);
        }
        service.setOrderEnabled(Boolean.TRUE.equals(service.getEnabled())
                && Boolean.TRUE.equals(service.getPurchaseEnabled())
                && product != null
                && Boolean.TRUE.equals(product.getEnabled())
                && service.getAmountCents() != null
                && service.getAmountCents() > 0);
        return service;
    }

    private PlusProduct upsertBackingProduct(Long productId,
                                             String name,
                                             String description,
                                             String imageUrl,
                                             long priceCents,
                                             long serviceFeeCents,
                                             String currency,
                                             boolean enabled,
                                             LocalDateTime now) {
        PlusProduct product = productId == null ? null : plusProductMapper.selectById(productId);
        if (product == null) {
            return createBackingProduct(name, description, imageUrl, priceCents,
                    serviceFeeCents, currency, enabled, now);
        }
        product.setName(name);
        product.setDescription(description);
        product.setImageUrl(imageUrl);
        product.setPriceCents(priceCents);
        product.setServiceFeeCents(serviceFeeCents);
        product.setCurrency(currency);
        product.setEnabled(enabled);
        plusProductMapper.updateById(product);
        return product;
    }

    private PlusProduct createBackingProduct(String name,
                                             String description,
                                             String imageUrl,
                                             long priceCents,
                                             long serviceFeeCents,
                                             String currency,
                                             boolean enabled,
                                             LocalDateTime now) {
        PlusProduct product = PlusProduct.builder()
                .name(name)
                .description(description)
                .imageUrl(imageUrl)
                .priceCents(priceCents)
                .serviceFeeCents(serviceFeeCents)
                .currency(currency)
                .enabled(enabled)
                .createdAt(now)
                .build();
        plusProductMapper.insert(product);
        if (product.getId() == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Service order backing record was not created");
        }
        return product;
    }

    private String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) throw badRequest(field + " is required");
        return normalized;
    }

    private String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw badRequest(field + " must be at most " + maxLength + " characters");
        return normalized;
    }

    private String optionalHttpUrl(String value) {
        String normalized = optionalText(value, "imageUrl", 1000);
        if (normalized == null) return null;
        if (OtherServiceImageStorageService.isManagedImageUrl(normalized)) return normalized;
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("not an absolute HTTP(S) URL");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw badRequest("imageUrl must be an absolute HTTP(S) URL");
        }
    }

    private int nonNegative(Integer value, String field) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0) throw badRequest(field + " must be non-negative");
        return normalized;
    }

    private long nonNegative(Long value, String field) {
        long normalized = value == null ? 0 : value;
        if (normalized < 0) throw badRequest(field + " must be non-negative");
        return normalized;
    }

    private String normalizeCurrency(String value) {
        String normalized = value == null || value.isBlank() ? "CNY" : value.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(normalized).matches()) throw badRequest("currency must be a three-letter ISO code");
        return normalized;
    }

    private String resolveActionLabel(String value) {
        String normalized = optionalText(value, "actionLabel", 40);
        return normalized == null ? "立即购买" : normalized;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
