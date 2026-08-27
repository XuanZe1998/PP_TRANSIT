package com.transit.service;

import com.transit.dto.MoneyAmount;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class MoneyServiceTests {
    private final MoneyService service=new MoneyService(new BigDecimal("7.12345678"));

    @Test void respectsExplicitScaleForWalletAndProducts(){
        assertThat(service.settlementQuote(new MoneyAmount(500_000,"CNY",10_000)).money()).isEqualTo(MoneyAmount.cents(5_000,"CNY"));
        assertThat(service.settlementQuote(new MoneyAmount(5_000,"CNY",100)).money()).isEqualTo(MoneyAmount.cents(5_000,"CNY"));
    }

    @Test void convertsUsdAndRoundsOnlyAtGatewayBoundary(){
        var quote=service.settlementQuote(new MoneyAmount(1_23,"USD",100));
        assertThat(quote.money().amount()).isEqualTo(876L);
        assertThat(quote.exchangeRate()).isEqualByComparingTo("7.12345678");
    }

    @Test void rejectsUnsupportedCurrencyZeroAndOverflow(){
        assertThatThrownBy(()->service.settlementQuote(new MoneyAmount(1,"EUR",100))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.settlementQuote(new MoneyAmount(0,"CNY",100))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.settlementQuote(new MoneyAmount(Long.MAX_VALUE,"USD",1))).isInstanceOf(ResponseStatusException.class);
    }
}
