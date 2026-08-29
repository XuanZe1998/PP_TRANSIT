package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.dto.PublicUpstream;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import com.transit.model.Token;
import com.transit.model.Log;
import com.transit.model.User;
import com.transit.provider.ProviderGateway;
import com.transit.provider.ProviderGatewayFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.codec.ServerSentEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransitService {

    private final ModelMappingMapper modelMappingMapper;
    private final ChannelMapper channelMapper;
    private final LogMapper logMapper;
    private final UserMapper userMapper;
    private final ProviderGatewayFactory providerGatewayFactory;
    private final GatewaySettlementService settlementService;
    private final ChannelUrlPolicy channelUrlPolicy;
    private final GatewayRateLimiter rateLimiter;
    private final ApiKeyService apiKeyService;
    private final ChannelSecretService channelSecretService;
    private final ChannelRoutePlanner routePlanner;
    private final ChannelHealthService channelHealthService;
    private final OpenAiStreamAdapter streamAdapter;
    private final JdbcTemplate jdbcTemplate;
    private final ModelPriceTierService priceTierService;
    @Autowired(required = false)
    private ModelContextPricingService modelContextPricingService;
    @Autowired(required = false)
    private MoneyService moneyService;
    @Autowired(required = false)
    private ProviderCredentialService providerCredentialService;
    @Autowired(required = false)
    private IdempotencyService idempotencyService;
    @Autowired(required = false)
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private SensitiveWordService sensitiveWordService;
    @Autowired(required = false)
    private PublicUpstreamMappingService publicUpstreamMappingService;
    @Autowired(required = false)
    private EnterpriseDataMaskingService enterpriseDataMaskingService;
    @Autowired(required = false)
    private ProviderModelCatalogService providerModelCatalogService;

    @Value("${billing.amount-scale:10000}")
    private long amountScale;

    @Value("${billing.default-max-output-tokens:4096}")
    private int defaultMaxOutputTokens;

    @Value("${gateway.max-request-content-bytes:2097152}")
    private int maxRequestContentBytes;

    public Mono<ChatResponse> chatCompletions(String authorization, ChatRequest request) {
        return chatCompletions(authorization, request, null);
    }

    public List<String> availableModels(String authorization, String clientIp) {
        String tokenKey = extractToken(authorization);
        Token token = validateToken(tokenKey, "*", clientIp, false);
        return modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::isEnabled, true)
                        .eq(ModelMapping::isBillingEnabled, true)
                        .orderByAsc(ModelMapping::getPublicModelName))
                .stream()
                .filter(PublicPricingPolicy::hasRequiredSale)
                .filter(mapping -> apiKeyService.modelAllowed(token, mapping.getPublicModelName()))
                .filter(mapping -> isVerifiedRoute(mapping, channelMapper.selectById(mapping.getChannelId())))
                .map(ModelMapping::getPublicModelName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public List<Map<String, Object>> availableModelCatalog(String authorization, String clientIp) {
        String tokenKey = extractToken(authorization);
        Token token = validateToken(tokenKey, "*", clientIp, false);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        List<ModelMapping> mappings = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::isEnabled, true)
                        .eq(ModelMapping::isBillingEnabled, true)
                        .orderByAsc(ModelMapping::getPublicModelName)
                        .orderByDesc(ModelMapping::getPriority));
        Map<Long, PublicUpstream> aliases = publicUpstreamMappingService == null ? Map.of()
                : publicUpstreamMappingService.forChannels(mappings.stream().map(ModelMapping::getChannelId).toList());
        mappings.stream()
                .filter(PublicPricingPolicy::hasRequiredSale)
                .filter(mapping -> apiKeyService.modelAllowed(token, mapping.getPublicModelName()))
                .forEach(mapping -> {
                    Channel channel = channelMapper.selectById(mapping.getChannelId());
                    if (!isVerifiedRoute(mapping, channel) || result.containsKey(mapping.getPublicModelName())) return;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", mapping.getPublicModelName());
                    item.put("object", "model");
                    item.put("created", Instant.now().getEpochSecond());
                    item.put("owned_by", Objects.toString(mapping.getVendor(), "third-party"));
                    // OpenAI-compatible catalog is user-visible. Never expose the
                    // internal provider/channel identity from this compatibility API.
                    PublicUpstream alias = aliases.get(mapping.getChannelId());
                    item.put("source", alias == null ? PublicUpstreamMappingService.FALLBACK_CODE : alias.getCode());
                    item.put("sourceName", alias == null ? PublicUpstreamMappingService.FALLBACK_NAME : alias.getName());
                    item.put("vendor", Objects.toString(mapping.getVendor(), "Other"));
                    item.put("capability", Objects.toString(mapping.getCapability(), "TEXT"));
                    item.put("inputModalities", csv(mapping.getInputModalities()));
                    item.put("outputModalities", csv(mapping.getOutputModalities()));
                    item.put("protocols", csv(mapping.getProtocols()));
                    item.put("pricingUnit", Objects.toString(mapping.getPricingUnit(), "TOKEN"));
                    item.put("available", true);
                    result.put(mapping.getPublicModelName(), item);
                });
        return List.copyOf(result.values());
    }

    private List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isBlank()).toList();
    }

    public Mono<ChatResponse> chatCompletions(String authorization, ChatRequest request, String clientIp) {
        validateRequest(request);
        String rawToken = extractToken(authorization);
        Token callerToken = validateToken(rawToken, request.getModel(), clientIp);
        return executeChat(callerToken, request, clientIp);
    }

    public Mono<ChatResponse> chatCompletions(String authorization, ChatRequest request, String clientIp,
                                               String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyService == null || objectMapper == null) {
            return chatCompletions(authorization, request, clientIp);
        }
        validateRequest(request);
        String rawToken = extractToken(authorization);
        Token callerToken = validateToken(rawToken, request.getModel(), clientIp);
        IdempotencyService.Claim claim = idempotencyService.claim("API_KEY", callerToken.getId(),
                "model.invoke:chat-completions", idempotencyKey, request, false);
        if (claim.replay()) {
            try { return Mono.just(objectMapper.treeToValue(claim.response(), ChatResponse.class)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                return Mono.error(new IllegalStateException("Stored idempotent response is invalid", error));
            }
        }
        return executeChat(callerToken, request, clientIp)
                .doOnNext(response -> idempotencyService.complete(claim, 200, response, "MODEL_RESPONSE",
                        response.getBilling() == null ? null : response.getBilling().getTraceId()))
                .doOnError(error -> {
                    if (isAmbiguousTimeout(error)) idempotencyService.unknown(claim, error, "MODEL_RESPONSE", null);
                    else idempotencyService.fail(claim, error);
                });
    }

    public Flux<ServerSentEvent<String>> chatCompletionsStream(String authorization, ChatRequest request,
                                                               String clientIp) {
        request.setStream(false);
        return chatCompletions(authorization, request, clientIp).flatMapMany(streamAdapter::encode);
    }

    public Mono<ChatResponse> chatCompletions(Token callerToken, ChatRequest request, String clientIp) {
        validateRequest(request);
        validateToken(callerToken, request.getModel(), clientIp, true);
        return executeChat(callerToken, request, clientIp);
    }

    private Mono<ChatResponse> executeChat(Token callerToken, ChatRequest request, String clientIp) {
        long startedAt = System.currentTimeMillis();
        String traceId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String tokenKey = apiKeyService.keyPreview(callerToken);
        rateLimiter.checkToken(callerToken);
        if (callerToken.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "API Key has no billable owner");
        }
        User caller = userMapper.selectById(callerToken.getUserId());
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API Key owner no longer exists");
        }
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(caller.getStatus(), "ACTIVE"))) {
            updateQuotaAndLog(callerToken, null, tokenKey, request.getModel(), 0, 0, 0, 0, "FAILED", traceId, startedAt, "USER_SUSPENDED", BillingBreakdown.zero());
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is suspended"));
        }
        String publicModel = request.getModel();
        if (sensitiveWordService != null && objectMapper != null) {
            sensitiveWordService.scanJson(traceId, callerToken.getOrganizationId(), caller.getId(),
                    callerToken.getId(), publicModel, objectMapper.valueToTree(request));
        }
        Long organizationId = callerToken.getOrganizationId() == null ? caller.getDefaultOrganizationId() : callerToken.getOrganizationId();
        EnterpriseDataMaskingService.MaskingContext masking = enterpriseDataMaskingService == null
                ? null : enterpriseDataMaskingService.mask(request, organizationId, caller.getId(), callerToken.getId(), traceId);
        
        List<ModelMapping> mappings = modelMappingMapper.selectList(
            new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getPublicModelName, publicModel)
                .eq(ModelMapping::isEnabled, true)
                .eq(ModelMapping::isBillingEnabled, true)
                .orderByDesc(ModelMapping::getPriority)
        );

        mappings = mappings.stream().filter(PublicPricingPolicy::hasRequiredSale).toList();

        if (mappings.isEmpty()) {
            updateQuotaAndLog(callerToken, null, tokenKey, publicModel, 0, 0, 0, 0, "FAILED", traceId, startedAt, "MODEL_NOT_MAPPED", BillingBreakdown.zero());
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No available channel for model: " + publicModel));
        }
        priceTierService.attach(mappings);

        List<Route> routes = routePlanner.plan(mappings.stream()
                        .map(candidate -> new ChannelRoutePlanner.Candidate(candidate,
                                channelSecretService.reveal(channelMapper.selectById(candidate.getChannelId()))))
                        .filter(candidate -> isRoutable(candidate.channel()))
                        .toList()).stream()
                .map(candidate -> new Route(candidate.mapping(), candidate.channel()))
                .toList();
        
        if (routes.isEmpty()) {
            updateQuotaAndLog(callerToken, null, tokenKey, publicModel, 0, 0, 0, 0, "FAILED", traceId, startedAt, "NO_HEALTHY_CHANNEL", BillingBreakdown.zero());
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No healthy channel for model: " + publicModel));
        }

        BigDecimal customerPriceRatio = userPriceRatio(caller.getGroupId());
        BigDecimal exchangeRate = money().usdCnyRate();
        int estimatedPromptTokens = estimateRequestTokens(request);
        int estimatedCompletionTokens = request.getMaxTokens() == null
                ? Math.max(1, defaultMaxOutputTokens) : request.getMaxTokens();
        int reservedTokens;
        try {
            reservedTokens = Math.addExact(estimatedPromptTokens, estimatedCompletionTokens);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requested token budget is too large");
        }
        long reservedSourceAmount = routes.stream()
                .mapToLong(route -> calculateBilling(route.mapping(), estimatedPromptTokens,
                        estimatedCompletionTokens, 0, 0, customerPriceRatio, exchangeRate).totalAmount())
                .max()
                .orElse(0L);
        long reservedAmount = money().usdToCnyAmount(reservedSourceAmount, exchangeRate);

        GatewaySettlementService.Reservation reservation;
        try {
            reservation = settlementService.reserve(callerToken, caller, reservedTokens, reservedAmount,
                    traceId, publicModel);
            try {
                settlementService.captureSourceSnapshot(traceId, reservedSourceAmount, amountScale, exchangeRate);
            } catch (RuntimeException snapshotFailure) {
                try {
                    settlementService.release(reservation, "pricing snapshot failed");
                } catch (RuntimeException releaseFailure) {
                    snapshotFailure.addSuppressed(releaseFailure);
                }
                throw snapshotFailure;
            }
        } catch (RuntimeException reservationFailure) {
            updateQuotaAndLog(callerToken, null, tokenKey, publicModel, 0, 0, 0, 0,
                    "FAILED", traceId, startedAt, safeMessage(reservationFailure), BillingBreakdown.zero());
            throw reservationFailure;
        }

        Channel firstChannel = routes.get(0).channel();
        return attemptRoute(routes, 0, request, publicModel, traceId)
                // WebClient emits on a Netty event-loop. All JDBC settlement and
                // audit work must run on a worker that permits blocking I/O.
                .publishOn(Schedulers.boundedElastic())
                .flatMap(routed -> {
                    ChatResponse resp = routed.response();
                    if (enterpriseDataMaskingService != null) enterpriseDataMaskingService.restore(resp, masking);
                    ModelMapping routeMapping = routed.route().mapping();
                    Channel routeChannel = routed.route().channel();
                    resp.setModel(publicModel);
                    normalizeAssistantAnswer(resp);
                    boolean estimatedUsage = normalizeUsage(resp, estimatedPromptTokens);
                    int prompt = resp.getUsage().getPromptTokens();
                    int completion = resp.getUsage().getCompletionTokens();
                    int total = resp.getUsage().getTotalTokens();
                    int cacheRead = Math.min(prompt, resp.getUsage().cacheReadTokens());
                    int cacheWrite = Math.min(Math.max(0, prompt - cacheRead), resp.getUsage().cacheWriteTokens());
                    int cached = Math.addExact(cacheRead, cacheWrite);
                    BillingBreakdown billing = calculateBilling(routeMapping, prompt, completion,
                            cacheRead, cacheWrite, customerPriceRatio, exchangeRate);
                    resp.setBilling(billingDetails(routeMapping, prompt, cacheRead, cacheWrite, customerPriceRatio,
                            billing, traceId));
                    settlementService.settle(reservation, total, billing.settlementAmount(),
                            "API usage " + publicModel + " (" + traceId + ")");
                    updateQuotaAndLog(callerToken, routeChannel, tokenKey, publicModel, prompt,
                            completion, cacheRead, cacheWrite, total, estimatedUsage ? "SUCCESS_ESTIMATED" : "SUCCESS",
                            traceId, startedAt, null, billing);
                    return Mono.just(resp);
                })
                .onErrorResume(e -> {
                    if (isAmbiguousTimeout(e)) {
                        settlementService.markUnknown(reservation, safeMessage(e));
                        updateQuotaAndLog(callerToken, firstChannel, tokenKey, publicModel, 0, 0,
                                0, 0, "UNKNOWN", traceId, startedAt, safeMessage(e), BillingBreakdown.zero());
                        return Mono.error(e);
                    }
                    try {
                        settlementService.release(reservation, safeMessage(e));
                    } catch (RuntimeException releaseFailure) {
                        log.error("Unable to release request reservation {}", traceId, releaseFailure);
                    }
                    updateQuotaAndLog(callerToken, firstChannel, tokenKey, publicModel, 0, 0,
                            0, 0, "FAILED", traceId, startedAt, safeMessage(e), BillingBreakdown.zero());
                    return Mono.error(e);
                });
    }

    private Mono<RoutedResponse> attemptRoute(List<Route> routes, int index, ChatRequest request,
                                              String publicModel, String traceId) {
        if (index >= routes.size()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "All upstream channels failed for model: " + publicModel));
        }
        Route route = routes.get(index);
        return Mono.defer(() -> {
                    long routeStartedAt = System.currentTimeMillis();
                    channelUrlPolicy.validate(route.channel().getBaseUrl());
                    rateLimiter.checkChannel(route.channel());
                    ProviderCredentialService.SelectedCredential credential = null;
                    if (providerCredentialService != null) {
                        credential = providerCredentialService.select(route.channel());
                        route.channel().setApiKey(credential.secret());
                        route.channel().setSelectedCredentialId(credential.id());
                    }
                    ProviderCredentialService.SelectedCredential selectedCredential = credential;
                    ProviderGateway gateway = providerGatewayFactory.resolve(route.channel().getType());
                    request.setModel(publicModel);
                    log.info("Routing request {} for {} to channel {} (provider: {}, model: {})",
                            traceId, publicModel, route.channel().getName(), route.channel().getType(),
                            route.mapping().getChannelModelName());
                    return gateway.chatCompletions(route.channel(), request, publicModel,
                                    route.mapping().getChannelModelName())
                            .flatMap(response -> healthUpdate(() -> {
                                        long latency = System.currentTimeMillis() - routeStartedAt;
                                        channelHealthService.recordSuccess(route.channel(), latency);
                                        if (providerModelCatalogService != null) {
                                            providerModelCatalogService.recordRouteSuccess(route.channel(),
                                                    route.mapping().getChannelModelName());
                                        }
                                        if (providerCredentialService != null && selectedCredential != null) {
                                            providerCredentialService.recordSuccess(selectedCredential.id(), latency);
                                        }
                                    })
                                    .thenReturn(response))
                            .map(response -> new RoutedResponse(route, response));
                })
                .onErrorResume(error -> {
                    if (providerCredentialService != null) {
                        providerCredentialService.recordFailure(route.channel().getSelectedCredentialId(), error);
                    }
                    boolean retryable = isRetryableUpstreamError(error);
                    Mono<Void> health = isChannelFault(error)
                            ? healthUpdate(() -> channelHealthService.recordFailure(route.channel(), error))
                            : Mono.empty();
                    return health.then(Mono.defer(() -> {
                        if (index + 1 >= routes.size() || !retryable) {
                            return Mono.error(error);
                        }
                        log.warn("Channel {} failed for request {}; trying fallback channel: {}",
                                route.channel().getName(), traceId, safeMessage(error));
                        return attemptRoute(routes, index + 1, request, publicModel, traceId);
                    }));
                });
    }

    private Mono<Void> healthUpdate(Runnable update) {
        return Mono.fromRunnable(update)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    log.warn("Unable to update channel health: {}", safeMessage(error));
                    return Mono.empty();
                })
                .then();
    }

    private boolean isVerifiedRoute(ModelMapping mapping, Channel channel) {
        return isRoutable(channel) && (providerModelCatalogService == null
                || providerModelCatalogService.isRouteVerified(channel, mapping.getChannelModelName()));
    }

    private boolean isChannelFault(Throwable error) {
        if (error instanceof ResponseStatusException) return false;
        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 401 || status == 403 || status == 408 || status == 409
                    || status == 429 || status >= 500;
        }
        return true;
    }

    private boolean isRetryableUpstreamError(Throwable error) {
        if (error instanceof WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 401 || status == 403 || status == 429;
        }
        if (error instanceof WebClientRequestException requestException) {
            Throwable cause = requestException.getCause();
            return cause instanceof java.net.ConnectException
                    || cause instanceof java.net.UnknownHostException
                    || cause instanceof java.net.NoRouteToHostException;
        }
        return false;
    }

    private boolean isAmbiguousTimeout(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof java.util.concurrent.TimeoutException
                    || cursor instanceof java.net.SocketTimeoutException
                    || cursor.getClass().getSimpleName().contains("ReadTimeout")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }

    private String extractToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }
        if (!authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Authorization must use Bearer authentication");
        }
        String token = authorization.substring(7).trim();
        if (token.isBlank() || token.length() > 255) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid API Key");
        }
        return token;
    }

    private Token validateToken(String tokenKey, String requestedModel, String clientIp) {
        return validateToken(tokenKey, requestedModel, clientIp, true);
    }

    private Token validateToken(String tokenKey, String requestedModel, String clientIp, boolean checkModel) {
        Token t = apiKeyService.findBySecret(tokenKey);
        return validateToken(t, requestedModel, clientIp, checkModel);
    }

    private Token validateToken(Token t, String requestedModel, String clientIp, boolean checkModel) {
        if (t == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid API Key");
        }
        if (!t.isEnabled()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "API Key is disabled");
        }
        if (t.getExpiredAt() != null && t.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "API Key expired");
        }
        if (t.getTotalQuota() > 0 && t.getUsedQuota() >= t.getTotalQuota()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "API Key quota exceeded");
        }
        if (checkModel && !apiKeyService.modelAllowed(t, requestedModel)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Model is not allowed for this API Key");
        }
        if (!ipAllowed(t.getIpWhitelist(), clientIp)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Client IP is not allowed for this API Key");
        }
        return t;
    }

    private void validateRequest(ChatRequest request) {
        if (request == null || request.getModel() == null || request.getModel().isBlank()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "model is required");
        }
        if (request.getModel().length() > 160 || !request.getModel().matches("[A-Za-z0-9._:/-]+")) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "model is invalid");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty() || request.getMessages().size() > 200) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "messages must contain 1 to 200 items");
        }
        long contentBytes = 0;
        for (ChatRequest.Message message : request.getMessages()) {
            if (message == null || message.getRole() == null
                    || !message.getRole().matches("(?i)system|developer|user|assistant|tool")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every message must have a supported role");
            }
            contentBytes += utf8Length(Objects.toString(message.getContent(), ""));
            contentBytes += utf8Length(Objects.toString(message.getName(), ""));
            if (contentBytes > Math.max(1, maxRequestContentBytes)) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Message content exceeds the configured request-size limit");
            }
        }
        if (request.getMaxTokens() != null && (request.getMaxTokens() < 1 || request.getMaxTokens() > 131072)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "max_tokens is out of range");
        }
    }

    /**
     * A provider-independent conservative estimate. Dividing UTF-8 bytes by
     * three intentionally overestimates ordinary Latin text while tracking CJK
     * text reasonably closely; the small per-message allowance covers roles and
     * chat framing. The response explicitly marks estimated usage when this is
     * used for final billing.
     */
    private int estimateRequestTokens(ChatRequest request) {
        long bytes = 0;
        for (ChatRequest.Message message : request.getMessages()) {
            bytes += utf8Length(Objects.toString(message.getRole(), ""));
            bytes += utf8Length(Objects.toString(message.getContent(), ""));
            bytes += utf8Length(Objects.toString(message.getName(), ""));
        }
        long estimate = ((bytes + 2L) / 3L) + (request.getMessages().size() * 8L) + 8L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, estimate));
    }

    private int estimateResponseTokens(ChatResponse response) {
        long bytes = 0;
        if (response.getChoices() != null) {
            for (ChatResponse.Choice choice : response.getChoices()) {
                if (choice != null && choice.getMessage() != null) {
                    bytes += utf8Length(Objects.toString(choice.getMessage().getContent(), ""));
                }
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, (bytes + 2L) / 3L));
    }

    private void normalizeAssistantAnswer(ChatResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned no assistant answer");
        }
        for (ChatResponse.Choice choice : response.getChoices()) {
            if (choice == null || choice.getMessage() == null) continue;
            ChatResponse.Message message = choice.getMessage();
            String content = Objects.toString(message.getContent(), "").trim();
            String reasoningContent = Objects.toString(message.getReasoningContent(), "").trim();
            String reasoning = Objects.toString(message.getReasoning(), "").trim();
            if (content.isBlank()) {
                String fallback = !reasoningContent.isBlank() ? reasoningContent : reasoning;
                if (!fallback.isBlank()) {
                    message.setContent(fallback);
                    content = fallback;
                }
            }
            if (!content.isBlank()) return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned an empty assistant answer");
    }

    private boolean normalizeUsage(ChatResponse response, int estimatedPromptTokens) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned an empty response");
        }
        ChatResponse.Usage usage = response.getUsage();
        boolean estimated = usage == null
                || usage.getPromptTokens() == null
                || usage.getCompletionTokens() == null;
        if (usage == null) {
            usage = new ChatResponse.Usage();
            response.setUsage(usage);
        }
        int prompt = usage.getPromptTokens() == null ? estimatedPromptTokens : usage.getPromptTokens();
        int completion = usage.getCompletionTokens() == null ? estimateResponseTokens(response) : usage.getCompletionTokens();
        if (prompt < 0 || completion < 0 || prompt > 10_000_000 || completion > 10_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned invalid token usage");
        }
        long calculatedTotal = (long) prompt + completion;
        int reportedTotal = usage.getTotalTokens() == null ? 0 : usage.getTotalTokens();
        if (reportedTotal < 0 || reportedTotal > 20_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Provider returned invalid total token usage");
        }
        usage.setPromptTokens(prompt);
        usage.setCompletionTokens(completion);
        usage.setTotalTokens((int) Math.max(calculatedTotal, reportedTotal));
        usage.setEstimated(estimated ? Boolean.TRUE : null);
        return estimated;
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void updateQuotaAndLog(Token t, Channel channel, String tokenKey, String model, int prompt, int completion, int cached, int total,
                                   String status, String traceId, long startedAt, String errorMessage, BillingBreakdown billing) {
        updateQuotaAndLog(t, channel, tokenKey, model, prompt, completion, cached, 0, total,
                status, traceId, startedAt, errorMessage, billing);
    }

    private void updateQuotaAndLog(Token t, Channel channel, String tokenKey, String model, int prompt, int completion,
                                   int cacheRead, int cacheWrite, int total, String status, String traceId,
                                   long startedAt, String errorMessage, BillingBreakdown billing) {
        int cached = Math.addExact(cacheRead, cacheWrite);
        Log logRow = Log.builder()
                .userId(t.getUserId())
                .organizationId(defaultOrganizationId(t.getUserId()))
                .tokenKey(tokenKey)
                .tokenId(t.getId())
                .model(model)
                .channelId(channel == null ? null : channel.getId())
                .credentialId(channel == null ? null : channel.getSelectedCredentialId())
                .sourceCode(channel == null ? null : channel.getSourceCode())
                .promptTokens(prompt)
                .completionTokens(completion)
                .cachedTokens(cached)
                .cacheReadTokens(cacheRead)
                .cacheWriteTokens(cacheWrite)
                .cacheMissTokens(Math.max(0, prompt - cacheRead - cacheWrite))
                .totalTokens(total)
                .cost(billing.totalAmount() > 0 ? billing.totalAmount() : total)
                .status(status)
                .latencyMs(Math.max(0, System.currentTimeMillis() - startedAt))
                .traceId(traceId)
                .errorMessage(errorMessage == null ? null : errorMessage.substring(0, Math.min(1000, errorMessage.length())))
                .saleAmount(billing.totalAmount())
                .costAmount(billing.totalCostAmount())
                .inputAmount(billing.inputAmount())
                .outputAmount(billing.outputAmount())
                .cachedAmount(billing.cachedAmount())
                .cacheReadAmount(billing.cacheReadAmount())
                .cacheWriteAmount(billing.cacheWriteAmount())
                .totalAmount(billing.totalAmount())
                .inputCostAmount(billing.inputCostAmount())
                .outputCostAmount(billing.outputCostAmount())
                .cachedCostAmount(billing.cachedCostAmount())
                .cacheReadCostAmount(billing.cacheReadCostAmount())
                .cacheWriteCostAmount(billing.cacheWriteCostAmount())
                .grossProfit(billing.grossProfit())
                .pricingTier(billing.highContext() ? "LONG_CONTEXT_2X" : billing.tier() == null ? null : billing.tier().getTierName())
                .contextThresholdTokens(billing.contextThresholdTokens())
                .inputUnitSalePrice(billing.inputUnitSalePrice())
                .outputUnitSalePrice(billing.outputUnitSalePrice())
                .modelCurrency("USD")
                .modelAmountScale(amountScale)
                .settlementAmount(billing.settlementAmount())
                .settlementCurrency("CNY")
                .exchangeRate(billing.exchangeRate())
                .createdAt(LocalDateTime.now())
                .build();
        try {
            logMapper.insert(logRow);
        } catch (RuntimeException auditFailure) {
            // A completed, charged request must never be surfaced as failed only
            // because the secondary audit sink is temporarily unavailable.
            log.error("Unable to persist gateway audit log for trace {}", traceId, auditFailure);
        }
    }

    private Long defaultOrganizationId(Long userId) {
        if (userId == null) return null;
        List<Long> values = jdbcTemplate.queryForList(
                "SELECT default_organization_id FROM users WHERE id = ?", Long.class, userId);
        return values.isEmpty() ? null : values.get(0);
    }

    private boolean isRoutable(Channel channel) {
        if (channel == null || !channel.isEnabled()) return false;
        boolean credentialAvailable = providerCredentialService != null
                ? providerCredentialService.hasAvailable(channel)
                : channel.getApiKey() != null && !channel.getApiKey().isBlank();
        if (!credentialAvailable) return false;
        String health = Objects.toString(channel.getHealthStatus(), "UNTESTED").toUpperCase();
        if ("DISABLED".equals(health) || "UNTESTED".equals(health)) return false;
        if ("COOLDOWN".equals(health)) {
            return channel.getCooldownUntil() == null || !channel.getCooldownUntil().isAfter(LocalDateTime.now());
        }
        return "HEALTHY".equals(health) || "DEGRADED".equals(health);
    }

    private boolean modelAllowed(String allowedModels, String requestedModel) {
        if (allowedModels == null || allowedModels.isBlank()) return true;
        return List.of(allowedModels.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> "*".equals(value) || value.equalsIgnoreCase(requestedModel));
    }

    private boolean ipAllowed(String ipWhitelist, String clientIp) {
        if (ipWhitelist == null || ipWhitelist.isBlank()) return true;
        if (clientIp == null || clientIp.isBlank()) return false;
        return List.of(ipWhitelist.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .anyMatch(value -> value.equals(clientIp) || (value.endsWith("*") && clientIp.startsWith(value.substring(0, value.length() - 1))));
    }

    private BillingBreakdown calculateBilling(ModelMapping mapping, int promptTokens, int completionTokens,
                                              int cacheReadTokens, int cacheWriteTokens,
                                              BigDecimal customerPriceRatio, BigDecimal exchangeRate) {
        if (!mapping.isBillingEnabled()) {
            return BillingBreakdown.zero();
        }
        ModelPriceTier tier = priceTier(mapping, promptTokens);
        int cachedTokens = Math.addExact(cacheReadTokens, cacheWriteTokens);
        int billableInputTokens = Math.max(0, promptTokens - cachedTokens);
        BigDecimal ratio = customerPriceRatio == null ? BigDecimal.ONE : customerPriceRatio;
        com.transit.model.ModelContextPricingPolicy contextPolicy = modelContextPricingService == null ? null : modelContextPricingService.find(mapping.getPublicModelName());
        boolean highContext = contextPolicy != null && Boolean.TRUE.equals(contextPolicy.getEnabled())
                && contextPolicy.getThresholdTokens() != null && promptTokens > contextPolicy.getThresholdTokens();
        BigDecimal contextMultiplier = highContext ? ModelContextPricingService.FIXED_MULTIPLIER : BigDecimal.ONE;
        BigDecimal effectiveInputPrice = tier.getSaleInputPrice().multiply(contextMultiplier).multiply(ratio);
        BigDecimal effectiveOutputPrice = tier.getSaleOutputPrice().multiply(contextMultiplier).multiply(ratio);
        long inputAmount = price(billableInputTokens, effectiveInputPrice, tier.getSalePriceUnit());
        long outputAmount = price(completionTokens, effectiveOutputPrice, tier.getSalePriceUnit());
        long cacheReadAmount = price(cacheReadTokens, tier.getSaleCacheReadPrice().multiply(ratio), tier.getSalePriceUnit());
        long cacheWriteAmount = price(cacheWriteTokens, tier.getSaleCacheWritePrice().multiply(ratio), tier.getSalePriceUnit());
        long inputCostAmount = price(billableInputTokens, tier.getCostInputPrice(), tier.getCostPriceUnit());
        long outputCostAmount = price(completionTokens, tier.getCostOutputPrice(), tier.getCostPriceUnit());
        long cacheReadCostAmount = price(cacheReadTokens, tier.getCostCacheReadPrice(), tier.getCostPriceUnit());
        long cacheWriteCostAmount = price(cacheWriteTokens, tier.getCostCacheWritePrice(), tier.getCostPriceUnit());
        long sourceTotal = Math.addExact(Math.addExact(inputAmount, outputAmount),
                Math.addExact(cacheReadAmount, cacheWriteAmount));
        return new BillingBreakdown(tier, inputAmount, outputAmount, cacheReadAmount, cacheWriteAmount,
                inputCostAmount, outputCostAmount, cacheReadCostAmount, cacheWriteCostAmount,
                money().usdToCnyAmount(sourceTotal, exchangeRate), exchangeRate,
                highContext, contextPolicy == null ? null : contextPolicy.getThresholdTokens(),
                effectiveInputPrice, effectiveOutputPrice);
    }

    private ChatResponse.Billing billingDetails(ModelMapping mapping, int promptTokens,
                                                int cacheReadTokens, int cacheWriteTokens,
                                                BigDecimal customerPriceRatio, BillingBreakdown breakdown,
                                                String traceId) {
        BigDecimal ratio = customerPriceRatio == null ? BigDecimal.ONE : customerPriceRatio;
        boolean enabled = mapping.isBillingEnabled();
        ModelPriceTier tier = breakdown.tier() == null ? priceTier(mapping, promptTokens) : breakdown.tier();
        int cachedTokens = Math.addExact(cacheReadTokens, cacheWriteTokens);
        ChatResponse.Billing details = new ChatResponse.Billing();
        details.setCurrency("USD");
        details.setAmountScale(amountScale);
        details.setTraceId(traceId);
        details.setBillingEnabled(enabled);
        details.setBillableInputTokens(Math.max(0, promptTokens - cachedTokens));
        details.setCachedTokens(cachedTokens);
        details.setCacheReadTokens(cacheReadTokens);
        details.setCacheWriteTokens(cacheWriteTokens);
        details.setPriceTier(breakdown.highContext() ? "LONG_CONTEXT_2X" : tier.getTierName());
        details.setSaleGroupName(tier.getSaleGroupName());
        details.setPriceUnit(tier.getSalePriceUnit());
        details.setPriceSuffix(tier.getSalePriceSuffix());
        details.setInputPricePerMillion(enabled
                ? breakdown.inputUnitSalePrice()
                : BigDecimal.ZERO);
        details.setOutputPricePerMillion(enabled
                ? breakdown.outputUnitSalePrice()
                : BigDecimal.ZERO);
        details.setCachedPricePerMillion(enabled
                ? tier.getSaleCacheReadPrice().multiply(ratio)
                : BigDecimal.ZERO);
        details.setCacheReadPricePerMillion(enabled ? tier.getSaleCacheReadPrice().multiply(ratio) : BigDecimal.ZERO);
        details.setCacheWritePricePerMillion(enabled ? tier.getSaleCacheWritePrice().multiply(ratio) : BigDecimal.ZERO);
        details.setInputAmount(breakdown.inputAmount());
        details.setOutputAmount(breakdown.outputAmount());
        details.setCachedAmount(breakdown.cachedAmount());
        details.setCacheReadAmount(breakdown.cacheReadAmount());
        details.setCacheWriteAmount(breakdown.cacheWriteAmount());
        details.setTotalAmount(breakdown.totalAmount());
        details.setSettlementAmount(breakdown.settlementAmount());
        details.setSettlementCurrency("CNY");
        details.setExchangeRate(breakdown.exchangeRate());
        return details;
    }

    private ModelPriceTier priceTier(ModelMapping mapping, int contextTokens) {
        ModelPriceTier tier = priceTierService.select(mapping, contextTokens);
        if (tier != null) return tier;
        return ModelPriceTier.builder()
                .tierName("默认挡位")
                .saleGroupName("本站售价")
                .saleInputPrice(coalesce(mapping.getInputPricePerMillion(), mapping.getPriceRatio(), BigDecimal.ONE))
                .saleOutputPrice(coalesce(mapping.getOutputPricePerMillion(), mapping.getPriceRatio(), BigDecimal.ONE))
                .saleCacheReadPrice(coalesce(mapping.getCachedPricePerMillion(), BigDecimal.ZERO))
                .saleCacheWritePrice(BigDecimal.ZERO)
                .salePriceUnit("M")
                .salePriceSuffix("USD / 1M Token")
                .costInputPrice(coalesce(mapping.getInputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO))
                .costOutputPrice(coalesce(mapping.getOutputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO))
                .costCacheReadPrice(coalesce(mapping.getCachedCostPerMillion(), BigDecimal.ZERO))
                .costCacheWritePrice(BigDecimal.ZERO)
                .costPriceUnit("M")
                .costPriceSuffix("USD / 1M Token")
                .build();
    }

    private BigDecimal userPriceRatio(Long groupId) {
        if (groupId == null) return BigDecimal.ONE;
        List<BigDecimal> values = jdbcTemplate.query(
                "SELECT price_ratio FROM user_groups WHERE id = ?",
                (rs, rowNum) -> rs.getBigDecimal(1), groupId);
        if (values.isEmpty() || values.get(0) == null || values.get(0).signum() <= 0) {
            return BigDecimal.ONE;
        }
        return values.get(0);
    }

    private long price(int tokens, BigDecimal pricePerMillion) {
        return price(tokens, pricePerMillion, "M");
    }

    private long price(int tokens, BigDecimal priceAmount, String unit) {
        if (tokens <= 0 || priceAmount == null || BigDecimal.ZERO.compareTo(priceAmount) == 0) return 0;
        if (priceAmount.signum() < 0) {
            throw new IllegalStateException("Negative model pricing is not allowed");
        }
        long unitDivisor = "KB".equalsIgnoreCase(unit) ? 1_000L : 1_000_000L;
        return priceAmount
                .multiply(BigDecimal.valueOf(tokens))
                .multiply(BigDecimal.valueOf(Math.max(1, amountScale)))
                .divide(BigDecimal.valueOf(unitDivisor), 0, RoundingMode.CEILING)
                .longValueExact();
    }

    private BigDecimal coalesce(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return BigDecimal.ZERO;
    }

    private MoneyService money() {
        return moneyService == null ? new MoneyService(new BigDecimal("6.76693506")) : moneyService;
    }

    private record BillingBreakdown(
            ModelPriceTier tier,
            long inputAmount,
            long outputAmount,
            long cacheReadAmount,
            long cacheWriteAmount,
            long inputCostAmount,
            long outputCostAmount,
            long cacheReadCostAmount,
            long cacheWriteCostAmount,
            long settlementAmount,
            BigDecimal exchangeRate,
            boolean highContext,
            Integer contextThresholdTokens,
            BigDecimal inputUnitSalePrice,
            BigDecimal outputUnitSalePrice
    ) {
        static BillingBreakdown zero() {
            return new BillingBreakdown(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ONE,
                    false, null, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        long cachedAmount() {
            return Math.addExact(cacheReadAmount, cacheWriteAmount);
        }

        long cachedCostAmount() {
            return Math.addExact(cacheReadCostAmount, cacheWriteCostAmount);
        }

        long totalAmount() {
            return Math.addExact(Math.addExact(inputAmount, outputAmount), cachedAmount());
        }

        long totalCostAmount() {
            return Math.addExact(Math.addExact(inputCostAmount, outputCostAmount), cachedCostAmount());
        }

        long grossProfit() {
            return totalAmount() - totalCostAmount();
        }
    }

    private record Route(ModelMapping mapping, Channel channel) {
    }

    private record RoutedResponse(Route route, ChatResponse response) {
    }
}
