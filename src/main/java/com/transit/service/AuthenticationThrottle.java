package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Local safety net for credential stuffing. Production clusters should back
 * this contract with Redis or another shared atomic store. */
@Component
public class AuthenticationThrottle {
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final long lockSeconds;
    private final Clock clock = Clock.systemUTC();

    public AuthenticationThrottle(
            @Value("${security.login.max-failures:5}") int maxFailures,
            @Value("${security.login.lock-seconds:300}") long lockSeconds) {
        this.maxFailures = Math.max(3, maxFailures);
        this.lockSeconds = Math.max(30, lockSeconds);
    }

    public void checkAllowed(String subject) {
        String key = normalize(subject);
        Attempt attempt = attempts.get(key);
        if (attempt != null && attempt.lockedUntil != null && attempt.lockedUntil.isAfter(now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed sign-in attempts; try again later");
        }
    }

    public void failure(String subject) {
        String key = normalize(subject);
        attempts.compute(key, (ignored, current) -> {
            Attempt next = current == null || (current.lockedUntil != null && current.lockedUntil.isBefore(now()))
                    ? new Attempt() : current;
            next.failures++;
            if (next.failures >= maxFailures) next.lockedUntil = now().plusSeconds(lockSeconds);
            return next;
        });
        if (attempts.size() > 100_000) {
            Instant cutoff = now().minusSeconds(lockSeconds * 2);
            attempts.entrySet().removeIf(entry -> entry.getValue().lastTouched.isBefore(cutoff));
        }
    }

    public void success(String subject) {
        attempts.remove(normalize(subject));
    }

    private String normalize(String value) {
        return value == null ? "<missing>" : value.trim().toLowerCase();
    }

    private Instant now() {
        return clock.instant();
    }

    private final class Attempt {
        private int failures;
        private Instant lockedUntil;
        private Instant lastTouched = now();
    }
}
