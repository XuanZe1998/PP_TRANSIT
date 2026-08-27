package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RedeemCodeService {
    private final JdbcTemplate jdbcTemplate;
    private final SecretHashService secretHashService;
    private final SecureRandom random = new SecureRandom();

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
                SELECT id, code_prefix, amount, max_uses, used_count, enabled, expires_at, created_at
                FROM redeem_codes ORDER BY created_at DESC LIMIT 500
                """);
    }

    public Map<String, Object> issue(String requestedCode, long amount, int maxUses) {
        if (amount <= 0 || amount > 100_000_000_000L || maxUses < 1 || maxUses > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Redeem code values are out of range");
        }
        String secret = requestedCode == null || requestedCode.isBlank() ? generate() : requestedCode.trim();
        if (secret.length() < 16 || secret.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Redeem codes must contain 16 to 80 characters");
        }
        String preview = preview(secret);
        jdbcTemplate.update("""
                INSERT INTO redeem_codes
                (code, code_prefix, amount, max_uses, used_count, enabled, expires_at, created_at)
                VALUES (?, ?, ?, ?, 0, TRUE, NULL, ?)
                """, secretHashService.hash(secret), preview, amount, maxUses, LocalDateTime.now());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("secret", secret);
        response.put("codePreview", preview);
        response.put("amount", amount);
        response.put("maxUses", maxUses);
        response.put("notice", "This redeem code is shown once and cannot be recovered");
        return response;
    }

    @Transactional
    public Map<String, Object> redeem(Long userId, String rawCode) {
        if (rawCode == null || rawCode.isBlank() || rawCode.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Redeem code is required");
        }
        String digest = secretHashService.hash(rawCode.trim());
        Map<String, Object> row = findByStoredCode(digest);
        if (row == null) {
            row = findByStoredCode(rawCode.trim());
            if (row != null) {
                jdbcTemplate.update("UPDATE redeem_codes SET code = ?, code_prefix = ? WHERE id = ?",
                        digest, preview(rawCode.trim()), row.get("id"));
            }
        }
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Redeem code not found");
        }
        int claimed = jdbcTemplate.update("""
                UPDATE redeem_codes SET used_count = used_count + 1
                WHERE id = ? AND enabled = TRUE AND used_count < max_uses
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
                """, row.get("id"));
        if (claimed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Redeem code is unavailable or expired");
        }
        long amount = ((Number) row.get("amount")).longValue();
        int credited = jdbcTemplate.update(
                "UPDATE users SET balance = balance + ? WHERE id = ? AND status = 'ACTIVE'",
                amount, userId);
        if (credited != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User account is unavailable");
        }
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM users WHERE id = ?", Long.class, userId);
        long balanceAfter = balance == null ? 0 : balance;
        String codePreview = row.get("code_prefix") == null ? preview(rawCode.trim()) : row.get("code_prefix").toString();
        jdbcTemplate.update("""
                INSERT INTO wallet_transactions
                (user_id, type, amount, balance_after, channel, remark, created_at)
                VALUES (?, 'REDEEM', ?, ?, 'redeem_code', ?, ?)
                """, userId, amount, balanceAfter, "Redeem code " + codePreview, LocalDateTime.now());
        return Map.of("balance", balanceAfter, "amount", amount, "codePreview", codePreview);
    }

    private Map<String, Object> findByStoredCode(String stored) {
        return jdbcTemplate.queryForList("""
                SELECT id, code_prefix, amount, max_uses, used_count, enabled, expires_at
                FROM redeem_codes WHERE code = ?
                """, stored).stream().findFirst().orElse(null);
    }

    private String generate() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return "rc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String preview(String secret) {
        return secret.substring(0, Math.min(8, secret.length())) + "…";
    }
}
