package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared private configuration and validation for all billing documents. */
@Component
public class BillingDocumentSettings {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    @Value("${payment.documents.merchant.legal-name:}")
    private String merchantLegalName;

    @Value("${payment.documents.merchant.address-line-1:}")
    private String merchantAddressLine1;

    @Value("${payment.documents.merchant.address-line-2:}")
    private String merchantAddressLine2;

    @Value("${payment.documents.merchant.country:}")
    private String merchantCountry;

    @Value("${payment.documents.merchant.contact-email:}")
    private String merchantContactEmail;

    @Value("${payment.documents.time-zone:Asia/Shanghai}")
    private String receiptTimeZone;

    @Value("${payment.documents.font-path:}")
    private String receiptFontPath;

    MerchantInfo merchant() {
        List<String> missing = new ArrayList<>();
        if (blank(merchantLegalName)) missing.add("payment.documents.merchant.legal-name");
        if (blank(merchantAddressLine1)) missing.add("payment.documents.merchant.address-line-1");
        if (blank(merchantAddressLine2)) missing.add("payment.documents.merchant.address-line-2");
        if (blank(merchantCountry)) missing.add("payment.documents.merchant.country");
        if (blank(merchantContactEmail)) missing.add("payment.documents.merchant.contact-email");
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt merchant configuration is incomplete: " + String.join(", ", missing));
        }
        String contactEmail = merchantContactEmail.trim().toLowerCase(Locale.ROOT);
        if (!EMAIL_PATTERN.matcher(contactEmail).matches()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt merchant configuration has an invalid payment.documents.merchant.contact-email");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(receiptTimeZone == null || receiptTimeZone.isBlank() ? "UTC" : receiptTimeZone.trim());
        } catch (DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Receipt configuration has an invalid payment.documents.time-zone");
        }
        return new MerchantInfo(
                merchantLegalName.trim(),
                merchantAddressLine1.trim(),
                merchantAddressLine2.trim(),
                merchantCountry.trim(),
                contactEmail,
                zone
        );
    }

    BillingPdfRenderer renderer() { return new BillingPdfRenderer(receiptFontPath); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    record MerchantInfo(String name,
                                String addressLine1,
                                String addressLine2,
                                String country,
                                String contactEmail,
                                ZoneId timeZone) {
    }
}
