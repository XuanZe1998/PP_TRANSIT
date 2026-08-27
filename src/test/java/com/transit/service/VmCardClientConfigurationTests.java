package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class VmCardClientConfigurationTests {

    @Test
    void enablesSandboxWritesButKeepsProductionBehindIndependentSwitch() {
        VmCardClientService service = new VmCardClientService(
                WebClient.builder().build(),
                new ObjectMapper(),
                new VmCardCryptoService(),
                mock(VmCardSavedCardService.class),
                mock(VmCardProductCodeService.class));
        ReflectionTestUtils.setField(service, "allowMutations", true);
        ReflectionTestUtils.setField(service, "allowProductionMutations", false);
        ReflectionTestUtils.setField(service, "environment", "sandbox");

        assertThat(service.configuration()).containsEntry("mutationsAllowed", true);

        ReflectionTestUtils.setField(service, "environment", "production");
        assertThat(service.configuration()).containsEntry("mutationsAllowed", false);

        ReflectionTestUtils.setField(service, "allowProductionMutations", true);
        assertThat(service.configuration()).containsEntry("mutationsAllowed", true);
    }

    @Test
    void rejectsVendorBusinessErrorsButAcceptsDocumentedSuccessShapes() throws Exception {
        VmCardClientService service = new VmCardClientService(
                WebClient.builder().build(),
                new ObjectMapper(),
                new VmCardCryptoService(),
                mock(VmCardSavedCardService.class),
                mock(VmCardProductCodeService.class));
        ObjectMapper mapper = new ObjectMapper();

        service.requireVendorSuccess(VmCardOperation.CREATE_CARD,
                mapper.readTree("{\"code\":0,\"msg\":\"ok\"}"));
        service.requireVendorSuccess(VmCardOperation.CREATE_CARD,
                mapper.readTree("{\"code\":200,\"data\":{}}"));
        service.requireVendorSuccess(VmCardOperation.UPDATE_CARD_LIMIT, mapper.readTree("{}"));

        assertThatThrownBy(() -> service.requireVendorSuccess(VmCardOperation.RECHARGE_CARD,
                mapper.readTree("{\"code\":400003,\"msg\":\"Amount Is Less Than 10\"}")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(422);
                    assertThat(exception.getReason()).contains("400003", "Amount Is Less Than 10");
                });
    }

    @Test
    void rejectsCardDetailWithoutCardIdBeforeCallingTheVendor() {
        VmCardClientService service = new VmCardClientService(
                WebClient.builder().build(),
                new ObjectMapper(),
                new VmCardCryptoService(),
                mock(VmCardSavedCardService.class),
                mock(VmCardProductCodeService.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "appId", "sandbox-app");
        ReflectionTestUtils.setField(service, "appSecret", "sandbox-secret");

        assertThatThrownBy(() -> service.execute("cardDetail", java.util.Map.of("card_id", "  ")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(400);
                    assertThat(exception.getReason()).contains("card_id", "getCardList", "not the card number");
                });
    }
}
