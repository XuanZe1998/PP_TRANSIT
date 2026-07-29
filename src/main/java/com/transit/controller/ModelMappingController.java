package com.transit.controller;

import com.transit.model.ModelMapping;
import com.transit.service.AdminModelService;
import com.transit.service.CurrentUserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/mappings")
@RequiredArgsConstructor
public class ModelMappingController {

    private final AdminModelService modelService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public Flux<ModelMapping> getAllMappings(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(modelService.list());
    }

    @PostMapping
    public Mono<ModelMapping> createMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @RequestBody MappingRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ModelMapping mapping = ModelMapping.builder()
                    .publicModelName(request.getPublicModelName())
                    .channelModelName(request.getChannelModelName())
                    .channelId(request.getChannelId())
                    .priority(request.getPriority())
                    .enabled(request.isEnabled())
                    .build();
            return modelService.create(mapping);
        });
    }

    @PutMapping("/{id}")
    public Mono<ModelMapping> updateMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                            @PathVariable Long id,
                                            @RequestBody MappingRequest request) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            return modelService.updateRouting(id, request.getPublicModelName(),
                    request.getChannelModelName(), request.getChannelId(),
                    request.getPriority(), request.isEnabled());
        });
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteMapping(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                    @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> modelService.delete(id));
    }

    @Data
    public static class MappingRequest {
        private String publicModelName;
        private String channelModelName;
        private Long channelId;
        private int priority;
        private boolean enabled;
    }
}
