package com.transit.controller;

import com.transit.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PaymentServiceProxyControllerTests {

    private final PaymentServiceProxyController controller =
            new PaymentServiceProxyController(mock(CurrentUserService.class));

    @Test
    void rewritesGatewayPathToFlaskApiPath() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/payment-service/api/regions");

        String path = ReflectionTestUtils.invokeMethod(controller, "extractPath", request);

        assertThat(path).isEqualTo("/api/regions");
    }

    @Test
    void preservesEventTaskPathWhenRewriting() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/payment-service/api/events/task-123");

        String path = ReflectionTestUtils.invokeMethod(controller, "extractPath", request);

        assertThat(path).isEqualTo("/api/events/task-123");
    }
}
