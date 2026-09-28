package com.transit.service;

import com.transit.mapper.PaymentIntentMapper;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentIntentSwitchTests {
    @Test void directPaymentStartCannotBypassClosedCheckout() {
        PaymentIntentMapper mapper = mock(PaymentIntentMapper.class);
        LegalDocumentService legal = mock(LegalDocumentService.class);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Checkout closed"))
                .when(legal).requireCheckoutOpen();
        PaymentIntentService payments = new PaymentIntentService(mapper, null, null, null, null, legal);
        assertThatThrownBy(() -> payments.start(new User(), 9L, "127.0.0.1", "pc"))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mapper);
    }
}
