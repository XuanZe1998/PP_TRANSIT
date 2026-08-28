package com.transit.service;

import com.transit.model.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountVerificationPolicyTests {
    @Test
    void emailOnlyAcceptsVerifiedEmailWithoutPhone() {
        AccountVerificationPolicy policy = new AccountVerificationPolicy("EMAIL_ONLY");
        User user = User.builder().emailVerifiedAt(LocalDateTime.now()).build();
        assertThat(policy.isComplete(user)).isTrue();
        assertThat(policy.requiresPhone()).isFalse();
    }

    @Test
    void dualModeStillRequiresVerifiedPhone() {
        AccountVerificationPolicy policy = new AccountVerificationPolicy("EMAIL_AND_PHONE");
        User user = User.builder().emailVerifiedAt(LocalDateTime.now()).build();
        assertThat(policy.isComplete(user)).isFalse();
        user.setPhoneVerifiedAt(LocalDateTime.now());
        assertThat(policy.isComplete(user)).isTrue();
    }

    @Test
    void enterpriseRegistrationNeedsVerifiedEmailButTreatsPhoneAsContactData() {
        AccountVerificationPolicy policy = new AccountVerificationPolicy("EMAIL_AND_PHONE");
        User user = User.builder().accountType("ENTERPRISE").emailVerifiedAt(LocalDateTime.now()).build();
        assertThat(policy.isComplete(user)).isTrue();
    }

    @Test
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> new AccountVerificationPolicy("OPTIONAL"))
                .isInstanceOf(IllegalStateException.class);
    }
}
