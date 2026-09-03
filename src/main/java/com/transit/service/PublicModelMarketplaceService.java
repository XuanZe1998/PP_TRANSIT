package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.dto.PublicModelComparison;
import com.transit.dto.PublicModelFacetOption;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
public class PublicModelMarketplaceService {
    public List<PublicModel> filter(List<PublicModel> models, Filters filters) {
        return filter(models, filters, null);
    }

    public List<PublicModel> sort(List<PublicModel> models, String sort) {
        Comparator<PublicModel> byName = Comparator.comparing(PublicModel::getPublicName, String.CASE_INSENSITIVE_ORDER);
        Comparator<PublicModel> comparator = switch (normalize(sort)) {
            case "name_desc" -> byName.reversed();
            case "price_asc" -> Comparator.comparing(this::comparablePrice,
                    Comparator.nullsLast(BigDecimal::compareTo)).thenComparing(byName);
            case "price_desc" -> Comparator.comparing((PublicModel model) -> comparablePrice(model) == null)
                    .thenComparing(this::comparablePrice, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(byName);
            case "hot" -> Comparator.comparingLong(PublicModel::getRouteCount).reversed().thenComparing(byName);
            case "recent" -> Comparator.comparing((PublicModel model) -> model.getPricingVerifiedAt() == null)
                    .thenComparing(PublicModel::getPricingVerifiedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(byName);
            default -> Comparator.comparingInt(PublicModel::getDisplayPriority).reversed().thenComparing(byName);
        };
        return models.stream().sorted(comparator).toList();
    }

    public Map<String, List<PublicModelFacetOption>> facets(List<PublicModel> models, Filters filters) {
        Map<String, List<PublicModelFacetOption>> result = new LinkedHashMap<>();
        result.put("routes", facet(filter(models, filters, "routes"), model -> one(model.getRouteCode()), model -> model.getRouteName()));
        result.put("publishers", facet(filter(models, filters, "publishers"), model -> one(model.getPublisherCode()), model -> model.getPublisherName()));
        result.put("categories", facet(filter(models, filters, "categories"), model -> one(model.getCategory()), model -> categoryLabel(model.getCategory())));
        result.put("capabilities", facet(filter(models, filters, "capabilities"), model -> one(model.getCapability()), model -> model.getCapability()));
        result.put("inputModalities", facet(filter(models, filters, "inputModalities"), model -> csv(model.getInputModalities()), ignored -> null));
        result.put("outputModalities", facet(filter(models, filters, "outputModalities"), model -> csv(model.getOutputModalities()), ignored -> null));
        result.put("protocols", facet(filter(models, filters, "protocols"), model -> csv(model.getProtocols()), ignored -> null));
        result.put("pricingUnits", facet(filter(models, filters, "pricingUnits"), model -> one(model.getPricingUnit()), model -> unitLabel(model.getPricingUnit())));
        result.put("plans", facet(filter(models, filters, "plans"), model -> one(model.getPlanCode()), model -> model.getPlanName()));
        result.put("priceStatuses", facet(filter(models, filters, "priceStatuses"), model -> one(model.getPricingStatus()), model -> statusLabel(model.getPricingStatus())));
        return result;
    }

    public PublicModelComparison comparison(List<PublicModel> models, String publicName) {
        PublicModel target = models.stream().filter(model -> model.getPublicName().equalsIgnoreCase(publicName)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在或当前不可用"));
        List<PublicModel> offers = models.stream()
                .filter(model -> target.getComparisonKey().equals(model.getComparisonKey()))
                .filter(this::isPublicOffer)
                .sorted(Comparator.comparing(PublicModel::getRouteName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(PublicModel::getPlanName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int comparableCount = (int) offers.stream().filter(model -> comparablePrice(model) != null).count();
        return new PublicModelComparison(target.getComparisonKey(), target.getDisplayName(), comparableCount, offers);
    }

    public static Filters criteria(String query, String routes, String publishers, String categories,
                                   String capabilities, String inputModalities, String outputModalities,
                                   String protocols, String pricingUnits, String plans, String priceStatuses) {
        return new Filters(normalize(query), values(routes), values(publishers), values(categories), values(capabilities),
                values(inputModalities), values(outputModalities), values(protocols), values(pricingUnits),
                values(plans), values(priceStatuses));
    }

    private List<PublicModel> filter(List<PublicModel> models, Filters f, String ignored) {
        return models.stream().filter(model -> {
            String haystack = String.join(" ", safe(model.getPublicName()), safe(model.getDisplayName()),
                    safe(model.getPublisherName()), safe(model.getCapability()), safe(model.getPlanName())).toLowerCase(Locale.ROOT);
            return (f.query().isBlank() || haystack.contains(f.query()))
                    && ignoredOrMatches(ignored, "routes", f.routes(), one(model.getRouteCode()))
                    && ignoredOrMatches(ignored, "publishers", f.publishers(), one(model.getPublisherCode()))
                    && ignoredOrMatches(ignored, "categories", f.categories(), one(model.getCategory()))
                    && ignoredOrMatches(ignored, "capabilities", f.capabilities(), one(model.getCapability()))
                    && ignoredOrMatches(ignored, "inputModalities", f.inputModalities(), csv(model.getInputModalities()))
                    && ignoredOrMatches(ignored, "outputModalities", f.outputModalities(), csv(model.getOutputModalities()))
                    && ignoredOrMatches(ignored, "protocols", f.protocols(), csv(model.getProtocols()))
                    && ignoredOrMatches(ignored, "pricingUnits", f.pricingUnits(), one(model.getPricingUnit()))
                    && ignoredOrMatches(ignored, "plans", f.plans(), one(model.getPlanCode()))
                    && ignoredOrMatches(ignored, "priceStatuses", f.priceStatuses(), one(model.getPricingStatus()));
        }).toList();
    }

    private boolean ignoredOrMatches(String ignored, String name, Set<String> selected, Set<String> actual) {
        return name.equals(ignored) || selected.isEmpty() || selected.stream().anyMatch(actual::contains);
    }

    private List<PublicModelFacetOption> facet(List<PublicModel> models,
                                                Function<PublicModel, Set<String>> values,
                                                Function<PublicModel, String> singleLabel) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (PublicModel model : models) {
            for (String value : values.apply(model)) {
                if (value.isBlank()) continue;
                counts.merge(value, 1L, Long::sum);
                String label = singleLabel.apply(model);
                labels.putIfAbsent(value, label == null || label.isBlank() ? value : label);
            }
        }
        return counts.entrySet().stream().map(entry -> new PublicModelFacetOption(entry.getKey(), labels.get(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(PublicModelFacetOption::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private BigDecimal comparablePrice(PublicModel model) {
        if ("FREE_PREVIEW".equalsIgnoreCase(model.getBillingMode())) return null;
        if (!model.isBillingConfigured()) return null;
        if (!"TOKEN".equalsIgnoreCase(model.getPricingUnit())) return positive(model.getSaleUnitPrice());
        BigDecimal input = positive(model.getMinInputPricePerMillion());
        return input != null ? input : positive(model.getMinOutputPricePerMillion());
    }
    private boolean isPublicOffer(PublicModel model) {
        return model.isAvailable() && (model.isBillingConfigured()
                || "FREE_PREVIEW".equalsIgnoreCase(model.getBillingMode()));
    }
    private BigDecimal positive(BigDecimal value) { return value != null && value.signum() > 0 ? value : null; }
    private static Set<String> values(String raw) { return csv(raw); }
    private static Set<String> one(String value) { String normalized=normalize(value); return normalized.isBlank()?Set.of():Set.of(normalized); }
    private static Set<String> csv(String raw) { Set<String> result=new LinkedHashSet<>(); for(String value:safe(raw).split("[,\\s]+")){String normalized=normalize(value);if(!normalized.isBlank())result.add(normalized);}return result; }
    private static String normalize(String value) { return safe(value).trim().toLowerCase(Locale.ROOT); }
    private static String safe(String value) { return value == null ? "" : value; }
    private String categoryLabel(String value) { return switch (normalize(value)) { case "multimodal"->"多模态模型";case "image"->"图像模型";case "video"->"视频模型";case "audio"->"音频模型";case "vector"->"向量模型";default->"大语言模型";}; }
    private String unitLabel(String value) { return switch (normalize(value)) { case "image"->"按张";case "second"->"按秒";case "minute"->"按分钟";case "character"->"按字符";case "task"->"按次";default->"Token";}; }
    private String statusLabel(String value) { return switch (normalize(value)) { case "verified"->"价格已核验";case "free_preview"->"免费预览";case "pending"->"价格待配置";default->safe(value);}; }

    public record Filters(String query, Set<String> routes, Set<String> publishers, Set<String> categories,
                          Set<String> capabilities, Set<String> inputModalities, Set<String> outputModalities,
                          Set<String> protocols, Set<String> pricingUnits, Set<String> plans,
                          Set<String> priceStatuses) { }
}
