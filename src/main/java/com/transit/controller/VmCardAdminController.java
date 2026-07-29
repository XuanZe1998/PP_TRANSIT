package com.transit.controller;

import com.transit.model.User;
import com.transit.service.AdminAuditService;
import com.transit.service.CurrentUserService;
import com.transit.service.VmCardClientService;
import com.transit.service.VmCardProductCodeService;
import com.transit.service.VmCardSavedCardService;
import com.transit.service.VmCardWebhookService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/vmcard")
@RequiredArgsConstructor
public class VmCardAdminController {
    private final VmCardClientService clientService;
    private final VmCardWebhookService webhookService;
    private final VmCardSavedCardService savedCardService;
    private final VmCardProductCodeService productCodeService;
    private final CurrentUserService currentUserService;
    private final AdminAuditService auditService;

    @GetMapping("/configuration")
    public Map<String, Object> configuration(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return clientService.configuration();
    }

    @PostMapping("/token-check")
    public Map<String, Object> tokenCheck(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                          HttpServletRequest request) {
        User admin = currentUserService.requireAdmin(authHeader);
        Map<String, Object> result = clientService.checkToken();
        auditService.record(admin, "TEST_VMCARD_TOKEN", "VMCARD", null, null,
                Map.of("environment", result.get("environment")), request.getRemoteAddr());
        return result;
    }

    @PostMapping("/execute/{operation}")
    public Map<String, Object> execute(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @PathVariable String operation,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest request) {
        User admin = currentUserService.requireAdmin(authHeader);
        Map<String, Object> result = clientService.execute(operation, body);
        auditService.record(admin, "TEST_VMCARD_OPERATION", "VMCARD", operation, null,
                Map.of("operation", operation, "environment", result.get("environment")), request.getRemoteAddr());
        return result;
    }

    @GetMapping("/webhook-events")
    public List<Map<String, Object>> webhookEvents(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return webhookService.recentEvents();
    }

    @GetMapping("/saved-cards")
    public List<Map<String, Object>> savedCards(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return savedCardService.recentCards();
    }

    @GetMapping("/product-codes")
    public List<Map<String, Object>> productCodes(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return productCodeService.list(clientService.currentEnvironment());
    }

    @PutMapping("/product-codes/{id}/availability")
    public Map<String, Object> updateProductCodeAvailability(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        User admin = currentUserService.requireAdmin(authHeader);
        Object rawAvailable = body == null ? null : body.get("available");
        if (!(rawAvailable instanceof Boolean available)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "available must be a boolean");
        }
        Map<String, Object> result = productCodeService.setAvailability(
                id, clientService.currentEnvironment(), available);
        auditService.record(admin, "UPDATE_VMCARD_PRODUCT_AVAILABILITY", "VMCARD_PRODUCT_CODE",
                String.valueOf(id), null,
                Map.of("available", available, "productCode", result.get("productCode")),
                request.getRemoteAddr());
        return result;
    }
}
