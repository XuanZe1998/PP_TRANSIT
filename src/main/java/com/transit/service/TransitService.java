package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.Token;
import com.transit.model.Log;
import com.transit.model.User;
import com.transit.provider.ProviderGateway;
import com.transit.provider.ProviderGatewayFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.codec.ServerSentEvent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
                        .orderByAsc(ModelMapping::getPublicModelName))
                .stream()
                .filter(mapping -> modelAllowed(token.getAllowedModels(), mapping.getPublicModelName()))
                .filter(mapping -> isRoutable(channelMapper.selectById(mapping.getChannelId())))
                .map(ModelMapping::getPublicModelName)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public Mono<ChatResponse> chatCompletions(String authorization, ChatRequest request, String clientIp) {
        validateRequest(request);
        String rawToken = extractToken(authorization);
        Token callerToken = validateToken(rawToken, request.getModel(), clientIp);
        return executeChat(callerToken, request, clientIp);
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
        
        List<ModelMapping> mappings = modelMappingMapper.selectList(
            new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getPublicModelName, publicModel)
                .eq(ModelMapping::isEnabled, true)
                .orderByDesc(ModelMapping::getPriority)
        );

        if (mappings.isEmpty()) {
            updateQuotaAndLog(callerToken, null, tokenKey, publicModel, 0, 0, 0, 0, "FAILED", traceId, startedAt, "MODEL_NOT_MAPPED", BillingBreakdown.zero());
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No available channel for model: " + publicModel));
        }

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
        int estimatedPromptTokens = estimateRequestTokens(request);
        int estimatedCompletionTokens = request.getMaxTokens() == null
                ? Math.max(1, defaultMaxOutputTokens) : request.getMaxTokens();
        int reservedTokens;
        try {
            reservedTokens = Math.addExact(estimatedPromptTokens, estimatedCompletionTokens);
        } catch (ArithmeticException overflow) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Requested token budget is too large");
        }
        long reservedAmount = routes.stream()
                .mapToLong(route -> calculateBilling(route.mapping(), estimatedPromptTokens,
                        estimatedCompletionTokens, 0, customerPriceRatio).totalAmount())
                .max()
                .orElse(0L);

        GatewaySettlementService.Reservation reservation;
        try {
            reservation = settlementService.reserve(callerToken, caller, reservedTokens, reservedAmount,
                    traceId, publicModel);
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
                    ModelMapping routeMapping = routed.route().mapping();
                    Channel routeChannel = routed.route().channel();
                    resp.setModel(publicModel);
                    normalizeAssistantAnswer(resp);
                    boolean estimatedUsage = normalizeUsage(resp, estimatedPromptTokens);
                    int prompt = resp.getUsage().getPromptTokens();
                    int completion = resp.getUsage().getCompletionTokens();
                    int total = resp.getUsage().getTotalTokens();
                    int cached = Math.min(prompt, Math.max(0, resp.getUsage().cachedTokens()));
                    BillingBreakdown billing = calculateBilling(routeMapping, prompt, completion,
                            cached, customerPriceRatio);
                    resp.setBilling(billingDetails(routeMapping, prompt, cached, customerPriceRatio,
                            billing, traceId));
                    settlementService.settle(reservation, total, billing.totalAmount(),
                            "API usage " + publicModel + " (" + traceId + ")");
                    updateQuotaAndLog(callerToken, routeChannel, tokenKey, publicModel, prompt,
                            completion, cached, total, estimatedUsage ? "SUCCESS_ESTIMATED" : "SUCCESS",
                            traceId, startedAt, null, billing);
                    return Mono.just(resp);
                })
                .onErrorResume(e -> {
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
                    ProviderGateway gateway = providerGatewayFactory.resolve(route.channel().getType());
                    request.setModel(publicModel);
                    log.info("Routing request {} for {} to channel {} (provider: {}, model: {})",
                            traceId, publicModel, route.channel().getName(), route.channel().getType(),
                            route.mapping().getChannelModelName());
                    return gateway.chatCompletions(route.channel(), request, publicModel,
                                    route.mapping().getChannelModelName())
                            .flatMap(response -> healthUpdate(() -> channelHealthService.recordSuccess(
                                            route.channel(), System.currentTimeMillis() - routeStartedAt))
                                    .thenReturn(response))
                            .map(response -> new RoutedResponse(route, response));
                })
                .onErrorResume(error -> {
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
            return status == 401 || status == 408 || status == 409 || status == 429 || status >= 500;
        }
        return true;
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
        if (checkModel && !modelAllowed(t.getAllowedModels(), requestedModel)) {
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
        Log logRow = Log.builder()
                .userId(t.getUserId())
                .tokenKey(tokenKey)
                .tokenId(t.getId())
                .model(model)
                .channelId(channel == null ? null : channel.getId())
                .promptTokens(prompt)
                .completionTokens(completion)
                .cachedTokens(cached)
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
                .totalAmount(billing.totalAmount())
                .inputCostAmount(billing.inputCostAmount())
                .outputCostAmount(billing.outputCostAmount())
                .cachedCostAmount(billing.cachedCostAmount())
                .grossProfit(billing.grossProfit())
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

    private boolean isRoutable(Channel channel) {
        if (channel == null || !channel.isEnabled() || channel.getApiKey() == null || channel.getApiKey().isBlank()) return false;
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
                                              int cachedTokens, BigDecimal customerPriceRatio) {
        if (!mapping.isBillingEnabled()) {
            return BillingBreakdown.zero();
        }
        int billableInputTokens = Math.max(0, promptTokens - cachedTokens);
        BigDecimal ratio = customerPriceRatio == null ? BigDecimal.ONE : customerPriceRatio;
        long inputAmount = price(billableInputTokens,
                coalesce(mapping.getInputPricePerMillion(), mapping.getPriceRatio(), BigDecimal.ONE).multiply(ratio));
        long outputAmount = price(completionTokens,
                coalesce(mapping.getOutputPricePerMillion(), mapping.getPriceRatio(), BigDecimal.ONE).multiply(ratio));
        long cachedAmount = price(cachedTokens,
                coalesce(mapping.getCachedPricePerMillion(), BigDecimal.ZERO).multiply(ratio));
        long inputCostAmount = price(billableInputTokens, coalesce(mapping.getInputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO));
        long outputCostAmount = price(completionTokens, coalesce(mapping.getOutputCostPerMillion(), mapping.getCostPerMillion(), BigDecimal.ZERO));
        long cachedCostAmount = price(cachedTokens, coalesce(mapping.getCachedCostPerMillion(), BigDecimal.ZERO));
        return new BillingBreakdown(inputAmount, outputAmount, cachedAmount, inputCostAmount, outputCostAmount, cachedCostAmount);
    }

    private ChatResponse.Billing billingDetails(ModelMapping mapping, int promptTokens, int cachedTokens,
                                                BigDecimal customerPriceRatio, BillingBreakdown breakdown,
                                                String traceId) {
        BigDecimal ratio = customerPriceRatio == null ? BigDecimal.ONE : customerPriceRatio;
        boolean enabled = mapping.isBillingEnabled();
        ChatResponse.Billing details = new ChatResponse.Billing();
        details.setCurrency("CNY");
        details.setAmountScale(amountScale);
        details.setTraceId(traceId);
        details.setBillingEnabled(enabled);
        details.setBillableInputTokens(Math.max(0, promptTokens - cachedTokens));
        details.setCachedTokens(cachedTokens);
        details.setInputPricePerMillion(enabled
                ? coalesce(mapping.getInputPricePerMillion(), mapping.getPriceRatio(), BigDecimal.ONE).multiply(ratio)
                : BigDecimal.ZERO);
        details.setOutputPricePerMillion(enabled
                ? coalesce(mapping.getOutputPricePerMillion(), mapping.getPriceRatio(), BigDecimal.ONE).multiply(ratio)
                : BigDecimal.ZERO);
        details.setCachedPricePerMillion(enabled
                ? coalesce(mapping.getCachedPricePerMillion(), BigDecimal.ZERO).multiply(ratio)
                : BigDecimal.ZERO);
        details.setInputAmount(breakdown.inputAmount());
        details.setOutputAmount(breakdown.outputAmount());
        details.setCachedAmount(breakdown.cachedAmount());
        details.setTotalAmount(breakdown.totalAmount());
        return details;
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
        if (tokens <= 0 || pricePerMillion == null || BigDecimal.ZERO.compareTo(pricePerMillion) == 0) return 0;
        if (pricePerMillion.signum() < 0) {
            throw new IllegalStateException("Negative model pricing is not allowed");
        }
        return pricePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .multiply(BigDecimal.valueOf(Math.max(1, amountScale)))
                .divide(BigDecimal.valueOf(1_000_000), 0, RoundingMode.CEILING)
                .longValueExact();
    }

    private BigDecimal coalesce(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null) return value;
        }
        return BigDecimal.ZERO;
    }

    private record BillingBreakdown(
            long inputAmount,
            long outputAmount,
            long cachedAmount,
            long inputCostAmount,
            long outputCostAmount,
            long cachedCostAmount
    ) {
        static BillingBreakdown zero() {
            return new BillingBreakdown(0, 0, 0, 0, 0, 0);
        }

        long totalAmount() {
            return Math.addExact(Math.addExact(inputAmount, outputAmount), cachedAmount);
        }

        long totalCostAmount() {
            return Math.addExact(Math.addExact(inputCostAmount, outputCostAmount), cachedCostAmount);
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
