package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.transit.mapper.UserVerificationCodeMapper;
import com.transit.model.UserVerificationCode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VerificationCodeService {
    private final UserVerificationCodeMapper mapper;
    private final VerificationDeliveryService delivery;
    private final SecureRandom random = new SecureRandom();
    @Value("${verification.hmac-secret:${JWT_SECRET:local-development-verification-secret-change-me}}")
    private String hmacSecret;

    @Transactional
    public Map<String, Object> send(String channel, String rawRecipient, String purpose) {
        String normalizedChannel = normalizeChannel(channel);
        String recipient = normalizeRecipient(normalizedChannel, rawRecipient);
        String normalizedPurpose = normalizePurpose(purpose);
        LocalDateTime now = LocalDateTime.now();
        UserVerificationCode latest = mapper.selectOne(new LambdaQueryWrapper<UserVerificationCode>()
                .eq(UserVerificationCode::getRecipient, recipient).eq(UserVerificationCode::getChannel, normalizedChannel)
                .eq(UserVerificationCode::getPurpose, normalizedPurpose).orderByDesc(UserVerificationCode::getCreatedAt).last("LIMIT 1"));
        if (latest != null && latest.getCreatedAt() != null && latest.getCreatedAt().isAfter(now.minusSeconds(60))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
        }
        Long daily = mapper.selectCount(new LambdaQueryWrapper<UserVerificationCode>()
                .eq(UserVerificationCode::getRecipient, recipient).eq(UserVerificationCode::getChannel, normalizedChannel)
                .ge(UserVerificationCode::getCreatedAt, now.toLocalDate().atStartOfDay()));
        if (daily != null && daily >= 10) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "今日验证码已达上限");
        String code = String.format("%06d", random.nextInt(1_000_000));
        if ("EMAIL".equals(normalizedChannel)) delivery.sendEmail(recipient, code); else delivery.sendSms(recipient, code);
        mapper.update(null, new LambdaUpdateWrapper<UserVerificationCode>().set(UserVerificationCode::getStatus, "SUPERSEDED")
                .eq(UserVerificationCode::getRecipient, recipient).eq(UserVerificationCode::getChannel, normalizedChannel)
                .eq(UserVerificationCode::getPurpose, normalizedPurpose).eq(UserVerificationCode::getStatus, "PENDING"));
        UserVerificationCode row = new UserVerificationCode();
        row.setRecipient(recipient); row.setChannel(normalizedChannel); row.setPurpose(normalizedPurpose);
        row.setCodeHash(hmac(recipient + "|" + normalizedPurpose + "|" + code)); row.setStatus("PENDING");
        row.setAttempts(0); row.setExpiresAt(now.plusMinutes(5)); row.setCreatedAt(now); mapper.insert(row);
        java.util.LinkedHashMap<String,Object> response = new java.util.LinkedHashMap<>();
        response.put("sent", true); response.put("expiresIn", 300); response.put("retryAfter", 60);
        if (delivery.debugCodeEnabled()) response.put("debugCode", code);
        return response;
    }

    @Transactional
    public void consume(String channel, String rawRecipient, String purpose, String code) {
        String normalizedChannel = normalizeChannel(channel), recipient = normalizeRecipient(normalizedChannel, rawRecipient);
        String normalizedPurpose = normalizePurpose(purpose);
        UserVerificationCode row = mapper.selectOne(new LambdaQueryWrapper<UserVerificationCode>()
                .eq(UserVerificationCode::getRecipient, recipient).eq(UserVerificationCode::getChannel, normalizedChannel)
                .eq(UserVerificationCode::getPurpose, normalizedPurpose).eq(UserVerificationCode::getStatus, "PENDING")
                .orderByDesc(UserVerificationCode::getCreatedAt).last("LIMIT 1"));
        if (row == null || row.getExpiresAt() == null || !row.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效或已过期");
        }
        if (row.getAttempts() != null && row.getAttempts() >= 5) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证失败次数过多");
        boolean matches = code != null && MessageDigest.isEqual(hmac(recipient + "|" + normalizedPurpose + "|" + code.trim()).getBytes(StandardCharsets.UTF_8), row.getCodeHash().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1); mapper.updateById(row);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误");
        }
        int changed = mapper.update(null, new LambdaUpdateWrapper<UserVerificationCode>()
                .set(UserVerificationCode::getStatus, "CONSUMED").set(UserVerificationCode::getConsumedAt, LocalDateTime.now())
                .eq(UserVerificationCode::getId, row.getId()).eq(UserVerificationCode::getStatus, "PENDING"));
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "验证码已使用");
    }

    private String hmac(String input) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e){throw new IllegalStateException("无法创建验证码摘要",e);}
    }
    private String normalizeChannel(String channel){String v=channel==null?"":channel.trim().toUpperCase(Locale.ROOT);if(!v.equals("EMAIL")&&!v.equals("PHONE"))throw bad("不支持的验证通道");return v;}
    private String normalizePurpose(String purpose){String v=purpose==null?"REGISTER":purpose.trim().toUpperCase(Locale.ROOT);if(!v.matches("REGISTER|BIND|CHANGE|NEW_LOGIN_IP|PASSWORD_RESET"))throw bad("不支持的验证用途");return v;}
    private String normalizeRecipient(String channel,String value){
        String v=value==null?"":value.trim();
        if("EMAIL".equals(channel)) {
            v=v.toLowerCase(Locale.ROOT);
            if (!v.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) throw bad("邮箱地址无效");
        } else {
            v=normalizePhone(value);
            if (!v.matches("^\\+[1-9]\\d{7,14}$")) throw bad("手机号应为 E.164 格式");
        }
        if(v.isBlank()||v.length()>255)throw bad("接收地址无效");return v;
    }
    public static String normalizePhone(String value) {
        String phone = value == null ? "" : value.trim().replace(" ", "").replace("-", "");
        if (phone.matches("1[3-9]\\d{9}")) return "+86" + phone;
        return phone;
    }
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
