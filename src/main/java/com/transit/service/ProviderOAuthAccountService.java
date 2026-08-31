package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ProviderCredentialMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ProviderCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProviderOAuthAccountService {
    private final UpstreamOAuthProviderRegistry registry;
    private final UpstreamOAuthClientConfigService clientConfigs;
    private final UpstreamOAuthStateService states;
    private final OAuthCredentialBundleService bundles;
    private final ProviderPriceTemplateService prices;
    private final ProviderOAuthTokenService oauthTokens;
    private final ProviderCredentialMapper credentialMapper;
    private final ChannelMapper channelMapper;
    private final ModelMappingMapper mappingMapper;
    private final JdbcTemplate jdbc;

    public Map<String, Object> status() {
        Map<String, Object> platforms = new LinkedHashMap<>();
        for (String platform : UpstreamOAuthProviderRegistry.OAUTH_PLATFORMS) {
            UpstreamOAuthClientConfigService.RuntimeConfig config = clientConfigs.resolve(platform);
            platforms.put(platform, Map.of("enabled", config.enabled(), "configured", config.configured(),
                    "encryptionReady", clientConfigs.encryptionReady(),
                    "ready", config.enabled() && config.configured() && clientConfigs.encryptionReady(), "source", config.source()));
        }
        return platforms;
    }

    public Map<String, Object> authorize(String rawPlatform, long adminId, Map<String, Object> body) {
        String platform = platform(rawPlatform); requireReady(platform);
        Long templateId = number(body.get("priceTemplateId"));
        if (templateId == null) throw bad("priceTemplateId 不能为空；未定价模型不会进入调度");
        Map<String, Object> template = prices.get(templateId);
        if (!platform.equalsIgnoreCase(String.valueOf(template.get("platform")))) throw bad("价格模板平台与授权平台不一致");
        String redirectUri = redirectUri(platform);
        String callbackMode = text(body.getOrDefault("callbackMode", "POPUP")).toUpperCase(Locale.ROOT);
        UpstreamOAuthClientConfigService.RuntimeConfig clientConfig = clientConfigs.resolve(platform);
        UpstreamOAuthStateService.Created state = states.create(platform, adminId, number(body.get("reauthorizeCredentialId")), number(body.get("upstreamProxyId")), templateId,
                text(body.getOrDefault("accountGroup", "default")), text(body.get("modelScope")), redirectUri, callbackMode, clientConfig.version());
        UpstreamOAuthProvider provider = registry.require(platform);
        String url = provider.authorizationUrl(state.state(), state.nonce(), UpstreamOAuthStateService.challenge(state.verifier()), redirectUri);
        return Map.of("flowId", state.flowId(), "platform", platform, "authorizationUrl", url,
                "callbackMode", callbackMode, "expiresAt", state.expiresAt(), "manualExchangeSupported", true);
    }

    public Map<String, Object> exchangeManual(String rawPlatform, Map<String, Object> body) {
        String callback = text(body.get("callbackUrl"));
        String code = text(body.get("code")), state = text(body.get("state"));
        if (!callback.isBlank()) {
            try {
                var params = UriComponentsBuilder.fromUriString(callback).build().getQueryParams();
                if (code.isBlank()) code = Objects.toString(params.getFirst("code"), "");
                if (state.isBlank()) state = Objects.toString(params.getFirst("state"), "");
            } catch (RuntimeException exception) { throw bad("callbackUrl 无效"); }
        }
        return complete(rawPlatform, code, state);
    }

    @Transactional
    public Map<String, Object> synchronizeModels(long credentialId) {
        ProviderCredential account = requireAccount(credentialId); Channel channel = channelMapper.selectById(account.getChannelId());
        oauthTokens.context(account, channel); account = requireAccount(credentialId);
        UpstreamOAuthProvider.OAuthToken token = bundles.decrypt(account.getCredentialBundle());
        UpstreamOAuthProvider.Inspection inspection = registry.require(account.getPlatform()).inspect(token, account.getUpstreamProxyId());
        List<String> priced = new ArrayList<>(), pending = new ArrayList<>();
        for (String model : inspection.models()) {
            ProviderPriceTemplateService.Match match = prices.match(account.getPlatform(), model, account.getPriceTemplateId());
            saveMapping(channel.getId(), account.getPlatform(), model, match, capabilities(inspection, model));
            if (match == null) pending.add(model); else priced.add(model);
        }
        account.setExternalAccountId(inspection.externalAccountId()); account.setEmailPreview(maskEmail(inspection.email()));
        account.setSubscriptionTier(inspection.subscriptionTier()); account.setEntitlementStatus(inspection.entitlementStatus());
        account.setModelScope(String.join(",", priced)); account.setCostReliable(!priced.isEmpty());
        account.setEnabled("ACTIVE".equalsIgnoreCase(inspection.entitlementStatus()) && !priced.isEmpty());
        account.setHealthStatus(account.isEnabled() ? "HEALTHY" : "DISABLED"); account.setUpdatedAt(LocalDateTime.now()); credentialMapper.updateById(account);
        channel.setModels(String.join("\n", inspection.models())); channelMapper.updateById(channel);
        event(account.getId(), "MODELS_SYNCED", null, false, "Discovered " + inspection.models().size() + " models; enabled " + priced.size());
        return Map.of("credentialId", credentialId, "enabledModels", priced, "pendingPricingModels", pending, "entitlementStatus", inspection.entitlementStatus());
    }

    @Transactional
    public Map<String, Object> synchronizeQuota(long credentialId) {
        ProviderCredential account = requireAccount(credentialId); oauthTokens.context(account, channelMapper.selectById(account.getChannelId())); account = requireAccount(credentialId);
        UpstreamOAuthProvider.Inspection inspection = registry.require(account.getPlatform()).inspect(bundles.decrypt(account.getCredentialBundle()), account.getUpstreamProxyId());
        long limit = longValue(inspection.metadata().get("quota_limit")), used = longValue(inspection.metadata().get("quota_used"));
        long remaining = inspection.metadata().containsKey("quota_remaining") ? longValue(inspection.metadata().get("quota_remaining")) : Math.max(0, limit - used);
        jdbc.update("INSERT INTO provider_account_quota_snapshots(credential_id,quota_type,used_amount,limit_amount,remaining_amount,source,captured_at) VALUES (?,?,?,?,?,?,?)",
                credentialId, "UPSTREAM", used, limit, remaining, "OAUTH_PROBE", LocalDateTime.now());
        event(credentialId, "QUOTA_SYNCED", null, false, "Quota synchronized; sensitive provider metadata omitted");
        return Map.of("credentialId", credentialId, "usedAmount", used, "limitAmount", limit, "remainingAmount", remaining, "capturedAt", LocalDateTime.now());
    }

    @Transactional
    public Map<String, Object> complete(String rawPlatform, String code, String state) {
        String platform = platform(rawPlatform); requireReady(platform);
        if (code == null || code.isBlank() || state == null || state.isBlank()) throw bad("OAuth code/state 不能为空");
        UpstreamOAuthStateService.Consumed flow = states.consume(platform, state);
        UpstreamOAuthClientConfigService.RuntimeConfig clientConfig = clientConfigs.resolve(platform);
        if (flow.clientConfigVersion() != clientConfig.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OAuth Client 配置在授权期间已变更，请重新开始授权");
        }
        UpstreamOAuthProvider provider = registry.require(platform);
        UpstreamOAuthProvider.OAuthToken token = provider.exchange(code, flow.verifier(), flow.redirectUri(), flow.proxyId());
        if (Boolean.TRUE.equals(token.metadata().get("idTokenPresent"))
                && !Objects.equals(flow.nonce(), token.metadata().get("idTokenNonce"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth nonce 校验失败");
        }
        UpstreamOAuthProvider.Inspection inspection = provider.inspect(token, flow.proxyId());
        if (!"ACTIVE".equalsIgnoreCase(inspection.entitlementStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "上游订阅 entitlement 不可用");
        Channel channel = managedChannel(platform, provider.upstreamBaseUrl());
        List<String> requested = requestedModels(inspection.models(), flow.modelScope());
        List<String> priced = new ArrayList<>(), pending = new ArrayList<>();
        for (String model : requested) {
            ProviderPriceTemplateService.Match match = prices.match(platform, model, flow.templateId());
            saveMapping(channel.getId(), platform, model, match, capabilities(inspection, model));
            if (match == null) pending.add(model); else priced.add(model);
        }
        if (priced.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "模型发现成功，但没有任何模型匹配可靠成本模板");
        ProviderCredential account = ProviderCredential.builder().channelId(channel.getId())
                .name(platform + " · " + maskEmail(inspection.email())).platform(platform).authType("OAUTH")
                .secret(null).secretPreview("OAuth ****").credentialBundle(bundles.encrypt(token))
                .oauthExpiresAt(LocalDateTime.ofInstant(token.expiresAt(), ZoneId.systemDefault()))
                .externalAccountId(inspection.externalAccountId()).emailPreview(maskEmail(inspection.email()))
                .subscriptionTier(inspection.subscriptionTier()).authorizationScope(token.scope())
                .entitlementStatus("ACTIVE").tokenVersion(1).lastRefreshedAt(LocalDateTime.now())
                .accountGroup(flow.accountGroup()).upstreamProxyId(flow.proxyId()).costMode("PRICE_TEMPLATE")
                .priceTemplateId(flow.templateId())
                .costReliable(true).modelScope(String.join(",", priced)).priority(0).weight(100)
                .enabled(true).healthStatus("HEALTHY").createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        if (flow.reauthorizeCredentialId() == null) {
            if (account.getExternalAccountId() != null && credentialMapper.selectCount(new LambdaQueryWrapper<ProviderCredential>()
                    .eq(ProviderCredential::getPlatform, platform).eq(ProviderCredential::getExternalAccountId, account.getExternalAccountId())) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该上游账号已授权，请使用重新授权");
            credentialMapper.insert(account);
        }
        else {
            ProviderCredential previous = requireAccount(flow.reauthorizeCredentialId());
            if (!platform.equals(previous.getPlatform()) || !Objects.equals(channel.getId(), previous.getChannelId())) throw bad("重新授权账号与平台不匹配");
            account.setId(previous.getId()); account.setCreatedAt(previous.getCreatedAt());
            account.setPriority(previous.getPriority()); account.setWeight(previous.getWeight()); account.setRpmLimit(previous.getRpmLimit());
            account.setTpmLimit(previous.getTpmLimit()); account.setConcurrencyLimit(previous.getConcurrencyLimit());
            credentialMapper.updateById(account);
        }
        channel.setModels(String.join("\n", requested)); channel.setHealthStatus("HEALTHY"); channelMapper.updateById(channel);
        event(account.getId(), "AUTHORIZED", null, false, "OAuth authorization and upstream probe succeeded");
        return Map.of("status", "AUTHORIZED", "credentialId", account.getId(), "channelId", channel.getId(),
                "platform", platform, "emailPreview", Objects.toString(account.getEmailPreview(), ""),
                "subscriptionTier", Objects.toString(account.getSubscriptionTier(), ""), "enabledModels", priced, "pendingPricingModels", pending);
    }

    private Channel managedChannel(String platform, String baseUrl) {
        List<Channel> rows = channelMapper.selectList(new LambdaQueryWrapper<Channel>().eq(Channel::getSourceCode, "oauth-" + platform.toLowerCase(Locale.ROOT)));
        if (!rows.isEmpty()) return rows.get(0);
        Channel channel = Channel.builder().name("Managed " + platform + " OAuth").type(type(platform))
                .sourceCode("oauth-" + platform.toLowerCase(Locale.ROOT)).sourceName(platform + " 订阅账号池")
                .protocolType(protocol(platform)).baseUrl(baseUrl).apiKey(null).models("").managed(true)
                .managedPlatform(platform).managedAuthType("OAUTH").enabled(true).groupName("managed-oauth")
                .weight(100).healthStatus("UNTESTED").createdAt(LocalDateTime.now()).build();
        channelMapper.insert(channel); return channel;
    }

    private void saveMapping(long channelId, String platform, String model, ProviderPriceTemplateService.Match match, List<String> capabilities) {
        ModelMapping row = mappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>().eq(ModelMapping::getChannelId, channelId)
                .eq(ModelMapping::getChannelModelName, model).last("LIMIT 1")).stream().findFirst().orElse(null);
        if (row == null) row = ModelMapping.builder().channelId(channelId).publicModelName(model).channelModelName(model).vendor(platform.toLowerCase(Locale.ROOT)).build();
        row.setEnabled(match != null); row.setBillingEnabled(match != null); row.setPricingStatus(match == null ? "PENDING" : "VERIFIED");
        row.setProtocols(protocols(platform, capabilities));
        row.setPricingMessage(match == null ? "待定价：模型未匹配价格模板" : "OAuth 探测与价格模板已验证");
        row.setPricingVerifiedAt(match == null ? null : LocalDateTime.now()); row.setPricingSourceUrl(match == null ? null : match.sourceUrl());
        if (match != null) {
            row.setPricingUnit(match.pricingUnit());
            row.setInputPricePerMillion(decimal(match.salePrice(), "inputPerMillion", "input"));
            row.setOutputPricePerMillion(decimal(match.salePrice(), "outputPerMillion", "output"));
            row.setCachedPricePerMillion(decimal(match.salePrice(), "cachedPerMillion", "cache"));
            row.setInputCostPerMillion(decimal(match.costPrice(), "inputPerMillion", "input"));
            row.setOutputCostPerMillion(decimal(match.costPrice(), "outputPerMillion", "output"));
            row.setCachedCostPerMillion(decimal(match.costPrice(), "cachedPerMillion", "cache"));
            row.setOfficialUnitPrice(decimal(match.officialPrice(), "unitPrice", "official"));
            row.setCostUnitPrice(decimal(match.costPrice(), "unitPrice", "cost")); row.setSaleUnitPrice(decimal(match.salePrice(), "unitPrice", "sale"));
        }
        if (row.getId() == null) mappingMapper.insert(row); else mappingMapper.updateById(row);
    }

    private List<String> requestedModels(List<String> discovered, String scope) {
        if (scope == null || scope.isBlank() || "*".equals(scope.trim())) return discovered;
        List<String> allowed = List.of(scope.split(",")).stream().map(String::trim).toList();
        return discovered.stream().filter(model -> allowed.stream().anyMatch(item -> item.equalsIgnoreCase(model))).toList();
    }
    private BigDecimal decimal(Map<String, Object> values, String primary, String fallback) { Object value = values.get(primary); if (value == null) value = values.get(fallback); try { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return BigDecimal.ZERO; } }
    private String redirectUri(String platform) { String base = clientConfigs.resolve(platform).callbackBaseUrl(); if (base == null || base.isBlank()) throw new ResponseStatusException(HttpStatus.CONFLICT, "缺少 OAuth 回调根地址"); return base.replaceAll("/+$", "") + "/upstream/oauth/callback/" + platform.toLowerCase(Locale.ROOT); }
    private void requireReady(String platform) { UpstreamOAuthClientConfigService.RuntimeConfig config = clientConfigs.resolve(platform); if (!clientConfigs.encryptionReady()) throw new ResponseStatusException(HttpStatus.CONFLICT, "数据加密主密钥未配置"); if (!config.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, platform + " OAuth 功能未启用"); if (!config.configured() || !registry.require(platform).configured()) throw new ResponseStatusException(HttpStatus.CONFLICT, platform + " OAuth client/端点配置不完整"); }
    private String platform(String value) { String normalized = text(value).toUpperCase(Locale.ROOT); if (!UpstreamOAuthProviderRegistry.OAUTH_PLATFORMS.contains(normalized)) throw bad("该平台不支持订阅 OAuth；OPENAI 仅允许官方 API Key"); return normalized; }
    private String type(String platform) { return switch (platform) { case "CLAUDE" -> "anthropic"; case "GEMINI" -> "gemini"; case "GROK" -> "xai"; case "ANTIGRAVITY" -> "antigravity"; default -> "openai"; }; }
    private String protocol(String platform) { return switch (platform) { case "CLAUDE" -> "anthropic-messages"; case "GEMINI" -> "gemini-generate-content"; case "ANTIGRAVITY" -> "multi-protocol"; default -> "openai-responses"; }; }
    private String protocols(String platform, List<String> discovered) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        result.add(switch (platform) { case "CLAUDE" -> "messages"; case "GEMINI" -> "gemini-generate-content"; case "ANTIGRAVITY" -> "messages"; default -> "responses"; });
        if (platform.equals("CODEX") || platform.equals("GROK")) result.add("chat-completions");
        for (String raw : discovered) {
            String value = raw.toLowerCase(Locale.ROOT).replace('_','-');
            switch (value) {
                case "generatecontent" -> result.add("gemini-generate-content");
                case "streamgeneratecontent" -> result.add("gemini-stream-generate-content");
                case "counttokens", "count-tokens" -> result.add(platform.equals("GEMINI") ? "gemini-count-tokens" : "count-tokens");
                case "embedcontent", "embeddings", "embedding" -> result.add("embeddings");
                case "chat", "chat-completions" -> result.add("chat-completions");
                default -> { if (List.of("responses","messages","images","image-edits","video","audio-speech","audio-transcriptions","audio-translations","custom-voices","web-search","x-search","realtime").contains(value)) result.add(value); }
            }
        }
        return String.join(",", result);
    }
    private List<String> capabilities(UpstreamOAuthProvider.Inspection inspection, String model) {
        Object raw = inspection.metadata().get("modelCapabilities");
        if (!(raw instanceof Map<?, ?> map) || !(map.get(model) instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
    }
    private void event(long id, String type, String errorClass, boolean retryable, String detail) {
        jdbc.update("INSERT INTO provider_account_events(credential_id,event_type,error_class,retryable,detail_masked,created_at) VALUES (?,?,?,?,?,?)",
                id, type, errorClass, retryable, detail, LocalDateTime.now());
    }
    private ProviderCredential requireAccount(long id) { ProviderCredential value = credentialMapper.selectById(id); if (value == null || !"OAUTH".equalsIgnoreCase(value.getAuthType())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OAuth 上游账号不存在"); return value; }
    private long longValue(Object value) { try { return value == null ? 0 : new BigDecimal(String.valueOf(value)).longValue(); } catch (Exception ignored) { return 0; } }
    private String maskEmail(String email) { if (email == null || email.isBlank()) return "账号"; int at = email.indexOf('@'); return at <= 1 ? "***" : email.substring(0, Math.min(2, at)) + "***" + email.substring(at); }
    private Long number(Object value) { return value instanceof Number number ? number.longValue() : value instanceof String text && !text.isBlank() ? Long.valueOf(text) : null; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
