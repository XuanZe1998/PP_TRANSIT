package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.model.User;
import com.transit.service.AdminAuditService;
import com.transit.service.ContactMethodService;
import com.transit.service.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ContactMethodController {
    private final ContactMethodService contactMethods;
    private final CurrentUserService currentUserService;
    private final AdminAuditService adminAuditService;

    @GetMapping("/public/contact-methods")
    public PageResponse<Map<String, Object>> publicPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean all) {
        return contactMethods.page(page, size, all, null);
    }

    @GetMapping("/admin/api/contact-methods")
    public PageResponse<Map<String, Object>> adminPage(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean all,
            @RequestParam(required = false) String query) {
        currentUserService.requireAdmin(authorization);
        return contactMethods.page(page, size, all, query);
    }

    @PostMapping("/admin/api/contact-methods")
    public Map<String, Object> create(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = currentUserService.requireAdmin(authorization);
        Map<String, Object> created = contactMethods.create(request);
        audit(admin, "CREATE_CONTACT_METHOD", created.get("id"), null, created, servletRequest);
        return created;
    }

    @PutMapping("/admin/api/contact-methods/{id}")
    public Map<String, Object> update(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable long id,
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = currentUserService.requireAdmin(authorization);
        Map<String, Object> before = contactMethods.find(id);
        Map<String, Object> updated = contactMethods.update(id, request);
        audit(admin, "UPDATE_CONTACT_METHOD", id, before, updated, servletRequest);
        return updated;
    }

    @DeleteMapping("/admin/api/contact-methods/{id}")
    public Map<String, Boolean> delete(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable long id,
            HttpServletRequest servletRequest) {
        User admin = currentUserService.requireAdmin(authorization);
        Map<String, Object> before = contactMethods.delete(id);
        audit(admin, "DELETE_CONTACT_METHOD", id, before, null, servletRequest);
        return Map.of("deleted", true);
    }

    private void audit(User admin, String action, Object id, Object before, Object after,
                       HttpServletRequest request) {
        adminAuditService.record(admin, action, "CONTACT_METHOD", id, before, after, request.getRemoteAddr());
    }
}
