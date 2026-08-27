package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ProviderCredentialMapper;
import com.transit.model.Channel;
import com.transit.model.ProviderCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

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

    public List<ProviderCredential> list(Long channelId) {
        return mapper.selectList(new LambdaQueryWrapper<ProviderCredential>()
                        .eq(ProviderCredential::getChannelId, channelId)
                        .orderByDesc(ProviderCredential::getPriority)
                        .orderByAsc(ProviderCredential::getId))
                .stream().map(this::redact).toList();
    }

    public boolean hasAvailable(Channel channel) {
        if (channel == null || channel.getId() == null) return false;
        long count = mapper.selectCount(new LambdaQueryWrapper<ProviderCredential>()
                .eq(ProviderCredential::getChannelId, channel.getId())
                .eq(ProviderCredential::isEnabled, true)
                .and(q -> q.isNull(ProviderCredential::getCooldownUntil)
                        .or().lt(ProviderCredential::getCooldownUntil, LocalDateTime.now())));
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
        return new SelectedCredential(selected.getId(), secrets.decrypt(selected.getSecret()));
    }

    public SelectedCredential select(Channel channel, Long credentialId) {
        ProviderCredential selected = require(channel.getId(), credentialId);
        if (!selected.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Credential is disabled");
        }
        acquire(selected.getId());
        return new SelectedCredential(selected.getId(), secrets.decrypt(selected.getSecret()));
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
                    .set(ProviderCredential::getLastError, null));
        } finally {
            release(credentialId);
        }
    }

    public void recordFailure(Long credentialId, Throwable error) {
        if (credentialId == null) return;
        String message = error == null ? "Unknown error" : String.valueOf(error.getMessage());
        try {
            mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ProviderCredential>()
                    .eq(ProviderCredential::getId, credentialId)
                    .set(ProviderCredential::getHealthStatus, "DEGRADED")
                    .setSql("consecutive_failures = consecutive_failures + 1")
                    .setSql("total_failures = total_failures + 1")
                    .set(ProviderCredential::getLastError, message.substring(0, Math.min(1000, message.length()))));
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

    public record SelectedCredential(Long id, String secret) {}
}
