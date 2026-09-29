package com.transit.controller;

import com.transit.model.User;
import com.transit.service.AdminAuditService;
import com.transit.service.CurrentUserService;
import com.transit.service.GatewayGroupDeletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayGroupDeletionControllerTests {
    @Mock CurrentUserService users;
    @Mock GatewayGroupDeletionService groupDeletion;
    @Mock AdminAuditService audit;
    @InjectMocks GatewayManagementController controller;

    @Test void requiresAdminBeforeAnyDeletion() {
        when(users.requireAdmin("user-token")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThatThrownBy(()->controller.deleteGroup("user-token",42)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(groupDeletion,audit);
    }
    @Test void recordsSuccessfulDeletionWithoutCredentials() {
        User operator=new User();
        when(users.requireAdmin("admin-token")).thenReturn(operator);
        controller.deleteGroup("admin-token",42);
        verify(groupDeletion).deleteDisabled(42);
        verify(audit).record(operator,"DELETE_GATEWAY_GROUP","CHANNEL",42L,null,Map.of("deleted",true),null);
    }
    @Test void rejectedDeletionIsNotRecordedAsSuccess() {
        when(users.requireAdmin("admin-token")).thenReturn(new User());
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT)).when(groupDeletion).deleteDisabled(42);
        assertThatThrownBy(()->controller.deleteGroup("admin-token",42)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(audit);
    }
}
