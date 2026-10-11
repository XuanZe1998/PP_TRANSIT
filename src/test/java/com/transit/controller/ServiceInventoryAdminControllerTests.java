package com.transit.controller;

import com.transit.model.User;
import com.transit.service.AdminAuditService;
import com.transit.service.ClientIpResolver;
import com.transit.service.CurrentUserService;
import com.transit.service.ServiceCommerceService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceInventoryAdminControllerTests {
    @Mock CurrentUserService users;
    @Mock ServiceCommerceService commerce;
    @Mock AdminAuditService audit;
    @Mock ClientIpResolver ips;
    @Mock HttpServletRequest request;
    @InjectMocks AdminApiController controller;

    @Test
    void requiresAdminAndNeverRevealsForRegularUsers() {
        when(users.requireAdmin("Bearer user")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.revealOtherServiceInventory("Bearer user", 1L, 2L, "VIEW", request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(commerce, audit);
    }
    @Test
    void returnsNoStoreAndAuditsOnlyMetadataForViewAndCopy() {
        User admin = admin();
        when(commerce.revealInventory(1L, 2L)).thenReturn("FULL-SECRET");
        var response = controller.revealOtherServiceInventory("Bearer admin", 1L, 2L, "VIEW", request).block();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody()).containsEntry("content", "FULL-SECRET");
        controller.revealOtherServiceInventory("Bearer admin", 1L, 2L, "COPY", request).block();
        verify(audit).record(eq(admin), eq("VIEW_SERVICE_INVENTORY"), eq("SERVICE_INVENTORY"), eq(2L), isNull(),
                eq(Map.of("serviceId", 1L, "outcome", "SUCCESS", "summary", Map.of("revealed", 1))), eq("127.0.0.1"));
        verify(audit).record(eq(admin), eq("COPY_SERVICE_INVENTORY"), eq("SERVICE_INVENTORY"), eq(2L), isNull(), any(), eq("127.0.0.1"));
    }
    @Test
    void auditsFailedOperationsWithoutExceptionOrSecretData() {
        User admin = admin();
        when(commerce.revealInventory(1L, 2L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "unsafe-secret-in-exception"));
        assertThatThrownBy(() -> controller.revealOtherServiceInventory("Bearer admin", 1L, 2L, "VIEW", request).block()).isInstanceOf(ResponseStatusException.class);
        verify(audit).record(eq(admin), eq("VIEW_SERVICE_INVENTORY"), eq("SERVICE_INVENTORY"), eq(2L), isNull(),
                eq(Map.of("serviceId", 1L, "outcome", "FAILED")), eq("127.0.0.1"), eq("FAILED"));
    }
    private User admin() {
        User admin = User.builder().id(7L).username("admin").role("ADMIN").build();
        when(users.requireAdmin("Bearer admin")).thenReturn(admin); when(ips.resolve(request)).thenReturn("127.0.0.1"); return admin;
    }
}
