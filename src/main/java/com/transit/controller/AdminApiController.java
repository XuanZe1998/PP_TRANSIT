package com.transit.controller;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.OtherService;
import com.transit.model.PlusOrder;
import com.transit.model.PlusProduct;
import com.transit.model.Token;
import com.transit.model.User;
import com.transit.service.AdminAuditQueryService;
import com.transit.service.AdminAuditService;
import com.transit.service.AdminBillingService;
import com.transit.service.AdminChannelService;
import com.transit.service.AdminDashboardService;
import com.transit.service.AdminModelService;
import com.transit.service.NvidiaCatalogService;
import com.transit.service.ModelDiscoveryService;
import com.transit.service.OtherServiceImageStorageService;
import com.transit.service.AdminReportService;
import com.transit.service.AdminSecurityService;
import com.transit.service.AdminTokenService;
import com.transit.service.AdminUserService;
import com.transit.service.CurrentUserService;
import com.transit.service.OtherServiceCatalogService;
import com.transit.service.PlusOrderService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
@RequiredArgsConstructor
public class AdminApiController {

    private final CurrentUserService currentUserService;
    private final AdminAuditService adminAuditService;
    private final AdminDashboardService dashboardService;
    private final AdminUserService userService;
    private final AdminChannelService channelService;
    private final AdminModelService modelService;
    private final NvidiaCatalogService nvidiaCatalogService;
    private final ModelDiscoveryService modelDiscoveryService;
    private final AdminTokenService tokenService;
    private final AdminBillingService billingService;
    private final AdminAuditQueryService auditQueryService;
    private final AdminSecurityService securityService;
    private final AdminReportService reportService;
    private final PlusOrderService plusOrderService;
    private final OtherServiceCatalogService otherServiceCatalogService;
    private final OtherServiceImageStorageService otherServiceImageStorageService;

    @GetMapping("/dashboard")
    public Mono<Map<String, Object>> dashboard(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Mono.fromCallable(dashboardService::overview);
    }

    @GetMapping("/users")
    public Flux<Map<String, Object>> users(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(userService.users());
    }

    @PutMapping("/users/{id}")
    public Mono<User> updateUser(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                 @PathVariable Long id,
                                 @RequestBody Map<String, Object> request,
                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            User updated = userService.updateUser(id, request);
            audit(admin, "UPDATE_USER", "USER", id, null, request, servletRequest);
            return updated;
        });
    }

    @PostMapping("/users/{id}/adjust-balance")
    public Mono<Map<String, Object>> adjustBalance(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                   @PathVariable Long id,
                                                   @RequestBody BalanceAdjustRequest request,
                                                   HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = userService.adjustBalance(id, request.getAmount(), request.getReason(), admin.getId());
            audit(admin, "ADJUST_BALANCE", "USER", id, null, result, servletRequest);
            return result;
        });
    }

    @GetMapping("/user-groups")
    public Flux<Map<String, Object>> userGroups(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(userService.groups());
    }

    @PostMapping("/user-groups")
    public Mono<Map<String, Object>> createUserGroup(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                     @RequestBody Map<String, Object> request,
                                                     HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = userService.createGroup(request);
            audit(admin, "CREATE_USER_GROUP", "USER_GROUP", result.get("name"), null, result, servletRequest);
            return result;
        });
    }

    @GetMapping("/channels")
    public Flux<Channel> channels(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(channelService.list());
    }

    @GetMapping("/channels/providers")
    public Flux<Map<String, Object>> channelProviders(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(modelDiscoveryService.providerCatalog());
    }

    @PostMapping("/channels")
    public Mono<Channel> createChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @RequestBody Channel channel,
                                       HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Channel created = channelService.create(channel);
            audit(admin, "CREATE_CHANNEL", "CHANNEL", created.getId(), null, created, servletRequest);
            return created;
        });
    }

    @PutMapping("/channels/{id}")
    public Mono<Channel> updateChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @PathVariable Long id,
                                       @RequestBody Channel channel,
                                       HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Channel updated = channelService.update(id, channel);
            audit(admin, "UPDATE_CHANNEL", "CHANNEL", id, null, updated, servletRequest);
            return updated;
        });
    }

    @PostMapping("/channels/{id}/test")
    public Mono<Map<String, Object>> testChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @PathVariable Long id,
                                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = channelService.test(id);
            audit(admin, "TEST_CHANNEL", "CHANNEL", id, null, result, servletRequest);
            return result;
        });
    }

    @PostMapping("/channels/{id}/test-model")
    public Mono<Map<String, Object>> testChannelModel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @PathVariable Long id,
                                                      @RequestBody Map<String, Object> request,
                                                      HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = channelService.testModel(id, request);
            audit(admin, "TEST_CHANNEL_MODEL", "CHANNEL", id, request, result, servletRequest);
            return result;
        });
    }

    @PostMapping("/channels/{id}/test-nvidia-catalog")
    public Mono<Map<String, Object>> testNvidiaCatalog(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                       @PathVariable Long id,
                                                       HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = nvidiaCatalogService.verifyAll(id);
            audit(admin, "TEST_NVIDIA_CATALOG", "CHANNEL", id, null,
                    Map.of("total", result.get("total"), "success", result.get("success"), "failed", result.get("failed")),
                    servletRequest);
            return result;
        });
    }

    @PostMapping("/channels/{id}/discover-models")
    public Mono<Map<String, Object>> discoverChannelModels(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = modelDiscoveryService.discover(id);
            audit(admin, "DISCOVER_CHANNEL_MODELS", "CHANNEL", id, null,
                    Map.of("existingCount", result.get("existingCount"),
                            "missingCount", result.get("missingCount")), servletRequest);
            return result;
        });
    }

    @PostMapping("/channels/{id}/sync-models")
    public Mono<Map<String, Object>> syncChannelModels(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        boolean activateNew = request != null && Boolean.TRUE.equals(request.get("activateNew"));
        return Mono.fromCallable(() -> {
            Map<String, Object> result = modelDiscoveryService.synchronize(id, activateNew);
            audit(admin, "SYNC_CHANNEL_MODELS", "CHANNEL", id,
                    Map.of("activateNew", activateNew),
                    Map.of("created", result.get("created"), "existing", result.get("existing")),
                    servletRequest);
            return result;
        });
    }

    @GetMapping("/channels/test-logs")
    public Flux<Map<String, Object>> channelTestLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(channelService.testLogs());
    }

    @DeleteMapping("/channels/{id}")
    public Mono<Void> deleteChannel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                    @PathVariable Long id,
                                    HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            channelService.delete(id);
            audit(admin, "DELETE_CHANNEL", "CHANNEL", id, null, null, servletRequest);
        });
    }

    @GetMapping("/models")
    public Flux<ModelMapping> models(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(modelService.list());
    }

    @PostMapping("/models")
    public Mono<ModelMapping> createModel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                          @RequestBody ModelMapping mapping,
                                          HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ModelMapping created = modelService.create(mapping);
            audit(admin, "CREATE_MODEL_MAPPING", "MODEL_MAPPING", created.getId(), null, created, servletRequest);
            return created;
        });
    }

    @PutMapping("/models/{id}")
    public Mono<ModelMapping> updateModel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                          @PathVariable Long id,
                                          @RequestBody ModelMapping mapping,
                                          HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ModelMapping updated = modelService.update(id, mapping);
            audit(admin, "UPDATE_MODEL_MAPPING", "MODEL_MAPPING", id, null, updated, servletRequest);
            return updated;
        });
    }

    @DeleteMapping("/models/{id}")
    public Mono<Void> deleteModel(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                  @PathVariable Long id,
                                  HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            modelService.delete(id);
            audit(admin, "DELETE_MODEL_MAPPING", "MODEL_MAPPING", id, null, null, servletRequest);
        });
    }

    @GetMapping("/tokens")
    public Flux<Map<String, Object>> tokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(tokenService.list());
    }

    @PostMapping("/tokens")
    public Mono<Map<String, Object>> createToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestBody Token token,
                                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> created = tokenService.create(token);
            audit(admin, "CREATE_TOKEN", "TOKEN", created.get("id"), null, "created", servletRequest);
            return created;
        });
    }

    @PutMapping("/tokens/{id}")
    public Mono<Map<String, Object>> updateToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @PathVariable Long id,
                                                 @RequestBody Token token,
                                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> updated = tokenService.update(id, token);
            audit(admin, "UPDATE_TOKEN", "TOKEN", id, null, "updated", servletRequest);
            return updated;
        });
    }

    @DeleteMapping("/tokens/{id}")
    public Mono<Void> deleteToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                  @PathVariable Long id,
                                  HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            tokenService.delete(id);
            audit(admin, "DELETE_TOKEN", "TOKEN", id, null, null, servletRequest);
        });
    }

    @GetMapping("/audit/request-logs")
    public Flux<Map<String, Object>> requestLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(auditQueryService.requestLogs());
    }

    @GetMapping("/audit/admin-logs")
    public Flux<Map<String, Object>> adminLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(auditQueryService.adminLogs());
    }

    @GetMapping("/finance/summary")
    public Mono<Map<String, Object>> financeSummary(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Mono.fromCallable(billingService::financeSummary);
    }

    @GetMapping("/finance/transactions")
    public Flux<Map<String, Object>> financeTransactions(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(billingService.transactions());
    }

    @GetMapping("/finance/redeem-codes")
    public Flux<Map<String, Object>> redeemCodes(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(billingService.redeemCodes());
    }

    @PostMapping("/finance/redeem-codes")
    public Mono<Map<String, Object>> createRedeemCode(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @RequestBody Map<String, Object> request,
                                                      HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = billingService.createRedeemCode(request);
            Map<String, Object> auditResult = new java.util.LinkedHashMap<>(result);
            auditResult.remove("secret");
            audit(admin, "CREATE_REDEEM_CODE", "REDEEM_CODE", result.get("codePreview"),
                    null, auditResult, servletRequest);
            return result;
        });
    }

    @GetMapping("/plus/products")
    public Flux<PlusProduct> plusProducts(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(plusOrderService.listAllProducts());
    }

    @GetMapping("/other-services")
    public Flux<OtherService> otherServices(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(otherServiceCatalogService.listAllServices());
    }

    @PostMapping(value = "/other-services/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, String>> uploadOtherServiceImage(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, String> result = Map.of("url", otherServiceImageStorageService.store(file));
            audit(admin, "UPLOAD_OTHER_SERVICE_IMAGE", "OTHER_SERVICE_IMAGE", result.get("url"),
                    null, result, servletRequest);
            return result;
        });
    }

    @PostMapping("/other-services")
    public Mono<OtherService> createOtherService(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestBody OtherService request,
                                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            OtherService service = otherServiceCatalogService.create(request);
            audit(admin, "CREATE_OTHER_SERVICE", "OTHER_SERVICE", service.getId(), null, service, servletRequest);
            return service;
        });
    }

    @PutMapping("/other-services/{id}")
    public Mono<OtherService> updateOtherService(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @PathVariable Long id,
                                                 @RequestBody OtherService request,
                                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            OtherService service = otherServiceCatalogService.update(id, request);
            audit(admin, "UPDATE_OTHER_SERVICE", "OTHER_SERVICE", id, null, service, servletRequest);
            return service;
        });
    }

    @DeleteMapping("/other-services/{id}")
    public Mono<Void> deleteOtherService(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                         @PathVariable Long id,
                                         HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            otherServiceCatalogService.delete(id);
            audit(admin, "DELETE_OTHER_SERVICE", "OTHER_SERVICE", id, null, null, servletRequest);
        });
    }

    @PostMapping("/plus/products")
    public Mono<PlusProduct> createPlusProduct(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                               @RequestBody PlusProduct request,
                                               HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            PlusProduct product = plusOrderService.createProduct(request);
            audit(admin, "CREATE_PLUS_PRODUCT", "PLUS_PRODUCT", product.getId(), null, product, servletRequest);
            return product;
        });
    }

    @PutMapping("/plus/products/{id}")
    public Mono<PlusProduct> updatePlusProduct(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                               @PathVariable Long id,
                                               @RequestBody PlusProduct request,
                                               HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            PlusProduct product = plusOrderService.updateProduct(id, request);
            audit(admin, "UPDATE_PLUS_PRODUCT", "PLUS_PRODUCT", id, null, product, servletRequest);
            return product;
        });
    }

    @DeleteMapping("/plus/products/{id}")
    public Mono<Void> deletePlusProduct(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                        @PathVariable Long id,
                                        HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            plusOrderService.deleteProduct(id);
            audit(admin, "DELETE_PLUS_PRODUCT", "PLUS_PRODUCT", id, null, null, servletRequest);
        });
    }

    @GetMapping("/plus/orders")
    public Flux<PlusOrder> plusOrders(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(plusOrderService.listAllOrders());
    }

    @PutMapping("/plus/orders/{id}")
    public Mono<PlusOrder> updatePlusOrder(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                           @PathVariable Long id,
                                           @RequestBody PlusOrderUpdateRequest request,
                                           HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            PlusOrder order = plusOrderService.fulfillOrder(id, request.getStatus(), request.getFulfillmentNote());
            audit(admin, "UPDATE_PLUS_ORDER", "PLUS_ORDER", id, null, order, servletRequest);
            return order;
        });
    }

    @GetMapping("/security/policies")
    public Flux<Map<String, Object>> securityPolicies(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(securityService.policies());
    }

    @PostMapping("/security/policies")
    public Mono<Map<String, Object>> saveSecurityPolicy(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @RequestBody Map<String, Object> request,
                                                        HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = securityService.savePolicy(request);
            audit(admin, "SAVE_SECURITY_POLICY", "SECURITY_POLICY", result.getOrDefault("id", result.get("name")), null, result, servletRequest);
            return result;
        });
    }

    @GetMapping("/settings")
    public Flux<Map<String, Object>> settings(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(reportService.settings());
    }

    @PutMapping("/settings")
    public Mono<Map<String, Object>> saveSetting(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestBody Map<String, Object> request,
                                                 HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = reportService.saveSetting(request);
            audit(admin, "SAVE_SETTING", "SETTING", result.get("key"), null, result, servletRequest);
            return result;
        });
    }

    @GetMapping("/reports")
    public Mono<Map<String, Object>> reports(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Mono.fromCallable(reportService::reports);
    }

    private User requireAdmin(String authHeader) {
        return currentUserService.requireAdmin(authHeader);
    }

    private void audit(User admin, String action, String targetType, Object targetId, Object beforeData, Object afterData, HttpServletRequest servletRequest) {
        adminAuditService.record(admin, action, targetType, targetId, beforeData, afterData, clientIp(servletRequest));
    }

    private String clientIp(HttpServletRequest servletRequest) {
        return servletRequest.getRemoteAddr();
    }

    @Data
    public static class BalanceAdjustRequest {
        private long amount;
        private String reason;
    }

    @Data
    public static class PlusOrderUpdateRequest {
        private String status;
        private String fulfillmentNote;
    }
}
