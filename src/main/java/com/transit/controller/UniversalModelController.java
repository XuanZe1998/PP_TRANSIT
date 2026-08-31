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
        applySession(body, request);
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

    @PostMapping(value="/images/edits", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<JsonNode>> imageEdit(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestPart("model") String model,
            @RequestPart("image") MultipartFile image, @RequestPart(value="prompt",required=false) String prompt,
            HttpServletRequest request) throws java.io.IOException {
        java.util.Map<String,String> fields = new java.util.HashMap<>(); if (prompt != null) fields.put("prompt", prompt);
        return service.multipartInvoke(auth, request.getRemoteAddr(), model, "image-edits", "/v1/images/edits", "image",
                image.getBytes(), image.getOriginalFilename(), image.getContentType(), fields, key).map(ResponseEntity::ok);
    }

    @PostMapping(value="/videos", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> videos(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return invoke(auth,key,body,request,"video","/v1/videos");
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

    @PostMapping(value="/audio/translations", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<JsonNode>> translation(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestPart("model") String model,
            @RequestPart("file") MultipartFile file, HttpServletRequest request) throws java.io.IOException {
        return service.multipartInvoke(auth,request.getRemoteAddr(),model,"audio-translations","/v1/audio/translations","file",
                file.getBytes(),file.getOriginalFilename(),file.getContentType(),java.util.Map.of(),key).map(ResponseEntity::ok);
    }

    @PostMapping(value="/audio/voices", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<JsonNode>> voice(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key, @RequestPart("model") String model,
            @RequestPart("audio") MultipartFile file, @RequestPart(value="name",required=false) String name,
            HttpServletRequest request) throws java.io.IOException {
        return service.multipartInvoke(auth,request.getRemoteAddr(),model,"custom-voices","/v1/audio/voices","audio",
                file.getBytes(),file.getOriginalFilename(),file.getContentType(),name==null?java.util.Map.of():java.util.Map.of("name",name),key).map(ResponseEntity::ok);
    }

    @PostMapping(value="/realtime/sessions", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> realtime(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @RequestBody JsonNode body, HttpServletRequest request) {
        return invoke(auth,key,body,request,"realtime","/v1/realtime/sessions");
    }

    private Mono<ResponseEntity<JsonNode>> invoke(String auth, String key, JsonNode body,
            HttpServletRequest request, String protocol, String path) {
        applySession(body, request);
        return service.invoke(auth, request.getRemoteAddr(), protocol, path, body, key)
                .map(ResponseEntity::ok);
    }

    private void applySession(JsonNode body, HttpServletRequest request) {
        if (!(body instanceof com.fasterxml.jackson.databind.node.ObjectNode object) || object.hasNonNull("session_id")) return;
        String value = request.getHeader("X-Session-Id");
        if (value == null || value.isBlank()) value = request.getHeader("session_id");
        if (value != null && !value.isBlank()) object.put("session_id", value.substring(0, Math.min(256, value.length())));
    }
}
