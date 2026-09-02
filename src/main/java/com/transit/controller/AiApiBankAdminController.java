package com.transit.controller;

import com.transit.service.AiApiBankCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
@RequestMapping("/admin/api/aiapibank")
@RequiredArgsConstructor
public class AiApiBankAdminController {
    private final AiApiBankCatalogService catalog;

    @GetMapping("/status")
    public Mono<Map<String, Object>> status() {
        return Mono.fromCallable(catalog::status).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/sync")
    public Mono<AiApiBankCatalogService.SyncResult> sync(@RequestBody(required = false) Map<String, Object> body) {
        boolean dryRun = body != null && Boolean.TRUE.equals(body.get("dryRun"));
        return Mono.fromCallable(() -> catalog.sync(dryRun)).subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(IllegalStateException.class, error -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, error.getMessage(), error));
    }
}

