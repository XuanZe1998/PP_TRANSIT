package com.transit.controller;

import com.transit.dto.PageResponse;
import com.transit.dto.RechargeOrderRequest;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import com.transit.service.CurrentUserService;
import com.transit.service.IdempotencyService;
import com.transit.service.RechargeOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/platform/user/recharge-orders")
@RequiredArgsConstructor
public class RechargeOrderController {
    private final CurrentUserService currentUserService;
    private final RechargeOrderService rechargeOrderService;
    private final IdempotencyService idempotencyService;

    @PostMapping
    public Mono<Object> create(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                               @RequestBody RechargeOrderRequest request) {
        User user = currentUserService.requireUser(auth);
        return Mono.fromCallable(() -> {
            IdempotencyService.Claim claim = idempotencyService.claim(
                    "USER", user.getId(), "CREATE_RECHARGE_ORDER", idempotencyKey, request, true);
            if (claim.replay()) return claim.response();
            try {
                WalletRechargeOrder order = rechargeOrderService.create(user, request);
                idempotencyService.complete(claim, 200, order, "WALLET_RECHARGE", order.getId());
                return order;
            } catch (RuntimeException exception) {
                idempotencyService.fail(claim, exception);
                throw exception;
            }
        });
    }

    @GetMapping
    public Mono<PageResponse<WalletRechargeOrder>> list(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
            @RequestParam(defaultValue = "true") boolean listPage,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        User user = currentUserService.requireUser(auth);
        return Mono.fromCallable(() -> rechargeOrderService.listPage(user, page, size));
    }

    @GetMapping("/{id}")
    public Mono<WalletRechargeOrder> get(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                         @PathVariable Long id) {
        return Mono.fromCallable(() -> rechargeOrderService.get(currentUserService.requireUser(auth), id));
    }

    @GetMapping("/{id}/invoice")
    public Mono<ResponseEntity<byte[]>> invoice(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                @PathVariable Long id) {
        User user = currentUserService.requireUser(auth);
        return pdf(() -> rechargeOrderService.invoice(user, id), "invoice-" + id + ".pdf");
    }

    @GetMapping("/{id}/receipt")
    public Mono<ResponseEntity<byte[]>> receipt(@RequestHeader(HttpHeaders.AUTHORIZATION) String auth,
                                                @PathVariable Long id) {
        User user = currentUserService.requireUser(auth);
        return pdf(() -> rechargeOrderService.receipt(user, id), "receipt-" + id + ".pdf");
    }

    private Mono<ResponseEntity<byte[]>> pdf(java.util.concurrent.Callable<byte[]> source, String name) {
        return Mono.fromCallable(() -> ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(name).build().toString())
                .body(source.call()));
    }
}
