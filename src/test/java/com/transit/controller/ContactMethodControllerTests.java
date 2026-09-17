package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.model.User;
import com.transit.service.AdminAuditService;
import com.transit.service.ContactMethodService;
import com.transit.service.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContactMethodControllerTests {
    private ContactMethodService contactMethods;
    private CurrentUserService currentUserService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        contactMethods = mock(ContactMethodService.class);
        currentUserService = mock(CurrentUserService.class);
        ContactMethodController controller = new ContactMethodController(
                contactMethods, currentUserService, mock(AdminAuditService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void publicEndpointDefaultsToTenRows() throws Exception {
        when(contactMethods.page(1, 10, false, null)).thenReturn(emptyPage());

        mvc.perform(get("/public/contact-methods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(10));

        verify(contactMethods).page(1, 10, false, null);
    }

    @Test
    void adminEndpointDefaultsToTenRows() throws Exception {
        when(currentUserService.requireAdmin("Bearer test")).thenReturn(User.builder().id(1L).role("ADMIN").build());
        when(contactMethods.page(1, 10, false, null)).thenReturn(emptyPage());

        mvc.perform(get("/admin/api/contact-methods").header("Authorization", "Bearer test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(10));

        verify(contactMethods).page(1, 10, false, null);
    }

    private PageResponse<Map<String, Object>> emptyPage() {
        PageResponse<Map<String, Object>> response = new PageResponse<>();
        response.setPage(1);
        response.setSize(10);
        response.setItems(List.of());
        return response;
    }
}
