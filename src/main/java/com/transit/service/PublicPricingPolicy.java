package com.transit.service;

import com.transit.model.ModelMapping;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/** Shared invariants for models that may be shown to or invoked by customers. */
public final class PublicPricingPolicy {
    private static final BigDecimal MARKUP = new BigDecimal("1.10");
    private static final Set<String> INPUT_ONLY_CAPABILITIES = Set.of("embedding", "rerank");

    private PublicPricingPolicy() {
    }

    public static BigDecimal saleFromCost(BigDecimal cost) {
        if (cost == null || cost.signum() <= 0) {
            return BigDecimal.ZERO.setScale(6);
        }
        return cost.multiply(MARKUP).setScale(6, RoundingMode.CEILING);
    }

    public static boolean hasRequiredSale(ModelMapping mapping) {
        if (mapping == null || !mapping.isEnabled()) {
            return false;
        }
        String mode = mapping.getBillingMode() == null ? "PAID" : mapping.getBillingMode().trim().toUpperCase(Locale.ROOT);
        if ("FREE_PREVIEW".equals(mode)) return true;
        if (!"PAID".equals(mode) || !mapping.isBillingEnabled()) return false;
        if (!"TOKEN".equalsIgnoreCase(mapping.getPricingUnit())) return positive(mapping.getSaleUnitPrice());
        if (!positive(mapping.getInputPricePerMillion())) return false;
        String capability = mapping.getCapability() == null
                ? "text" : mapping.getCapability().trim().toLowerCase(Locale.ROOT);
        return INPUT_ONLY_CAPABILITIES.contains(capability)
                || positive(mapping.getOutputPricePerMillion());
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
