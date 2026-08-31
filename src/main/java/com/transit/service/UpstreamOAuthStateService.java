package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpstreamOAuthStateService {
    private final JdbcTemplate jdbc;
    private final ChannelSecretService secrets;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public Created create(String platform, long adminId, Long reauthorizeCredentialId, Long proxyId, long templateId, String accountGroup,
                          String modelScope, String redirectUri, String callbackMode) {
        return create(platform, adminId, reauthorizeCredentialId, proxyId, templateId, accountGroup,
                modelScope, redirectUri, callbackMode, 0);
    }

    @Transactional
    public Created create(String platform, long adminId, Long reauthorizeCredentialId, Long proxyId, long templateId, String accountGroup,
                          String modelScope, String redirectUri, String callbackMode, long clientConfigVersion) {
        String state = random(32), verifier = random(64), nonce = random(32), flowId = UUID.randomUUID().toString();
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(10);
        jdbc.update("""
                INSERT INTO upstream_oauth_states(flow_id,state_hash,platform,encrypted_code_verifier,encrypted_nonce,
                admin_user_id,reauthorize_credential_id,upstream_proxy_id,price_template_id,account_group,model_scope,redirect_uri,callback_mode,
                oauth_client_config_version,expires_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, flowId, sha256(state), platform, secrets.encrypt(verifier), secrets.encrypt(nonce), adminId, reauthorizeCredentialId, proxyId,
                templateId, blank(accountGroup, "default"), modelScope, redirectUri, blank(callbackMode, "POPUP"),
                clientConfigVersion, expiresAt, LocalDateTime.now());
        return new Created(flowId, state, verifier, nonce, expiresAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Consumed consume(String platform, String state) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM upstream_oauth_states WHERE state_hash=? AND platform=?", sha256(state), platform);
        if (rows.isEmpty()) throw invalid();
        Map<String, Object> row = rows.get(0);
        LocalDateTime expiresAt = local(row.get("expires_at"));
        if (row.get("consumed_at") != null || expiresAt == null || !expiresAt.isAfter(LocalDateTime.now())) throw invalid();
        int changed = jdbc.update("UPDATE upstream_oauth_states SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND expires_at>?",
                LocalDateTime.now(), row.get("id"), LocalDateTime.now());
        if (changed != 1) throw invalid();
        return new Consumed(((Number) row.get("id")).longValue(), String.valueOf(row.get("flow_id")), platform,
                secrets.decrypt(String.valueOf(row.get("encrypted_code_verifier"))),
                secrets.decrypt(String.valueOf(row.get("encrypted_nonce"))), ((Number) row.get("admin_user_id")).longValue(), nullableLong(row.get("reauthorize_credential_id")),
                nullableLong(row.get("upstream_proxy_id")), ((Number) row.get("price_template_id")).longValue(),
                String.valueOf(row.get("account_group")), nullable(row.get("model_scope")), String.valueOf(row.get("redirect_uri")),
                row.get("oauth_client_config_version") instanceof Number number ? number.longValue() : 0);
    }

    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state 已过期、已消费或无效"); }
    private String random(int bytes) { byte[] value = new byte[bytes]; random.nextBytes(value); return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    public static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String nullable(Object value) { return value == null ? null : String.valueOf(value); }
    private Long nullableLong(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private LocalDateTime local(Object value) { return value instanceof LocalDateTime time ? time : value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : null; }
    public record Created(String flowId, String state, String verifier, String nonce, LocalDateTime expiresAt) {}
    public record Consumed(long id, String flowId, String platform, String verifier, String nonce, long adminId, Long reauthorizeCredentialId,
                           Long proxyId, long templateId, String accountGroup, String modelScope, String redirectUri,
                           long clientConfigVersion) {}
}
