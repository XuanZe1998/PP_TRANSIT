package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.OtherServiceMapper;
import com.transit.model.OtherService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtherServiceCardKeySecurityTests {

    @Test
    void cardKeyServiceForcesAutomaticDeliveryAndDoesNotSerializeDestination() throws Exception {
        OtherServiceMapper mapper = mock(OtherServiceMapper.class);
        OtherServiceCatalogService service = new OtherServiceCatalogService(mapper);
        ReflectionTestUtils.setField(service, "redemptionAllowedHosts", "redeem.example.com");

        OtherService created = service.create(cardRequest("https://redeem.example.com/start?channel=modelhub"));

        assertThat(created.getFulfillmentMode()).isEqualTo("AUTOMATIC_DELIVERY");
        assertThat(created.getRedemptionConfigured()).isTrue();
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(created))
                .doesNotContain("redeem.example.com");
    }

    @Test
    void rejectsInsecureOrUnapprovedRedemptionDestinations() {
        OtherServiceCatalogService service = new OtherServiceCatalogService(mock(OtherServiceMapper.class));
        ReflectionTestUtils.setField(service, "redemptionAllowedHosts", "redeem.example.com");

        assertThatThrownBy(() -> service.create(cardRequest("http://redeem.example.com/start")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.create(cardRequest("https://evil.example/start")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> service.create(cardRequest("https://user:secret@redeem.example.com/start")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(cardRequest("https://redeem.example.com:8443/start")))
                .isInstanceOf(ResponseStatusException.class);

        ReflectionTestUtils.setField(service, "redemptionAllowedHosts", "");
        assertThat(service.create(cardRequest("https://redeem.example.com/start")).getRedemptionConfigured()).isTrue();
    }

    @Test
    void resolvesOnlyEnabledCardKeyServices() {
        OtherServiceMapper mapper = mock(OtherServiceMapper.class);
        OtherServiceCatalogService service = new OtherServiceCatalogService(mapper);
        ReflectionTestUtils.setField(service, "redemptionAllowedHosts", "redeem.example.com");
        OtherService stored = cardRequest("https://redeem.example.com/start");
        stored.setId(7L);
        stored.setEnabled(true);
        when(mapper.selectById(7L)).thenReturn(stored);

        assertThat(service.requireRedemptionDestination(7L))
                .isEqualTo(URI.create("https://redeem.example.com/start"));

        stored.setEnabled(false);
        assertThatThrownBy(() -> service.requireRedemptionDestination(7L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
    }

    private OtherService cardRequest(String destination) {
        return OtherService.builder()
                .name("Secure card")
                .enabled(true)
                .purchaseEnabled(true)
                .productType("CARD_KEY")
                .fulfillmentMode("MANUAL_PROCESSING")
                .redemptionUrl(destination)
                .priceCents(1000L)
                .serviceFeeCents(0L)
                .currency("CNY")
                .build();
    }
}
