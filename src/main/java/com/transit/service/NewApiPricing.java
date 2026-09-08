package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NewApiPricing {
    private static final BigDecimal ZERO = BigDecimal.ZERO, ONE = BigDecimal.ONE;
    private static final Pattern SERVICE_TIER_EXPRESSION = Pattern.compile(
            "^\\s*param\\(\\s*\\\"service_tier\\\"\\s*\\)\\s*!=\\s*\\\"priority\\\"\\s*\\?\\s*"
                    + "tier\\(\\s*\\\"base\\\"\\s*,\\s*([a-z0-9.*+\\s]+)\\)\\s*:\\s*"
                    + "tier\\(\\s*\\\"priority\\\"\\s*,\\s*([a-z0-9.*+\\s]+)\\)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_TERM = Pattern.compile(
            "^(p|c|cr|cc)\\s*\\*\\s*(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    public record Group(String name, String description, BigDecimal ratio) {}
    public record Price(String model, String status, String message, String unit,
                        BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite,
                        BigDecimal perRequest, BigDecimal saleInput, BigDecimal saleOutput,
                        BigDecimal saleCacheRead, BigDecimal saleCacheWrite, BigDecimal salePerRequest, Price priorityPrice) {
        public Price(String model, String status, String message, String unit,
                     BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite,
                     BigDecimal perRequest, BigDecimal saleInput, BigDecimal saleOutput,
                     BigDecimal saleCacheRead, BigDecimal saleCacheWrite, BigDecimal salePerRequest) {
            this(model, status, message, unit, input, output, cacheRead, cacheWrite, perRequest,
                    saleInput, saleOutput, saleCacheRead, saleCacheWrite, salePerRequest, null);
        }
        public boolean supported() { return "READY".equals(status); }
        public boolean quoted() { return supported() || "TIERED".equals(status); }
    }
    public List<Group> groups(JsonNode pricing) {
        if (pricing == null) return List.of();
        List<Group> result = new ArrayList<>();
        pricing.path("usable_group").fields().forEachRemaining(entry -> {
            BigDecimal ratio = optional(pricing.path("group_ratio").get(entry.getKey()));
            if (ratio != null && entry.getKey().length() <= 160)
                result.add(new Group(entry.getKey(), entry.getValue().asText(""), ratio));
        });
        result.sort(Comparator.comparing(Group::name));
        return result;
    }
    public boolean groupExists(JsonNode pricing, String group) {
        return pricing != null && (pricing.path("usable_group").has(group) || pricing.path("group_ratio").has(group));
    }
    public Map<String, JsonNode> rows(JsonNode pricing) {
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        if (pricing != null) pricing.path("data").forEach(row -> rows.put(row.path("model_name").asText(), row));
        return rows;
    }
    public boolean inGroup(JsonNode row, String group) {
        if (row == null) return false;
        for (JsonNode name : row.path("enable_groups"))
            if (name.asText().equals(group) || name.asText().equals("all")) return true;
        return false;
    }
    public Price calculate(String model, JsonNode row, String group, BigDecimal ratio,
                           BigDecimal markup, BigDecimal unitUsd) {
        if (row == null || ratio == null) return pending(model, "缺少该模型价格或分组倍率");
        if (!inGroup(row, group)) return pending(model, "所选分组未提供此模型");
        if (row.hasNonNull("audio_ratio") || row.hasNonNull("audio_completion_ratio") || row.hasNonNull("image_ratio"))
            return pending(model, "多模态独立倍率需人工确认");
        String billingMode = row.path("billing_mode").asText("");
        String billingExpression = row.path("billing_expr").asText("");
        if ("tiered_expr".equalsIgnoreCase(billingMode) && !billingExpression.isBlank()) {
            return calculateServiceTier(model, row, billingExpression, ratio, markup, unitUsd);
        }
        if (!billingExpression.isBlank() || (!billingMode.isBlank() && !"ratio".equals(billingMode)))
            return pending(model, "动态或插件计费需人工配置，未执行上游表达式");
        try {
            int quotaType = row.path("quota_type").isIntegralNumber() ? row.get("quota_type").intValue() : -1;
            BigDecimal input = ZERO, output = ZERO, read = ZERO, write = ZERO, request = ZERO;
            if (quotaType == 1) request = required(row, "model_price").multiply(ratio).multiply(unitUsd);
            else if (quotaType == 0) {
                input = required(row, "model_ratio").multiply(BigDecimal.valueOf(2)).multiply(ratio).multiply(unitUsd);
                output = input.multiply(required(row, "completion_ratio"));
                // Defaults match standard New API GetCacheRatio/GetCreateCacheRatio.
                read = input.multiply(row.hasNonNull("cache_ratio") ? required(row, "cache_ratio") : ONE);
                write = input.multiply(row.hasNonNull("create_cache_ratio") ? required(row, "create_cache_ratio") : new BigDecimal("1.25"));
            } else return pending(model, "未知计费单位");
            return new Price(model, "READY", "按所选分组换算；价格单位 USD，尚未验证实际调用",
                    quotaType == 1 ? "TASK" : "TOKEN", money(input), money(output), money(read), money(write), money(request),
                    money(input.multiply(markup)), money(output.multiply(markup)), money(read.multiply(markup)),
                    money(write.multiply(markup)), money(request.multiply(markup)));
        } catch (IllegalArgumentException e) { return pending(model, "价格字段缺失、无效或超过允许范围"); }
    }

    public void apply(ModelMapping mapping, Price price, String base, String group) {
        applyProtocolMetadata(mapping);
        mapping.setPricingSourceUrl(base + "/api/pricing");
        mapping.setPricingMessage(price.message());
        mapping.setPricingVerifiedAt(LocalDateTime.now());
        if (price.quoted()) applyAmounts(mapping, price);
        if (!price.supported()) {
            mapping.setEnabled(false);
            mapping.setBillingEnabled(false);
            mapping.setBillingMode("DISABLED");
            mapping.setPricingStatus("PENDING");
            return; // Preserve previously known amounts, never replace unknown prices by zero.
        }
        applyAmounts(mapping, price);
        if (!price.unit().equals(mapping.getPricingUnit())) mapping.setEnabled(false);
        mapping.setPricingUnit(price.unit());
        mapping.setBillingEnabled(true); mapping.setBillingMode("PAID"); mapping.setPricingStatus("VERIFIED");
        mapping.setPriceTiers(price.priorityPrice() == null ? List.of(tier(price, group, "base", 0))
                : List.of(tier(price, group, "base", 0), tier(price.priorityPrice(), group, "priority", 1)));
    }

    private ModelPriceTier tier(Price price, String group, String serviceTier, int order) {
        return ModelPriceTier.builder().tierName("priority".equals(serviceTier) ? "优先服务 priority" : "标准服务 base")
                .serviceTier(serviceTier).sortOrder(order)
                .officialGroupName("上游分组报价（非模型厂商官网价）")
                .officialInputPrice(price.input()).officialOutputPrice(price.output())
                .officialCacheReadPrice(price.cacheRead()).officialCacheWritePrice(price.cacheWrite())
                .officialPerRequestPrice(price.perRequest()).officialPriceUnit("M").officialPriceSuffix("USD / 1M Token")
                .costGroupName(group.substring(0, Math.min(120, group.length())))
                .costInputPrice(price.input()).costOutputPrice(price.output()).costCacheReadPrice(price.cacheRead())
                .costCacheWritePrice(price.cacheWrite()).costCacheWrite1hPrice(price.cacheWrite().multiply(new BigDecimal("1.6")))
                .costPerRequestPrice(price.perRequest()).costPriceUnit("M").costPriceSuffix("USD / 1M Token")
                .saleGroupName("本站售价").saleInputPrice(price.saleInput()).saleOutputPrice(price.saleOutput())
                .saleCacheReadPrice(price.saleCacheRead()).saleCacheWritePrice(price.saleCacheWrite())
                .saleCacheWrite1hPrice(price.saleCacheWrite().multiply(new BigDecimal("1.6")))
                .salePerRequestPrice(price.salePerRequest()).salePriceUnit("M").salePriceSuffix("USD / 1M Token")
                .build();
    }

    private void applyAmounts(ModelMapping mapping, Price price) {
        mapping.setInputCostPerMillion(price.input()); mapping.setOutputCostPerMillion(price.output());
        mapping.setCachedCostPerMillion(price.cacheRead()); mapping.setCostPerMillion(price.input());
        mapping.setInputPricePerMillion(price.saleInput()); mapping.setOutputPricePerMillion(price.saleOutput());
        mapping.setCachedPricePerMillion(price.saleCacheRead());
        mapping.setCostUnitPrice(price.perRequest()); mapping.setSaleUnitPrice(price.salePerRequest());
        mapping.setPricingUnit(price.unit());
    }

    private Price calculateServiceTier(String model, JsonNode row, String expression, BigDecimal ratio,
                                       BigDecimal markup, BigDecimal unitUsd) {
        if (row.path("quota_type").asInt(-1) != 0) return pending(model, "分层表达式不是 Token 计费");
        Matcher matcher = SERVICE_TIER_EXPRESSION.matcher(expression);
        if (!matcher.matches()) return pending(model, "上游动态价格格式未识别，未执行表达式");
        try {
            Map<String, BigDecimal> base = coefficients(matcher.group(1));
            Map<String, BigDecimal> priority = coefficients(matcher.group(2));
            BigDecimal factor = ratio.multiply(unitUsd);
            BigDecimal input = money(base.get("p").multiply(factor));
            BigDecimal output = money(base.get("c").multiply(factor));
            BigDecimal read = money(base.get("cr").multiply(factor));
            BigDecimal write = money(base.get("cc").multiply(factor));
            BigDecimal saleInput = money(input.multiply(markup));
            BigDecimal saleOutput = money(output.multiply(markup));
            BigDecimal saleRead = money(read.multiply(markup));
            BigDecimal saleWrite = money(write.multiply(markup));
            String message = "已读取上游当前分层报价：base " + quote(base, factor)
                    + "；priority " + quote(priority, factor)
                    + "；USD/百万 Token，已支持按请求 service_tier 自动计费";
            BigDecimal pi = money(priority.get("p").multiply(factor));
            BigDecimal po = money(priority.get("c").multiply(factor));
            BigDecimal pr = money(priority.get("cr").multiply(factor));
            BigDecimal pw = money(priority.get("cc").multiply(factor));
            Price priorityPrice = new Price(model, "READY", message, "TOKEN", pi, po, pr, pw, ZERO,
                    money(pi.multiply(markup)), money(po.multiply(markup)), money(pr.multiply(markup)), money(pw.multiply(markup)), ZERO);
            return new Price(model, "READY", message, "TOKEN", input, output, read, write, ZERO,
                    saleInput, saleOutput, saleRead, saleWrite, ZERO, priorityPrice);
        } catch (IllegalArgumentException error) {
            return pending(model, "上游动态价格格式未识别，未执行表达式");
        }
    }

    private Map<String, BigDecimal> coefficients(String body) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        values.put("p", ZERO); values.put("c", ZERO); values.put("cr", ZERO); values.put("cc", ZERO);
        for (String raw : body.split("\\+")) {
            Matcher term = PRICE_TERM.matcher(raw.trim());
            if (!term.matches()) throw new IllegalArgumentException("Unsupported price term");
            String name = term.group(1).toLowerCase(Locale.ROOT);
            if (!seen.add(name)) throw new IllegalArgumentException("Duplicate price term");
            BigDecimal amount = new BigDecimal(term.group(2));
            if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000")) > 0)
                throw new IllegalArgumentException("Invalid price coefficient");
            values.put(name, amount);
        }
        if (values.get("p").signum() == 0 || values.get("c").signum() == 0)
            throw new IllegalArgumentException("Missing token price coefficient");
        return values;
    }

    private String quote(Map<String, BigDecimal> values, BigDecimal factor) {
        return "输入 " + plain(money(values.get("p").multiply(factor)))
                + "、输出 " + plain(money(values.get("c").multiply(factor)))
                + "、缓存读 " + plain(money(values.get("cr").multiply(factor)))
                + "、缓存写 " + plain(money(values.get("cc").multiply(factor)));
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** New API's catalog does not expose a normalized capability field, so use conservative model families. */
    public static void applyProtocolMetadata(ModelMapping mapping) {
        if (!isImageModel(mapping.getChannelModelName())) return;
        mapping.setCapability("image");
        mapping.setInputModalities("text");
        mapping.setOutputModalities("image");
        mapping.setProtocols("images");
        mapping.setEndpointPath("/v1/images/generations");
    }

    public static boolean isImageModel(String model) {
        if (model == null) return false;
        String value = model.toLowerCase(Locale.ROOT);
        return value.contains("image") || value.contains("dall-e") || value.contains("imagen")
                || value.contains("seedream") || value.contains("flux") || value.contains("recraft")
                || value.contains("ideogram") || value.contains("stable-diffusion");
    }
    private Price pending(String model, String message) {
        return new Price(model, "PENDING", message, "UNKNOWN", null, null, null, null, null, null, null, null, null, null);
    }
    private BigDecimal required(JsonNode row, String field) {
        BigDecimal value = optional(row.get(field));
        if (value == null) throw new IllegalArgumentException("Missing price");
        return value;
    }
    private BigDecimal optional(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        BigDecimal d = n.decimalValue();
        return d.signum() >= 0 && d.compareTo(new BigDecimal("1000000")) <= 0 ? d : null;
    }
    private BigDecimal money(BigDecimal value) {
        if (value.signum() < 0 || value.compareTo(new BigDecimal("1000000")) > 0) throw new IllegalArgumentException("Price overflow");
        return value.setScale(8, RoundingMode.HALF_UP);
    }
}
