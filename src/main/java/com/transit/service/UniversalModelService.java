package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Channel;
import com.transit.model.Log;
import com.transit.model.ModelMapping;
import com.transit.model.Token;
import com.transit.model.User;
import com.transit.provider.HaoeeProtocolClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class UniversalModelService {
    private final ApiKeyService apiKeys;
    private final UserMapper users;
    private final ModelMappingMapper mappings;
    private final ChannelMapper channels;
    private final ProviderCredentialService credentials;
    private final HaoeeProtocolClient haoee;
    private final GatewayRateLimiter rateLimiter;
    private final GatewaySettlementService settlement;
    private final LogMapper logs;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final SensitiveWordService sensitiveWords;
    @Autowired(required = false)
    private AgentDistributionService agentDistribution;

    @Value("${billing.amount-scale:10000}") private long amountScale;
    @Value("${billing.default-max-output-tokens:4096}") private int defaultMaxOutputTokens;

    public Mono<JsonNode> invoke(String authorization, String clientIp, String protocol,
                                 String defaultPath, JsonNode request, String idempotencyKey) {
        requireObjectRequest(request);
        rejectUnsupportedResponsesLifecycle(protocol, request);
        AuthContext auth = authorize(authorization, clientIp, request);
        IdempotencyService.Claim claim = idempotency.claim("API_KEY", auth.token().getId(),
                "model.invoke:" + protocol, idempotencyKey, request, false);
        if (claim.replay()) return Mono.just(claim.response());
        String traceId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sensitiveWords.scanJson(traceId, auth.token().getOrganizationId(), auth.user().getId(),
                auth.token().getId(), auth.model(), request);
        Route route = route(auth.model(), protocol, sessionKey(request), reliableCostRequired(auth.user().getId()));
        ObjectNode payload = (ObjectNode) request.deepCopy();
        payload.put("model", route.mapping().getChannelModelName());
        int estimatedInput = estimateInput(payload);
        int estimatedOutput = estimatedOutput(payload, protocol);
        PricingQuote reservedQuote = quote(route.mapping(), payload, estimatedInput, estimatedOutput, 0);
        long reservedAmount = reservedQuote.saleAmount();
        GatewaySettlementService.Reservation reservation = settlement.reserve(auth.token(), auth.user(),
                Math.max(1, estimatedInput + estimatedOutput), reservedAmount, traceId, auth.model());
        String path = route.mapping().getEndpointPath() == null || route.mapping().getEndpointPath().isBlank()
                ? defaultPath : route.mapping().getEndpointPath();
        long started = System.currentTimeMillis();
        return haoee.invoke(route.channel(), route.mapping().getChannelModelName(), path, HttpMethod.POST, payload)
                .publishOn(Schedulers.boundedElastic())
                .map(response -> {
                    if ("responses".equals(protocol) && "failed".equalsIgnoreCase(response.path("status").asText())) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, responseFailureMessage(response));
                    }
                    Usage usage = usage(response, estimatedInput);
                    PricingQuote actualQuote = quote(route.mapping(), payload, usage.input(), usage.output(), usage.cacheRead());
                    long actualAmount = actualQuote.saleAmount();
                    settleUsage(reservation, Math.max(1, usage.input() + usage.output()), actualAmount,
                            actualQuote.costAmount(), "API usage " + auth.model() + " (" + traceId + ")", auth.user().getId());
                    writeLog(auth, route, usage, actualQuote, traceId, started, "SUCCESS", null);
                    idempotency.complete(claim, 200, response, "MODEL_RESPONSE", traceId);
                    credentials.recordSuccess(route.credentialId(), System.currentTimeMillis() - started);
                    return response;
                })
                .onErrorResume(error -> {
                    boolean unknown = ambiguousTimeout(error);
                    if (unknown) settlement.markUnknown(reservation, safe(error));
                    else settlement.release(reservation, safe(error));
                    writeLog(auth, route, Usage.zero(), PricingQuote.zero(route.mapping()), traceId, started, unknown ? "UNKNOWN" : "FAILED", safe(error));
                    if (unknown) idempotency.unknown(claim, error, "MODEL_RESPONSE", traceId);
                    else idempotency.fail(claim, error);
                    if (unknown) credentials.releaseUnknown(route.credentialId());
                    else credentials.recordFailure(route.credentialId(), error);
                    return Mono.error(error);
                });
    }

    /**
     * Streams OpenAI Responses SSE frames without rewriting their event names or data.
     * Terminal accounting is intentionally driven by response.completed/incomplete/failed,
     * not by transport completion, because a broken connection may already have incurred cost.
     */
    public Flux<ServerSentEvent<String>> streamResponses(
            String authorization, String clientIp, JsonNode request) {
        requireObjectRequest(request);
        rejectUnsupportedResponsesLifecycle("responses", request);
        AuthContext auth = authorize(authorization, clientIp, request);
        String traceId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sensitiveWords.scanJson(traceId, auth.token().getOrganizationId(), auth.user().getId(),
                auth.token().getId(), auth.model(), request);
        Route route = route(auth.model(), "responses", sessionKey(request), reliableCostRequired(auth.user().getId()));
        ObjectNode payload = (ObjectNode) request.deepCopy();
        payload.put("model", route.mapping().getChannelModelName());
        payload.put("stream", true);
        int estimatedInput = estimateInput(payload);
        int estimatedOutput = estimatedOutput(payload, "responses");
        PricingQuote reservedQuote = quote(route.mapping(), payload, estimatedInput, estimatedOutput, 0);
        GatewaySettlementService.Reservation reservation = settlement.reserve(auth.token(), auth.user(),
                Math.max(1, estimatedInput + estimatedOutput), reservedQuote.saleAmount(), traceId, auth.model());
        String path = route.mapping().getEndpointPath() == null || route.mapping().getEndpointPath().isBlank()
                ? "/v1/responses" : route.mapping().getEndpointPath();
        long started = System.currentTimeMillis();
        AtomicBoolean finalized = new AtomicBoolean();
        AtomicBoolean receivedEvent = new AtomicBoolean();

        return haoee.streamEvents(route.channel(), route.mapping().getChannelModelName(), path, payload)
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(event -> {
                    receivedEvent.set(true);
                    String type = responseEventType(event);
                    if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
                        if (!finalized.compareAndSet(false, true)) return;
                        JsonNode eventData = responseEventData(event);
                        Usage actual = responseUsage(eventData, estimatedInput);
                        PricingQuote actualQuote = quote(route.mapping(), payload,
                                actual.input(), actual.output(), actual.cacheRead());
                        try {
                            settleUsage(reservation, Math.max(1, actual.input() + actual.output()),
                                    actualQuote.saleAmount(), actualQuote.costAmount(), "Responses streaming API usage " + auth.model()
                                            + " (" + traceId + ")", auth.user().getId());
                            writeLog(auth, route, actual, actualQuote, traceId, started,
                                    "SUCCESS", "response.incomplete".equals(type)
                                            ? "Upstream response completed with incomplete status" : null);
                            credentials.recordSuccess(route.credentialId(), System.currentTimeMillis() - started);
                        } catch (RuntimeException settlementError) {
                            settlement.markUnknown(reservation, safe(settlementError));
                            writeLog(auth, route, actual, reservedQuote, traceId, started,
                                    "UNKNOWN", safe(settlementError));
                            credentials.releaseUnknown(route.credentialId());
                            throw settlementError;
                        }
                    } else if ("response.failed".equals(type) && finalized.compareAndSet(false, true)) {
                        String reason = responseFailureMessage(responseEventData(event));
                        settlement.release(reservation, reason);
                        writeLog(auth, route, Usage.zero(), PricingQuote.zero(route.mapping()), traceId,
                                started, "FAILED", reason);
                        credentials.recordFailure(route.credentialId(), new IllegalStateException(reason));
                    }
                })
                .doOnError(error -> {
                    if (!finalized.compareAndSet(false, true)) return;
                    boolean unknown = receivedEvent.get() || ambiguousTimeout(error);
                    if (unknown) settlement.markUnknown(reservation, safe(error));
                    else settlement.release(reservation, safe(error));
                    writeLog(auth, route, Usage.zero(), unknown ? reservedQuote : PricingQuote.zero(route.mapping()),
                            traceId, started, unknown ? "UNKNOWN" : "FAILED", safe(error));
                    if (unknown) credentials.releaseUnknown(route.credentialId());
                    else credentials.recordFailure(route.credentialId(), error);
                })
                .doFinally(signal -> {
                    if (!finalized.compareAndSet(false, true)) return;
                    Schedulers.boundedElastic().schedule(() -> {
                        String reason = "Responses stream ended without a terminal event: " + signal;
                        settlement.markUnknown(reservation, reason);
                        writeLog(auth, route, Usage.zero(), reservedQuote, traceId, started,
                                "UNKNOWN", reason);
                        credentials.releaseUnknown(route.credentialId());
                    });
                });
    }

    public Mono<JsonNode> transcribe(String authorization, String clientIp, String model,
                                     byte[] file, String fileName, String contentType,
                                     java.util.Map<String, String> fields, String idempotencyKey) {
        return multipartInvoke(authorization, clientIp, model, "audio-transcriptions", "/v1/audio/transcriptions",
                "file", file, fileName, contentType, fields, idempotencyKey);
    }

    public Mono<JsonNode> multipartInvoke(String authorization, String clientIp, String model, String protocol,
                                     String defaultPath, String partName, byte[] file, String fileName, String contentType,
                                     java.util.Map<String, String> fields, String idempotencyKey) {
        ObjectNode authBody = objectMapper.createObjectNode().put("model", model);
        authBody.put("file_name", fileName == null ? "audio.bin" : fileName);
        authBody.put("file_size", file == null ? 0 : file.length);
        authBody.put("file_sha256", sha256(file == null ? new byte[0] : file));
        AuthContext auth = authorize(authorization, clientIp, authBody);
        IdempotencyService.Claim claim = idempotency.claim("API_KEY", auth.token().getId(),
                "model.invoke:" + protocol, idempotencyKey, authBody, false);
        if (claim.replay()) return Mono.just(claim.response());
        Route route = route(model, protocol, sessionKey(authBody), reliableCostRequired(auth.user().getId()));
        int estimatedInput = Math.max(1, (file == null ? 0 : file.length) / 3);
        ObjectNode billingPayload = authBody.deepCopy();
        fields.forEach(billingPayload::put);
        PricingQuote reservedQuote = quote(route.mapping(), billingPayload, estimatedInput, 0, 0);
        long reservedAmount = reservedQuote.saleAmount();
        String traceId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        GatewaySettlementService.Reservation reservation = settlement.reserve(auth.token(), auth.user(),
                estimatedInput, reservedAmount, traceId, model);
        String path = route.mapping().getEndpointPath() == null || route.mapping().getEndpointPath().isBlank()
                ? defaultPath : route.mapping().getEndpointPath();
        long started = System.currentTimeMillis();
        java.util.Map<String, String> upstreamFields = new java.util.HashMap<>(fields);
        upstreamFields.put("model", route.mapping().getChannelModelName());
        return haoee.multipart(route.channel(), route.mapping().getChannelModelName(), path, partName,
                        fileName, contentType, file, upstreamFields)
                .publishOn(Schedulers.boundedElastic())
                .map(response -> {
                    Usage usage = usage(response, estimatedInput);
                    PricingQuote actualQuote = quote(route.mapping(), billingPayload, usage.input(), usage.output(), usage.cacheRead());
                    long actual = actualQuote.saleAmount();
                    settleUsage(reservation, Math.max(1, usage.input() + usage.output()), actual,
                            actualQuote.costAmount(), protocol + " " + model + " (" + traceId + ")", auth.user().getId());
                    writeLog(auth, route, usage, actualQuote, traceId, started, "SUCCESS", null);
                    idempotency.complete(claim, 200, response, "MODEL_RESPONSE", traceId);
                    credentials.recordSuccess(route.credentialId(), System.currentTimeMillis() - started);
                    return response;
                })
                .onErrorResume(error -> {
                    boolean unknown = ambiguousTimeout(error);
                    if (unknown) settlement.markUnknown(reservation, safe(error));
                    else settlement.release(reservation, safe(error));
                    if (unknown) idempotency.unknown(claim, error, "MODEL_RESPONSE", traceId);
                    else idempotency.fail(claim, error);
                    if (unknown) credentials.releaseUnknown(route.credentialId());
                    else credentials.recordFailure(route.credentialId(), error);
                    return Mono.error(error);
                });
    }

    public boolean hasHaoeeRoute(String publicModel, String protocol) {
        if (publicModel == null || publicModel.isBlank()) return false;
        return candidateMappings(publicModel, protocol).stream().anyMatch(mapping -> {
            Channel channel = channels.selectById(mapping.getChannelId());
            return isUniversalChannel(channel);
        });
    }

    public reactor.core.publisher.Flux<ServerSentEvent<String>> streamChat(
            String authorization, String clientIp, Object requestObject) {
        JsonNode request = objectMapper.valueToTree(requestObject);
        AuthContext auth = authorize(authorization, clientIp, request);
        String traceId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sensitiveWords.scanJson(traceId, auth.token().getOrganizationId(), auth.user().getId(),
                auth.token().getId(), auth.model(), request);
        Route route = route(auth.model(), "chat-completions", sessionKey(request), reliableCostRequired(auth.user().getId()));
        ObjectNode payload = (ObjectNode) request.deepCopy();
        payload.put("model", route.mapping().getChannelModelName());
        payload.put("stream", true);
        ObjectNode streamOptions = payload.with("stream_options");
        streamOptions.put("include_usage", true);
        int estimatedInput = estimateInput(payload);
        int estimatedOutput = estimatedOutput(payload, "chat-completions");
        PricingQuote reservedQuote = quote(route.mapping(), payload, estimatedInput, estimatedOutput, 0);
        long reservedAmount = reservedQuote.saleAmount();
        GatewaySettlementService.Reservation reservation = settlement.reserve(auth.token(), auth.user(),
                estimatedInput + estimatedOutput, reservedAmount, traceId, auth.model());
        AtomicReference<Usage> observed = new AtomicReference<>(Usage.zero());
        AtomicInteger streamedBytes = new AtomicInteger();
        AtomicBoolean finished = new AtomicBoolean();
        long started = System.currentTimeMillis();
        String path = route.mapping().getEndpointPath() == null || route.mapping().getEndpointPath().isBlank()
                ? "/v1/chat/completions" : route.mapping().getEndpointPath();
        return haoee.stream(route.channel(), route.mapping().getChannelModelName(), path, payload)
                .map(data -> {
                    if (data != null) streamedBytes.addAndGet(data.getBytes(StandardCharsets.UTF_8).length);
                    Usage parsed = streamUsage(data);
                    if (parsed.input() > 0 || parsed.output() > 0) observed.set(parsed);
                    return ServerSentEvent.builder(data).build();
                })
                .doFinally(signal -> Schedulers.boundedElastic().schedule(() -> {
                    if (!finished.compareAndSet(false, true)) return;
                    Usage actual = observed.get();
                    boolean complete = signal == reactor.core.publisher.SignalType.ON_COMPLETE;
                    if (actual.input() == 0 && actual.output() == 0) {
                        actual = new Usage(estimatedInput,
                                complete ? Math.max(1, streamedBytes.get() / 3) : 0, 0, 0);
                    }
                    PricingQuote finalQuote = complete
                            ? quote(route.mapping(), payload, actual.input(), actual.output(), actual.cacheRead())
                            : reservedQuote;
                    long charge = finalQuote.saleAmount();
                    settleUsage(reservation, Math.max(1, actual.input() + actual.output()), charge,
                            finalQuote.costAmount(), "Streaming API usage " + auth.model() + " (" + traceId + ")", auth.user().getId());
                    writeLog(auth, route, actual, finalQuote, traceId, started,
                            complete ? "SUCCESS" : "UNKNOWN", complete ? null : "Streaming request ended before a final usage event");
                    if (complete) credentials.recordSuccess(route.credentialId(), System.currentTimeMillis() - started);
                    else credentials.releaseUnknown(route.credentialId());
                }));
    }

    /** Raw SSE bridge for managed provider-native protocols (Messages, Gemini and Codex aliases). */
    public Flux<ServerSentEvent<String>> streamProtocol(String authorization, String clientIp, String protocol,
                                                         String path, JsonNode request) {
        requireObjectRequest(request);
        AuthContext auth = authorize(authorization, clientIp, request);
        String traceId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        sensitiveWords.scanJson(traceId, auth.token().getOrganizationId(), auth.user().getId(), auth.token().getId(), auth.model(), request);
        Route route = route(auth.model(), protocol, sessionKey(request), reliableCostRequired(auth.user().getId()));
        ObjectNode payload = (ObjectNode) request.deepCopy(); payload.put("model", route.mapping().getChannelModelName());
        int input = estimateInput(payload), output = estimatedOutput(payload, protocol);
        PricingQuote quote = quote(route.mapping(), payload, input, output, 0);
        GatewaySettlementService.Reservation reservation = settlement.reserve(auth.token(), auth.user(), Math.max(1, input + output), quote.saleAmount(), traceId, auth.model());
        long started = System.currentTimeMillis();
        AtomicBoolean completed = new AtomicBoolean(false);
        return haoee.streamEvents(route.channel(), route.mapping().getChannelModelName(), path, payload)
                .doOnComplete(() -> {
                    if (completed.compareAndSet(false, true)) {
                        settleUsage(reservation, Math.max(1, input + output), quote.saleAmount(), quote.costAmount(), "Streaming " + protocol + " " + auth.model(), auth.user().getId());
                        writeLog(auth, route, new Usage(input, output, 0, 0), quote, traceId, started, "SUCCESS", null);
                        credentials.recordSuccess(route.credentialId(), System.currentTimeMillis() - started);
                    }
                }).doOnError(error -> {
                    if (completed.compareAndSet(false, true)) {
                        boolean unknown = ambiguousTimeout(error);
                        if (unknown) settlement.markUnknown(reservation, safe(error)); else settlement.release(reservation, safe(error));
                        if (unknown) credentials.releaseUnknown(route.credentialId()); else credentials.recordFailure(route.credentialId(), error);
                    }
                });
    }

    private Usage streamUsage(String data) {
        if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return Usage.zero();
        String json = data.startsWith("data:") ? data.substring(5).trim() : data.trim();
        try { return usage(objectMapper.readTree(json), 0); }
        catch (Exception ignored) { return Usage.zero(); }
    }

    private String responseEventType(ServerSentEvent<String> event) {
        if (event != null && event.event() != null && !event.event().isBlank()) return event.event().trim();
        return responseEventData(event).path("type").asText("").trim();
    }

    private JsonNode responseEventData(ServerSentEvent<String> event) {
        if (event == null || event.data() == null || event.data().isBlank()) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(event.data()); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private Usage responseUsage(JsonNode eventData, int estimatedInput) {
        JsonNode response = eventData.path("response");
        return usage(response.isObject() ? response : eventData, estimatedInput);
    }

    private String responseFailureMessage(JsonNode node) {
        JsonNode response = node.path("response");
        JsonNode error = response.isObject() ? response.path("error") : node.path("error");
        String message = error.path("message").asText("").trim();
        if (message.isBlank()) message = node.path("message").asText("").trim();
        return message.isBlank() ? "Upstream Responses request failed" : message;
    }

    private void requireObjectRequest(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "JSON object body is required");
        }
    }

    private void rejectUnsupportedResponsesLifecycle(String protocol, JsonNode request) {
        if ("responses".equals(protocol) && request.path("background").asBoolean(false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "background Responses are not supported; use a synchronous or streaming request");
        }
    }

    public long estimateAmount(ModelMapping mapping, JsonNode request) {
        return quote(mapping, request, estimateInput(request), estimatedOutput(request,
                Objects.toString(mapping.getProtocols(), "tasks")), 0).saleAmount();
    }

    public PricingQuote estimateQuote(ModelMapping mapping, JsonNode request) {
        return quote(mapping, request, estimateInput(request), estimatedOutput(request,
                Objects.toString(mapping.getProtocols(), "tasks")), 0);
    }

    public AuthContext authorize(String authorization, String clientIp, JsonNode request) {
        String secret = bearer(authorization);
        Token token = apiKeys.findBySecret(secret);
        if (token == null || !token.isEnabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API Key");
        if (token.getExpiredAt() != null && token.getExpiredAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "API Key expired");
        }
        String model = request == null ? "" : request.path("model").asText("").trim();
        if (model.isBlank() || !model.matches("[A-Za-z0-9._:/-]{1,160}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model is required");
        }
        if (!apiKeys.modelAllowed(token, model)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Model is not allowed for this API Key");
        }
        User user = users.selectById(token.getUserId());
        if (user == null || !"ACTIVE".equalsIgnoreCase(Objects.toString(user.getStatus(), "ACTIVE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API Key owner is unavailable");
        }
        rateLimiter.checkToken(token);
        return new AuthContext(token, user, model);
    }

    public Route route(String publicModel, String protocol) {
        return route(publicModel, protocol, null);
    }

    public Route route(String publicModel, String protocol, String sessionId) {
        return route(publicModel, protocol, sessionId, false);
    }

    public Route route(String publicModel, String protocol, String sessionId, boolean requireReliableCost) {
        List<ModelMapping> candidates = candidateMappings(publicModel, protocol);
        for (ModelMapping mapping : candidates) {
            Channel channel = channels.selectById(mapping.getChannelId());
            if (!isUniversalChannel(channel)) continue;
            ProviderCredentialService.SelectedCredential selected = null;
            try {
                selected = credentials.select(channel, publicModel, sessionId, requireReliableCost);
                // Keep constructor-level unit tests and older credential adapters compatible while
                // all production implementations use the richer selector above.
                if (selected == null && !requireReliableCost) selected = credentials.select(channel);
                channel.setApiKey(selected.secret());
                channel.setAuthContext(selected.authContext());
                channel.setSelectedCredentialId(selected.id());
                rateLimiter.checkChannel(channel);
                return new Route(mapping, channel, selected.id());
            } catch (ResponseStatusException unavailable) {
                if (selected != null) credentials.releaseUnknown(selected.id());
                if (unavailable.getStatusCode().value() != 503 && unavailable.getStatusCode().value() != 429) throw unavailable;
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "No compatible Haoee route or managed OAuth route for model: " + publicModel);
    }

    private List<ModelMapping> candidateMappings(String publicModel, String protocol) {
        return mappings.selectList(new LambdaQueryWrapper<ModelMapping>()
                        .eq(ModelMapping::getPublicModelName, publicModel)
                        .eq(ModelMapping::isEnabled, true)
                        .eq(ModelMapping::isBillingEnabled, true)
                        .orderByDesc(ModelMapping::getPriority))
                .stream().filter(PublicPricingPolicy::hasRequiredSale)
                .filter(mapping -> supports(mapping, protocol)).toList();
    }

    private boolean isUniversalChannel(Channel channel) {
        if (channel == null || !channel.isEnabled()) return false;
        String health = Objects.toString(channel.getHealthStatus(), "UNTESTED").toUpperCase(Locale.ROOT);
        boolean healthy = List.of("HEALTHY", "DEGRADED").contains(health)
                || ("COOLDOWN".equals(health) && channel.getCooldownUntil() != null
                && channel.getCooldownUntil().isBefore(LocalDateTime.now()));
        return healthy && (channel.isManaged()
                || "haoee".equalsIgnoreCase(channel.getType())
                || "haoee-openai".equalsIgnoreCase(channel.getType()));
    }

    private boolean supports(ModelMapping mapping, String protocol) {
        String configured = Objects.toString(mapping.getProtocols(), "chat-completions").toLowerCase(Locale.ROOT);
        return configured.lines().flatMap(line -> java.util.Arrays.stream(line.split(",")))
                .map(String::trim).anyMatch(protocol::equalsIgnoreCase);
    }

    private Usage usage(JsonNode response, int estimatedInput) {
        JsonNode usage = response.path("usage");
        int input = integer(usage, "prompt_tokens", integer(usage, "input_tokens", estimatedInput));
        int output = integer(usage, "completion_tokens", integer(usage, "output_tokens", 0));
        int cacheRead = integer(usage.path("prompt_tokens_details"), "cached_tokens",
                integer(usage.path("input_tokens_details"), "cached_tokens",
                        integer(usage, "cache_read_input_tokens", 0)));
        int cacheWrite = integer(usage, "cache_creation_input_tokens", 0);
        return new Usage(Math.max(0, input), Math.max(0, output), Math.max(0, cacheRead), Math.max(0, cacheWrite));
    }

    private int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() ? value.asInt() : fallback;
    }

    private int estimateInput(JsonNode payload) {
        return Math.max(1, payload.toString().getBytes(StandardCharsets.UTF_8).length / 3);
    }

    private int estimatedOutput(JsonNode payload, String protocol) {
        if (List.of("embeddings", "reranks").contains(protocol)) return 0;
        int configured = payload.path("max_output_tokens").asInt(payload.path("max_tokens").asInt(defaultMaxOutputTokens));
        return Math.max(1, Math.min(131_072, configured));
    }

    private PricingQuote quote(ModelMapping mapping, JsonNode request, int input, int output, int cacheRead) {
        String unit = Objects.toString(mapping.getPricingUnit(), "TOKEN").toUpperCase(Locale.ROOT);
        if ("FREE_PREVIEW".equalsIgnoreCase(mapping.getBillingMode())) {
            return new PricingQuote(unit, billableQuantity(unit, request), BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
        if (!mapping.isBillingEnabled()) return PricingQuote.zero(mapping);
        if (!"TOKEN".equals(unit)) {
            BigDecimal quantity = billableQuantity(unit, request);
            BigDecimal salePrice = nonNegative(mapping.getSaleUnitPrice());
            BigDecimal costPrice = nonNegative(mapping.getCostUnitPrice());
            return new PricingQuote(unit, quantity, salePrice, costPrice,
                    scaled(salePrice.multiply(quantity)), scaled(costPrice.multiply(quantity)));
        }
        int uncached = Math.max(0, input - cacheRead);
        BigDecimal sale = mapping.getInputPricePerMillion().multiply(BigDecimal.valueOf(uncached))
                .add(mapping.getOutputPricePerMillion().multiply(BigDecimal.valueOf(output)))
                .add(mapping.getCachedPricePerMillion().multiply(BigDecimal.valueOf(cacheRead)));
        BigDecimal cost = nonNegative(mapping.getInputCostPerMillion()).multiply(BigDecimal.valueOf(uncached))
                .add(nonNegative(mapping.getOutputCostPerMillion()).multiply(BigDecimal.valueOf(output)))
                .add(nonNegative(mapping.getCachedCostPerMillion()).multiply(BigDecimal.valueOf(cacheRead)));
        return new PricingQuote(unit, BigDecimal.valueOf(Math.max(0, input + output)), BigDecimal.ZERO, BigDecimal.ZERO,
                scaled(sale.divide(BigDecimal.valueOf(1_000_000), 18, RoundingMode.HALF_UP)),
                scaled(cost.divide(BigDecimal.valueOf(1_000_000), 18, RoundingMode.HALF_UP)));
    }

    private BigDecimal billableQuantity(String unit, JsonNode request) {
        JsonNode body = request == null ? objectMapper.createObjectNode() : request;
        return switch (unit) {
            case "SECOND" -> requiredPositive(body, "duration", "duration_seconds");
            case "IMAGE" -> BigDecimal.valueOf(Math.max(1, body.path("n").asInt(1)));
            case "MINUTE" -> requiredPositive(body, "duration_seconds", "duration")
                    .divide(BigDecimal.valueOf(60), 6, RoundingMode.CEILING);
            case "CHARACTER" -> {
                String text = body.path("input").asText(body.path("text").asText(body.path("prompt").asText("")));
                int characters = text.codePointCount(0, text.length());
                if (characters <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Billable text is required");
                yield BigDecimal.valueOf(characters).divide(BigDecimal.valueOf(1_000), 6, RoundingMode.CEILING);
            }
            case "TASK" -> BigDecimal.ONE;
            default -> BigDecimal.ZERO;
        };
    }

    private BigDecimal requiredPositive(JsonNode body, String primary, String fallback) {
        JsonNode value = body.path(primary);
        if (!value.isNumber() && fallback != null) value = body.path(fallback);
        BigDecimal quantity = value.isNumber() ? value.decimalValue() : BigDecimal.ZERO;
        if (quantity.signum() <= 0 || quantity.compareTo(BigDecimal.valueOf(86_400)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, primary + " must be a positive number");
        }
        return quantity.setScale(6, RoundingMode.CEILING);
    }

    private long scaled(BigDecimal usd) {
        return nonNegative(usd).multiply(BigDecimal.valueOf(amountScale)).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private void writeLog(AuthContext auth, Route route, Usage usage, PricingQuote quote, String traceId,
                          long started, String status, String error) {
        Log row = Log.builder().organizationId(auth.token().getOrganizationId()).userId(auth.user().getId())
                .tokenId(auth.token().getId()).tokenKey(apiKeys.keyPreview(auth.token())).model(auth.model())
                .channelId(route.channel().getId()).credentialId(route.credentialId())
                .sourceCode(route.channel().getSourceCode()).promptTokens(usage.input())
                .completionTokens(usage.output()).cacheReadTokens(usage.cacheRead())
                .cacheWriteTokens(usage.cacheWrite()).cachedTokens(usage.cacheRead() + usage.cacheWrite())
                .cacheMissTokens(Math.max(0, usage.input() - usage.cacheRead()))
                .totalTokens(usage.input() + usage.output()).cost(quote.saleAmount()).saleAmount(quote.saleAmount())
                .costAmount(quote.costAmount()).totalAmount(quote.saleAmount())
                .grossProfit(quote.saleAmount() - quote.costAmount()).billingUnit(quote.unit())
                .billableQuantity(quote.quantity()).unitSalePrice(quote.saleUnitPrice()).unitCostPrice(quote.costUnitPrice()).status(status)
                .traceId(traceId).latencyMs(System.currentTimeMillis() - started).errorMessage(error)
                .createdAt(LocalDateTime.now()).build();
        logs.insert(row);
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization must use Bearer authentication");
        }
        String value = authorization.substring(7).trim();
        if (value.isBlank() || value.length() > 255) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API Key");
        return value;
    }

    private String sessionKey(JsonNode request) {
        if (request == null) return null;
        String previous = request.path("previous_response_id").asText("").trim();
        if (!previous.isBlank()) return previous.substring(0, Math.min(256, previous.length()));
        String session = request.path("session_id").asText("").trim();
        return session.isBlank() ? null : session.substring(0, Math.min(256, session.length()));
    }

    private boolean reliableCostRequired(Long userId) {
        return agentDistribution != null && agentDistribution.requiresReliableCost(userId);
    }

    private void settleUsage(GatewaySettlementService.Reservation reservation, int tokens, long saleAmount,
                             long costAmount, String remark, Long userId) {
        if (reliableCostRequired(userId)) settlement.settle(reservation, tokens, saleAmount, costAmount, remark);
        else settlement.settle(reservation, tokens, saleAmount, remark);
    }

    private String safe(Throwable error) {
        String value = error == null ? "Unknown error" : Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        return value.substring(0, Math.min(500, value.length()));
    }

    public boolean ambiguousTimeout(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof java.util.concurrent.TimeoutException
                    || cursor instanceof java.net.SocketTimeoutException
                    || cursor.getClass().getSimpleName().contains("ReadTimeout")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record AuthContext(Token token, User user, String model) {}
    public record Route(ModelMapping mapping, Channel channel, Long credentialId) {}
    public record PricingQuote(String unit, BigDecimal quantity, BigDecimal saleUnitPrice,
                               BigDecimal costUnitPrice, long saleAmount, long costAmount) {
        static PricingQuote zero(ModelMapping mapping) {
            return new PricingQuote(Objects.toString(mapping == null ? null : mapping.getPricingUnit(), "TOKEN"),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
    }
    private record Usage(int input, int output, int cacheRead, int cacheWrite) {
        static Usage zero() { return new Usage(0, 0, 0, 0); }
    }
}
