package com.transit.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.AdminAuditService;
import com.transit.service.IdempotencyService;
import com.transit.service.SubscriptionServiceClient;
import com.transit.service.SubscriptionServiceOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SubscriptionServiceController {
    private final SubscriptionServiceClient client;
    private final CurrentUserService currentUserService;
    private final IdempotencyService idempotencyService;
    private final AdminAuditService auditService;

    @GetMapping("/public/subscription-services/catalog")
    public Map<String, Object> catalog(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size,
                                       @RequestParam(defaultValue = "") String query,
                                       @RequestParam(defaultValue = "") String source) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("page_no", page);
        parameters.put("page_size", size);
        if (!query.isBlank()) parameters.put("keywords", query.strip());
        if (!source.isBlank()) parameters.put("source", source.strip());
        JsonNode data = client.invoke(SubscriptionServiceOperation.GOODS_LIST, parameters);
        return client.page(data, parameters);
    }

    @GetMapping("/public/subscription-services/catalog/{goodsNo}")
    public JsonNode detail(@PathVariable String goodsNo) {
        if (goodsNo == null || goodsNo.isBlank() || goodsNo.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品编号无效");
        }
        return client.invoke(SubscriptionServiceOperation.GOODS_DETAIL, Map.of("goods_no", goodsNo.strip()));
    }

    @PostMapping("/subscription-services/quote")
    public JsonNode quote(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                          @RequestBody Map<String, Object> request) {
        currentUserService.requireUser(authorization);
        return client.invoke(SubscriptionServiceOperation.PURCHASE_QUOTE, request);
    }

    @GetMapping("/admin/api/subscription-services/configuration")
    public Map<String, Object> configuration(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        currentUserService.requireAdmin(authorization);
        return client.configuration();
    }

    @GetMapping("/admin/api/subscription-services/operations")
    public List<Map<String, Object>> operations(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        currentUserService.requireAdmin(authorization);
        return Arrays.stream(SubscriptionServiceOperation.values()).map(operation -> Map.<String, Object>of(
                "key", operation.key(), "path", operation.path(), "group", operation.group(),
                "label", operation.label(), "paged", operation.paged(), "mutation", operation.mutation()
        )).toList();
    }

    @PostMapping("/admin/api/subscription-services/operations/{operationKey}")
    public Object invoke(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                         @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                         @PathVariable String operationKey,
                         @RequestBody(required = false) Map<String, Object> request) {
        User admin = currentUserService.requireAdmin(authorization);
        SubscriptionServiceOperation operation = SubscriptionServiceOperation.find(operationKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订阅服务操作不存在"));
        Map<String, Object> safeRequest = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        IdempotencyService.Claim claim = idempotencyService.claim("ADMIN", admin.getId(),
                "SUBSCRIPTION_SERVICE:" + operation.key(), idempotencyKey, safeRequest, operation.mutation());
        if (claim.replay()) return claim.response();
        try {
            JsonNode data = client.invoke(operation, safeRequest);
            Object result = operation.paged() ? client.page(data, safeRequest) : data;
            idempotencyService.complete(claim, 200, result, "SUBSCRIPTION_SERVICE", operation.key());
            if (operation.mutation()) {
                auditService.record(admin, "EXECUTE_SUBSCRIPTION_SERVICE_OPERATION", "SUBSCRIPTION_SERVICE",
                        operation.key(), null, Map.of("operation", operation.key(), "completed", true), null);
            }
            return result;
        } catch (RuntimeException exception) {
            idempotencyService.fail(claim, exception);
            throw exception;
        }
    }
}
