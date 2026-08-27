package com.transit.dto;

import java.util.Locale;

/** Explicit integer money contract. One currency unit equals {@code scale} amount units. */
public record MoneyAmount(long amount, String currency, long scale) {
    public MoneyAmount {
        if (scale <= 0) throw new IllegalArgumentException("money scale must be positive");
        if (currency == null || !currency.trim().toUpperCase(Locale.ROOT).matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("money currency must be a three-letter ISO code");
        }
        currency = currency.trim().toUpperCase(Locale.ROOT);
    }

    public static MoneyAmount cents(long amount, String currency) {
        return new MoneyAmount(amount, currency, 100);
    }

    public static MoneyAmount walletUnits(long amount) {
        return new MoneyAmount(amount, "CNY", 10_000);
    }
}
