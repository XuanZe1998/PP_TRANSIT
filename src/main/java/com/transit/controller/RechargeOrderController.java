package com.transit.controller;

import com.transit.dto.RechargeOrderRequest;
import com.transit.model.User;
import com.transit.model.WalletRechargeOrder;
import com.transit.service.CurrentUserService;
import com.transit.service.RechargeOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/platform/user/recharge-orders")
@RequiredArgsConstructor
public class RechargeOrderController {
    private final CurrentUserService currentUserService;
    private final RechargeOrderService rechargeOrderService;
    @PostMapping public Mono<WalletRechargeOrder> create(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody RechargeOrderRequest request){User u=currentUserService.requireUser(auth);return Mono.fromCallable(()->rechargeOrderService.create(u,request));}
    @GetMapping public Flux<WalletRechargeOrder> list(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth){User u=currentUserService.requireUser(auth);return Flux.fromIterable(rechargeOrderService.list(u));}
    @GetMapping("/{id}") public Mono<WalletRechargeOrder> get(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable Long id){User u=currentUserService.requireUser(auth);return Mono.fromCallable(()->rechargeOrderService.get(u,id));}
    @GetMapping("/{id}/invoice") public Mono<ResponseEntity<byte[]>> invoice(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable Long id){User u=currentUserService.requireUser(auth);return pdf(()->rechargeOrderService.invoice(u,id),"invoice-"+id+".pdf");}
    @GetMapping("/{id}/receipt") public Mono<ResponseEntity<byte[]>> receipt(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable Long id){User u=currentUserService.requireUser(auth);return pdf(()->rechargeOrderService.receipt(u,id),"receipt-"+id+".pdf");}
    private Mono<ResponseEntity<byte[]>> pdf(java.util.concurrent.Callable<byte[]> source,String name){return Mono.fromCallable(()->ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CACHE_CONTROL,"private, no-store").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name).build().toString()).body(source.call()));}
}
