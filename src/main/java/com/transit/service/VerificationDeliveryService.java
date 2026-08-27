package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class VerificationDeliveryService {
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final WebClient webClient;

    @Value("${verification.email.from:}") private String emailFrom;
    @Value("${verification.sms.provider:}") private String smsProvider;
    @Value("${verification.sms.endpoint:}") private String smsEndpoint;
    @Value("${verification.sms.token:}") private String smsToken;
    @Value("${verification.debug-code-enabled:false}") private boolean debugCodeEnabled;
    @Value("${spring.mail.host:}") private String mailHost;
    @Value("${spring.mail.username:}") private String mailUsername;
    @Value("${spring.mail.password:}") private String mailPassword;

    public boolean emailConfigured() {
        return mailSenderProvider.getIfAvailable() != null
                && emailFrom != null && !emailFrom.isBlank()
                && mailHost != null && !mailHost.isBlank()
                && mailUsername != null && !mailUsername.isBlank()
                && mailPassword != null && !mailPassword.isBlank();
    }

    public boolean smsConfigured() {
        return ("TENCENT".equalsIgnoreCase(smsProvider) || "ALIYUN".equalsIgnoreCase(smsProvider))
                && smsEndpoint != null && !smsEndpoint.isBlank() && smsToken != null && !smsToken.isBlank();
    }

    public void sendEmail(String recipient, String code) {
        if (!emailConfigured()) {
            if (debugCodeEnabled) return;
            throw unavailable("邮件发送通道未配置");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(emailFrom);
        message.setTo(recipient);
        message.setSubject("平台验证码");
        message.setText("您的验证码是 " + code + "，5 分钟内有效。请勿转发。");
        mailSenderProvider.getObject().send(message);
    }

    public void sendSms(String recipient, String code) {
        if (!smsConfigured()) {
            if (debugCodeEnabled) return;
            throw unavailable("短信发送通道未配置");
        }
        try {
            webClient.post().uri(smsEndpoint)
                    .headers(headers -> headers.setBearerAuth(smsToken))
                    .bodyValue(Map.of("provider", smsProvider.toUpperCase(), "phone", recipient,
                            "template", "verification_code", "code", code, "ttlSeconds", 300))
                    .retrieve().toBodilessEntity().block();
        } catch (Exception exception) {
            throw unavailable("短信发送失败");
        }
    }

    public boolean debugCodeEnabled() { return debugCodeEnabled; }
    private ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
