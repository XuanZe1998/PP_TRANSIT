package com.transit.controller;

import com.transit.dto.OperationsOverview;
import com.transit.dto.ProviderCatalogItem;
import com.transit.service.OperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/ops")
@RequiredArgsConstructor
public class OperationsController {

    private final OperationsService operationsService;

    @GetMapping("/overview")
    public Mono<OperationsOverview> overview() {
        return Mono.fromCallable(operationsService::overview);
    }

    @GetMapping("/catalog")
    public Flux<ProviderCatalogItem> providerCatalog() {
        return Flux.fromIterable(operationsService.providerCatalog());
    }
}
