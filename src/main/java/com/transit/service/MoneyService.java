package com.transit.service;

import com.transit.dto.MoneyAmount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class MoneyService {
    private final BigDecimal configuredUsdCnyRate;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public MoneyService(@Value("${payment.usd-cny-rate:6.76693506}") BigDecimal usdCnyRate,
                        JdbcTemplate jdbcTemplate) {
        this.configuredUsdCnyRate = usdCnyRate;
        this.jdbcTemplate = jdbcTemplate;
    }

    MoneyService(BigDecimal usdCnyRate) {
        this.configuredUsdCnyRate = usdCnyRate;
        this.jdbcTemplate = null;
    }

    public SettlementQuote settlementQuote(MoneyAmount source) {
        if (source == null || source.amount() <= 0) throw conflict("Payment amount must be positive");
        BigDecimal rate;
        if ("CNY".equals(source.currency())) rate = BigDecimal.ONE.setScale(8);
        else if ("USD".equals(source.currency())) rate = usdCnyRate();
        else throw conflict("AnyiPay only supports CNY or USD prices converted to CNY");
        try {
            long cents = BigDecimal.valueOf(source.amount())
                    .divide(BigDecimal.valueOf(source.scale()), 12, RoundingMode.HALF_UP)
                    .multiply(rate).multiply(BigDecimal.valueOf(100))
                    .setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (cents <= 0) throw new ArithmeticException("zero settlement");
            return new SettlementQuote(MoneyAmount.cents(cents, "CNY"), rate);
        } catch (ArithmeticException exception) {
            throw conflict("Payment amount is outside the supported range");
        }
    }

    public String gatewayMoney(long cents) {
        if (cents <= 0) throw conflict("Payment amount must be positive");
        return BigDecimal.valueOf(cents, 2).setScale(2).toPlainString();
    }

    public MoneyAmount enrich(long amount, String currency, long scale) {
        return new MoneyAmount(amount, currency, scale);
    }

    /** Returns the admin-configurable fixed rate used as a request snapshot. */
    public BigDecimal usdCnyRate() {
        BigDecimal rate = configuredUsdCnyRate;
        try {
            if (jdbcTemplate == null) return requireRate(rate);
            java.util.List<String> values = jdbcTemplate.queryForList(
                    "SELECT setting_value FROM system_settings WHERE setting_key='billing.usd_cny_rate'",
                    String.class);
            if (!values.isEmpty() && values.get(0) != null && !values.get(0).isBlank()) {
                rate = new BigDecimal(values.get(0).trim());
            }
        } catch (RuntimeException ignored) {
            // Startup migrations may not have created system_settings yet; the
            // environment value remains a safe fallback.
        }
        return requireRate(rate);
    }

    public long usdToCnyAmount(long usdAmount, BigDecimal rate) {
        if (usdAmount <= 0) return 0;
        return BigDecimal.valueOf(usdAmount).multiply(requireRate(rate))
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private BigDecimal requireRate(BigDecimal rate) {
        if (rate == null || rate.compareTo(new BigDecimal("0.01")) < 0
                || rate.compareTo(new BigDecimal("100")) > 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "USD/CNY payment rate is invalid");
        }
        return rate.setScale(8, RoundingMode.HALF_UP);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    public record SettlementQuote(MoneyAmount money, BigDecimal exchangeRate) {}
}
