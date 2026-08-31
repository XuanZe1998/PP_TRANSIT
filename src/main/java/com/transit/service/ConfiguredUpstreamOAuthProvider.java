package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standard authorization-code/PKCE client driven entirely by private deployment configuration. */
public final class ConfiguredUpstreamOAuthProvider implements UpstreamOAuthProvider {
    private final String platform;
    private final UpstreamOAuthClientConfigService configs;
    private final UpstreamProxyHttpClientFactory clients;

    public ConfiguredUpstreamOAuthProvider(String platform, UpstreamOAuthClientConfigService configs, UpstreamProxyHttpClientFactory clients) {
        this.platform = platform;
        this.configs = configs;
        this.clients = clients;
    }

    @Override public String platform() { return platform; }
    @Override public boolean configured() { return config().configured(); }
    @Override public String upstreamBaseUrl() { return config().upstreamBaseUrl(); }

    @Override
    public String authorizationUrl(String state, String nonce, String challenge, String redirectUri) {
        UpstreamOAuthClientConfigService.RuntimeConfig config = requireConfigured();
        return UriComponentsBuilder.fromUriString(config.authorizationUri())
                .queryParam("response_type", "code").queryParam("client_id", config.clientId())
                .queryParam("redirect_uri", redirectUri).queryParam("scope", normalizedScopes(config))
                .queryParam("state", state).queryParam("nonce", nonce)
                .queryParam("code_challenge", challenge).queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
    }

    @Override public OAuthToken exchange(String code, String verifier, String redirectUri, Long proxyId) {
        UpstreamOAuthClientConfigService.RuntimeConfig config = requireConfigured();
        MultiValueMap<String, String> form = baseForm(config, "authorization_code");
        form.add("code", code); form.add("code_verifier", verifier); form.add("redirect_uri", redirectUri);
        return token(config, form, null, proxyId);
    }

    @Override public OAuthToken refresh(String refreshToken, Long proxyId) {
        UpstreamOAuthClientConfigService.RuntimeConfig config = requireConfigured();
        MultiValueMap<String, String> form = baseForm(config, "refresh_token");
        form.add("refresh_token", refreshToken);
        return token(config, form, refreshToken, proxyId);
    }

    @Override public Inspection inspect(OAuthToken token, Long proxyId) {
        UpstreamOAuthClientConfigService.RuntimeConfig config = requireConfigured();
        JsonNode identity = get(config.userinfoUri(), token.accessToken(), proxyId);
        JsonNode catalog = get(config.modelsUri(), token.accessToken(), proxyId);
        JsonNode probe;
        if (text(config.probeUri())) probe = get(config.probeUri(), token.accessToken(), proxyId);
        else throw new IllegalStateException(platform + " OAuth probe URI is not configured");
        List<String> models = modelNames(catalog);
        if (models.isEmpty()) throw new IllegalStateException(platform + " returned no usable models");
        Map<String, Object> claims = claims(token.accessToken());
        String subject = first(identity, "sub", "id", "user_id", "account_id");
        String email = first(identity, "email", "preferred_username");
        String tier = first(identity, "subscription_tier", "plan", "tier");
        if (subject == null) subject = string(claims.get("sub"));
        if (email == null) email = string(claims.get("email"));
        if (tier == null) tier = string(claims.get("plan"));
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("modelCount", models.size());
        metadata.put("modelCapabilities", modelCapabilities(catalog));
        for (String field : List.of("quota_limit", "quota_used", "quota_remaining", "resets_at"))
            if (probe != null && probe.hasNonNull(field) && probe.get(field).isValueNode()) metadata.put(field, probe.get(field).asText());
        return new Inspection(subject, email, tier, "ACTIVE", models, metadata);
    }

    private OAuthToken token(UpstreamOAuthClientConfigService.RuntimeConfig config, MultiValueMap<String, String> form, String previousRefreshToken, Long proxyId) {
        JsonNode json = clients.client(proxyId).post().uri(config.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form)).retrieve().bodyToMono(JsonNode.class).block();
        if (json == null || !text(json.path("access_token").asText())) throw new IllegalStateException(platform + " token response is invalid");
        long seconds = Math.max(30, json.path("expires_in").asLong(3600));
        String refresh = json.path("refresh_token").asText(previousRefreshToken);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (json.hasNonNull("id_token")) {
            metadata.put("idTokenPresent", true);
            Object nonce = claims(json.get("id_token").asText()).get("nonce"); if (nonce != null) metadata.put("idTokenNonce", String.valueOf(nonce));
        }
        return new OAuthToken(json.path("access_token").asText(), refresh, Instant.now().plusSeconds(seconds),
                json.path("scope").asText(normalizedScopes(config)), json.path("token_type").asText("Bearer"), metadata);
    }

    private MultiValueMap<String, String> baseForm(UpstreamOAuthClientConfigService.RuntimeConfig config, String grantType) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType); form.add("client_id", config.clientId());
        if (text(config.clientSecret())) form.add("client_secret", config.clientSecret());
        return form;
    }

    private JsonNode get(String uri, String token, Long proxyId) {
        if (!text(uri)) throw new IllegalStateException(platform + " OAuth inspection endpoint is not configured");
        return clients.client(proxyId).get().uri(URI.create(uri)).headers(headers -> headers.setBearerAuth(token))
                .retrieve().bodyToMono(JsonNode.class).block();
    }

    private List<String> modelNames(JsonNode root) {
        List<String> result = new ArrayList<>();
        if (root == null) return result;
        JsonNode rows = root.isArray() ? root : (root.has("data") ? root.get("data") : root.get("models"));
        if (rows != null && rows.isArray()) for (JsonNode row : rows) {
            String name = first(row, "id", "name", "model");
            if (name != null && !name.isBlank()) result.add(name.startsWith("models/") ? name.substring(7) : name);
        }
        return result.stream().distinct().toList();
    }

    private Map<String, List<String>> modelCapabilities(JsonNode root) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (root == null) return result;
        JsonNode rows = root.isArray() ? root : (root.has("data") ? root.get("data") : root.get("models"));
        if (rows == null || !rows.isArray()) return result;
        for (JsonNode row : rows) {
            String name = first(row, "id", "name", "model"); if (name == null) continue;
            if (name.startsWith("models/")) name = name.substring(7);
            JsonNode values = row.has("supportedGenerationMethods") ? row.get("supportedGenerationMethods") : row.get("capabilities");
            List<String> capabilities = new ArrayList<>();
            if (values != null && values.isArray()) values.forEach(value -> capabilities.add(value.asText()));
            else if (values != null && values.isObject()) values.fields().forEachRemaining(entry -> { if (entry.getValue().asBoolean(false)) capabilities.add(entry.getKey()); });
            result.put(name, capabilities);
        }
        return result;
    }

    private Map<String, Object> claims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return Map.of();
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(decoded, Map.class);
        } catch (Exception ignored) { return Map.of(); }
    }

    private String first(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) if (node.hasNonNull(name) && text(node.get(name).asText())) return node.get(name).asText();
        return null;
    }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private String normalizedScopes(UpstreamOAuthClientConfigService.RuntimeConfig config) { return config.scopes() == null ? "" : config.scopes().replace(',', ' ').trim(); }
    private boolean text(String value) { return value != null && !value.isBlank(); }
    private UpstreamOAuthClientConfigService.RuntimeConfig config() { return configs.resolve(platform); }
    private UpstreamOAuthClientConfigService.RuntimeConfig requireConfigured() {
        UpstreamOAuthClientConfigService.RuntimeConfig config = config();
        if (!config.configured()) throw new IllegalStateException(platform + " OAuth client is not completely configured");
        return config;
    }
}
