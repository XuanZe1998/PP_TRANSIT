package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.service.ClientIpResolver;
import com.transit.service.TransitService;
import com.transit.service.UniversalModelService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/** Provider-native aliases used by official CLIs while billing still uses the common gateway. */
@RestController
@RequiredArgsConstructor
public class SubscriptionProtocolController {
    private final UniversalModelService gateway;
    private final TransitService transit;
    private final ClientIpResolver ips;

    @GetMapping("/v1beta/models")
    public Mono<Map<String, Object>> geminiModels(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestHeader(value="x-goog-api-key",required=false) String apiKey, HttpServletRequest request) {
        return Mono.fromCallable(() -> Map.of("models", transit.availableModelCatalog(auth(authorization, apiKey), ips.resolve(request)).stream()
                .map(model -> Map.of("name", "models/" + model.get("id"), "displayName", model.get("id"),
                        "supportedGenerationMethods", List.of("generateContent", "streamGenerateContent", "countTokens"))).toList()));
    }

    @PostMapping(value="/v1beta/models/{model}:generateContent", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> geminiGenerate(@PathVariable String model, @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestHeader(value="x-goog-api-key",required=false) String apiKey, @RequestBody ObjectNode body, HttpServletRequest request) {
        body.put("model", model); return gateway.invoke(auth(authorization, apiKey), ips.resolve(request), "gemini-generate-content",
                "/v1beta/models/" + model + ":generateContent", body, request.getHeader("Idempotency-Key")).map(ResponseEntity::ok);
    }

    @PostMapping(value="/v1beta/models/{model}:streamGenerateContent", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> geminiStream(@PathVariable String model, @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestHeader(value="x-goog-api-key",required=false) String apiKey, @RequestBody ObjectNode body, HttpServletRequest request) {
        body.put("model", model); return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noCache())
                .body(gateway.streamProtocol(auth(authorization, apiKey), ips.resolve(request), "gemini-stream-generate-content",
                        "/v1beta/models/" + model + ":streamGenerateContent", body));
    }

    @PostMapping(value="/v1beta/models/{model}:countTokens", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> geminiCount(@PathVariable String model, @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestHeader(value="x-goog-api-key",required=false) String apiKey, @RequestBody ObjectNode body, HttpServletRequest request) {
        body.put("model", model); return gateway.invoke(auth(authorization, apiKey), ips.resolve(request), "gemini-count-tokens",
                "/v1beta/models/" + model + ":countTokens", body, request.getHeader("Idempotency-Key")).map(ResponseEntity::ok);
    }

    @PostMapping(value="/v1beta/models/{model}:embedContent", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> geminiEmbedding(@PathVariable String model, @RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestHeader(value="x-goog-api-key",required=false) String apiKey, @RequestBody ObjectNode body, HttpServletRequest request) {
        body.put("model", model); return gateway.invoke(auth(authorization, apiKey), ips.resolve(request), "embeddings",
                "/v1beta/models/" + model + ":embedContent", body, request.getHeader("Idempotency-Key")).map(ResponseEntity::ok);
    }

    @PostMapping(value={"/antigravity/v1/**", "/antigravity/v1beta/**", "/backend-api/codex/**"}, consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> aliases(@RequestHeader(value=HttpHeaders.AUTHORIZATION,required=false) String authorization,
            @RequestBody ObjectNode body, HttpServletRequest request) {
        String incoming = request.getRequestURI();
        String path = incoming.startsWith("/antigravity") ? incoming.substring("/antigravity".length()) : incoming;
        String protocol = protocol(path);
        if (body.path("stream").asBoolean(false) || path.contains("streamGenerateContent")) {
            return Mono.just(ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).cacheControl(CacheControl.noCache())
                    .body(gateway.streamProtocol(authorization, ips.resolve(request), protocol, path, body)));
        }
        return gateway.invoke(authorization, ips.resolve(request), protocol, path, body, request.getHeader("Idempotency-Key"))
                .map(value -> (ResponseEntity<?>) ResponseEntity.ok(value));
    }

    private String protocol(String path) {
        if (path.contains("messages/count_tokens")) return "count-tokens";
        if (path.contains("messages")) return "messages";
        if (path.contains("streamGenerateContent")) return "gemini-stream-generate-content";
        if (path.contains("generateContent")) return "gemini-generate-content";
        if (path.contains("countTokens")) return "gemini-count-tokens";
        if (path.contains("chat/completions")) return "chat-completions";
        return "responses";
    }
    private String auth(String authorization, String apiKey) { return authorization != null && !authorization.isBlank() ? authorization : "Bearer " + (apiKey == null ? "" : apiKey.trim()); }
}
