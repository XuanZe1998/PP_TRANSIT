package com.transit.controller;

import com.transit.service.ProviderAccountAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Dedicated upstream-account callback boundary, separate from end-user login OAuth. */
@RestController
@RequiredArgsConstructor
public class UpstreamOAuthCallbackController {
    private final ProviderAccountAdminService accounts;

    @GetMapping("/upstream/oauth/callback/{platform}")
    public Map<String, Object> callback(@PathVariable String platform,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(required = false) String error) {
        Map<String, Object> status = accounts.status();
        if (!Boolean.TRUE.equals(status.get("oauthEnabled"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "上游 OAuth 号池未启用");
        }
        if (error != null && !error.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游授权未完成");
        if (code == null || code.isBlank() || state == null || state.length() < 24) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上游 OAuth 回调参数无效");
        }
        // Token exchange remains fail-closed until a provider-specific standard OAuth client is configured.
        return accounts.oauthAuthorization(platform);
    }
}
