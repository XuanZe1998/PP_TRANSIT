package com.transit.controller;

import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.OrganizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {
    private final CurrentUserService users;
    private final OrganizationService organizations;

    @GetMapping
    public Flux<Map<String, Object>> list(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth) {
        return Flux.fromIterable(organizations.list(users.requireUser(auth)));
    }

    @PostMapping
    public Mono<Map<String, Object>> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                             @RequestBody Map<String, Object> request) {
        User user = users.requireUser(auth);
        return Mono.fromCallable(() -> organizations.create(user, String.valueOf(request.getOrDefault("name", ""))));
    }

    @GetMapping("/{id}/members")
    public Flux<Map<String, Object>> members(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                             @PathVariable Long id) {
        return Flux.fromIterable(organizations.members(users.requireUser(auth), id));
    }

    @PostMapping("/{id}/invitations")
    public Mono<Object> invite(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                               @RequestHeader(value="Idempotency-Key", required=false) String key,
                               @PathVariable Long id, @RequestBody Map<String, Object> request) {
        User user = users.requireUser(auth);
        return Mono.fromCallable(() -> organizations.invite(user, id, request, key));
    }

    @PostMapping("/invitations/{token}/accept")
    public Mono<Map<String, Object>> accept(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                             @PathVariable String token) {
        return Mono.fromCallable(() -> organizations.accept(users.requireUser(auth), token));
    }

    @PostMapping("/{id}/allocations")
    public Mono<Object> allocate(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                 @RequestHeader(value="Idempotency-Key", required=false) String key,
                                 @PathVariable Long id, @RequestBody Map<String, Object> request) {
        User user = users.requireUser(auth);
        return Mono.fromCallable(() -> organizations.allocate(user, id, request, key, false));
    }

    @PostMapping("/{id}/allocations/reclaim")
    public Mono<Object> reclaim(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                @RequestHeader(value="Idempotency-Key", required=false) String key,
                                @PathVariable Long id, @RequestBody Map<String, Object> request) {
        User user = users.requireUser(auth);
        return Mono.fromCallable(() -> organizations.allocate(user, id, request, key, true));
    }

    @GetMapping("/{id}/usage")
    public Flux<Map<String, Object>> usage(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                           @PathVariable Long id,
                                           @RequestParam(required=false) String start,
                                           @RequestParam(required=false) String end) {
        return Flux.fromIterable(organizations.usage(users.requireUser(auth), id, start, end));
    }
}
