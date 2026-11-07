package com.transit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.net.URI;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PaymentExperienceService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AdminReportService settings;
    public static final String KEY = "payment.experience";
    public Map<String,String> defaults() {
        Map<String,String> v = new LinkedHashMap<>();
        v.put("amountLabel", "应付金额");
        v.put("amountUnavailable", "金额待确认");
        v.put("expiryLabel", "支付截止时间（北京时间）");
        v.put("remainingLabel", "剩余支付时间");
        v.put("expiryUnavailable", "支付有效期暂不可用，请核对收银台提示。");
        v.put("expiredNotice", "支付已过期，请刷新订单状态；如已付款，请勿重复支付。");
        v.put("title", "完成支付");
        v.put("qrPrompt", "请使用{method}扫码支付");
        v.put("qrNotice", "支付完成后，状态更新可能稍有延迟。请稍等片刻并刷新页面查看订单状态，请勿重复支付。");
        v.put("schemePrompt", "请在移动设备上唤起{method}完成付款。");
        v.put("openButton", "打开{method}");
        v.put("redirectPrompt", "将在 MaPay 收银台完成付款，付款后可返回本页查看结果。");
        v.put("redirectButton", "前往支付");
        v.put("pendingNotice", "支付状态以服务端回调为准，请勿重复创建订单。");
        v.put("emptyText", "支付动作尚未生成");
        v.put("alipayLabel", "支付宝");
        v.put("wxpayLabel", "微信支付");
        v.put("websiteUrl", "https://linknux.com");
        v.put("emailSubject", "付款成功：订单 {orderNo}");
        v.put("emailBody", "您的付款已确认。账单与收据见附件，也可登录订单页面下载。请勿重复付款。");
        return v;
    }
    public Map<String,String> read() {
        Map<String,String> result = defaults();
        var rows = jdbc.queryForList("SELECT setting_value FROM system_settings WHERE setting_key=?", String.class, KEY);
        if (!rows.isEmpty()) {
            try { result.putAll(mapper.readValue(rows.get(0), new TypeReference<Map<String,String>>() {})); }
            catch (Exception e) { throw new IllegalStateException("Invalid payment experience configuration", e); }
        }
        return result;
    }
    @Transactional
    public Map<String,String> save(Map<String,String> input) {
        if (input == null || !input.keySet().equals(defaults().keySet())) throw bad();
        Map<String,String> clean = new LinkedHashMap<>();
        input.forEach((k,v) -> {
            if (v == null || v.isBlank() || v.length() > (k.endsWith("Notice") || k.equals("emailBody") ? 3000 : 300)
                    || (k.equals("emailSubject") && (v.contains("\r") || v.contains("\n")))) throw bad();
            clean.put(k,v.trim());
        });
        try {
            URI u = URI.create(clean.get("websiteUrl"));
            if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null || !(u.getPath().isEmpty() || "/".equals(u.getPath()))) throw bad();
            clean.put("websiteUrl", clean.get("websiteUrl").replaceAll("/+$", ""));
            settings.saveSetting(Map.of("key", KEY, "value", mapper.writeValueAsString(clean), "description", "Payment display and email content"));
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw bad(); }
        return clean;
    }
    public String downloadUrl(String type, long id) {
        if (!Set.of("service", "recharge").contains(type) || id < 1) throw bad();
        return read().get("websiteUrl") + "/billing/" + type + "/" + id;
    }
    private ResponseStatusException bad() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment content: required fields, length limits and HTTPS site origin must be valid"); }
}
