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
import com.transit.model.Token;
import com.transit.service.EnterpriseDataMaskingService;

@RestController
@RequestMapping("/organizations")
@RequiredArgsConstructor
public class OrganizationController {
    private final CurrentUserService users;
    private final OrganizationService organizations;
    private final EnterpriseDataMaskingService masking;

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

    @PatchMapping("/{id}/members/{userId}")
    public Mono<Map<String,Object>> updateMember(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                 @PathVariable Long id, @PathVariable Long userId,
                                                 @RequestBody Map<String,Object> request) {
        return Mono.fromCallable(() -> organizations.updateMember(users.requireUser(auth), id, userId, request));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Mono<Map<String,Object>> removeMember(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                 @PathVariable Long id, @PathVariable Long userId) {
        return Mono.fromCallable(() -> { organizations.removeMember(users.requireUser(auth), id, userId); return Map.of("removed", true); });
    }

    @GetMapping("/{id}/tokens")
    public Flux<Map<String,Object>> tokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) {
        return Flux.fromIterable(organizations.tokens(users.requireUser(auth), id));
    }

    @PostMapping("/{id}/members/{userId}/tokens")
    public Mono<Map<String,Object>> createToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                @PathVariable Long id, @PathVariable Long userId,
                                                @RequestBody Token request) {
        return Mono.fromCallable(() -> organizations.createToken(users.requireUser(auth), id, userId, request));
    }

    @PatchMapping("/{id}/tokens/{tokenId}")
    public Mono<Map<String,Object>> updateToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                @PathVariable Long id, @PathVariable Long tokenId,
                                                @RequestBody Token request) {
        return Mono.fromCallable(() -> organizations.updateToken(users.requireUser(auth), id, tokenId, request));
    }

    @DeleteMapping("/{id}/tokens/{tokenId}")
    public Mono<Map<String,Object>> deleteToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                @PathVariable Long id, @PathVariable Long tokenId) {
        return Mono.fromCallable(() -> { organizations.deleteToken(users.requireUser(auth), id, tokenId); return Map.of("deleted", true); });
    }

    @GetMapping("/{id}/data-security")
    public Mono<Map<String,Object>> dataSecurity(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth, @PathVariable Long id) {
        User user = users.requireUser(auth); return Mono.fromCallable(() -> masking.getPolicy(user.getId(), id));
    }

    @PutMapping("/{id}/data-security")
    public Mono<Map<String,Object>> saveDataSecurity(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                     @PathVariable Long id, @RequestBody Map<String,Object> request) {
        User user = users.requireUser(auth); return Mono.fromCallable(() -> masking.savePolicy(user.getId(), id, request));
    }
}
