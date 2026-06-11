package com.transit.controller;

import com.transit.mapper.TokenMapper;
import com.transit.model.Token;
import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/tokens")
@RequiredArgsConstructor
public class TokenController {

    private final TokenMapper tokenMapper;
    private final CurrentUserService currentUserService;

    @GetMapping
    public Flux<Token> getAllTokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        currentUserService.requireAdmin(authHeader);
        return Flux.fromIterable(tokenMapper.selectList(null));
    }

    @PostMapping
    public Mono<Token> createToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                   @RequestBody Token token) {
        currentUserService.requireAdmin(authHeader);
        if (token.getKey() == null || token.getKey().isEmpty()) {
            token.setKey("sk-" + UUID.randomUUID().toString().replace("-", ""));
        }
        return Mono.fromCallable(() -> {
            tokenMapper.insert(token);
            return token;
        });
    }

    @PutMapping("/{id}")
    public Mono<Token> updateToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                   @PathVariable Long id,
                                   @RequestBody Token token) {
        currentUserService.requireAdmin(authHeader);
        token.setId(id);
        return Mono.fromCallable(() -> {
            tokenMapper.updateById(token);
            return token;
        });
    }

    @DeleteMapping("/{id}")
    public Mono<Void> deleteToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                  @PathVariable Long id) {
        currentUserService.requireAdmin(authHeader);
        return Mono.fromRunnable(() -> tokenMapper.deleteById(id));
    }
}
