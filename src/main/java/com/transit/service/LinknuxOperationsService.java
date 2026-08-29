package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LinknuxOperationsService {
    private final JdbcTemplate jdbc;
    @Value("${features.linknux.ops.enabled:false}") private boolean enabled;

    public Map<String, Object> overview() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(5);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status,latency_ms,total_tokens,sale_amount,cost_amount,credential_id FROM logs WHERE created_at>=?", since);
        long requests = rows.size();
        long successes = rows.stream().filter(row -> String.valueOf(row.get("status")).startsWith("SUCCESS")).count();
        long tokens = rows.stream().mapToLong(row -> number(row.get("total_tokens"))).sum();
        long sale = rows.stream().mapToLong(row -> number(row.get("sale_amount"))).sum();
        long cost = rows.stream().mapToLong(row -> number(row.get("cost_amount"))).sum();
        List<Long> latencies = rows.stream().map(row -> number(row.get("latency_ms"))).sorted().toList();
        long activeAccounts = number(jdbc.queryForMap("SELECT COUNT(*) value FROM provider_credentials WHERE enabled=TRUE AND health_status<>'DISABLED'").get("value"));
        long alerts = number(jdbc.queryForMap("SELECT COUNT(*) value FROM alert_events WHERE status='OPEN'").get("value"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled); result.put("windowMinutes", 5); result.put("requests", requests);
        result.put("qps", decimal(requests, 300)); result.put("tps", decimal(tokens, 300));
        result.put("successRate", requests == 0 ? BigDecimal.ONE : BigDecimal.valueOf(successes).divide(BigDecimal.valueOf(requests), 4, RoundingMode.HALF_UP));
        result.put("p50Ms", percentile(latencies, .50)); result.put("p95Ms", percentile(latencies, .95)); result.put("p99Ms", percentile(latencies, .99));
        result.put("saleAmount", sale); result.put("costAmount", cost); result.put("grossProfit", Math.max(0, sale - cost));
        result.put("activeAccounts", activeAccounts); result.put("openAlerts", alerts);
        result.put("errorClasses", jdbc.queryForList("SELECT COALESCE(last_error_class,'NONE') error_class,COUNT(*) count FROM provider_credentials GROUP BY COALESCE(last_error_class,'NONE')"));
        result.put("heartbeats", jdbc.queryForList("SELECT task_key,status,last_succeeded_at,last_failed_at,last_error,updated_at FROM task_heartbeats ORDER BY task_key"));
        return result;
    }

    public Flux<Map<String, Object>> realtime() {
        return Flux.interval(Duration.ZERO, Duration.ofSeconds(2)).map(ignored -> overview()).take(Duration.ofMinutes(30));
    }

    public List<Map<String, Object>> publicStatus() {
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        LocalDateTime fifteenDaysAgo = LocalDateTime.now().minusDays(15);
        return jdbc.queryForList("""
                SELECT c.id,c.name,c.source_code,c.health_status,c.average_latency_ms,
                       COALESCE(SUM(CASE WHEN h.checked_at>=? THEN 1 ELSE 0 END),0) checks_7d,
                       COALESCE(SUM(CASE WHEN h.checked_at>=? AND h.status='HEALTHY' THEN 1 ELSE 0 END),0) healthy_7d,
                       COALESCE(SUM(CASE WHEN h.checked_at>=? THEN 1 ELSE 0 END),0) checks_15d,
                       COALESCE(SUM(CASE WHEN h.checked_at>=? AND h.status='HEALTHY' THEN 1 ELSE 0 END),0) healthy_15d
                FROM channels c LEFT JOIN channel_monitor_history h ON h.channel_id=c.id
                WHERE c.enabled=TRUE GROUP BY c.id,c.name,c.source_code,c.health_status,c.average_latency_ms ORDER BY c.name
                """, sevenDaysAgo, sevenDaysAgo, fifteenDaysAgo, fifteenDaysAgo);
    }

    public List<Map<String, Object>> announcements(Long userId, boolean admin) {
        if (admin) return jdbc.queryForList("SELECT * FROM announcements ORDER BY id DESC");
        return jdbc.queryForList("""
                SELECT a.id,a.title,a.content,a.audience,a.starts_at,a.ends_at,a.created_at,
                       CASE WHEN r.user_id IS NULL THEN FALSE ELSE TRUE END is_read
                FROM announcements a LEFT JOIN announcement_reads r ON r.announcement_id=a.id AND r.user_id=?
                WHERE a.enabled=TRUE AND (a.starts_at IS NULL OR a.starts_at<=CURRENT_TIMESTAMP)
                  AND (a.ends_at IS NULL OR a.ends_at>CURRENT_TIMESTAMP) AND a.audience IN ('ALL','USERS')
                ORDER BY a.id DESC
                """, userId);
    }

    public Map<String, Object> createAnnouncement(Long adminId, Map<String, Object> body) {
        jdbc.update("INSERT INTO announcements(title,content,audience,starts_at,ends_at,enabled,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                text(body.get("title"), 200), text(body.get("content"), 20000), text(body.getOrDefault("audience", "ALL"), 32),
                date(body.get("startsAt")), date(body.get("endsAt")), !Boolean.FALSE.equals(body.get("enabled")), adminId, LocalDateTime.now(), LocalDateTime.now());
        return Map.of("status", "CREATED");
    }

    public void markAnnouncementRead(Long userId, Long id) {
        jdbc.update("INSERT INTO announcement_reads(announcement_id,user_id,read_at) VALUES (?,?,?) ON DUPLICATE KEY UPDATE read_at=VALUES(read_at)", id, userId, LocalDateTime.now());
    }

    public Map<String, Object> requestBackup(Long adminId) {
        String no = "BK" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
        jdbc.update("INSERT INTO backup_runs(request_no,status,requested_by,created_at) VALUES (?,'REQUESTED',?,?)", no, adminId, LocalDateTime.now());
        return Map.of("requestNo", no, "status", "REQUESTED", "message", "备份请求已登记；由受限服务器发布脚本执行，应用不具备恢复权限");
    }

    public List<Map<String, Object>> backups() { return jdbc.queryForList("SELECT id,request_no,status,storage_path_masked,size_bytes,checksum_sha256,started_at,completed_at,error_message,created_at FROM backup_runs ORDER BY id DESC LIMIT 100"); }

    @Scheduled(fixedDelayString = "${features.linknux.ops.aggregate-ms:60000}", initialDelayString = "${features.linknux.ops.aggregate-ms:60000}")
    @Transactional
    public void aggregateMinute() {
        if (!enabled) return;
        LocalDateTime bucket = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(1);
        heartbeat("ops-minute-aggregate", "RUNNING", null);
        try {
            List<Map<String, Object>> groups = jdbc.queryForList("""
                    SELECT COALESCE(channel_id,0) channel_id,COALESCE(credential_id,0) credential_id,COALESCE(model,'') model,
                           COUNT(*) request_count,SUM(CASE WHEN status LIKE 'SUCCESS%' THEN 1 ELSE 0 END) success_count,
                           SUM(CASE WHEN status NOT LIKE 'SUCCESS%' THEN 1 ELSE 0 END) error_count,
                           COALESCE(SUM(prompt_tokens),0) input_tokens,COALESCE(SUM(completion_tokens),0) output_tokens,
                           COALESCE(SUM(latency_ms),0) latency_sum_ms,COALESCE(SUM(sale_amount),0) sale_amount,COALESCE(SUM(cost_amount),0) cost_amount
                    FROM logs WHERE created_at>=? AND created_at<? GROUP BY COALESCE(channel_id,0),COALESCE(credential_id,0),COALESCE(model,'')
                    """, bucket, bucket.plusMinutes(1));
            for (Map<String, Object> group : groups) jdbc.update("""
                    INSERT INTO ops_minute_metrics(bucket_start,channel_id,credential_id,model,request_count,success_count,error_count,input_tokens,output_tokens,latency_sum_ms,sale_amount,cost_amount)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE request_count=VALUES(request_count),success_count=VALUES(success_count),error_count=VALUES(error_count),input_tokens=VALUES(input_tokens),output_tokens=VALUES(output_tokens),latency_sum_ms=VALUES(latency_sum_ms),sale_amount=VALUES(sale_amount),cost_amount=VALUES(cost_amount)
                    """, bucket, group.get("channel_id"), group.get("credential_id"), group.get("model"), group.get("request_count"), group.get("success_count"), group.get("error_count"), group.get("input_tokens"), group.get("output_tokens"), group.get("latency_sum_ms"), group.get("sale_amount"), group.get("cost_amount"));
            heartbeat("ops-minute-aggregate", "HEALTHY", null);
        } catch (RuntimeException error) { heartbeat("ops-minute-aggregate", "FAILED", safe(error)); throw error; }
    }

    @Scheduled(fixedDelayString = "${features.linknux.ops.monitor-ms:300000}", initialDelayString = "${features.linknux.ops.monitor-ms:300000}")
    public void captureChannelHealth() {
        if (!enabled) return;
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,health_status,average_latency_ms,last_error FROM channels WHERE enabled=TRUE")) {
            jdbc.update("INSERT INTO channel_monitor_history(channel_id,status,latency_ms,detail_masked,checked_at) VALUES (?,?,?,?,?)",
                    row.get("id"), row.get("health_status"), row.get("average_latency_ms"), safeText(row.get("last_error")), LocalDateTime.now());
        }
        heartbeat("channel-health-snapshot", "HEALTHY", null);
    }

    @Scheduled(cron = "${features.linknux.ops.cleanup-cron:0 23 3 * * *}")
    public void cleanup() {
        if (!enabled) return;
        jdbc.update("DELETE FROM channel_monitor_history WHERE checked_at<?", LocalDateTime.now().minusDays(90));
        jdbc.update("DELETE FROM ops_minute_metrics WHERE bucket_start<?", LocalDateTime.now().minusDays(31));
        heartbeat("ops-retention-cleanup", "HEALTHY", null);
    }

    private void heartbeat(String key, String status, String error) {
        jdbc.update("INSERT INTO task_heartbeats(task_key,status,last_started_at,last_succeeded_at,last_failed_at,last_error,updated_at) VALUES (?,?,CURRENT_TIMESTAMP,CASE WHEN ?='HEALTHY' THEN CURRENT_TIMESTAMP ELSE NULL END,CASE WHEN ?='FAILED' THEN CURRENT_TIMESTAMP ELSE NULL END,?,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE status=VALUES(status),last_started_at=VALUES(last_started_at),last_succeeded_at=COALESCE(VALUES(last_succeeded_at),last_succeeded_at),last_failed_at=COALESCE(VALUES(last_failed_at),last_failed_at),last_error=VALUES(last_error),updated_at=VALUES(updated_at)", key, status, status, status, error);
    }
    private long percentile(List<Long> values, double p) { if (values.isEmpty()) return 0; return values.get(Math.min(values.size()-1, (int)Math.ceil(values.size()*p)-1)); }
    private BigDecimal decimal(long value, long seconds) { return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(seconds), 3, RoundingMode.HALF_UP); }
    private long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private String text(Object value, int max) { String text = value == null ? "" : String.valueOf(value).trim(); if (text.isBlank() || text.length()>max) throw new IllegalArgumentException("字段不能为空或过长"); return text; }
    private LocalDateTime date(Object value) { return value == null || String.valueOf(value).isBlank() ? null : LocalDateTime.parse(String.valueOf(value)); }
    private String safe(Throwable error) { return safeText(error == null ? null : error.getMessage()); }
    private String safeText(Object value) { String text=value==null?null:String.valueOf(value).replaceAll("(?i)(sk-[a-z0-9_-]{8})[a-z0-9_-]+", "$1****"); return text==null?null:text.substring(0,Math.min(1000,text.length())); }
}
