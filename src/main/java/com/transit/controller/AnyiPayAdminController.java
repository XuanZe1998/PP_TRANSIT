package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.service.AnyiPayClient;
import com.transit.service.CurrentUserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/payment/anyipay")
public class AnyiPayAdminController {

    private final AnyiPayClient anyiPayClient;
    private final CurrentUserService currentUserService;

    @GetMapping("/merchant")
    public Mono<JsonNode> merchant(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        requireAdmin(authorization);
        return Mono.fromCallable(anyiPayClient::merchantInfo);
    }

    @GetMapping("/orders")
    public Mono<JsonNode> orders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "50") int limit,
                                 @RequestParam(required = false) Integer status) {
        requireAdmin(authorization);
        return Mono.fromCallable(() -> anyiPayClient.merchantOrders(offset, limit, status));
    }

    @PostMapping("/refund")
    public Mono<JsonNode> refund(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                 @RequestBody RefundRequest request) {
        requireAdmin(authorization);
        return Mono.fromCallable(() -> anyiPayClient.refund(request.getTradeNo(), request.getOutTradeNo(),
                request.money, request.outRefundNo));
    }

    @PostMapping("/refund/query")
    public Mono<JsonNode> refundQuery(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                      @RequestBody RefundQueryRequest request) {
        requireAdmin(authorization);
        return Mono.fromCallable(() -> anyiPayClient.queryRefund(request.refundNo, request.outRefundNo));
    }

    @PostMapping("/close")
    public Mono<JsonNode> close(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                @RequestBody OrderReferenceRequest request) {
        requireAdmin(authorization);
        return Mono.fromCallable(() -> anyiPayClient.closePayment(request.tradeNo, request.outTradeNo));
    }

    @PostMapping("/transfer")
    public Mono<JsonNode> transfer(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                   @RequestBody TransferRequest request) {
        requireAdmin(authorization);
        return Mono.fromCallable(() -> anyiPayClient.submitTransfer(request.type, request.account,
                request.name, request.money, request.remark, request.outBizNo, request.bookId));
    }

    @PostMapping("/transfer/query")
    public Mono<JsonNode> transferQuery(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                        @RequestBody TransferQueryRequest request) {
        requireAdmin(authorization);
        return Mono.fromCallable(() -> anyiPayClient.queryTransfer(request.bizNo, request.outBizNo));
    }

    @GetMapping("/transfer/balance")
    public Mono<JsonNode> transferBalance(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        requireAdmin(authorization);
        return Mono.fromCallable(anyiPayClient::transferBalance);
    }

    private void requireAdmin(String authorization) {
        currentUserService.requireAdmin(authorization);
    }

    @Data
    public static class OrderReferenceRequest {
        private String tradeNo;
        private String outTradeNo;
    }

    @Data
    public static class RefundRequest extends OrderReferenceRequest {
        private String money;
        private String outRefundNo;
    }

    @Data
    public static class RefundQueryRequest {
        private String refundNo;
        private String outRefundNo;
    }

    @Data
    public static class TransferRequest {
        private String type;
        private String account;
        private String name;
        private String money;
        private String remark;
        private String outBizNo;
        private String bookId;
    }

    @Data
    public static class TransferQueryRequest {
        private String bizNo;
        private String outBizNo;
    }
}
