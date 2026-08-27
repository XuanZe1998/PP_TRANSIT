package com.transit.controller;

import com.transit.model.ProviderCredential;
import com.transit.service.CurrentUserService;
import com.transit.service.ProviderCredentialService;
import com.transit.service.AdminChannelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/admin/api/channels/{channelId}/credentials")
@RequiredArgsConstructor
public class ProviderCredentialAdminController {
    private final CurrentUserService currentUserService;
    private final ProviderCredentialService service;
    private final AdminChannelService channelService;

    @GetMapping
    public Flux<ProviderCredential> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                         @PathVariable Long channelId) {
        currentUserService.requireAdmin(auth);
        return Flux.fromIterable(service.list(channelId));
    }

    @PostMapping
    public Mono<ProviderCredential> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                            @PathVariable Long channelId,
                                            @RequestBody ProviderCredential request) {
        currentUserService.requireAdmin(auth);
        return Mono.fromCallable(() -> service.create(channelId, request));
    }

    @PutMapping("/{id}")
    public Mono<ProviderCredential> update(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                            @PathVariable Long channelId, @PathVariable Long id,
                                            @RequestBody ProviderCredential request) {
        currentUserService.requireAdmin(auth);
        return Mono.fromCallable(() -> service.update(channelId, id, request));
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                             @PathVariable Long channelId, @PathVariable Long id) {
        currentUserService.requireAdmin(auth);
        return Mono.fromRunnable(() -> service.delete(channelId, id));
    }

    @PostMapping("/{id}/test")
    public Mono<java.util.Map<String, Object>> test(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                     @PathVariable Long channelId, @PathVariable Long id) {
        currentUserService.requireAdmin(auth);
        return Mono.fromCallable(() -> channelService.testCredential(channelId, id));
    }
}
