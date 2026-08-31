package com.transit.controller;

import com.transit.model.User;
import com.transit.service.AdminAuditService;
import com.transit.service.CurrentUserService;
import com.transit.service.UpstreamOAuthClientConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/provider-oauth-clients")
@RequiredArgsConstructor
public class ProviderOAuthClientAdminController {
    private final CurrentUserService currentUsers;
    private final UpstreamOAuthClientConfigService configs;
    private final AdminAuditService audit;

    @GetMapping
    public List<Map<String, Object>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        currentUsers.requireAdmin(auth);
        return configs.list();
    }

    @PutMapping("/{platform}")
    public Map<String, Object> save(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                    @PathVariable String platform,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        User admin = currentUsers.requireAdmin(auth);
        Map<String, Object> before = configs.view(platform);
        Map<String, Object> after = configs.save(platform, admin.getId(), body);
        audit.record(admin, "UPDATE_PROVIDER_OAUTH_CLIENT", "PROVIDER_OAUTH_CLIENT", platform,
                auditView(before), auditView(after), request.getRemoteAddr());
        return after;
    }

    @PostMapping("/{platform}/test")
    public Map<String, Object> test(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                    @PathVariable String platform,
                                    HttpServletRequest request) {
        User admin = currentUsers.requireAdmin(auth);
        Map<String, Object> result = configs.test(platform);
        audit.record(admin, "TEST_PROVIDER_OAUTH_CLIENT", "PROVIDER_OAUTH_CLIENT", platform,
                null, Map.of("platform", result.get("platform"), "status", result.get("status")), request.getRemoteAddr());
        return result;
    }

    private Map<String, Object> auditView(Map<String, Object> value) {
        return Map.of("platform", value.get("platform"), "source", value.get("source"),
                "enabled", value.get("enabled"), "configured", value.get("configured"),
                "clientIdPreview", value.get("clientIdPreview"), "hasClientSecret", value.get("hasClientSecret"),
                "version", value.get("version"));
    }
}
