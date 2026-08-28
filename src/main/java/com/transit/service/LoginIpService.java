package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LoginIpService {
    private final JdbcTemplate jdbc;
    private final SecretHashService hashes;
    private final PersonalDataCryptoService crypto;
    private final VerificationCodeService verificationCodes;

    public String digest(String ip) { return hashes.hash("login-ip|" + ip); }

    public boolean isTrusted(Long userId, String ip) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM login_ip_history WHERE user_id=? AND ip_digest=? AND verified=TRUE AND revoked_at IS NULL",
                Integer.class, userId, digest(ip));
        return count != null && count > 0;
    }

    public boolean hasHistory(Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM login_ip_history WHERE user_id=?", Integer.class, userId);
        return count != null && count > 0;
    }

    @Transactional
    public void trust(Long userId, String ip) {
        String digest = digest(ip); LocalDateTime now = LocalDateTime.now();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM login_ip_history WHERE user_id=? AND ip_digest=?", Integer.class, userId, digest);
        if (count == null || count == 0) {
            jdbc.update("INSERT INTO login_ip_history(user_id,ip_digest,encrypted_ip,ip_preview,verified,first_seen_at,last_seen_at) VALUES (?,?,?,?,TRUE,?,?)",
                    userId, digest, crypto.encrypt(ip), mask(ip), now, now);
        } else {
            jdbc.update("UPDATE login_ip_history SET verified=TRUE,revoked_at=NULL,last_seen_at=?,encrypted_ip=COALESCE(encrypted_ip,?) WHERE user_id=? AND ip_digest=?",
                    now, crypto.encrypt(ip), userId, digest);
        }
    }

    public void touch(Long userId, String ip) {
        jdbc.update("UPDATE login_ip_history SET last_seen_at=? WHERE user_id=? AND ip_digest=? AND revoked_at IS NULL",
                LocalDateTime.now(), userId, digest(ip));
    }

    @Transactional
    public Map<String,Object> createChallenge(Long userId, String email, String ip) {
        LocalDateTime now = LocalDateTime.now();
        Integer recent = jdbc.queryForObject("SELECT COUNT(*) FROM login_ip_challenges WHERE user_id=? AND created_at>=?",
                Integer.class, userId, now.minusMinutes(10));
        if (recent != null && recent >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "新地址验证请求过于频繁，请稍后再试");
        verificationCodes.send("EMAIL", email, "NEW_LOGIN_IP");
        String challengeId = "ipc_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO login_ip_challenges(challenge_id,user_id,ip_digest,encrypted_ip,ip_preview,status,attempts,expires_at,created_at) VALUES (?,?,?,?,?,'PENDING',0,?,?)",
                challengeId, userId, digest(ip), crypto.encrypt(ip), mask(ip), now.plusMinutes(5), now);
        return Map.of("verificationRequired", true, "challengeId", challengeId,
                "maskedEmail", maskEmail(email), "expiresIn", 300);
    }

    @Transactional
    public VerifiedChallenge verify(String challengeId, String code, String currentIp) {
        Map<String,Object> row = jdbc.queryForList("SELECT * FROM login_ip_challenges WHERE challenge_id=? FOR UPDATE", challengeId)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "登录验证不存在"));
        if (!"PENDING".equals(row.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "登录验证已使用");
        LocalDateTime expires = (LocalDateTime) row.get("expires_at");
        if (expires == null || !expires.isAfter(LocalDateTime.now())) {
            jdbc.update("UPDATE login_ip_challenges SET status='EXPIRED' WHERE challenge_id=?", challengeId);
            throw new ResponseStatusException(HttpStatus.GONE, "登录验证已过期");
        }
        long userId = ((Number) row.get("user_id")).longValue();
        String expectedDigest = row.get("ip_digest").toString();
        if (!expectedDigest.equals(digest(currentIp)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "登录地址已变化，请重新发起登录");
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id=?", String.class, userId);
        verificationCodes.consume("EMAIL", email, "NEW_LOGIN_IP", code);
        int changed = jdbc.update("UPDATE login_ip_challenges SET status='CONSUMED',consumed_at=? WHERE challenge_id=? AND status='PENDING'",
                LocalDateTime.now(), challengeId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "登录验证已使用");
        trust(userId, currentIp);
        return new VerifiedChallenge(userId, currentIp, expectedDigest);
    }

    public List<Map<String,Object>> history(Long userId) {
        return jdbc.query("SELECT id,encrypted_ip,ip_preview,verified,first_seen_at,last_seen_at,revoked_at FROM login_ip_history WHERE user_id=? ORDER BY last_seen_at DESC",
                (rs, row) -> {
                    Map<String,Object> item = new java.util.LinkedHashMap<>();
                    item.put("id", rs.getLong("id"));
                    String encrypted = rs.getString("encrypted_ip");
                    String clear = encrypted == null ? null : crypto.decrypt(encrypted);
                    item.put("ip", clear == null ? rs.getString("ip_preview") : clear);
                    item.put("ip_preview", rs.getString("ip_preview"));
                    item.put("verified", rs.getBoolean("verified"));
                    item.put("first_seen_at", rs.getObject("first_seen_at"));
                    item.put("last_seen_at", rs.getObject("last_seen_at"));
                    item.put("revoked_at", rs.getObject("revoked_at"));
                    return item;
                }, userId);
    }

    public void revoke(Long userId, Long historyId) {
        int changed = jdbc.update("UPDATE login_ip_history SET verified=FALSE,revoked_at=? WHERE id=? AND user_id=?",
                LocalDateTime.now(), historyId, userId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该登录地址");
    }

    public String mask(String ip) {
        if (ip == null || ip.isBlank()) return "未知";
        if (ip.contains(":")) {
            String[] parts = ip.split(":", -1);
            return parts.length > 2 ? parts[0] + ":" + parts[1] + ":****" : "****";
        }
        String[] parts = ip.split("\\.");
        return parts.length == 4 ? parts[0] + "." + parts[1] + ".*." + parts[3] : "****";
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return "***";
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    public record VerifiedChallenge(long userId, String ip, String ipDigest) {}
}
