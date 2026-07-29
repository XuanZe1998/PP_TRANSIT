package com.transit.controller;

import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.ShopGptItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/shopgpt/item-68")
@RequiredArgsConstructor
public class ShopGptItemController {

    private final CurrentUserService currentUserService;
    private final ShopGptItemService shopGptItemService;

    @GetMapping("/session")
    public Mono<Map<String, Object>> prepare(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> shopGptItemService.prepare(user));
    }

    @GetMapping("/captcha")
    public Mono<ResponseEntity<ShopGptItemService.CaptchaImage>> captcha(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .body(shopGptItemService.captcha(user)));
    }

    @PostMapping("/sync")
    public Mono<Map<String, Object>> sync(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                          @RequestBody ShopGptDraftRequest request) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> shopGptItemService.sync(user, request.quantity(), request.captcha(), request.payId()));
    }

    @PostMapping("/trade")
    public Mono<Map<String, Object>> trade(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader,
                                           @RequestBody ShopGptDraftRequest request) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> shopGptItemService.trade(user, request.quantity(), request.captcha(), request.payId()));
    }

    public record ShopGptDraftRequest(int quantity, String captcha, Integer payId) {
    }
}
