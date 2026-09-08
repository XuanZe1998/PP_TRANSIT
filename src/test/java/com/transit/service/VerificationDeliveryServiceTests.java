package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VerificationDeliveryServiceTests {

    @Test
    void mailTransportFailuresBecomeServiceUnavailableResponses() {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        JavaMailSender sender = mock(JavaMailSender.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        when(provider.getObject()).thenReturn(sender);
        doThrow(new MailSendException("SMTP unavailable")).when(sender).send(any(org.springframework.mail.SimpleMailMessage.class));

        VerificationDeliveryService service = new VerificationDeliveryService(
                provider, WebClient.builder().build());
        ReflectionTestUtils.setField(service, "emailFrom", "no-reply@example.com");
        ReflectionTestUtils.setField(service, "mailHost", "smtp.example.com");
        ReflectionTestUtils.setField(service, "mailUsername", "smtp-user");
        ReflectionTestUtils.setField(service, "mailPassword", "smtp-password");

        assertThatThrownBy(() -> service.sendEmail("user@example.com", "123456"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(503);
                    assertThat(exception.getReason()).isEqualTo("邮件发送失败");
                });
    }
}
