package com.transit.controller;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.service.TransitService;
import com.transit.service.UniversalModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatController {

    private final TransitService transitService;
    @Autowired(required = false)
    private UniversalModelService universalModelService;

    @Value("${gateway.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                    @RequestBody ChatRequest request,
                                                    HttpServletRequest httpRequest) {
        String clientIp = clientIp(httpRequest);
        if (request.isStream()) {
            if (universalModelService != null
                    && universalModelService.hasHaoeeRoute(request.getModel(), "chat-completions")) {
                return Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                        .header("X-Accel-Buffering", "no")
                        .body(universalModelService.streamChat(authorization, clientIp, request)));
            }
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(transitService.chatCompletionsStream(authorization, request, clientIp)));
        }
        return transitService.chatCompletions(authorization, request, clientIp, idempotencyKey)
                .map(response -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response));
    }

    @GetMapping("/models")
    public Mono<Map<String, Object>> models(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {
        return Mono.fromCallable(() -> Map.of("object", "list",
                "data", transitService.availableModelCatalog(authorization, clientIp(httpRequest))));
    }

    private String clientIp(HttpServletRequest httpRequest) {
        String forwarded = httpRequest.getHeader("X-Forwarded-For");
        if (trustForwardedHeaders && forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return httpRequest.getRemoteAddr();
    }
}
