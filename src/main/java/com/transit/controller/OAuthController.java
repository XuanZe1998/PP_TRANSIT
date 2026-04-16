package com.transit.controller;

import com.transit.service.OAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/oauth")
@RequiredArgsConstructor
public class OAuthController {

    private final OAuthService oauthService;

    @GetMapping("/authorize")
    public Mono<ResponseEntity<Map<String, String>>> authorize(
            @RequestParam("provider") String provider,
            @RequestParam(value = "client_id", defaultValue = "default") String clientId,
            @RequestParam(value = "redirect_uri", required = false) String redirectUri) {
        String state = UUID.randomUUID().toString();
        String url = oauthService.getAuthorizeUrl(provider, state);
        return Mono.just(ResponseEntity.ok(Map.of("url", url, "state", state)));
    }

    @GetMapping("/callback/{provider}")
    public Mono<ResponseEntity<Map<String, Object>>> callback(
            @PathVariable String provider,
            @RequestParam("code") String code,
            @RequestParam("state") String state) {
        return oauthService.handleCallback(provider, code, state)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/token")
    public Mono<ResponseEntity<Map<String, Object>>> token(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam("code") String code,
            @RequestParam(value = "refresh_token", required = false) String refreshToken,
            @RequestParam(value = "grant_type", defaultValue = "authorization_code") String grantType) {
        try {
            Map<String, Object> response = oauthService.exchangeToken(clientId, clientSecret, 
                grantType.equals("refresh_token") ? refreshToken : code, grantType);
            return Mono.just(ResponseEntity.ok(response));
        } catch (Exception e) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
        }
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Map<String, String>>> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            oauthService.revokeToken(token);
        }
        return Mono.just(ResponseEntity.ok(Map.of("message", "Logged out successfully")));
    }

    @PostMapping("/revoke")
    public Mono<ResponseEntity<Map<String, String>>> revoke(@RequestParam("token") String token) {
        oauthService.revokeToken(token);
        return Mono.just(ResponseEntity.ok(Map.of("message", "Token revoked")));
    }

    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestParam("client_id") String clientId,
            @RequestParam("client_secret") String clientSecret,
            @RequestParam("refresh_token") String refreshToken) {
        try {
            Map<String, Object> response = oauthService.refreshToken(clientId, clientSecret, refreshToken);
            return Mono.just(ResponseEntity.ok(response));
        } catch (Exception e) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", e.getMessage())));
        }
    }
}
