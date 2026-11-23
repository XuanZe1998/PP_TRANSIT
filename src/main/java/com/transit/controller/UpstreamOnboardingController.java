package com.transit.controller;

import com.transit.service.AdminAuditService;
import com.transit.service.CurrentUserService;
import com.transit.service.NewApiOnboardingService;
import com.transit.service.OpenAiCompatibleOnboarding;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/api/gateway/onboard/openai-compatible")
@RequiredArgsConstructor
public class UpstreamOnboardingController {
    private final CurrentUserService users;
    private final OpenAiCompatibleOnboarding onboarding;
    private final AdminAuditService audit;

    @PostMapping("/preview")
    public NewApiOnboardingService.Preview preview(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                   @RequestBody NewApiOnboardingService.Request request) {
        users.requireAdmin(auth);
        return onboarding.preview(request);
    }

    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                       @RequestBody NewApiOnboardingService.Request request) {
        var admin = users.requireAdmin(auth);
        var result = onboarding.connect(request);
        audit.record(admin, "CONNECT_UPSTREAM", "CHANNEL", null, null, Map.of("adapter", "openai-compatible"), null);
        return result;
    }
}
