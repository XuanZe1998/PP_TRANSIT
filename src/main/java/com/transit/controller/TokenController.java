package com.transit.controller;

import com.transit.model.Token;
import com.transit.service.AdminTokenService;
import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final AdminTokenService tokenService;
    private final CurrentUserService currentUserService;

    @GetMapping
    public Flux<Map<String, Object>> getAllTokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(tokenService.list());
    }

    @PostMapping
    public Mono<Map<String, Object>> createToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestBody Token token) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> tokenService.create(token));
    }

    @PutMapping("/{id}")
    public Mono<Map<String, Object>> updateToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @PathVariable Long id,
                                                 @RequestBody Token token) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromCallable(() -> tokenService.update(id, token));
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                  @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> tokenService.delete(id));
    }
}
