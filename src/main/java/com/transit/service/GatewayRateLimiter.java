package com.transit.service;

import com.transit.model.Channel;
import com.transit.model.Token;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

@Component
public class GatewayRateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int defaultTokenRpm;
    private final Clock clock;

    @Autowired(required = false)
    private StringRedisTemplate redis;

    @Value("${gateway.rate-limit.distributed-enabled:true}")
    private boolean distributedEnabled;

    @Autowired
    public GatewayRateLimiter(@Value("${gateway.rate-limit.default-token-rpm:120}") int defaultTokenRpm) {
        this(defaultTokenRpm, Clock.systemUTC());
    }

    GatewayRateLimiter(int defaultTokenRpm, Clock clock) {
        this.defaultTokenRpm = Math.max(0, defaultTokenRpm);
        this.clock = clock;
    }

    public void checkToken(Token token) {
        if (defaultTokenRpm > 0) {
            acquire("token:" + token.getId(), defaultTokenRpm, "API Key rate limit exceeded");
        }
    }

    public void checkChannel(Channel channel) {
        if (channel.getRpmLimit() > 0) {
            acquire("channel:" + channel.getId(), channel.getRpmLimit(), "Channel rate limit exceeded");
        }
    }

    private void acquire(String key, int limit, String message) {
        long minute = clock.instant().getEpochSecond() / 60;
        if (distributedEnabled && redis != null) {
            try {
                String redisKey = "gateway:rate:" + key + ":" + minute;
                Long count = redis.opsForValue().increment(redisKey);
                if (count != null && count == 1) redis.expire(redisKey, Duration.ofMinutes(2));
                if (count != null && count > limit) {
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
                }
                return;
            } catch (ResponseStatusException limited) {
                throw limited;
            } catch (RuntimeException unavailable) {
                // Correctness-critical balance enforcement remains in MySQL;
                // rate limiting degrades to the bounded local limiter.
            }
        }
        Window window = windows.compute(key, (ignored, current) -> {
            if (current == null || current.minute != minute) {
                return new Window(minute);
            }
            return current;
        });
        if (window.count.incrementAndGet() > limit) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
        if (windows.size() > 100_000) {
            windows.entrySet().removeIf(entry -> entry.getValue().minute < minute - 1);
        }
    }

    private static final class Window {
        private final long minute;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long minute) {
            this.minute = minute;
        }
    }
}
