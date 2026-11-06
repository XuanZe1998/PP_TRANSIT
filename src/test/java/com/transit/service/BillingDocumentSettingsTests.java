package com.transit.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class BillingDocumentSettingsTests {
    @Test void failsClosedForMissingMerchantInvalidEmailAndTimezone() {
        BillingDocumentSettings settings = new BillingDocumentSettings();
        assertEquals(503, assertThrows(ResponseStatusException.class, settings::merchant).getStatusCode().value());
        ReflectionTestUtils.setField(settings, "merchantLegalName", " Test Merchant ");
        ReflectionTestUtils.setField(settings, "merchantAddressLine1", "Test Street");
        ReflectionTestUtils.setField(settings, "merchantAddressLine2", "Test City");
        ReflectionTestUtils.setField(settings, "merchantCountry", "Test Country");
        ReflectionTestUtils.setField(settings, "merchantContactEmail", "invalid");
        assertThrows(ResponseStatusException.class, settings::merchant);
        ReflectionTestUtils.setField(settings, "merchantContactEmail", " Billing@Example.com ");
        ReflectionTestUtils.setField(settings, "receiptTimeZone", "invalid-zone");
        assertThrows(ResponseStatusException.class, settings::merchant);
        ReflectionTestUtils.setField(settings, "receiptTimeZone", "Asia/Shanghai");
        assertEquals("Test Merchant", settings.merchant().name());
        assertEquals("billing@example.com", settings.merchant().contactEmail());
        assertEquals(ZoneId.of("Asia/Shanghai"), settings.merchant().timeZone());
    }
}
