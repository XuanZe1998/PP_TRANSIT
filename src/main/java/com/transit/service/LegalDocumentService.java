package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LegalDocumentService {
    private final JdbcTemplate jdbc;
    private static final Map<String,String> DEFAULTS = Map.of(
            "legal.operator", "LinkNux API 服务平台",
            "legal.contact_email", "support@linknux.com",
            "legal.address", "请以运营主体公示信息为准",
            "legal.terms_version", "2026-08-28",
            "legal.privacy_version", "2026-08-28",
            "legal.effective_date", "2026-08-28"
    );

    public Map<String,Object> publicDocuments() {
        Map<String,Object> out = new LinkedHashMap<>();
        Map<String, String> settings = new java.util.HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT setting_key, setting_value FROM system_settings WHERE setting_key LIKE 'legal.%' OR setting_key = 'commerce.payments_enabled'")) {
            if (row.get("setting_value") != null)
                settings.put(String.valueOf(row.get("setting_key")), String.valueOf(row.get("setting_value")));
        }
        DEFAULTS.forEach((key, fallback) -> out.put(key.substring(6), settings.getOrDefault(key, fallback)));
        // A seeded support address is not proof that the operator has verified that mailbox.
        if (DEFAULTS.get("legal.contact_email").equals(out.get("contact_email"))) out.put("contact_email", "");
        for (String field : List.of("registration", "jurisdiction", "support_hours", "terms", "privacy",
                "refund", "ai_data", "cookies", "rights", "support", "security", "subprocessors")) {
            out.put(field, settings.getOrDefault("legal." + field, field.equals("terms") ? termsText() : field.equals("privacy") ? privacyText() : ""));
            out.put(field + "_en", settings.getOrDefault("legal." + field + "_en", ""));
        }
        out.put("operator_en", settings.getOrDefault("legal.operator_en", ""));
        out.put("address_en", settings.getOrDefault("legal.address_en", ""));
        List<String> missing = new java.util.ArrayList<>();
        for (String key : List.of("operator", "address", "registration", "jurisdiction", "contact_email",
                "terms", "privacy", "refund", "ai_data", "rights", "support",
                "terms_version", "privacy_version", "effective_date")) {
            String value = String.valueOf(out.getOrDefault(key, "")).trim();
            if (value.isEmpty() || ((key.equals("contact_email") || key.endsWith("_version") || key.equals("effective_date"))
                    && value.equals(DEFAULTS.get("legal." + key))) || (key.equals("operator") && value.equals(DEFAULTS.get("legal.operator")))
                    || (key.equals("address") && value.equals(DEFAULTS.get("legal.address")))
                    || ((key.equals("terms") || key.equals("privacy"))
                        && value.equals(key.equals("terms") ? termsText() : privacyText()))) missing.add(key);
        }
        for (String key : List.of("terms", "privacy", "refund", "ai_data", "rights", "support")) {
            if (String.valueOf(out.get(key + "_en")).isBlank()) missing.add(key + "_en");
        }
        boolean approved = "true".equalsIgnoreCase(settings.getOrDefault("legal.publication_approved", "false"));
        out.put("publication_ready", missing.isEmpty() && approved);
        // Payment activation is a separate, fail-closed operator decision.
        out.put("payments_enabled", "true".equalsIgnoreCase(settings.getOrDefault("commerce.payments_enabled", "false")));
        out.put("checkout_ready", Boolean.TRUE.equals(out.get("publication_ready")) && Boolean.TRUE.equals(out.get("payments_enabled")));
        out.put("checkout_disclosure_id", checkoutDisclosureId(out));
        out.put("missing_fields", missing);
        out.put("legalReviewRequired", !approved);
        return out;
    }

    public boolean isPublicationReady() { return Boolean.TRUE.equals(publicDocuments().get("publication_ready")); }

    public void requireCheckoutOpen() {
        if (!Boolean.TRUE.equals(publicDocuments().get("checkout_ready")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout is closed: payment switch or reviewed disclosures are not ready");
    }

    public void recordCheckoutConsent(Long userId, String businessType, Long businessId, String disclosureId) {
        Map<String, Object> current = publicDocuments();
        if (!Boolean.TRUE.equals(current.get("checkout_ready")) || !isCurrentAccepted(userId)
                || disclosureId == null || !disclosureId.equals(current.get("checkout_disclosure_id")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout disclosures changed; review and accept the current terms");
        try {
            String snapshot = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(current);
            jdbc.update("INSERT INTO legal_checkout_acceptances(user_id,business_type,business_id,disclosure_id,disclosure_snapshot,accepted_at) VALUES (?,?,?,?,?,?)",
                    userId, businessType, businessId, disclosureId, snapshot, LocalDateTime.now());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Cannot snapshot checkout disclosure", e);
        }
    }

    private String checkoutDisclosureId(Map<String, Object> out) {
        StringBuilder material = new StringBuilder();
        // Any published copy relevant to a purchase must invalidate stale checkout confirmations.
        out.entrySet().stream().filter(entry -> !List.of("publication_ready", "missing_fields",
                        "legalReviewRequired", "checkout_disclosure_id", "payments_enabled", "checkout_ready").contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> material.append(entry.getKey()).append('\0').append(entry.getValue()).append('\0'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    public boolean isCurrentAccepted(Long userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM legal_acceptances WHERE user_id=? AND terms_version=? AND privacy_version=?",
                Integer.class, userId, termsVersion(), privacyVersion());
        return count != null && count > 0;
    }

    public void accept(Long userId, String terms, String privacy, String ipDigest) {
        if (!termsVersion().equals(terms) || !privacyVersion().equals(privacy))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "协议版本已更新，请重新阅读并接受");
        if (!isCurrentAccepted(userId)) jdbc.update("INSERT INTO legal_acceptances(user_id,terms_version,privacy_version,ip_digest,accepted_at) VALUES (?,?,?,?,?)",
                userId, terms, privacy, ipDigest, LocalDateTime.now());
    }

    public String termsVersion() { return setting("legal.terms_version", DEFAULTS.get("legal.terms_version")); }
    public String privacyVersion() { return setting("legal.privacy_version", DEFAULTS.get("legal.privacy_version")); }

    private String setting(String key, String fallback) {
        var rows = jdbc.queryForList("SELECT setting_value FROM system_settings WHERE setting_key=?", key);
        return rows.isEmpty() || rows.get(0).get("setting_value") == null ? fallback : rows.get(0).get("setting_value").toString();
    }

    private String termsText() { return "使用本平台即表示您同意妥善保管账户与 API Key，并对企业成员授权负责。服务按实际用量计费，第三方模型提供方可能按请求处理数据。禁止违法、侵权、绕过安全控制或滥用服务。企业主可管理组织成员、额度和 Token，相关操作会保留审计与财务记录。具体退款、服务可用性、终止及争议处理规则以页面公示为准。本文需由正式法律顾问复核。"; }
    private String privacyText() { return "我们为注册、鉴权、计费、安全审计和服务交付处理账户资料、企业联系信息、调用元数据及加密登录 IP 历史。请求内容可能转交所选第三方模型服务商；企业可显式开启请求脱敏。仅在实现目的所需期限内保存数据，并采取租户隔离、加密和访问控制。您可申请查阅、更正、删除或撤回信任设备；跨境处理将依法履行适用义务。本文以《个人信息保护法》和《网络数据安全管理条例》为一般合规基线，需由正式法律顾问复核。"; }
}
