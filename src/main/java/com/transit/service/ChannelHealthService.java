package com.transit.service;

import com.transit.model.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/** Persists circuit-breaker state and publishes bounded-cardinality metrics. */
@Service
@RequiredArgsConstructor
public class ChannelHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public void recordSuccess(Channel channel, long latencyMs) {
        if (channel == null || channel.getId() == null) return;
        long safeLatency = Math.max(0, latencyMs);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE channels
                   SET consecutive_failures = 0,
                       total_successes = total_successes + 1,
                       average_latency_ms = CASE
                           WHEN total_successes = 0 THEN ?
                           ELSE ((average_latency_ms * total_successes) + ?) / (total_successes + 1)
                       END,
                       health_status = 'HEALTHY',
                       cooldown_until = NULL,
                       last_error = NULL,
                       last_tested_at = ?,
                       last_success_at = ?
                 WHERE id = ?
                """, safeLatency, safeLatency, now, now, channel.getId());
        meterRegistry.counter("gateway.channel.requests", "channel", channel.getId().toString(),
                "result", "success").increment();
        Timer.builder("gateway.channel.latency")
                .tag("channel", channel.getId().toString())
                .register(meterRegistry)
                .record(Duration.ofMillis(safeLatency));
    }

    @Transactional
    public void recordFailure(Channel channel, Throwable error) {
        if (channel == null || channel.getId() == null) return;
        LocalDateTime now = LocalDateTime.now();
        String message = safeMessage(error);
        jdbcTemplate.update("""
                UPDATE channels
                   SET consecutive_failures = consecutive_failures + 1,
                       total_failures = total_failures + 1,
                       last_error = ?,
                       last_tested_at = ?
                 WHERE id = ?
                """, message, now, channel.getId());
        Map<String, Object> state = jdbcTemplate.queryForMap("""
                SELECT consecutive_failures, failure_threshold, cooldown_seconds, auto_disable
                  FROM channels WHERE id = ?
                """, channel.getId());
        int failures = number(state.get("consecutive_failures"), 1);
        int threshold = Math.max(1, number(state.get("failure_threshold"), 3));
        boolean autoDisable = booleanValue(state.get("auto_disable"));
        boolean coolingDown = autoDisable && failures >= threshold;
        LocalDateTime cooldownUntil = coolingDown
                ? now.plusSeconds(Math.max(5, number(state.get("cooldown_seconds"), 60)))
                : null;
        jdbcTemplate.update("UPDATE channels SET health_status = ?, cooldown_until = ? WHERE id = ?",
                coolingDown ? "COOLDOWN" : "DEGRADED", cooldownUntil, channel.getId());
        meterRegistry.counter("gateway.channel.requests", "channel", channel.getId().toString(),
                "result", "failure").increment();
        if (coolingDown) {
            meterRegistry.counter("gateway.channel.circuit.opens", "channel", channel.getId().toString())
                    .increment();
        }
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "Unknown upstream failure";
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.substring(0, Math.min(1000, message.length()));
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
