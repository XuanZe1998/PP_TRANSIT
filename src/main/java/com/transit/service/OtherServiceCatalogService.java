package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.OtherServiceMapper;
import com.transit.mapper.ServiceInventoryItemMapper;
import com.transit.model.OtherService;
import com.transit.dto.MoneyAmount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OtherServiceCatalogService {
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^[A-Z]{3}$");
    private static final String STANDARD_PRODUCT = "STANDARD";
    private static final String CARD_KEY_PRODUCT = "CARD_KEY";

    private final OtherServiceMapper otherServiceMapper;
    private final ServiceInventoryItemMapper inventoryItemMapper;
    private final ObjectMapper objectMapper;

    @Value("${service-orders.redemption-allowed-hosts:}")
    private String redemptionAllowedHosts = "";

    @Autowired
    public OtherServiceCatalogService(OtherServiceMapper otherServiceMapper,
                                      ServiceInventoryItemMapper inventoryItemMapper,
                                      ObjectMapper objectMapper) {
        this.otherServiceMapper = otherServiceMapper;
        this.inventoryItemMapper = inventoryItemMapper;
        this.objectMapper = objectMapper;
    }

    OtherServiceCatalogService(OtherServiceMapper otherServiceMapper) {
        this(otherServiceMapper, null, new ObjectMapper());
    }

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

    public Map<String,Object> adminDetail(Long id) {
        OtherService service = otherServiceMapper.selectById(id);
        if (service == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        enrich(service); Map<String,Object> view = new LinkedHashMap<>();
        view.put("id", service.getId()); view.put("name", service.getName()); view.put("description", service.getDescription());
        view.put("imageUrl", service.getImageUrl()); view.put("sortOrder", service.getSortOrder()); view.put("enabled", service.getEnabled());
        view.put("actionLabel", service.getActionLabel()); view.put("priceCents", service.getPriceCents()); view.put("serviceFeeCents", service.getServiceFeeCents());
        view.put("currency", service.getCurrency()); view.put("purchaseEnabled", service.getPurchaseEnabled()); view.put("productType", service.getProductType());
        view.put("fulfillmentMode", service.getFulfillmentMode()); view.put("purchasePrompt", service.getPurchasePrompt()); view.put("maxPurchaseQuantity", service.getMaxPurchaseQuantity());
        view.put("manualStock", service.getManualStock()); view.put("wholesaleTiersJson", service.getWholesaleTiersJson()); view.put("inputSchemaJson", service.getInputSchemaJson());
        view.put("redemptionUrl", service.getRedemptionUrl()); view.put("redemptionConfigured", service.getRedemptionConfigured()); view.put("availableStock", service.getAvailableStock());
        return view;
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
        String productType = productType(request.getProductType());
        String fulfillmentMode = CARD_KEY_PRODUCT.equals(productType)
                ? "AUTOMATIC_DELIVERY" : fulfillmentMode(request.getFulfillmentMode());
        String redemptionUrl = redemptionUrl(request.getRedemptionUrl(), productType, true);

        OtherService service = OtherService.builder()
                .name(name)
                .description(description)
                .imageUrl(imageUrl)
                .sortOrder(nonNegative(request.getSortOrder(), "sortOrder"))
                .enabled(enabled)
                .actionLabel(resolveActionLabel(request.getActionLabel()))
                .priceCents(priceCents)
                .serviceFeeCents(serviceFeeCents)
                .currency(currency)
                .purchaseEnabled(purchaseEnabled)
                .productType(productType)
                .fulfillmentMode(fulfillmentMode)
                .purchasePrompt(optionalText(request.getPurchasePrompt(), "purchasePrompt", 1000))
                .maxPurchaseQuantity(positive(request.getMaxPurchaseQuantity(), "maxPurchaseQuantity"))
                .manualStock(manualStock(request.getManualStock(), fulfillmentMode))
                .manualReserved(0)
                .wholesaleTiersJson(validateTiers(request.getWholesaleTiersJson(), priceCents))
                .inputSchemaJson(validateInputSchema(request.getInputSchemaJson()))
                .redemptionUrl(redemptionUrl)
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
        String productType = productType(request.getProductType() == null
                ? service.getProductType() : request.getProductType());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        service.setName(name);
        service.setDescription(description);
        service.setImageUrl(imageUrl);
        service.setSortOrder(nonNegative(request.getSortOrder(), "sortOrder"));
        service.setEnabled(enabled);
        service.setActionLabel(resolveActionLabel(request.getActionLabel()));
        service.setPriceCents(priceCents);
        service.setServiceFeeCents(serviceFeeCents);
        service.setCurrency(currency);
        service.setPurchaseEnabled(purchaseEnabled);
        service.setProductType(productType);
        boolean hasCommerceConfig = request.getFulfillmentMode() != null && !request.getFulfillmentMode().isBlank();
        String fulfillmentMode = CARD_KEY_PRODUCT.equals(productType) ? "AUTOMATIC_DELIVERY" : hasCommerceConfig
                ? fulfillmentMode(request.getFulfillmentMode()) : fulfillmentMode(service.getFulfillmentMode());
        service.setFulfillmentMode(fulfillmentMode);
        if (CARD_KEY_PRODUCT.equals(productType)) {
            if (request.getRedemptionUrl() != null && !request.getRedemptionUrl().isBlank()) {
                service.setRedemptionUrl(redemptionUrl(request.getRedemptionUrl(), productType, true));
            } else if (service.getRedemptionUrl() == null || service.getRedemptionUrl().isBlank()) {
                throw badRequest("redemptionUrl is required for CARD_KEY services");
            }
        } else {
            service.setRedemptionUrl(null);
        }
        if (hasCommerceConfig) {
            service.setPurchasePrompt(optionalText(request.getPurchasePrompt(), "purchasePrompt", 1000));
            service.setMaxPurchaseQuantity(positive(request.getMaxPurchaseQuantity(), "maxPurchaseQuantity"));
            service.setManualStock(manualStock(request.getManualStock(), fulfillmentMode));
            service.setWholesaleTiersJson(validateTiers(request.getWholesaleTiersJson(), priceCents));
            service.setInputSchemaJson(validateInputSchema(request.getInputSchemaJson()));
        }
        if (service.getManualReserved() == null) service.setManualReserved(0);
        service.setUpdatedAt(now);
        otherServiceMapper.updateById(service);
        return enrich(service);
    }

    public void delete(Long id) {
        if (otherServiceMapper.selectById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        otherServiceMapper.deleteById(id);
    }

    public OtherService requirePurchasableService(Long serviceId) {
        OtherService service = otherServiceMapper.selectById(serviceId);
        if (service == null || !Boolean.TRUE.equals(service.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found or unavailable");
        }
        enrich(service);
        if (!Boolean.TRUE.equals(service.getOrderEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Service is not currently available for purchase");
        }
        return service;
    }

    private List<OtherService> enrich(List<OtherService> services) {
        if (services == null || services.isEmpty()) return services == null ? List.of() : services;
        services.forEach(this::enrich);
        return services;
    }

    private OtherService enrich(OtherService service) {
        long price = service.getPriceCents() == null ? 0 : service.getPriceCents();
        long fee = service.getServiceFeeCents() == null ? 0 : service.getServiceFeeCents();
        try {
            service.setAmountCents(Math.addExact(price, fee));
        } catch (ArithmeticException exception) {
            service.setAmountCents(null);
        }
        String currency = service.getCurrency() == null ? "CNY" : service.getCurrency();
        service.setPriceMoney(MoneyAmount.cents(price, currency));
        service.setServiceFeeMoney(MoneyAmount.cents(fee, currency));
        if (service.getAmountCents() != null) service.setAmountMoney(MoneyAmount.cents(service.getAmountCents(), currency));
        service.setOrderEnabled(Boolean.TRUE.equals(service.getEnabled())
                && Boolean.TRUE.equals(service.getPurchaseEnabled())
                && service.getAmountCents() != null
                && service.getAmountCents() > 0);
        if (service.getFulfillmentMode() == null) service.setFulfillmentMode("MANUAL_PROCESSING");
        if (service.getProductType() == null) service.setProductType(STANDARD_PRODUCT);
        boolean hasRedemption = CARD_KEY_PRODUCT.equals(service.getProductType())
                && service.getRedemptionUrl() != null && !service.getRedemptionUrl().isBlank();
        service.setRedemptionConfigured(hasRedemption);
        service.setRedemptionPath(hasRedemption ? "/services/" + service.getId() + "/redeem" : null);
        if (service.getMaxPurchaseQuantity() == null || service.getMaxPurchaseQuantity() < 1) {
            service.setMaxPurchaseQuantity(1);
        }
        if ("AUTOMATIC_DELIVERY".equals(service.getFulfillmentMode()) && inventoryItemMapper != null) {
            service.setAvailableStock(Math.toIntExact(inventoryItemMapper.selectCount(
                    new LambdaQueryWrapper<com.transit.model.ServiceInventoryItem>()
                            .eq(com.transit.model.ServiceInventoryItem::getServiceId, service.getId())
                            .eq(com.transit.model.ServiceInventoryItem::getStatus, "AVAILABLE"))));
        } else if (service.getManualStock() != null) {
            service.setAvailableStock(Math.max(0, service.getManualStock()
                    - (service.getManualReserved() == null ? 0 : service.getManualReserved())));
        }
        if (service.getAvailableStock() != null && service.getAvailableStock() < 1) {
            service.setOrderEnabled(false);
        }
        return service;
    }

    private String fulfillmentMode(String value) {
        String normalized = value == null || value.isBlank() ? "MANUAL_PROCESSING" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals("AUTOMATIC_DELIVERY") && !normalized.equals("MANUAL_PROCESSING")) {
            throw badRequest("fulfillmentMode must be AUTOMATIC_DELIVERY or MANUAL_PROCESSING");
        }
        return normalized;
    }

    private String productType(String value) {
        String normalized = value == null || value.isBlank() ? STANDARD_PRODUCT : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.equals(STANDARD_PRODUCT) && !normalized.equals(CARD_KEY_PRODUCT)) {
            throw badRequest("productType must be STANDARD or CARD_KEY");
        }
        return normalized;
    }

    private String redemptionUrl(String value, String productType, boolean required) {
        if (!CARD_KEY_PRODUCT.equals(productType)) return null;
        String normalized = optionalText(value, "redemptionUrl", 2000);
        if (normalized == null) {
            if (required) throw badRequest("redemptionUrl is required for CARD_KEY services");
            return null;
        }
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getHost().endsWith(".") || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            List<String> allowedHosts = java.util.Arrays.stream(redemptionAllowedHosts.split(","))
                    .map(String::trim).map(valueHost -> valueHost.toLowerCase(Locale.ROOT))
                    .filter(valueHost -> !valueHost.isBlank()).toList();
            if (uri.getPort() != -1 && uri.getPort() != 443) {
                throw badRequest("redemptionUrl must use the standard HTTPS port");
            }
            if (!allowedHosts.isEmpty() && allowedHosts.stream().noneMatch(allowed ->
                    host.equals(allowed) || host.endsWith("." + allowed))) {
                throw badRequest("redemptionUrl host is not in service-orders.redemption-allowed-hosts");
            }
            return uri.normalize().toASCIIString();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw badRequest("redemptionUrl must be an absolute HTTPS URL without credentials or fragments");
        }
    }

    public URI requireRedemptionDestination(Long serviceId) {
        OtherService service = otherServiceMapper.selectById(serviceId);
        if (service == null || !Boolean.TRUE.equals(service.getEnabled())
                || !CARD_KEY_PRODUCT.equals(productType(service.getProductType()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Card redemption service not found");
        }
        return URI.create(redemptionUrl(service.getRedemptionUrl(), CARD_KEY_PRODUCT, true));
    }

    private Integer manualStock(Integer value, String mode) {
        if (!"MANUAL_PROCESSING".equals(mode)) return null;
        if (value != null && value < 0) throw badRequest("manualStock must be non-negative or null for unlimited stock");
        return value;
    }

    private int positive(Integer value, String field) {
        int normalized = value == null ? 1 : value;
        if (normalized < 1 || normalized > 1000) throw badRequest(field + " must be between 1 and 1000");
        return normalized;
    }

    private String validateTiers(String json, long listPrice) {
        if (json == null || json.isBlank()) return "[]";
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.size() > 50) throw new IllegalArgumentException();
            int previous = 1;
            for (JsonNode tier : root) {
                int minimum = tier.path("minQuantity").asInt(0);
                long unit = tier.path("unitPriceCents").asLong(-1);
                if (minimum < 2 || minimum <= previous || unit < 0 || unit > listPrice) throw new IllegalArgumentException();
                previous = minimum;
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception invalid) {
            throw badRequest("wholesaleTiersJson must be an ordered array of valid quantity tiers");
        }
    }

    private String validateInputSchema(String json) {
        if (json == null || json.isBlank()) return "[]";
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.size() > 20) throw new IllegalArgumentException();
            java.util.Set<String> keys = new java.util.HashSet<>();
            for (JsonNode field : root) {
                String key = field.path("key").asText("");
                String label = field.path("label").asText("");
                int max = field.path("maxLength").asInt(200);
                if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,39}") || label.isBlank() || label.length() > 80
                        || max < 1 || max > 2000 || !keys.add(key)) throw new IllegalArgumentException();
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception invalid) {
            throw badRequest("inputSchemaJson contains an invalid custom field definition");
        }
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
