package com.transit.controller;

import com.transit.service.OAuthService;
import com.transit.service.CurrentUserService;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

@RestController
@RequestMapping("/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;
    private final CurrentUserService currentUserService;

    @GetMapping("/authorize")
    public Mono<ResponseEntity<Map<String, String>>> authorize(
            @RequestParam("provider") String provider,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        return Mono.fromCallable(() -> {
            User target = authHeader == null || authHeader.isBlank() ? null : currentUserService.requireUser(authHeader);
            OAuthService.AuthorizationStart start = oauthService.beginAuthorization(provider, target == null ? null : target.getId());
            return ResponseEntity.ok(Map.of("url", start.url(), "state", start.state()));
        });
    }

    @GetMapping("/callback/{provider}")
    public Mono<ResponseEntity<Map<String, Object>>> callback(
            @PathVariable String provider,
            @RequestParam("code") String code,
            @RequestParam("state") String state,
            HttpServletRequest request) {
        return oauthService.handleCallback(provider, code, state, request)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/token")
    public Mono<ResponseEntity<Map<String, Object>>> token(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri,
            @RequestParam(value = "grant_type", defaultValue = "authorization_code") String grantType) {
        String credential = "refresh_token".equals(grantType) ? refreshToken : code;
        return Mono.fromCallable(() -> ResponseEntity.ok(
                oauthService.exchangeToken(clientId, clientSecret, credential, grantType, redirectUri)));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Map<String, String>>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        String token = bearer(authHeader);
        oauthService.revokeToken(token);
        return Mono.just(ResponseEntity.ok(Map.of("message", "Logged out successfully")));
    }

    @PostMapping("/revoke")
    public Mono<ResponseEntity<Map<String, String>>> revoke(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
            @RequestParam(value = "token", required = false) String requestedToken) {
        String callerToken = bearer(authHeader);
        if (requestedToken != null && !requestedToken.isBlank() && !requestedToken.equals(callerToken)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "A session may only revoke itself");
        }
        oauthService.revokeToken(callerToken);
        return Mono.just(ResponseEntity.ok(Map.of("message", "Token revoked")));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam("refresh_token") String refreshToken) {
        return Mono.fromCallable(() -> ResponseEntity.ok(
                oauthService.refreshToken(clientId, clientSecret, refreshToken)));
    }

    private String bearer(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ") || authHeader.substring(7).isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }
        return authHeader.substring(7).trim();
    }
}
