package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ProviderCredentialMapper;
import com.transit.model.Channel;
import com.transit.model.ProviderCredential;
import com.transit.model.ProviderAuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ProviderCredentialService {
    private final ProviderCredentialMapper mapper;
    private final ChannelMapper channelMapper;
    private final ChannelSecretService secrets;
    private final ConcurrentHashMap<Long, AtomicInteger> roundRobin = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    @Autowired(required = false)
    private StringRedisTemplate redis;
    @Autowired(required = false)
    private ProviderOAuthTokenService oauthTokens;
    @Value("${features.linknux.provider-accounts.enabled:false}")
    private boolean providerAccountsEnabled;

    public List<ProviderCredential> list(Long channelId) {
        return mapper.selectList(new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getChannelId, channelId)
                        .orderByDesc(ProviderCredential::getPriority)
                        .orderByAsc(ProviderCredential::getId))
                .stream().map(this::redact).toList();
    }

    public boolean hasAvailable(Channel channel) {
        if (channel == null || channel.getId() == null) return false;
        LambdaQueryWrapper<ProviderCredential> query = new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getChannelId, channel.getId())
                .eq(ProviderCredential::isEnabled, true)
                .and(q -> q.isNull(ProviderCredential::getCooldownUntil)
                        .or().lt(ProviderCredential::getCooldownUntil, LocalDateTime.now()));
        if (channel.isManaged()) query.eq(ProviderCredential::getAuthType, "OAUTH")
                .eq(ProviderCredential::getEntitlementStatus, "ACTIVE").eq(ProviderCredential::isCostReliable, true)
                .in(ProviderCredential::getHealthStatus, List.of("HEALTHY", "DEGRADED"));
        long count = mapper.selectCount(query);
        return count > 0 || (channel.getApiKey() != null && !channel.getApiKey().isBlank());
    }

    @Transactional
    public ProviderCredential create(Long channelId, ProviderCredential request) {
        requireChannel(channelId);
        validate(request, true);
        ProviderCredential row = ProviderCredential.builder()
                .channelId(channelId).name(request.getName().trim())
                .secret(secrets.encrypt(request.getSecret()))
                .secretPreview(mask(request.getSecret()))
                .priority(request.getPriority()).weight(Math.max(1, request.getWeight()))
                .platform(normalize(request.getPlatform(), "COMPATIBLE"))
                .authType(normalize(request.getAuthType(), "API_KEY"))
                .accountGroup(normalize(request.getAccountGroup(), "default"))
                .upstreamProxyId(request.getUpstreamProxyId())
                .costMode(normalize(request.getCostMode(), "MODEL_MAPPING"))
                .periodCostAmount(Math.max(0, request.getPeriodCostAmount()))
                .costReliable(request.isCostReliable())
                .modelScope(request.getModelScope())
                .rpmLimit(Math.max(0, request.getRpmLimit()))
                .tpmLimit(Math.max(0, request.getTpmLimit()))
                .concurrencyLimit(Math.max(0, request.getConcurrencyLimit()))
                .enabled(request.isEnabled()).healthStatus("UNTESTED")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        mapper.insert(row);
        return redact(row);
    }

    @Transactional
    public ProviderCredential update(Long channelId, Long id, ProviderCredential request) {
        ProviderCredential row = require(channelId, id);
        validate(request, false);
        row.setName(request.getName().trim());
        if (request.getSecret() != null && !request.getSecret().isBlank() && !request.getSecret().contains("****")) {
            row.setSecret(secrets.encrypt(request.getSecret()));
            row.setSecretPreview(mask(request.getSecret()));
            row.setHealthStatus("UNTESTED");
        }
        row.setPriority(request.getPriority());
        row.setWeight(Math.max(1, request.getWeight()));
        row.setRpmLimit(Math.max(0, request.getRpmLimit()));
        row.setTpmLimit(Math.max(0, request.getTpmLimit()));
        row.setConcurrencyLimit(Math.max(0, request.getConcurrencyLimit()));
        row.setPlatform(normalize(request.getPlatform(), row.getPlatform()));
        row.setAuthType(normalize(request.getAuthType(), row.getAuthType()));
        row.setAccountGroup(normalize(request.getAccountGroup(), row.getAccountGroup()));
        row.setUpstreamProxyId(request.getUpstreamProxyId());
        row.setCostMode(normalize(request.getCostMode(), row.getCostMode()));
        row.setPeriodCostAmount(Math.max(0, request.getPeriodCostAmount()));
        row.setCostReliable(request.isCostReliable());
        row.setModelScope(request.getModelScope());
        row.setEnabled(request.isEnabled());
        row.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(row);
        return redact(row);
    }

    public void delete(Long channelId, Long id) {
        mapper.deleteById(require(channelId, id));
    }

    /** Selects one healthy credential while preserving the legacy channel key as a fallback. */
    public SelectedCredential select(Channel channel) {
        return providerAccountsEnabled || channel.isManaged() ? selectEnhanced(channel, null, null, false) : selectLegacy(channel);
    }

    /** Selects an account with optional model compatibility and session stickiness. */
    public SelectedCredential select(Channel channel, String model, String sessionId) {
        return select(channel, model, sessionId, false);
    }

    public SelectedCredential select(Channel channel, String model, String sessionId, boolean requireReliableCost) {
        if (!providerAccountsEnabled && !channel.isManaged() && !requireReliableCost) return selectLegacy(channel);
        return selectEnhanced(channel, model, sessionId, requireReliableCost);
    }

    private SelectedCredential selectEnhanced(Channel channel, String model, String sessionId, boolean requireReliableCost) {
        if (sessionId != null && !sessionId.isBlank()) {
            Long stickyId = stickyCredential(channel.getId(), sessionId);
            if (stickyId != null) {
                ProviderCredential sticky = mapper.selectById(stickyId);
                if (eligible(sticky, channel, model) && (!requireReliableCost || sticky.isCostReliable())) {
                    acquire(sticky.getId());
                    return selected(sticky, channel);
                }
            }
        }
        List<ProviderCredential> candidates = mapper.selectList(new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getChannelId, channel.getId())
                        .eq(ProviderCredential::isEnabled, true))
                .stream()
                .filter(item -> eligible(item, channel, model))
                .filter(item -> !requireReliableCost || item.isCostReliable())
                .sorted(Comparator.comparingInt(ProviderCredential::getPriority).reversed()
                        .thenComparingLong(item -> active(item.getId()))
                        .thenComparingLong(ProviderCredential::getAverageLatencyMs)
                        .thenComparing(item -> item.getLastUsedAt() == null ? LocalDateTime.MIN : item.getLastUsedAt())
                        .thenComparingLong(ProviderCredential::getId))
                .toList();
        if (candidates.isEmpty()) {
            if (requireReliableCost) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No active account has a reliable cost snapshot for commission traffic");
            }
            String legacy = secrets.decrypt(channel.getApiKey());
            if (legacy == null || legacy.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Channel has no active credential");
            }
            return new SelectedCredential(null, legacy);
        }
        int topPriority = candidates.get(0).getPriority();
        long minimumActive = candidates.stream().filter(item -> item.getPriority() == topPriority)
                .mapToLong(item -> active(item.getId())).min().orElse(0);
        List<ProviderCredential> concurrencyTier = candidates.stream()
                .filter(item -> item.getPriority() == topPriority && active(item.getId()) == minimumActive).toList();
        long minimumLatency = concurrencyTier.stream().mapToLong(ProviderCredential::getAverageLatencyMs).min().orElse(0);
        List<ProviderCredential> latencyTier = concurrencyTier.stream().filter(item -> item.getAverageLatencyMs() == minimumLatency).toList();
        LocalDateTime oldestUse = latencyTier.stream().map(item -> item.getLastUsedAt() == null ? LocalDateTime.MIN : item.getLastUsedAt()).min(LocalDateTime::compareTo).orElse(LocalDateTime.MIN);
        List<ProviderCredential> tier = latencyTier.stream().filter(item -> Objects.equals(item.getLastUsedAt() == null ? LocalDateTime.MIN : item.getLastUsedAt(), oldestUse)).toList();
        int totalWeight = tier.stream().mapToInt(item -> Math.max(1, item.getWeight())).sum();
        int cursor = Math.floorMod(roundRobin.computeIfAbsent(channel.getId(), ignored -> new AtomicInteger())
                .getAndIncrement(), totalWeight);
        ProviderCredential selected = tier.get(0);
        for (ProviderCredential candidate : tier) {
            cursor -= Math.max(1, candidate.getWeight());
            if (cursor < 0) { selected = candidate; break; }
        }
        acquire(selected.getId());
        if (sessionId != null && !sessionId.isBlank()) rememberSticky(channel.getId(), sessionId, selected.getId());
        return selected(selected, channel);
    }

    /** Exact pre-Linknux selection semantics used while the rollout flag is disabled. */
    private SelectedCredential selectLegacy(Channel channel) {
        List<ProviderCredential> candidates = mapper.selectList(new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getChannelId, channel.getId())
                        .eq(ProviderCredential::isEnabled, true))
                .stream()
                .filter(item -> item.getCooldownUntil() == null || item.getCooldownUntil().isBefore(LocalDateTime.now()))
                .filter(item -> !"DISABLED".equalsIgnoreCase(item.getHealthStatus()))
                .filter(item -> item.getConcurrencyLimit() <= 0 || active(item.getId()) < item.getConcurrencyLimit())
                .sorted(Comparator.comparingInt(ProviderCredential::getPriority).reversed()
                        .thenComparingLong(item -> active(item.getId()))
                        .thenComparingLong(ProviderCredential::getAverageLatencyMs)
                        .thenComparingLong(ProviderCredential::getId))
                .toList();
        if (candidates.isEmpty()) {
            String legacy = secrets.decrypt(channel.getApiKey());
            if (legacy == null || legacy.isBlank()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Channel has no active credential");
            }
            return new SelectedCredential(null, legacy);
        }
        int topPriority = candidates.get(0).getPriority();
        long minimumActive = candidates.stream().filter(item -> item.getPriority() == topPriority)
                .mapToLong(item -> active(item.getId())).min().orElse(0);
        List<ProviderCredential> tier = candidates.stream()
                .filter(item -> item.getPriority() == topPriority && active(item.getId()) == minimumActive).toList();
        int cursor = Math.floorMod(roundRobin.computeIfAbsent(channel.getId(), ignored -> new AtomicInteger())
                .getAndIncrement(), tier.size());
        ProviderCredential selected = tier.get(cursor);
        acquire(selected.getId());
        return selected(selected, channel);
    }

    public SelectedCredential select(Channel channel, Long credentialId) {
        ProviderCredential selected = require(channel.getId(), credentialId);
        if (!selected.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Credential is disabled");
        }
        acquire(selected.getId());
        return selected(selected, channel);
    }

    public void recordSuccess(Long credentialId, long latencyMs) {
        if (credentialId == null) return;
        try {
            mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProviderCredential>()
                    .eq(ProviderCredential::getId, credentialId)
                    .set(ProviderCredential::getHealthStatus, "HEALTHY")
                    .set(ProviderCredential::getConsecutiveFailures, 0)
                    .setSql("total_successes = total_successes + 1")
                    .set(ProviderCredential::getAverageLatencyMs, Math.max(0, latencyMs))
                    .set(ProviderCredential::getLastUsedAt, LocalDateTime.now())
                    .set(ProviderCredential::getLastError, null));
        } finally {
            release(credentialId);
        }
    }

    public void recordSuccess(Long credentialId, long latencyMs, long tokens) {
        recordSuccess(credentialId, latencyMs);
        if (credentialId == null || tokens <= 0 || redis == null) return;
        try {
            String key = "gateway:credential:" + credentialId + ":tpm:" + LocalDateTime.now().withSecond(0).withNano(0);
            redis.opsForValue().increment(key, tokens); redis.expire(key, Duration.ofMinutes(2));
        } catch (RuntimeException ignored) { }
    }

    public void recordFailure(Long credentialId, Throwable error) {
        if (credentialId == null) return;
        String message = error == null ? "Unknown error" : String.valueOf(error.getMessage());
        try {
            var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProviderCredential>()
                    .eq(ProviderCredential::getId, credentialId)
                    .set(ProviderCredential::getHealthStatus, "DEGRADED")
                    .setSql("consecutive_failures = consecutive_failures + 1")
                    .setSql("total_failures = total_failures + 1")
                    .set(ProviderCredential::getLastErrorClass, UpstreamErrorClassifier.classify(error).name())
                    .set(ProviderCredential::getLastError, message.substring(0, Math.min(1000, message.length())));
            if (error instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
                if (response.getStatusCode().value() == 403) update.set(ProviderCredential::getEntitlementStatus, "INVALID")
                        .set(ProviderCredential::isEnabled, false).set(ProviderCredential::getHealthStatus, "DISABLED");
                if (response.getStatusCode().value() == 429) {
                    long seconds = 60;
                    try { seconds = Math.max(1, Long.parseLong(response.getHeaders().getFirst("Retry-After"))); } catch (Exception ignored) { }
                    update.set(ProviderCredential::getCooldownUntil, LocalDateTime.now().plusSeconds(Math.min(seconds, 86400)));
                }
            }
            mapper.update(null, update);
        } finally {
            release(credentialId);
        }
    }

    /** Releases capacity when the upstream acceptance/result is unknown without penalizing health. */
    public void releaseUnknown(Long credentialId) {
        if (credentialId != null) release(credentialId);
    }

    private long active(Long credentialId) {
        if (credentialId == null) return 0;
        if (redis != null) {
            try {
                String value = redis.opsForValue().get(redisKey(credentialId));
                return value == null ? 0 : Math.max(0, Long.parseLong(value));
            } catch (RuntimeException ignored) { /* local fail-open fallback */ }
        }
        AtomicInteger value = inFlight.get(credentialId);
        return value == null ? 0 : Math.max(0, value.get());
    }

    private void acquire(Long credentialId) {
        inFlight.computeIfAbsent(credentialId, ignored -> new AtomicInteger()).incrementAndGet();
        if (redis != null) {
            try {
                String key = redisKey(credentialId);
                redis.opsForValue().increment(key);
                redis.expire(key, Duration.ofMinutes(30));
                String rpmKey = "gateway:credential:" + credentialId + ":rpm:" +
                        java.time.LocalDateTime.now().withSecond(0).withNano(0);
                redis.opsForValue().increment(rpmKey);
                redis.expire(rpmKey, Duration.ofMinutes(2));
            } catch (RuntimeException ignored) { /* local counter remains authoritative for this instance */ }
        }
    }

    private void release(Long credentialId) {
        AtomicInteger local = inFlight.get(credentialId);
        if (local != null) local.updateAndGet(value -> Math.max(0, value - 1));
        if (redis != null) {
            try {
                String key = redisKey(credentialId);
                Long current = redis.opsForValue().decrement(key);
                if (current != null && current < 0) redis.opsForValue().set(key, "0", Duration.ofMinutes(30));
            } catch (RuntimeException ignored) { /* stale counters self-expire */ }
        }
    }

    private String redisKey(Long credentialId) { return "gateway:credential:" + credentialId + ":inflight"; }

    private boolean eligible(ProviderCredential item, Channel channel, String model) {
        if (item == null || channel == null || !Objects.equals(item.getChannelId(), channel.getId()) || !item.isEnabled()) return false;
        LocalDateTime now = LocalDateTime.now();
        if (item.getCooldownUntil() != null && item.getCooldownUntil().isAfter(now)) return false;
        if (item.getTemporaryUnschedulableUntil() != null && item.getTemporaryUnschedulableUntil().isAfter(now)) return false;
        if ("DISABLED".equalsIgnoreCase(item.getHealthStatus())) return false;
        if ("OAUTH".equalsIgnoreCase(item.getAuthType())) {
            if (item.getCredentialBundle() == null || item.getCredentialBundle().isBlank()
                    || !"ACTIVE".equalsIgnoreCase(item.getEntitlementStatus())) return false;
        }
        if (item.getConcurrencyLimit() > 0 && active(item.getId()) >= item.getConcurrencyLimit()) return false;
        if (model != null && item.getModelScope() != null && !item.getModelScope().isBlank()
                && java.util.Arrays.stream(item.getModelScope().split(",")).map(String::trim)
                .noneMatch(scope -> glob(scope, model))) return false;
        return !rpmExhausted(item) && !tpmExhausted(item);
    }

    private boolean rpmExhausted(ProviderCredential item) {
        if (item.getRpmLimit() <= 0 || redis == null) return false;
        try {
            String value = redis.opsForValue().get("gateway:credential:" + item.getId() + ":rpm:" +
                    java.time.LocalDateTime.now().withSecond(0).withNano(0));
            return value != null && Long.parseLong(value) >= item.getRpmLimit();
        } catch (RuntimeException ignored) { return false; }
    }

    private Long stickyCredential(Long channelId, String sessionId) {
        if (redis == null || sessionId.length() > 256) return null;
        try {
            String value = redis.opsForValue().get(stickyKey(channelId, sessionId));
            return value == null ? null : Long.valueOf(value);
        } catch (RuntimeException ignored) { return null; }
    }

    private void rememberSticky(Long channelId, String sessionId, Long credentialId) {
        if (redis == null || sessionId.length() > 256) return;
        try {
            redis.opsForValue().set(stickyKey(channelId, sessionId),
                    String.valueOf(credentialId), Duration.ofHours(2));
        } catch (RuntimeException ignored) { /* sticky routing is best effort */ }
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private ProviderCredential redact(ProviderCredential row) {
        row.setSecret(null);
        return row;
    }

    private void validate(ProviderCredential value, boolean secretRequired) {
        if (value == null || value.getName() == null || value.getName().isBlank() || value.getName().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credential name is required");
        }
        if (secretRequired && (value.getSecret() == null || value.getSecret().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credential secret is required");
        }
        if (value.getAuthType() != null && !value.getAuthType().isBlank() && !"API_KEY".equalsIgnoreCase(value.getAuthType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth/Token/Cookie 凭证不能手工导入，请使用管理员 OAuth 授权向导");
        }
        if (value.getWeight() < 0 || value.getRpmLimit() < 0 || value.getTpmLimit() < 0
                || value.getConcurrencyLimit() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credential limits are invalid");
        }
    }

    private Channel requireChannel(Long id) {
        Channel channel = channelMapper.selectById(id);
        if (channel == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        return channel;
    }

    private ProviderCredential require(Long channelId, Long id) {
        ProviderCredential row = mapper.selectById(id);
        if (row == null || !channelId.equals(row.getChannelId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Credential not found");
        }
        return row;
    }

    private String mask(String value) {
        if (value == null || value.length() < 10) return "****";
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    private boolean tpmExhausted(ProviderCredential item) {
        if (item.getTpmLimit() <= 0 || redis == null) return false;
        try {
            String value = redis.opsForValue().get("gateway:credential:" + item.getId() + ":tpm:" + LocalDateTime.now().withSecond(0).withNano(0));
            return value != null && Long.parseLong(value) >= item.getTpmLimit();
        } catch (RuntimeException ignored) { return false; }
    }

    private String stickyKey(Long channelId, String sessionId) {
        return "gateway:sticky:" + channelId + ":" + UpstreamOAuthStateService.sha256(sessionId).substring(0, 32);
    }

    public void refreshOAuth(Long credentialId) {
        if (oauthTokens == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAuth token service is unavailable");
        oauthTokens.forceRefresh(credentialId);
    }

    private boolean glob(String pattern, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (char current : pattern.toCharArray()) {
            if (current == '*') regex.append(".*"); else if (current == '?') regex.append('.');
            else regex.append(java.util.regex.Pattern.quote(String.valueOf(current)));
        }
        return value.matches("(?i)" + regex.append('$'));
    }

    private SelectedCredential selected(ProviderCredential account, Channel channel) {
        if ("OAUTH".equalsIgnoreCase(account.getAuthType())) {
            if (oauthTokens == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OAuth token service is unavailable");
            try {
                ProviderAuthContext context = oauthTokens.context(account, channel);
                return new SelectedCredential(account.getId(), null, context);
            } catch (RuntimeException error) {
                release(account.getId());
                throw error;
            }
        }
        String secret = secrets.decrypt(account.getSecret());
        return new SelectedCredential(account.getId(), secret,
                new ProviderAuthContext(account.getId(), account.getPlatform(), "API_KEY", secret, null,
                        channel.getBaseUrl(), account.getUpstreamProxyId(), account.getEntitlementStatus(), Map.of()));
    }

    public record SelectedCredential(Long id, String secret, ProviderAuthContext authContext) {
        public SelectedCredential(Long id, String secret) { this(id, secret, null); }
    }
}
