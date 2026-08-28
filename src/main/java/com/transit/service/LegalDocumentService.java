package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
        DEFAULTS.forEach((key, fallback) -> out.put(key.substring(6), setting(key, fallback)));
        out.put("terms", termsText()); out.put("privacy", privacyText()); out.put("legalReviewRequired", true);
        return out;
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
