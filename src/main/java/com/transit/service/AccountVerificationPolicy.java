package com.transit.service;

import com.transit.model.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Component
public class AccountVerificationPolicy {
    public enum Mode { EMAIL_ONLY, EMAIL_AND_PHONE }

    private final Mode mode;

    public AccountVerificationPolicy(@Value("${account.verification-mode:EMAIL_AND_PHONE}") String configuredMode) {
        try {
            this.mode = Mode.valueOf((configuredMode == null ? "" : configuredMode)
                    .trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("account.verification-mode must be EMAIL_ONLY or EMAIL_AND_PHONE", invalid);
        }
    }

    public Mode mode() { return mode; }
    public boolean requiresPhone() { return mode == Mode.EMAIL_AND_PHONE; }

    public boolean isComplete(User user) {
        return user != null && user.getEmailVerifiedAt() != null
                && (!requiresPhone() || user.getPhoneVerifiedAt() != null);
    }

    public boolean registrationReady(VerificationDeliveryService delivery) {
        return delivery.emailConfigured() && (!requiresPhone() || delivery.smsConfigured());
    }

    public void requireComplete(User user, String action) {
        if (isComplete(user)) return;
        String requirement = requiresPhone() ? "邮箱和手机号" : "邮箱";
        throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                "完成" + requirement + "验证后才能" + action);
    }
}
