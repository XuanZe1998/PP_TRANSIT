package com.transit.service;

/**
 * Sensitive card data used only inside the paid Service 07 fulfillment flow.
 * Never return this record from a controller or write it to logs.
 */
public record VmCardCheckoutCard(
        String cardId,
        String number,
        String expiry,
        String cvc,
        String billingName,
        String billingAddress,
        String billingCity,
        String billingState,
        String billingZip,
        String billingCountry
) {
}
