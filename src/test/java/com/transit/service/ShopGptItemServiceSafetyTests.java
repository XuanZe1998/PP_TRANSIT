package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopGptItemServiceSafetyTests {

    @Test
    void featureIsDisabledByDefaultAndEveryOperationStopsBeforeCreatingHttpClient() {
        ShopGptItemService service = new ShopGptItemService(new ObjectMapper());
        service.initializeClient();
        User user = User.builder().id(1L).email("user@example.com").build();

        assertDisabled(() -> service.prepare(user));
        assertDisabled(() -> service.captcha(user));
        assertDisabled(() -> service.sync(user, 1, "", 1));
        assertDisabled(() -> service.trade(user, 1, "captcha", 1));
        assertThat(ReflectionTestUtils.getField(service, "webClient")).isNull();
    }

    @Test
    void enablingWithoutExplicitSupplierConfigurationFailsFastWithoutNetworkTraffic() {
        ShopGptItemService service = new ShopGptItemService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);

        assertThatThrownBy(service::initializeClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shopgpt.base-url");
        assertThat(ReflectionTestUtils.getField(service, "webClient")).isNull();
    }

    @Test
    void supplierBaseUrlMustBeHttpsAndMustNotContainCredentials() {
        ShopGptItemService service = new ShopGptItemService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "configuredBaseUrl", "http://user:password@shop.example.test");

        assertThatThrownBy(service::initializeClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS origin");
        assertThat(ReflectionTestUtils.getField(service, "webClient")).isNull();
    }

    @Test
    void generatedSupplierOrderPasswordsAreRandomAndNotDerivedFromEmail() {
        String first = ShopGptItemService.newOrderPassword();
        String second = ShopGptItemService.newOrderPassword();

        assertThat(first).hasSizeGreaterThanOrEqualTo(40).doesNotContain("user@example.com");
        assertThat(second).isNotEqualTo(first);
    }

    private void assertDisabled(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(503);
                    assertThat(exception.getReason()).contains("features.shopgpt.enabled=false");
                });
    }
}
