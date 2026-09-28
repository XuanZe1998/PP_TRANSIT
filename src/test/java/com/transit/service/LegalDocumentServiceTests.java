package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class LegalDocumentServiceTests {
    private LegalDocumentService service(Map<String, String> settings) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenAnswer(call -> settings.entrySet().stream()
                .map(entry -> Map.<String, Object>of("setting_key", entry.getKey(), "setting_value", entry.getValue()))
                .toList());
        when(jdbc.queryForList(anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return settings.containsKey(key) ? List.of(Map.of("setting_value", settings.get(key))) : List.of();
        });
        return new LegalDocumentService(jdbc);
    }

    @Test void defaultsAreClearlyUnpublished() {
        Map<String, Object> result = service(Map.of()).publicDocuments();
        assertThat(result.get("publication_ready")).isEqualTo(false);
        assertThat(result.get("payments_enabled")).isEqualTo(false);
        assertThat(result.get("checkout_ready")).isEqualTo(false);
        assertThat((List<String>) result.get("missing_fields")).contains("operator", "address", "refund", "ai_data", "terms_en");
        assertThat(result.get("legalReviewRequired")).isEqualTo(true);
    }

    @Test void checkoutConsentRequiresCurrentDigestAndSnapshotsTheCopy() {
        Map<String, String> settings = new HashMap<>();
        for (String field : List.of("operator", "address", "registration", "jurisdiction", "contact_email",
                "terms", "privacy", "refund", "ai_data", "rights", "support",
                "terms_version", "privacy_version", "effective_date")) settings.put("legal." + field, "Reviewed " + field);
        for (String field : List.of("terms", "privacy", "refund", "ai_data", "rights", "support"))
            settings.put("legal." + field + "_en", "Reviewed English " + field);
        settings.put("legal.publication_approved", "true");
        settings.put("commerce.payments_enabled", "true");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString())).thenAnswer(call -> settings.entrySet().stream()
                .map(entry -> Map.<String, Object>of("setting_key", entry.getKey(), "setting_value", entry.getValue()))
                .toList());
        when(jdbc.queryForList(anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(1);
            return settings.containsKey(key) ? List.of(Map.of("setting_value", settings.get(key))) : List.of();
        });
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(1);
        LegalDocumentService legal = new LegalDocumentService(jdbc);
        assertThatThrownBy(() -> legal.recordCheckoutConsent(1L, "PAYMENT_INTENT", 9L, "stale"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(jdbc, never()).update(anyString(), any(), any(), any(), any(), any());
        String disclosureId = String.valueOf(legal.publicDocuments().get("checkout_disclosure_id"));
        legal.recordCheckoutConsent(1L, "PAYMENT_INTENT", 9L, disclosureId);
        verify(jdbc).update(anyString(), eq(1L), eq("PAYMENT_INTENT"), eq(9L), eq(disclosureId),
                org.mockito.ArgumentMatchers.argThat(value -> value.toString().contains("Reviewed refund")), any());
    }

    @Test void disclosureDigestChangesWhenSupportOrRightsChange() {
        Map<String, String> settings = new HashMap<>();
        LegalDocumentService legal = service(settings);
        String before = String.valueOf(legal.publicDocuments().get("checkout_disclosure_id"));
        settings.put("legal.support_en", "New support terms");
        String afterSupport = String.valueOf(legal.publicDocuments().get("checkout_disclosure_id"));
        assertThat(afterSupport).isNotEqualTo(before);
        settings.put("legal.rights", "New rights request process");
        assertThat(legal.publicDocuments().get("checkout_disclosure_id")).isNotEqualTo(afterSupport);
    }

    @Test void reviewedBilingualDisclosureRequiresExplicitApproval() {
        Map<String, String> settings = new HashMap<>();
        for (String field : List.of("operator", "address", "registration", "jurisdiction", "contact_email",
                "terms", "privacy", "refund", "ai_data", "rights", "support",
                "terms_version", "privacy_version", "effective_date")) settings.put("legal." + field, "Reviewed " + field);
        for (String field : List.of("terms", "privacy", "refund", "ai_data", "rights", "support"))
            settings.put("legal." + field + "_en", "Reviewed English " + field);
        assertThat(service(settings).isPublicationReady()).isFalse();
        settings.put("legal.publication_approved", "true");
        assertThat(service(settings).isPublicationReady()).isTrue();
        assertThat(service(settings).publicDocuments().get("checkout_ready")).isEqualTo(false);
        settings.put("commerce.payments_enabled", "true");
        assertThat(service(settings).publicDocuments().get("checkout_ready")).isEqualTo(true);
        settings.remove("legal.refund_en");
        assertThat(service(settings).isPublicationReady()).isFalse();
        assertThat(service(settings).publicDocuments().get("checkout_ready")).isEqualTo(false);
    }

    @Test void paymentSwitchCannotBypassLegalReviewAndFailsClosed() {
        Map<String, String> settings = new HashMap<>();
        settings.put("commerce.payments_enabled", "true");
        assertThat(service(settings).publicDocuments().get("checkout_ready")).isEqualTo(false);
        assertThatThrownBy(() -> service(settings).requireCheckoutOpen())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        settings.put("commerce.payments_enabled", "TRUE ");
        assertThat(service(settings).publicDocuments().get("payments_enabled")).isEqualTo(false);
    }
}
