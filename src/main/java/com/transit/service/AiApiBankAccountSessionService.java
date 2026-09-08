package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Maintains an AiAPIBank account session without retaining the account password. */
@Service
@RequiredArgsConstructor
public class AiApiBankAccountSessionService {
    private static final String REFRESH_PURPOSE = "aiapibank-refresh";
    private static final String PENDING_PURPOSE = "aiapibank-login-challenge";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;
    private final ChannelSecretService secrets;
    private final Map<Long, CachedAccess> accessTokens = new ConcurrentHashMap<>();

    @Value("${aiapibank.base-url:https://aiapibank.com}") private String baseUrl;
    @Value("${aiapibank.request-timeout-seconds:30}") private int timeoutSeconds;

    public record AuthorizationResult(boolean authenticated, boolean requiresTwoFactor,
                                      String accountEmailPreview, String status) {}

    @Transactional
    public AuthorizationResult authorize(long siteId, String email, String password, String totpCode) {
        requireSite(siteId);
        if (!secrets.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "服务端尚未配置凭据加密密钥，不能保存 AiAPIBank 授权");
        }
        String totp = text(totpCode);
        JsonNode session;
        String preview = previewEmail(email);
        if (!totp.isBlank() && text(password).isBlank()) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT encrypted_pending_token,account_email_preview
                    FROM gateway_account_credentials WHERE site_id=?
                    """, siteId);
            if (rows.isEmpty() || text(rows.get(0).get("encrypted_pending_token")).isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "登录验证已失效，请重新输入账号密码");
            }
            String pending = secrets.decryptForPurpose(PENDING_PURPOSE,
                    text(rows.get(0).get("encrypted_pending_token")));
            preview = text(rows.get(0).get("account_email_preview"));
            session = authenticateTwoFactor(pending, totp);
        } else {
            String normalizedEmail = requireEmail(email);
            String suppliedPassword = requirePassword(password);
            JsonNode login = post("/api/v1/auth/login", objectMapper.createObjectNode()
                    .put("email", normalizedEmail).put("password", suppliedPassword));
            preview = previewEmail(normalizedEmail);
            if (login.path("requires_2fa").asBoolean(false)) {
                String pending = requiredToken(login, "temp_token", "AiAPIBank 未返回两步验证令牌");
                if (totp.isBlank()) {
                    storePending(siteId, preview, pending);
                    accessTokens.remove(siteId);
                    return new AuthorizationResult(false, true, preview, "TWO_FACTOR_REQUIRED");
                }
                session = authenticateTwoFactor(pending, totp);
            } else {
                session = login;
            }
        }
        storeSession(siteId, preview, session);
        return new AuthorizationResult(true, false, preview, "READY");
    }

    public boolean isAuthorized(long siteId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM gateway_account_credentials
                WHERE site_id=? AND encrypted_refresh_token IS NOT NULL AND encrypted_refresh_token<>''
                """, Integer.class, siteId);
        return count != null && count == 1;
    }

    /** Returns a short-lived access token, refreshing and rotating the stored refresh token when needed. */
    public synchronized String accessToken(Long siteId) {
        if (siteId == null) return null;
        CachedAccess cached = accessTokens.get(siteId);
        if (cached != null && cached.expiresAt().isAfter(LocalDateTime.now().plusSeconds(60))) {
            return cached.token();
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT encrypted_refresh_token FROM gateway_account_credentials WHERE site_id=?
                """, siteId);
        if (rows.isEmpty() || text(rows.get(0).get("encrypted_refresh_token")).isBlank()) return null;
        try {
            String refresh = secrets.decryptForPurpose(REFRESH_PURPOSE,
                    text(rows.get(0).get("encrypted_refresh_token")));
            JsonNode session = post("/api/v1/auth/refresh", objectMapper.createObjectNode()
                    .put("refresh_token", refresh));
            String access = requiredToken(session, "access_token", "AiAPIBank 刷新响应缺少访问令牌");
            String rotated = text(session.path("refresh_token").asText(refresh));
            int expiresIn = Math.max(120, session.path("expires_in").asInt(600));
            LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(expiresIn);
            jdbc.update("""
                    UPDATE gateway_account_credentials
                    SET access_token='',encrypted_refresh_token=?,auth_status='READY',access_expires_at=?,
                        last_authenticated_at=?,last_error=NULL,updated_at=? WHERE site_id=?
                    """, secrets.encryptForPurpose(REFRESH_PURPOSE, rotated), expiresAt,
                    LocalDateTime.now(), LocalDateTime.now(), siteId);
            accessTokens.put(siteId, new CachedAccess(access, expiresAt));
            return access;
        } catch (RuntimeException failure) {
            accessTokens.remove(siteId);
            jdbc.update("""
                    UPDATE gateway_account_credentials SET auth_status='ERROR',last_error=?,updated_at=? WHERE site_id=?
                    """, "账号授权已失效，请重新登录授权", LocalDateTime.now(), siteId);
            throw new IllegalStateException("AiAPIBank 账号授权已失效，请重新登录授权");
        }
    }

    @Transactional
    public void clear(long siteId) {
        requireSite(siteId);
        accessTokens.remove(siteId);
        jdbc.update("DELETE FROM gateway_account_credentials WHERE site_id=?", siteId);
    }

    private JsonNode authenticateTwoFactor(String pending, String totp) {
        if (!totp.matches("\\d{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入6位两步验证码");
        }
        return post("/api/v1/auth/login/2fa", objectMapper.createObjectNode()
                .put("temp_token", pending).put("totp_code", totp));
    }

    private void storePending(long siteId, String preview, String pending) {
        upsert(siteId, "", secrets.encryptForPurpose(PENDING_PURPOSE, pending), preview,
                "TWO_FACTOR_REQUIRED", null, null);
    }

    private void storeSession(long siteId, String preview, JsonNode session) {
        String access = requiredToken(session, "access_token", "AiAPIBank 登录响应缺少访问令牌");
        String refresh = requiredToken(session, "refresh_token", "AiAPIBank 登录响应缺少刷新令牌");
        int expiresIn = Math.max(120, session.path("expires_in").asInt(600));
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(expiresIn);
        upsert(siteId, secrets.encryptForPurpose(REFRESH_PURPOSE, refresh), "", preview,
                "READY", expiresAt, now);
        accessTokens.put(siteId, new CachedAccess(access, expiresAt));
    }

    private void upsert(long siteId, String refresh, String pending, String preview, String status,
                        LocalDateTime expiresAt, LocalDateTime authenticatedAt) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update("""
                UPDATE gateway_account_credentials
                SET access_token='',encrypted_refresh_token=?,encrypted_pending_token=?,account_email_preview=?,
                    auth_status=?,access_expires_at=?,last_authenticated_at=?,last_error=NULL,updated_at=?
                WHERE site_id=?
                """, refresh, pending, preview, status, expiresAt, authenticatedAt, now, siteId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO gateway_account_credentials(site_id,access_token,encrypted_refresh_token,
                        encrypted_pending_token,account_email_preview,auth_status,access_expires_at,
                        last_authenticated_at,last_error,updated_at)
                    VALUES(?,'',?,?,?,?,?,?,NULL,?)
                    """, siteId, refresh, pending, preview, status, expiresAt, authenticatedAt, now);
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            JsonNode root = webClient.post().uri(rootUrl() + path).bodyValue(body).retrieve()
                    .bodyToMono(JsonNode.class).block(Duration.ofSeconds(Math.max(3, timeoutSeconds)));
            if (root == null || root.isNull()) throw new IllegalStateException("AiAPIBank 认证响应为空");
            if (root.has("code") && root.path("code").asInt(-1) != 0) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AiAPIBank 拒绝了账号认证");
            }
            JsonNode payload = root.path("data");
            return payload.isObject() ? payload : root;
        } catch (WebClientResponseException response) {
            if (response.getStatusCode().value() == 401) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AiAPIBank 账号、密码或验证码不正确");
            }
            if (response.getStatusCode().value() == 429) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AiAPIBank 登录请求过于频繁，请稍后重试");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AiAPIBank 认证服务暂时不可用（HTTP " + response.getStatusCode().value() + "）");
        }
    }

    private void requireSite(long siteId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM upstream_sites WHERE id=? AND adapter='aiapibank'",
                Integer.class, siteId);
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AiAPIBank 站点不存在");
        }
    }

    private String requireEmail(String email) {
        String value = text(email).toLowerCase();
        if (value.length() > 190 || !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入有效的 AiAPIBank 登录邮箱");
        }
        return value;
    }

    private String requirePassword(String password) {
        if (password == null || password.isEmpty() || password.length() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入 AiAPIBank 登录密码");
        }
        return password;
    }

    private String requiredToken(JsonNode payload, String field, String message) {
        String value = text(payload.path(field).asText(""));
        if (value.isBlank()) throw new IllegalStateException(message);
        return value;
    }

    private String previewEmail(String email) {
        String value = text(email);
        int at = value.indexOf('@');
        if (at <= 0) return "";
        return value.substring(0, 1) + "***" + value.substring(at);
    }

    private String rootUrl() {
        String root = Objects.toString(baseUrl, "").replaceAll("/+$", "");
        String catalogPath = "/api/v1/model-plaza";
        return root.endsWith(catalogPath) ? root.substring(0, root.length() - catalogPath.length()) : root;
    }
    private String text(Object value) { return Objects.toString(value, "").trim(); }
    private record CachedAccess(String token, LocalDateTime expiresAt) {}
}
