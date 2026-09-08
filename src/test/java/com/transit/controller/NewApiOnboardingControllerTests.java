package com.transit.controller;

import com.transit.service.AdminAuditService;
import com.transit.service.CurrentUserService;
import com.transit.service.NewApiOnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class NewApiOnboardingControllerTests {
    @Test void bothOperationsRequireAdminBeforeNetworkOrPersistence() {
        CurrentUserService users = mock(CurrentUserService.class);
        NewApiOnboardingService service = mock(NewApiOnboardingService.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        when(users.requireAdmin("denied")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        var sync = mock(com.transit.service.NewApiSyncService.class);
        var catalogs = mock(com.transit.service.NewApiCatalogManagementService.class);
        var controller = new NewApiOnboardingController(users, service, audit, sync, catalogs);
        assertThatThrownBy(() -> controller.preview("denied", null)).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.connect("denied", null, null)).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.syncStatus("denied")).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.synchronize("denied", 1, null)).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.configure("denied", 1, null, null)).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.catalog("denied")).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.syncCatalog("denied", 1, null)).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.configureCatalog("denied", 1, null, null)).hasMessageContaining("403");
        assertThatThrownBy(() -> controller.importCatalog("denied", null, null)).hasMessageContaining("403");
        verifyNoInteractions(service, audit, sync, catalogs);
    }
}
