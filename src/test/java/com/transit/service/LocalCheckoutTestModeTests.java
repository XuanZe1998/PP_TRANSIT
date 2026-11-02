package com.transit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class LocalCheckoutTestModeTests {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private LegalDocumentService localService() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        LegalDocumentService legal = new LegalDocumentService(jdbc);
        ReflectionTestUtils.setField(legal, "localPaymentTestMode", true);
        ReflectionTestUtils.setField(legal, "localCheckoutBypass", true);
        ReflectionTestUtils.setField(legal, "serverAddress", "127.0.0.1");
        return legal;
    }

    @Test void opensOnlySimulatedCheckoutWithoutApprovingPublishedTermsOrRealPayments() {
        LegalDocumentService legal = localService();
        var data = legal.publicDocuments();
        assertThat(data.get("local_checkout_test_mode")).isEqualTo(true);
        assertThat(data.get("checkout_ready")).isEqualTo(true);
        assertThat(data.get("payments_enabled")).isEqualTo(false);
        assertThat(data.get("publication_ready")).isEqualTo(false);
        assertThat(data.get("legalReviewRequired")).isEqualTo(true);
        assertThat((List<?>) data.get("missing_fields")).isNotEmpty();
        assertThatCode(legal::requireCheckoutOpen).doesNotThrowAnyException();
        String digest = data.get("checkout_disclosure_id").toString();
        ReflectionTestUtils.setField(legal, "localCheckoutBypass", false);
        assertThat(legal.publicDocuments().get("checkout_disclosure_id")).isEqualTo(digest);
    }

    @Test void skipsConsentWithoutRecordingFakeAcceptance() {
        LegalDocumentService legal = localService();
        clearInvocations(jdbc);
        legal.recordCheckoutConsent(7L, "SERVICE_ORDER", 9L, null);
        verifyNoInteractions(jdbc);
        assertThatThrownBy(() -> legal.recordCheckoutConsent(null, "SERVICE_ORDER", 9L, null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0.0.0.0", "::", "192.168.1.1", "api.example.com", "localhost"})
    void wildcardAndNonLoopbackListenersCannotBypassCheckout(String address) {
        LegalDocumentService legal = localService();
        ReflectionTestUtils.setField(legal, "serverAddress", address);
        assertClosed(legal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localPaymentTestMode", "localCheckoutBypass"})
    void requiresBothExplicitTestSwitches(String field) {
        LegalDocumentService legal = localService();
        ReflectionTestUtils.setField(legal, field, false);
        assertClosed(legal);
    }

    @Test void enablingRealPaymentsDisablesBypass() {
        LegalDocumentService legal = localService();
        ReflectionTestUtils.setField(legal, "realPaymentEnabled", true);
        assertClosed(legal);
    }

    private void assertClosed(LegalDocumentService legal) {
        assertThat(legal.isLocalCheckoutTestMode()).isFalse();
        assertThat(legal.publicDocuments().get("checkout_ready")).isEqualTo(false);
        assertThatThrownBy(legal::requireCheckoutOpen).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> legal.recordCheckoutConsent(7L, "SERVICE_ORDER", 9L, null))
                .isInstanceOf(ResponseStatusException.class);
    }
}
