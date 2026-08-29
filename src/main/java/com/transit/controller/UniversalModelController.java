package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.service.UniversalModelService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class UniversalModelController {
    private final UniversalModelService service;

    @PostMapping(value="/responses", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> responses(
            @RequestHeader(value=HttpHeaders.AUTHORIZATION, required=false) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        if (body != null && body.path("stream").asBoolean(false)) {
            return Mono.just((ResponseEntity<?>) ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noCache())
                    .header("X-Accel-Buffering", "no")
                    .body(service.streamResponses(auth, request.getRemoteAddr(), body)));
        }
        return service.invoke(auth, request.getRemoteAddr(), "responses", "/v1/responses", body, key)
                .map(response -> (ResponseEntity<?>) ResponseEntity.ok(response));
    }

    @PostMapping(value="/embeddings", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> embeddings(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return invoke(auth, key, body, request, "embeddings", "/compatible-mode/v1/embeddings");
    }

    @PostMapping(value="/reranks", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> reranks(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return invoke(auth, key, body, request, "reranks", "/compatible-api/v1/reranks");
    }

    @PostMapping(value="/images/generations", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> images(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return invoke(auth, key, body, request, "images", "/v1/images/generations");
    }

    @PostMapping(value="/audio/speech", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> speech(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return invoke(auth, key, body, request, "audio-speech", "/v1/audio/speech");
    }

    @PostMapping(value="/audio/transcriptions", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<JsonNode>> transcription(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestPart("model") String model, @RequestPart("file") MultipartFile file,
            @RequestPart(value="language",required=false) String language,
            @RequestPart(value="response_format",required=false) String responseFormat,
            HttpServletRequest request) throws java.io.IOException {
        java.util.Map<String,String> fields = new java.util.HashMap<>();
        if (language != null) fields.put("language", language);
        if (responseFormat != null) fields.put("response_format", responseFormat);
        return service.transcribe(auth, request.getRemoteAddr(), model, file.getBytes(),
                file.getOriginalFilename(), file.getContentType(), fields, key).map(ResponseEntity::ok);
    }

    private Mono<ResponseEntity<JsonNode>> invoke(String auth, String key, JsonNode body,
            HttpServletRequest request, String protocol, String path) {
        return service.invoke(auth, request.getRemoteAddr(), protocol, path, body, key)
                .map(ResponseEntity::ok);
    }
}
