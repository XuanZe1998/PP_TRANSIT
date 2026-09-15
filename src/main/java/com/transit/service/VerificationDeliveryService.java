package com.transit.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VerificationDeliveryService {
    private static final String EMAIL_TEMPLATE = "templates/verification-email.html";

    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final WebClient webClient;

    @Value("${verification.email.from:}") private String emailFrom;
    @Value("${verification.email.subject:Linknux 安全验证码}") private String emailSubject = "Linknux 安全验证码";
    @Value("${verification.email.brand-name:Linknux}") private String emailBrandName = "Linknux";
    @Value("${verification.email.website-url:https://linknux.com}") private String emailWebsiteUrl = "https://linknux.com";
    @Value("${verification.email.support-email:support@linknux.com}") private String emailSupportEmail = "support@linknux.com";
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
        try {
            JavaMailSender mailSender = mailSenderProvider.getObject();
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, StandardCharsets.UTF_8.name());
            helper.setFrom(emailFrom, valueOrDefault(emailBrandName, "Linknux"));
            helper.setTo(recipient);
            helper.setSubject(valueOrDefault(emailSubject, "Linknux 安全验证码"));
            helper.setText(plainText(code), renderHtml(code));
            mailSender.send(message);
        } catch (MailException | MessagingException | IOException exception) {
            throw unavailable("邮件发送失败");
        }
    }

    private String plainText(String code) {
        String brandName = valueOrDefault(emailBrandName, "Linknux");
        return "欢迎使用 " + brandName + "！感谢你选择我们的 AI 能力平台。"
                + "\n我们希望帮助你更轻松地连接主流模型、开发应用与释放创意。"
                + "\n\n" + brandName + " 验证码：" + code
                + "\n\n验证码 5 分钟内有效，且仅能使用一次。如非本人操作，请忽略此邮件，切勿向他人透露验证码。";
    }

    private String renderHtml(String code) throws IOException {
        String template = new ClassPathResource(EMAIL_TEMPLATE)
                .getContentAsString(StandardCharsets.UTF_8);
        return template
                .replace("{{brandName}}", escapeHtml(valueOrDefault(emailBrandName, "Linknux")))
                .replace("{{verificationCode}}", escapeHtml(code))
                .replace("{{websiteUrl}}", escapeHtml(valueOrDefault(emailWebsiteUrl, "https://linknux.com")))
                .replace("{{supportEmail}}", escapeHtml(valueOrDefault(emailSupportEmail, "support@linknux.com")));
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
