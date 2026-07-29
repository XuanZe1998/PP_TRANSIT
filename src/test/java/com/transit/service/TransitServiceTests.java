package com.transit.service;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Channel;
import com.transit.model.Log;
import com.transit.model.ModelMapping;
import com.transit.model.Token;
import com.transit.model.User;
import com.transit.provider.ProviderGateway;
import com.transit.provider.ProviderGatewayFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransitServiceTests {

    @Mock private ModelMappingMapper mappingMapper;
    @Mock private ChannelMapper channelMapper;
    @Mock private LogMapper logMapper;
    @Mock private UserMapper userMapper;
    @Mock private ProviderGatewayFactory gatewayFactory;
    @Mock private GatewaySettlementService settlementService;
    @Mock private ChannelUrlPolicy channelUrlPolicy;
    @Mock private GatewayRateLimiter rateLimiter;
    @Mock private ApiKeyService apiKeyService;
    @Mock private ChannelSecretService channelSecretService;
    @Mock private ChannelHealthService channelHealthService;
    @Mock private OpenAiStreamAdapter streamAdapter;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ProviderGateway primaryGateway;
    @Mock private ProviderGateway fallbackGateway;

    private TransitService service;

    @BeforeEach
    void setUp() {
        service = new TransitService(mappingMapper, channelMapper, logMapper, userMapper,
                gatewayFactory, settlementService, channelUrlPolicy, rateLimiter, apiKeyService,
                channelSecretService, new ChannelRoutePlanner(), channelHealthService, streamAdapter, jdbcTemplate);
        ReflectionTestUtils.setField(service, "amountScale", 10_000L);
        ReflectionTestUtils.setField(service, "defaultMaxOutputTokens", 64);
        ReflectionTestUtils.setField(service, "maxRequestContentBytes", 2_097_152);
        when(channelSecretService.reveal(any(Channel.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void retriesTheNextHealthyMappingWhenPrimaryProviderFails() {
        Token token = token(9L);
        User user = user(90L);
        GatewaySettlementService.Reservation reservation = reservation(token, user);
        ModelMapping primaryMapping = mapping(1L, 11L, 100);
        ModelMapping fallbackMapping = mapping(2L, 12L, 90);
        Channel primary = channel(11L, "primary", "primary");
        Channel fallback = channel(12L, "fallback", "fallback");

        arrangeBillableRequest(token, user, reservation);
        when(apiKeyService.findBySecret("sk-test")).thenReturn(token);
        when(mappingMapper.selectList(any())).thenReturn(List.of(primaryMapping, fallbackMapping));
        when(channelMapper.selectById(11L)).thenReturn(primary);
        when(channelMapper.selectById(12L)).thenReturn(fallback);
        when(gatewayFactory.resolve("primary")).thenReturn(primaryGateway);
        when(gatewayFactory.resolve("fallback")).thenReturn(fallbackGateway);
        when(primaryGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("provider unavailable")));
        ChatResponse successful = response();
        when(fallbackGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(successful));

        StepVerifier.create(service.chatCompletions("Bearer sk-test", request(), "203.0.113.10"))
                .expectNextMatches(response -> "public-model".equals(response.getModel())
                        && response.getBilling() != null
                        && response.getBilling().getAmountScale() == 10_000L
                        && BigDecimal.ONE.compareTo(response.getBilling().getInputPricePerMillion()) == 0
                        && response.getBilling().getInputAmount() == 1L
                        && response.getBilling().getOutputAmount() == 1L
                        && response.getBilling().getTotalAmount() == 2L)
                .verifyComplete();

        verify(primaryGateway).chatCompletions(any(), any(), anyString(), anyString());
        verify(fallbackGateway).chatCompletions(any(), any(), anyString(), anyString());
        verify(settlementService).settle(eq(reservation), eq(3), eq(2L), startsWith("API usage public-model"));
        verify(settlementService, never()).release(any(), anyString());
    }

    @Test
    void doesNotRetryAProviderRequestRejectedAsBadInput() {
        Token token = token(10L);
        User user = user(100L);
        GatewaySettlementService.Reservation reservation = reservation(token, user);
        ModelMapping primaryMapping = mapping(1L, 11L, 100);
        ModelMapping fallbackMapping = mapping(2L, 12L, 90);
        arrangeBillableRequest(token, user, reservation);
        when(apiKeyService.findBySecret("sk-bad-request")).thenReturn(token);
        when(mappingMapper.selectList(any())).thenReturn(List.of(primaryMapping, fallbackMapping));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "primary", "primary"));
        when(channelMapper.selectById(12L)).thenReturn(channel(12L, "fallback", "fallback"));
        when(gatewayFactory.resolve("primary")).thenReturn(primaryGateway);
        when(primaryGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(WebClientResponseException.create(
                        400, "Bad Request", HttpHeaders.EMPTY, new byte[0], null)));

        StepVerifier.create(service.chatCompletions("Bearer sk-bad-request", request(), "203.0.113.10"))
                .expectError(WebClientResponseException.BadRequest.class)
                .verify();

        verify(fallbackGateway, never()).chatCompletions(any(), any(), anyString(), anyString());
        verify(settlementService).release(eq(reservation), anyString());
        verify(settlementService, never()).settle(any(), anyInt(), anyLong(), anyString());
    }

    @Test
    void reservationFailureStopsBeforeCallingAnyUpstream() {
        Token token = token(11L);
        User user = user(110L);
        when(apiKeyService.keyPreview(token)).thenReturn("sk-at-test");
        when(userMapper.selectById(user.getId())).thenReturn(user);
        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping(1L, 11L, 100)));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "primary", "primary"));
        when(settlementService.reserve(eq(token), eq(user), anyInt(), anyLong(), anyString(), eq("public-model")))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "Insufficient balance"));

        StepVerifier.create(Mono.defer(() -> service.chatCompletions(token, request(), "203.0.113.10")))
                .expectErrorMatches(error -> error instanceof org.springframework.web.server.ResponseStatusException status
                        && status.getStatusCode().value() == 402)
                .verify();

        verify(gatewayFactory, never()).resolve(anyString());
        verify(primaryGateway, never()).chatCompletions(any(), any(), anyString(), anyString());
        verify(settlementService, never()).settle(any(), anyInt(), anyLong(), anyString());
    }

    @Test
    void upstreamFailureReleasesTheEntireReservation() {
        Token token = token(12L);
        User user = user(120L);
        GatewaySettlementService.Reservation reservation = reservation(token, user);
        arrangeBillableRequest(token, user, reservation);
        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping(1L, 11L, 100)));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "primary", "primary"));
        when(gatewayFactory.resolve("primary")).thenReturn(primaryGateway);
        when(primaryGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("provider unavailable")));

        StepVerifier.create(service.chatCompletions(token, request(), "203.0.113.10"))
                .expectErrorMessage("provider unavailable")
                .verify();

        verify(settlementService).release(eq(reservation), anyString());
        verify(settlementService, never()).settle(any(), anyInt(), anyLong(), anyString());
    }

    @Test
    void missingProviderUsageIsEstimatedMarkedAndSettled() {
        Token token = token(13L);
        User user = user(130L);
        GatewaySettlementService.Reservation reservation = reservation(token, user);
        arrangeBillableRequest(token, user, reservation);
        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping(1L, 11L, 100)));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "primary", "primary"));
        when(gatewayFactory.resolve("primary")).thenReturn(primaryGateway);
        when(primaryGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(responseWithoutUsage("estimated response")));

        StepVerifier.create(service.chatCompletions(token, request(), "203.0.113.10"))
                .expectNextMatches(response -> response.getUsage() != null
                        && Boolean.TRUE.equals(response.getUsage().getEstimated())
                        && response.getUsage().getPromptTokens() > 0
                        && response.getUsage().getCompletionTokens() > 0
                        && response.getUsage().getTotalTokens()
                        >= response.getUsage().getPromptTokens() + response.getUsage().getCompletionTokens())
                .verifyComplete();

        verify(settlementService).settle(eq(reservation), anyInt(), anyLong(), startsWith("API usage public-model"));
        verify(settlementService, never()).release(any(), anyString());
        verify(logMapper).insert(org.mockito.ArgumentMatchers.argThat((Log log) ->
                "SUCCESS_ESTIMATED".equals(log.getStatus()) && log.getTotalTokens() > 0));
    }

    @Test
    void returnsServerCalculatedCachedUsageRatesAndAmounts() {
        Token token = token(14L);
        User user = user(140L);
        GatewaySettlementService.Reservation reservation = reservation(token, user);
        arrangeBillableRequest(token, user, reservation);
        ModelMapping mapping = mapping(1L, 11L, 100);
        mapping.setInputPricePerMillion(new BigDecimal("2.5"));
        mapping.setOutputPricePerMillion(new BigDecimal("5"));
        mapping.setCachedPricePerMillion(BigDecimal.ONE);
        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "primary", "primary"));
        when(gatewayFactory.resolve("primary")).thenReturn(primaryGateway);

        ChatResponse response = new ChatResponse();
        ChatResponse.Usage usage = new ChatResponse.Usage();
        usage.setPromptTokens(1_000);
        usage.setCompletionTokens(500);
        usage.setTotalTokens(1_500);
        ChatResponse.PromptTokensDetails promptDetails = new ChatResponse.PromptTokensDetails();
        promptDetails.setCachedTokens(400);
        usage.setPromptTokensDetails(promptDetails);
        response.setUsage(usage);
        response.setChoices(List.of(choice("ok")));
        when(primaryGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(response));

        StepVerifier.create(service.chatCompletions(token, request(), "203.0.113.10"))
                .expectNextMatches(result -> result.getBilling() != null
                        && result.getBilling().getCachedTokens() == 400
                        && result.getBilling().getBillableInputTokens() == 600
                        && new BigDecimal("2.5").compareTo(result.getBilling().getInputPricePerMillion()) == 0
                        && result.getBilling().getInputAmount() == 15L
                        && result.getBilling().getOutputAmount() == 25L
                        && result.getBilling().getCachedAmount() == 4L
                        && result.getBilling().getTotalAmount() == 44L)
                .verifyComplete();

        verify(settlementService).settle(eq(reservation), eq(1_500), eq(44L), startsWith("API usage public-model"));
    }

    @Test
    void emptyAssistantAnswerIsRejectedAndReservationIsReleased() {
        Token token = token(15L);
        User user = user(150L);
        GatewaySettlementService.Reservation reservation = reservation(token, user);
        arrangeBillableRequest(token, user, reservation);
        when(mappingMapper.selectList(any())).thenReturn(List.of(mapping(1L, 11L, 100)));
        when(channelMapper.selectById(11L)).thenReturn(channel(11L, "primary", "primary"));
        when(gatewayFactory.resolve("primary")).thenReturn(primaryGateway);

        ChatResponse empty = response();
        empty.setChoices(List.of(choice("")));
        when(primaryGateway.chatCompletions(any(), any(), anyString(), anyString()))
                .thenReturn(Mono.just(empty));

        StepVerifier.create(service.chatCompletions(token, request(), "203.0.113.10"))
                .expectErrorMatches(error -> error instanceof ResponseStatusException status
                        && status.getStatusCode().value() == 502)
                .verify();

        verify(settlementService).release(eq(reservation), anyString());
        verify(settlementService, never()).settle(any(), anyInt(), anyLong(), anyString());
    }

    private ModelMapping mapping(long id, long channelId, int priority) {
        return ModelMapping.builder()
                .id(id)
                .channelId(channelId)
                .publicModelName("public-model")
                .channelModelName("provider-model-" + id)
                .priority(priority)
                .enabled(true)
                .billingEnabled(true)
                .build();
    }

    private Channel channel(long id, String name, String type) {
        return Channel.builder()
                .id(id)
                .name(name)
                .type(type)
                .baseUrl("https://" + name + ".example.com")
                .apiKey("provider-secret")
                .enabled(true)
                .healthStatus("HEALTHY")
                .build();
    }

    private ChatRequest request() {
        ChatRequest request = new ChatRequest();
        request.setModel("public-model");
        ChatRequest.Message message = new ChatRequest.Message();
        message.setRole("user");
        message.setContent("hello");
        request.setMessages(List.of(message));
        return request;
    }

    private ChatResponse response() {
        ChatResponse response = new ChatResponse();
        ChatResponse.Usage usage = new ChatResponse.Usage();
        usage.setPromptTokens(2);
        usage.setCompletionTokens(1);
        usage.setTotalTokens(3);
        response.setUsage(usage);
        response.setChoices(List.of(choice("ok")));
        return response;
    }

    private ChatResponse.Choice choice(String content) {
        ChatResponse.Message message = new ChatResponse.Message();
        message.setRole("assistant");
        message.setContent(content);
        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        return choice;
    }

    private ChatResponse responseWithoutUsage(String content) {
        ChatResponse response = new ChatResponse();
        ChatResponse.Message message = new ChatResponse.Message();
        message.setRole("assistant");
        message.setContent(content);
        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        response.setChoices(List.of(choice));
        return response;
    }

    private Token token(long id) {
        return Token.builder().id(id).userId(id * 10).key("sha256:test").enabled(true).build();
    }

    private User user(long id) {
        return User.builder().id(id).username("user-" + id).status("ACTIVE").balance(100_000).build();
    }

    private GatewaySettlementService.Reservation reservation(Token token, User user) {
        return new GatewaySettlementService.Reservation(
                "reservation-" + token.getId(), token.getId(), user.getId(), 20, 2);
    }

    private void arrangeBillableRequest(Token token, User user,
                                        GatewaySettlementService.Reservation reservation) {
        when(apiKeyService.keyPreview(token)).thenReturn("sk-at-test");
        when(userMapper.selectById(token.getUserId())).thenReturn(user);
        when(settlementService.reserve(eq(token), eq(user), anyInt(), anyLong(), anyString(), eq("public-model")))
                .thenReturn(reservation);
    }
}
