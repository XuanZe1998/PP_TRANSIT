package com.transit.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface UpstreamOAuthProvider {
    String platform();
    boolean configured();
    String upstreamBaseUrl();
    String authorizationUrl(String state, String nonce, String verifierChallenge, String redirectUri);
    OAuthToken exchange(String code, String verifier, String redirectUri, Long proxyId);
    OAuthToken refresh(String refreshToken, Long proxyId);
    Inspection inspect(OAuthToken token, Long proxyId);

    record OAuthToken(String accessToken, String refreshToken, Instant expiresAt, String scope,
                      String tokenType, Map<String, Object> metadata) {}
    record Inspection(String externalAccountId, String email, String subscriptionTier,
                      String entitlementStatus, List<String> models, Map<String, Object> metadata) {}
}
