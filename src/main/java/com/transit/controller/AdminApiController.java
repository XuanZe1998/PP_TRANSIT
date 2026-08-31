package com.transit.controller;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.OtherService;
import com.transit.model.ServiceInventoryItem;
import com.transit.model.ServiceOrder;
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
import com.transit.service.ModelMarketDisplayService;
import com.transit.service.ProviderModelCatalogService;
import com.transit.service.ProviderModelVerificationService;
import com.transit.service.PublicPricingReconciliationService;
import com.transit.service.IdempotencyService;
import com.transit.service.OtherServiceImageStorageService;
import com.transit.service.AdminReportService;
import com.transit.service.AdminSecurityService;
import com.transit.service.AdminTokenService;
import com.transit.service.AdminUserService;
import com.transit.service.CurrentUserService;
import com.transit.service.OtherServiceCatalogService;
import com.transit.service.ServiceOrderService;
import com.transit.service.ServiceCommerceService;
import com.transit.service.UsageAnalyticsService;
import com.transit.service.SensitiveWordService;
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
    private final ModelMarketDisplayService modelMarketDisplayService;
    private final NvidiaCatalogService nvidiaCatalogService;
    private final ModelDiscoveryService modelDiscoveryService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final ProviderModelVerificationService providerModelVerificationService;
    private final PublicPricingReconciliationService publicPricingReconciliationService;
    private final IdempotencyService idempotencyService;
    private final AdminTokenService tokenService;
    private final AdminBillingService billingService;
    private final AdminAuditQueryService auditQueryService;
    private final AdminSecurityService securityService;
    private final AdminReportService reportService;
    private final OtherServiceCatalogService otherServiceCatalogService;
    private final OtherServiceImageStorageService otherServiceImageStorageService;
    private final ServiceCommerceService serviceCommerceService;
    private final UsageAnalyticsService usageAnalyticsService;
    private final SensitiveWordService sensitiveWordService;

    @GetMapping("/dashboard")
    public Mono<Map<String, Object>> dashboard(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Mono.fromCallable(dashboardService::overview);
    }

    @GetMapping("/usage/analytics")
    public Mono<Map<String, Object>> usageAnalytics(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "tokenId", required = false) Long tokenId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "audienceType", required = false) String audienceType,
            @RequestParam(value = "organizationId", required = false) Long organizationId) {
        requireAdmin(authHeader);
        return Mono.fromCallable(() -> usageAnalyticsService.analytics(
                null, userId, from, to, model, tokenId, status, audienceType, organizationId));
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

    @PostMapping("/users/{id}/upgrade-enterprise")
    public Mono<Map<String,Object>> upgradeEnterprise(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                       @PathVariable Long id, @RequestBody Map<String,Object> request,
                                                       HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String,Object> result = userService.upgradeToEnterprise(id, request);
            audit(admin, "UPGRADE_ENTERPRISE", "USER", id, null, result, servletRequest);
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

    @GetMapping("/pricing/reconciliation")
    public Mono<PublicPricingReconciliationService.ReconciliationReport> previewPublicPricing(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Mono.fromCallable(publicPricingReconciliationService::preview);
    }

    @PostMapping("/pricing/reconciliation/apply")
    public Mono<PublicPricingReconciliationService.ReconciliationReport> applyPublicPricing(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            PublicPricingReconciliationService.ReconciliationReport report = publicPricingReconciliationService.apply();
            audit(admin, "RECONCILE_PUBLIC_PRICING", "MODEL_MAPPING", "PUBLIC",
                    null, Map.of("updatedRoutes", report.updatedRouteCount(),
                            "pausedRoutes", report.pausedRouteCount(), "verifiedAt", report.verifiedAt()), servletRequest);
            return report;
        });
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

    @PutMapping("/channels/{id}/model-pricing")
    public Mono<ModelMapping> saveChannelModelPricing(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody ModelMapping mapping,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ModelMapping updated = channelService.saveModelPricing(id, mapping);
            audit(admin, "SAVE_CHANNEL_MODEL_PRICING", "MODEL_MAPPING", updated.getId(), null, updated, servletRequest);
            return updated;
        });
    }

    @DeleteMapping("/channels/{id}/model-pricing/{mappingId}")
    public Mono<Void> deleteChannelModelPricing(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @PathVariable Long mappingId,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            channelService.deleteModelPricing(id, mappingId);
            audit(admin, "DELETE_CHANNEL_MODEL_PRICING", "MODEL_MAPPING", mappingId, null,
                    Map.of("channelId", id), servletRequest);
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

    @GetMapping("/model-market/display-settings")
    public Flux<Map<String, Object>> modelMarketDisplaySettings(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(modelMarketDisplayService.list());
    }

    @PutMapping("/model-market/display-settings")
    public Mono<Map<String, Object>> updateModelMarketDisplaySettings(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = modelMarketDisplayService.update(request);
            audit(admin, "UPDATE_MODEL_MARKET_DISPLAY", "MODEL_MARKET", "PUBLIC",
                    null, Map.of("updated", result.getOrDefault("updated", 0)), servletRequest);
            return result;
        });
    }

    @GetMapping("/model-catalog")
    public Flux<com.transit.model.ProviderModel> providerModelCatalog(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "status", required = false) String status) {
        requireAdmin(authHeader);
        return Flux.fromIterable(providerModelCatalogService.list(source, status));
    }

    @GetMapping("/model-catalog/verifications")
    public Flux<Map<String, Object>> providerModelVerificationHistory(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "modelId", required = false) Long modelId,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        requireAdmin(authHeader);
        return Flux.fromIterable(providerModelCatalogService.verificationHistory(modelId, source, limit));
    }

    @GetMapping("/model-catalog/exclusions")
    public Flux<Map<String, Object>> providerModelExclusions(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "source", required = false) String source) {
        requireAdmin(authHeader);
        return Flux.fromIterable(providerModelCatalogService.exclusions(source));
    }

    @PostMapping("/model-catalog/purge-failed")
    public Mono<Object> purgeFailedProviderModels(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        Map<String, Object> body = request == null ? Map.of() : request;
        String source = String.valueOf(body.getOrDefault("source", "")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!source.isBlank() && !List.of("nvidia", "haoee").contains(source)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "source must be nvidia or haoee");
        }
        IdempotencyService.Claim claim = idempotencyService.claim("ADMIN", admin.getId(),
                "provider-catalog:purge-failed:" + (source.isBlank() ? "all" : source),
                idempotencyKey, body, true);
        if (claim.replay()) return Mono.just(claim.response());
        return Mono.fromCallable(() -> {
            try {
                int removed = providerModelCatalogService.purgeFailed(source.isBlank() ? null : source, admin.getId());
                Map<String, Object> result = Map.of("removed", removed, "source", source.isBlank() ? "all" : source);
                idempotencyService.complete(claim, 200, result, "PROVIDER_MODEL_EXCLUSION", source);
                audit(admin, "PURGE_FAILED_PROVIDER_MODELS", "PROVIDER_CATALOG", source, body, result, servletRequest);
                return (Object) result;
            } catch (RuntimeException error) {
                idempotencyService.fail(claim, error);
                throw error;
            }
        });
    }

    @DeleteMapping("/model-catalog/exclusions/{id}")
    public Mono<Map<String, Object>> restoreProviderModelExclusion(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> restored = providerModelCatalogService.restoreExclusion(id);
            audit(admin, "RESTORE_PROVIDER_MODEL_EXCLUSION", "PROVIDER_MODEL_EXCLUSION", id,
                    restored, Map.of("restored", true), servletRequest);
            return restored;
        });
    }

    @PostMapping("/model-catalog/sync")
    public Mono<Object> synchronizeProviderCatalog(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        String source = String.valueOf(request.getOrDefault("source", "")).trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("nvidia", "haoee").contains(source)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "source must be nvidia or haoee");
        }
        IdempotencyService.Claim claim = idempotencyService.claim("ADMIN", admin.getId(),
                "provider-catalog:sync:" + source, idempotencyKey, request, true);
        if (claim.replay()) return Mono.just(claim.response());
        return Mono.fromCallable(() -> {
            try {
                int total;
                if ("nvidia".equals(source)) {
                    nvidiaCatalogService.syncCatalog();
                    total = providerModelCatalogService.listBySource(source).size();
                } else {
                    total = providerModelCatalogService.synchronizeConfiguredHaoee();
                }
                Map<String, Object> result = Map.of("source", source, "total", total,
                        "statusCounts", providerModelCatalogService.statusCounts(source));
                idempotencyService.complete(claim, 200, result, "PROVIDER_CATALOG", source);
                audit(admin, "SYNC_PROVIDER_CATALOG", "PROVIDER_CATALOG", source, request, result, servletRequest);
                return (Object) result;
            } catch (RuntimeException error) {
                idempotencyService.fail(claim, error);
                throw error;
            }
        });
    }

    @PostMapping("/model-catalog/verify")
    public Mono<Object> verifyProviderCatalog(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        IdempotencyService.Claim claim = idempotencyService.claim("ADMIN", admin.getId(),
                "provider-catalog:verify", idempotencyKey, request, true);
        if (claim.replay()) return Mono.just(claim.response());
        return Mono.fromCallable(() -> {
            try {
                boolean allowPaid = Boolean.TRUE.equals(request.get("allowPaid"));
                List<Long> ids;
                if (request.get("id") instanceof Number id) {
                    ids = providerModelVerificationService.queueOne(id.longValue(), allowPaid);
                } else {
                    String source = String.valueOf(request.getOrDefault("source", "")).trim().toLowerCase(java.util.Locale.ROOT);
                    int limit = request.get("limit") instanceof Number number ? number.intValue() : 20;
                    ids = providerModelVerificationService.queue(source, limit, allowPaid);
                }
                providerModelVerificationService.verifyQueuedAsync(ids, allowPaid);
                Map<String, Object> result = Map.of("status", "QUEUED", "modelIds", ids, "count", ids.size());
                idempotencyService.complete(claim, 202, result, "MODEL_VERIFICATION_BATCH", ids.hashCode());
                audit(admin, "VERIFY_PROVIDER_CATALOG", "PROVIDER_CATALOG", ids.hashCode(), request, result, servletRequest);
                return (Object) result;
            } catch (RuntimeException error) {
                idempotencyService.fail(claim, error);
                throw error;
            }
        });
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
    public Mono<Map<String, Object>> requestLogs(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "audienceType", required = false) String audienceType,
            @RequestParam(value = "organizationId", required = false) Long organizationId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "query", required = false) String query,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "50") int pageSize) {
        requireAdmin(authHeader);
        return Mono.fromCallable(() -> auditQueryService.requestLogs(
                audienceType, organizationId, userId, model, from, to, query, page, pageSize));
    }

    @GetMapping("/audit/filter-options")
    public Mono<Map<String, Object>> auditFilterOptions(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "audienceType", required = false) String audienceType,
            @RequestParam(value = "organizationId", required = false) Long organizationId,
            @RequestParam(value = "query", required = false) String query) {
        requireAdmin(authHeader);
        return Mono.fromCallable(() -> auditQueryService.filterOptions(audienceType, organizationId, query));
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

    @GetMapping("/finance/transactions/page")
    public Mono<com.transit.dto.PageResponse<Map<String, Object>>> financeTransactionsPage(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "query", required = false) String query) {
        requireAdmin(authHeader);
        return Mono.fromCallable(() -> billingService.transactionsPage(page, size, query));
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

    @GetMapping("/finance/recharge-plans")
    public Flux<Map<String, Object>> rechargePlans(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(billingService.rechargePlans());
    }

    @PostMapping("/finance/recharge-plans")
    public Mono<Map<String, Object>> createRechargePlan(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @RequestBody Map<String, Object> request,
                                                        HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = billingService.createRechargePlan(request);
            audit(admin, "CREATE_RECHARGE_PLAN", "RECHARGE_PLAN", result.get("id"), null, result, servletRequest);
            return result;
        });
    }

    @PutMapping("/finance/recharge-plans/{id}")
    public Mono<Map<String, Object>> updateRechargePlan(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @PathVariable Long id,
                                                        @RequestBody Map<String, Object> request,
                                                        HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = billingService.updateRechargePlan(id, request);
            audit(admin, "UPDATE_RECHARGE_PLAN", "RECHARGE_PLAN", id, null, result, servletRequest);
            return result;
        });
    }

    @DeleteMapping("/finance/recharge-plans/{id}")
    public Mono<Void> deleteRechargePlan(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                         @PathVariable Long id,
                                         HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            billingService.deleteRechargePlan(id);
            audit(admin, "DELETE_RECHARGE_PLAN", "RECHARGE_PLAN", id, null, null, servletRequest);
        });
    }

    @GetMapping("/other-services")
    public Flux<OtherService> otherServices(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(otherServiceCatalogService.listAllServices());
    }

    @GetMapping("/other-services/{id}")
    public Mono<Map<String,Object>> otherServiceDetail(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                        @PathVariable Long id) {
        requireAdmin(authHeader); return Mono.fromCallable(() -> otherServiceCatalogService.adminDetail(id));
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

    @PostMapping("/other-services/{id}/inventory/import")
    public Mono<Map<String, Integer>> importOtherServiceInventory(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestBody InventoryImportRequest request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            int imported = serviceCommerceService.importInventory(id, request == null ? null : request.getContent());
            Map<String, Integer> result = Map.of("imported", imported);
            audit(admin, "IMPORT_SERVICE_INVENTORY", "OTHER_SERVICE", id, null, result, servletRequest);
            return result;
        });
    }

    @GetMapping("/other-services/{id}/inventory")
    public Flux<ServiceInventoryItem> otherServiceInventory(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @RequestParam(required = false) String status) {
        requireAdmin(authHeader);
        return Flux.fromIterable(serviceCommerceService.listInventory(id, status));
    }

    @GetMapping("/other-services/{id}/inventory/stats")
    public Mono<Map<String, Long>> otherServiceInventoryStats(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id) {
        requireAdmin(authHeader);
        return Mono.fromCallable(() -> serviceCommerceService.inventoryStats(id));
    }

    @DeleteMapping("/other-services/{id}/inventory/{inventoryId}")
    public Mono<Void> deleteOtherServiceInventory(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable Long id,
            @PathVariable Long inventoryId,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            serviceCommerceService.deleteAvailableInventory(id, inventoryId);
            audit(admin, "DELETE_SERVICE_INVENTORY", "SERVICE_INVENTORY", inventoryId,
                    null, Map.of("serviceId", id), servletRequest);
        });
    }

    @PutMapping("/other-services/{id}/inventory/{inventoryId}")
    public Mono<ServiceInventoryItem> replaceOtherServiceInventory(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader, @PathVariable Long id,
            @PathVariable Long inventoryId, @RequestBody InventoryImportRequest request,
            HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            ServiceInventoryItem item = serviceCommerceService.replaceAvailableInventory(id, inventoryId, request == null ? null : request.getContent());
            audit(admin, "REPLACE_SERVICE_INVENTORY", "SERVICE_INVENTORY", inventoryId, null, Map.of("serviceId", id), servletRequest);
            return item;
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

    @GetMapping("/security/sensitive-words")
    public Flux<Map<String, Object>> sensitiveWords(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        requireAdmin(authHeader);
        return Flux.fromIterable(sensitiveWordService.list());
    }

    @PostMapping("/security/sensitive-words")
    public Mono<Map<String, Object>> saveSensitiveWord(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestBody Map<String, Object> request, HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = sensitiveWordService.save(request);
            audit(admin, "SAVE_SENSITIVE_WORD", "SENSITIVE_WORD", result.get("id"), null, result, servletRequest);
            return result;
        });
    }

    @PostMapping("/security/sensitive-words/bulk")
    public Mono<Map<String, Object>> bulkSensitiveWords(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestBody Map<String, Object> request, HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromCallable(() -> {
            Map<String, Object> result = sensitiveWordService.bulk(request);
            audit(admin, "BULK_IMPORT_SENSITIVE_WORDS", "SENSITIVE_WORD", result.get("created"), null, result, servletRequest);
            return result;
        });
    }

    @PostMapping("/security/sensitive-words/test")
    public Flux<Map<String, Object>> testSensitiveWords(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestBody Map<String, Object> request) {
        requireAdmin(authHeader);
        return Flux.fromIterable(sensitiveWordService.preview(request));
    }

    @DeleteMapping("/security/sensitive-words/{id}")
    public Mono<Void> deleteSensitiveWord(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                          @PathVariable Long id, HttpServletRequest servletRequest) {
        User admin = requireAdmin(authHeader);
        return Mono.fromRunnable(() -> {
            sensitiveWordService.delete(id);
            audit(admin, "DELETE_SENSITIVE_WORD", "SENSITIVE_WORD", id, null, null, servletRequest);
        });
    }

    @GetMapping("/security/events")
    public Flux<Map<String, Object>> securityEvents(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        requireAdmin(authHeader);
        return Flux.fromIterable(sensitiveWordService.events(limit));
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
    public static class ServiceOrderUpdateRequest {
        private String status;
        private String fulfillmentNote;
    }

    @Data
    public static class InventoryImportRequest {
        private String content;
    }
}
