package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.LogMapper;
import com.transit.model.Channel;
import com.transit.model.Log;
import com.transit.model.ModelMapping;
import com.transit.model.User;
import com.transit.dto.MoneyAmount;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlatformOperationsService {

    @Value("${platform.public-base-url:https://linknux.com}")
    private String publicBaseUrl;

    private final JdbcTemplate jdbcTemplate;
    private final LogMapper logMapper;
    private final ChannelMapper channelMapper;
    private final RedeemCodeService redeemCodeService;

    public Map<String, Object> userWallet(User user) {
        List<Map<String, Object>> transactions = jdbcTemplate.queryForList(
                "SELECT id, type, amount, balance_after, channel, remark, created_at FROM wallet_transactions WHERE user_id = ? ORDER BY created_at DESC LIMIT 50",
                user.getId()
        );
        for (Map<String,Object> transaction : transactions) {
            transaction.put("amountMoney",new MoneyAmount(((Number)transaction.get("amount")).longValue(),"CNY",10000));
            transaction.put("balanceAfterMoney",new MoneyAmount(((Number)transaction.get("balance_after")).longValue(),"CNY",10000));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("balance", user.getBalance());
        payload.put("balanceMoney", new MoneyAmount(user.getBalance(), "CNY", 10000));
        payload.put("monthSpend", sumUserCost(user.getId()));
        payload.put("giftBalance", sumWalletType(user.getId(), "GIFT"));
        payload.put("invoiceableAmount", Math.max(0, sumWalletType(user.getId(), "RECHARGE") - sumUserCost(user.getId())));
        payload.put("monthSpendMoney",new MoneyAmount(((Number)payload.get("monthSpend")).longValue(),"CNY",10000));
        payload.put("giftBalanceMoney",new MoneyAmount(((Number)payload.get("giftBalance")).longValue(),"CNY",10000));
        payload.put("invoiceableMoney",new MoneyAmount(((Number)payload.get("invoiceableAmount")).longValue(),"CNY",10000));
        payload.put("transactions", transactions);
        List<Map<String,Object>> plans = jdbcTemplate.queryForList("""
                SELECT id, name, amount, bonus_percent AS bonus
                FROM recharge_plans WHERE enabled = TRUE ORDER BY sort_order, id
                """);
        for (Map<String,Object> plan : plans) {
            long base=((Number)plan.get("amount")).longValue();
            int percent=((Number)plan.get("bonus")).intValue();
            long bonus=java.math.BigDecimal.valueOf(base).multiply(java.math.BigDecimal.valueOf(percent))
                    .divide(java.math.BigDecimal.valueOf(100),0,java.math.RoundingMode.HALF_UP).longValue();
            plan.put("paymentMoney",new MoneyAmount(base,"CNY",10000));
            plan.put("baseCreditMoney",new MoneyAmount(base,"CNY",10000));
            plan.put("bonusCreditMoney",new MoneyAmount(bonus,"CNY",10000));
            plan.put("totalCreditMoney",new MoneyAmount(Math.addExact(base,bonus),"CNY",10000));
        }
        payload.put("plans", plans);
        return payload;
    }

    public Map<String, Object> recharge(User user, long amount, String channel, String remark) {
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "Balance can only be credited after a verified payment or an administrator adjustment"
        );
    }

    @Transactional
    public Map<String, Object> redeem(User user, String code) {
        return redeemCodeService.redeem(user.getId(), code);
    }

    public Map<String, Object> userSecurity(User user) {
        return Map.of(
                "profile", Map.of(
                        "id", user.getId(),
                        "username", user.getUsername(),
                        "email", user.getEmail(),
                        "role", user.getRole(),
                        "createdAt", user.getCreatedAt()
                ),
                "implementedCapabilities", List.of(
                        "密码哈希存储",
                        "会话注销与服务端撤销",
                        "API Key 模型/IP/有效期限制",
                        "登录失败限速"),
                "plannedCapabilities", List.of("双因素认证", "登录设备管理", "Webhook 通知")
        );
    }

    public Map<String, Object> docsMetadata() {
        String baseUrl = publicBaseUrl.replaceAll("/+$", "") + "/v1";
        return Map.of(
                "baseUrl", baseUrl,
                "endpoints", Map.of(
                        "models", baseUrl + "/models",
                        "chatCompletions", baseUrl + "/chat/completions",
                        "responses", baseUrl + "/responses",
                        "embeddings", baseUrl + "/embeddings"),
                "keyPolicy", Map.of(
                        "allowAllModels", true,
                        "modelSelectionField", "model",
                        "description", "一个授权全部模型的 Key 可调用账户可见的所有模型，每次请求用 model 字段选择。"),
                "clients", List.of(
                        docsClient("curl", "curl / HTTP", "OPENAI", "使用完整 /v1/chat/completions 端点。"),
                        docsClient("python", "Python OpenAI SDK", "OPENAI", "base_url 填写以 /v1 结尾的地址。"),
                        docsClient("javascript", "JavaScript OpenAI SDK", "OPENAI", "baseURL 填写以 /v1 结尾的地址。"),
                        docsClient("workbuddy", "WorkBuddy", "OPENAI", "选择 OpenAI 兼容服务，填写 Base URL、Key 和模型 ID。"),
                        docsClient("teleagent", "TeleAgent", "OPENAI", "配置自定义 OpenAI 兼容模型服务。"),
                        docsClient("qclaw", "QClaw 小龙虾", "OPENAI", "Provider 选择 OpenAI 兼容，并填写平台模型 ID。"),
                        docsClient("openclaw", "OpenClaw", "OPENAI", "Provider 选择 OpenAI 兼容。"),
                        docsClient("ccswitch", "CC Switch", "OPENAI_OR_ANTHROPIC", "按目标客户端协议配置统一网关。"),
                        docsClient("trae", "TRAE", "OPENAI", "添加自定义 OpenAI 兼容模型。"),
                        docsClient("opencode", "OpenCode", "OPENAI", "provider.baseURL 填写以 /v1 结尾的地址。"),
                        docsClient("cherry-studio", "Cherry Studio", "OPENAI", "API 主机地址填写以 /v1 结尾的地址。"),
                        docsClient("claude-code", "Claude Code", "ANTHROPIC", "仅在所选模型开放 Anthropic 兼容协议时使用。"),
                        docsClient("codex", "Codex", "OPENAI", "配置 OpenAI 兼容 Base URL、API Key 和模型 ID。")),
                "errors", List.of(
                        Map.of("code", "TOKEN_DISABLED", "message", "Token has been disabled"),
                        Map.of("code", "QUOTA_EXCEEDED", "message", "Token quota exceeded"),
                        Map.of("code", "MODEL_NOT_MAPPED", "message", "No enabled channel for requested public model")
                )
        );
    }

    private Map<String, String> docsClient(String id, String name, String protocol, String note) {
        return Map.of("id", id, "name", name, "protocol", protocol, "note", note);
    }

    public Map<String, Object> adminDashboard() {
        List<Log> logs = logMapper.selectList(null);
        long success = logs.stream().filter(log -> "SUCCESS".equalsIgnoreCase(log.getStatus())).count();
        long failed = logs.stream().filter(log -> "FAILED".equalsIgnoreCase(log.getStatus())).count();
        long totalCost = logs.stream().mapToLong(Log::getCost).sum();
        long totalTokens = logs.stream().mapToLong(Log::getTotalTokens).sum();
        return Map.of(
                "metrics", Map.of(
                        "requests", logs.size(),
                        "successRate", logs.isEmpty() ? 0 : success * 100.0 / logs.size(),
                        "failedRequests", failed,
                        "consumedTokens", totalTokens,
                        "estimatedRevenue", totalCost
                ),
                "riskQueue", List.of(
                        Map.of("type", "CHANNEL_BALANCE", "title", "渠道余额低", "severity", "HIGH"),
                        Map.of("type", "FAILURE_RATE", "title", "失败率升高", "severity", failed > success ? "HIGH" : "MEDIUM"),
                        Map.of("type", "FULFILLMENT", "title", "订单待履约", "severity", "MEDIUM")
                ),
                "generatedAt", LocalDateTime.now()
        );
    }

    public List<Map<String, Object>> adminUsers() {
        return jdbcTemplate.queryForList("""
                SELECT u.id, u.username, u.email, u.role, u.balance, u.created_at,
                       COALESCE(g.display_name, '默认用户组') AS group_name,
                       COALESCE(g.price_ratio, 1) AS price_ratio
                FROM users u
                LEFT JOIN user_groups g ON g.name = 'default'
                ORDER BY u.created_at DESC
                """);
    }

    public Map<String, Object> createUserGroup(Map<String, Object> request) {
        String name = stringValue(request, "name", "group-" + UUID.randomUUID().toString().substring(0, 8));
        String displayName = stringValue(request, "displayName", name);
        double ratio = doubleValue(request, "priceRatio", 1);
        long quota = longValue(request, "monthlyQuota", 0);
        jdbcTemplate.update(
                "INSERT INTO user_groups(name, display_name, price_ratio, monthly_quota, description) VALUES (?, ?, ?, ?, ?)",
                name, displayName, ratio, quota, stringValue(request, "description", "")
        );
        return Map.of("name", name, "displayName", displayName, "priceRatio", ratio, "monthlyQuota", quota);
    }

    public List<Map<String, Object>> modelPricing() {
        return jdbcTemplate.queryForList("""
                SELECT m.id, m.public_model_name, m.channel_model_name, m.priority, m.enabled,
                       COALESCE(c.name, '') AS channel_name,
                       COALESCE(c.group_name, 'default') AS group_name,
                       m.price_ratio, m.cost_per_million, m.traffic_percent, m.capability_tags
                FROM model_mappings m
                LEFT JOIN channels c ON c.id = m.channel_id
                ORDER BY m.public_model_name ASC, m.priority DESC
                """);
    }

    public Map<String, Object> upsertModelPricing(Long id, Map<String, Object> request) {
        if (id == null) {
            jdbcTemplate.update("""
                    INSERT INTO model_mappings(public_model_name, channel_model_name, channel_id, priority, enabled, price_ratio, cost_per_million, traffic_percent, capability_tags)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    stringValue(request, "publicModelName", "new-model"),
                    stringValue(request, "channelModelName", "new-model"),
                    nullableLong(request.get("channelId")),
                    intValue(request, "priority", 0),
                    boolValue(request, "enabled", true),
                    doubleValue(request, "priceRatio", 1),
                    doubleValue(request, "costPerMillion", 0),
                    intValue(request, "trafficPercent", 100),
                    stringValue(request, "capabilityTags", "")
            );
            return Map.of("created", true);
        }
        jdbcTemplate.update("""
                UPDATE model_mappings
                SET price_ratio = ?, cost_per_million = ?, traffic_percent = ?, capability_tags = ?, enabled = ?
                WHERE id = ?
                """,
                doubleValue(request, "priceRatio", 1),
                doubleValue(request, "costPerMillion", 0),
                intValue(request, "trafficPercent", 100),
                stringValue(request, "capabilityTags", ""),
                boolValue(request, "enabled", true),
                id
        );
        return Map.of("updated", true, "id", id);
    }

    public List<Map<String, Object>> financeTransactions() {
        return jdbcTemplate.queryForList("""
                SELECT wt.id, wt.user_id, u.username, wt.type, wt.amount, wt.balance_after, wt.channel, wt.remark, wt.created_at
                FROM wallet_transactions wt
                LEFT JOIN users u ON u.id = wt.user_id
                ORDER BY wt.created_at DESC
                LIMIT 200
                """);
    }

    public Map<String, Object> createRedeemCode(Map<String, Object> request) {
        String code = stringValue(request, "code", null);
        long amount = longValue(request, "amount", 20_0000L);
        int maxUses = intValue(request, "maxUses", 1);
        return redeemCodeService.issue(code, amount, maxUses);
    }

    public List<Map<String, Object>> redeemCodes() {
        return redeemCodeService.list();
    }

    public List<Map<String, Object>> settings() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT setting_key, setting_value, description, updated_at FROM system_settings ORDER BY setting_key");
        rows.forEach(row -> {
            String key = String.valueOf(row.get("setting_key")).toLowerCase();
            if (key.contains("key") || key.contains("secret") || key.contains("password") || key.contains("token")) row.put("setting_value", "****");
        });
        return rows;
    }

    public Map<String, Object> updateSetting(Map<String, Object> request) {
        String key = stringValue(request, "key", "custom.setting");
        String normalizedKey = key.toLowerCase();
        if (normalizedKey.startsWith("creative.") || normalizedKey.contains("api-key") || normalizedKey.contains("secret") || normalizedKey.contains("access-key")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "敏感配置不能写入明文 system_settings，请使用对应的专用配置页面");
        }
        String value = stringValue(request, "value", "");
        String description = stringValue(request, "description", "");
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM system_settings WHERE setting_key = ?",
                Integer.class,
                key
        );
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO system_settings(setting_key, setting_value, description, updated_at) VALUES (?, ?, ?, ?)",
                    key,
                    value,
                    description,
                    LocalDateTime.now()
            );
        } else {
            jdbcTemplate.update(
                    "UPDATE system_settings SET setting_value = ?, description = ?, updated_at = ? WHERE setting_key = ?",
                    value,
                    description,
                    LocalDateTime.now(),
                    key
            );
        }
        return Map.of("key", key, "value", value);
    }

    public List<Map<String, Object>> securityPolicies() {
        return jdbcTemplate.queryForList("SELECT id, name, scope, action, threshold_value, enabled, created_at FROM security_policies ORDER BY id DESC");
    }

    public Map<String, Object> saveSecurityPolicy(Map<String, Object> request) {
        Long id = nullableLong(request.get("id"));
        if (id == null) {
            jdbcTemplate.update(
                    "INSERT INTO security_policies(name, scope, action, threshold_value, enabled, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    stringValue(request, "name", "新策略"),
                    stringValue(request, "scope", "全站"),
                    stringValue(request, "action", "告警"),
                    stringValue(request, "threshold", ""),
                    boolValue(request, "enabled", true),
                    LocalDateTime.now()
            );
            return Map.of("created", true);
        }
        jdbcTemplate.update(
                "UPDATE security_policies SET name = ?, scope = ?, action = ?, threshold_value = ?, enabled = ? WHERE id = ?",
                stringValue(request, "name", "策略"),
                stringValue(request, "scope", "全站"),
                stringValue(request, "action", "告警"),
                stringValue(request, "threshold", ""),
                boolValue(request, "enabled", true),
                id
        );
        return Map.of("updated", true, "id", id);
    }

    public Map<String, Object> reports() {
        List<Log> logs = logMapper.selectList(null);
        long revenue = logs.stream().mapToLong(Log::getCost).sum();
        long failures = logs.stream().filter(log -> "FAILED".equalsIgnoreCase(log.getStatus())).count();
        return Map.of(
                "grossMargin", revenue == 0 ? 0 : 0.32,
                "p95LatencyMs", queryLong("SELECT COALESCE(MAX(latency_ms), 0) FROM logs"),
                "highPriceModelShare", 0.18,
                "failureCost", failures * 10,
                "templates", List.of(
                        Map.of("dimension", "用户 / 分组", "metrics", "费用 / Token", "period", "日 / 周 / 月", "format", "CSV"),
                        Map.of("dimension", "模型 / 渠道", "metrics", "延迟 / 成功率", "period", "小时 / 日", "format", "XLSX"),
                        Map.of("dimension", "订单 / 钱包", "metrics", "收入 / 退款", "period", "月", "format", "PDF")
                )
        );
    }

    public Map<String, Object> integrationExport(String clientType, User user) {
        String baseUrl = "/api/v1";
        String token = "YOUR_API_KEY";
        String config = switch (clientType == null ? "openai" : clientType.toLowerCase()) {
            case "cherry-studio" -> "provider: API Transit\nbase_url: " + baseUrl + "\napi_key: " + token;
            case "claude-code-router" -> "{\n  \"Providers\": [{\"name\":\"api-transit\",\"api_base_url\":\"" + baseUrl + "\",\"api_key\":\"" + token + "\"}]\n}";
            default -> "OPENAI_BASE_URL=" + baseUrl + "\nOPENAI_API_KEY=" + token;
        };
        return Map.of(
                "clientType", clientType == null ? "openai" : clientType,
                "config", config,
                "requiresApiKey", true,
                "notice", "Create an API Key and replace YOUR_API_KEY locally; secrets are never exported or stored",
                "generatedAt", LocalDateTime.now());
    }

    public Map<String, Object> channelGovernance() {
        List<Channel> channels = channelMapper.selectList(null);
        return Map.of(
                "channels", channels,
                "policies", List.of("失败自动切换", "成本优先", "区域亲和", "并发限流"),
                "healthSummary", Map.of(
                        "total", channels.size(),
                        "enabled", channels.stream().filter(Channel::isEnabled).count()
                )
        );
    }

    private long sumUserCost(Long userId) {
        return logMapper.selectList(new LambdaQueryWrapper<Log>().eq(Log::getUserId, userId))
                .stream()
                .mapToLong(Log::getCost)
                .sum();
    }

    private long sumWalletType(Long userId, String type) {
        Number number = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM wallet_transactions WHERE user_id = ? AND type = ?",
                Number.class,
                userId,
                type
        );
        return number == null ? 0 : number.longValue();
    }

    private long queryLong(String sql) {
        Number number = jdbcTemplate.queryForObject(sql, Number.class);
        return number == null ? 0 : number.longValue();
    }

    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Long.parseLong(value.toString());
    }

    private int intValue(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Integer.parseInt(value.toString());
    }

    private double doubleValue(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Double.parseDouble(value.toString());
    }

    private boolean boolValue(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value == null || value.toString().isBlank()) return fallback;
        return Boolean.parseBoolean(value.toString());
    }

    private Long nullableLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }
}
