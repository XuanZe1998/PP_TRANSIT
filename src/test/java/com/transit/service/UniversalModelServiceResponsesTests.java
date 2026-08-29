package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UniversalModelServiceResponsesTests {
    @Mock private ApiKeyService apiKeys;
    @Mock private UserMapper users;
    @Mock private ModelMappingMapper mappings;
    @Mock private ChannelMapper channels;
    @Mock private ProviderCredentialService credentials;
    @Mock private HaoeeProtocolClient haoee;
    @Mock private GatewayRateLimiter rateLimiter;
    @Mock private GatewaySettlementService settlement;
    @Mock private LogMapper logs;
    @Mock private IdempotencyService idempotency;
    @Mock private SensitiveWordService sensitiveWords;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private UniversalModelService service;
    private Token token;
    private User user;
    private ModelMapping mapping;
    private Channel channel;
    private GatewaySettlementService.Reservation reservation;

    @BeforeEach
    void setUp() {
        service = new UniversalModelService(apiKeys, users, mappings, channels, credentials, haoee,
                rateLimiter, settlement, logs, idempotency, objectMapper, sensitiveWords);
        ReflectionTestUtils.setField(service, "amountScale", 10_000L);
        ReflectionTestUtils.setField(service, "defaultMaxOutputTokens", 32);

        token = Token.builder().id(11L).userId(22L).organizationId(33L)
                .enabled(true).allowAllModels(true).build();
        user = User.builder().id(22L).status("ACTIVE").build();
        mapping = ModelMapping.builder().id(44L).publicModelName("gpt-5.4-pro")
                .channelModelName("gpt-5.4-pro-upstream").channelId(55L)
                .protocols("responses").billingMode("FREE_PREVIEW").build();
        channel = Channel.builder().id(55L).type("haoee").sourceCode("haoee")
                .baseUrl("https://maas.haoee.com/v1").healthStatus("HEALTHY").enabled(true).build();
        reservation = new GatewaySettlementService.Reservation("req_test", 11L, 22L, 64, 0);

        when(apiKeys.findBySecret("test-secret")).thenReturn(token);
        when(apiKeys.modelAllowed(token, "gpt-5.4-pro")).thenReturn(true);
        when(apiKeys.keyPreview(token)).thenReturn("sk-****");
        when(users.selectById(22L)).thenReturn(user);
        when(mappings.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));
        when(channels.selectById(55L)).thenReturn(channel);
        when(credentials.select(channel)).thenReturn(new ProviderCredentialService.SelectedCredential(66L, "upstream-secret"));
        when(settlement.reserve(eq(token), eq(user), anyInt(), anyLong(), anyString(), eq("gpt-5.4-pro")))
                .thenReturn(reservation);
    }

    @Test
    void completedEventRewritesModelUsesNestedUsageAndSettlesExactlyOnce() {
        ServerSentEvent<String> completed = event("response.completed", """
                {"type":"response.completed","response":{"status":"completed",
                 "usage":{"input_tokens":9,"output_tokens":3,
                  "input_tokens_details":{"cached_tokens":4}}}}
                """);
        when(haoee.streamEvents(eq(channel), eq("gpt-5.4-pro-upstream"), eq("/v1/responses"), any()))
                .thenReturn(Flux.just(event("response.created", "{\"type\":\"response.created\"}"),
                        completed, completed));

        StepVerifier.create(service.streamResponses("Bearer test-secret", "127.0.0.1", request(true)))
                .expectNextCount(3)
                .verifyComplete();

        verify(settlement, times(1)).settle(eq(reservation), eq(12), anyLong(), contains("gpt-5.4-pro"));
        verify(credentials, times(1)).recordSuccess(eq(66L), anyLong());
        ArgumentCaptor<Log> log = ArgumentCaptor.forClass(Log.class);
        verify(logs, times(1)).insert(log.capture());
        assertThat(log.getValue().getPromptTokens()).isEqualTo(9);
        assertThat(log.getValue().getCompletionTokens()).isEqualTo(3);
        assertThat(log.getValue().getCacheReadTokens()).isEqualTo(4);
        assertThat(log.getValue().getStatus()).isEqualTo("SUCCESS");

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(haoee).streamEvents(eq(channel), eq("gpt-5.4-pro-upstream"), eq("/v1/responses"), payload.capture());
        assertThat(payload.getValue().path("model").asText()).isEqualTo("gpt-5.4-pro-upstream");
        assertThat(payload.getValue().path("stream").asBoolean()).isTrue();
    }

    @Test
    void incompleteEventAlsoSettlesReportedUsage() {
        when(haoee.streamEvents(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.just(event("response.incomplete", """
                        {"type":"response.incomplete","response":{"status":"incomplete",
                         "usage":{"input_tokens":5,"output_tokens":2}}}
                        """)));

        StepVerifier.create(service.streamResponses("Bearer test-secret", "127.0.0.1", request(true)))
                .expectNextCount(1)
                .verifyComplete();

        verify(settlement).settle(eq(reservation), eq(7), anyLong(), anyString());
        verify(credentials).recordSuccess(eq(66L), anyLong());
    }

    @Test
    void failedEventReleasesReservationAndRecordsFailure() {
        when(haoee.streamEvents(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.just(event("response.failed", """
                        {"type":"response.failed","response":{"status":"failed",
                         "error":{"message":"upstream rejected request"}}}
                        """)));

        StepVerifier.create(service.streamResponses("Bearer test-secret", "127.0.0.1", request(true)))
                .expectNextCount(1)
                .verifyComplete();

        verify(settlement).release(reservation, "upstream rejected request");
        verify(settlement, never()).settle(any(), anyInt(), anyLong(), anyString());
        verify(credentials).recordFailure(eq(66L), any(IllegalStateException.class));
    }

    @Test
    void transportFailureBeforeAnyEventReleasesReservation() {
        when(haoee.streamEvents(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.error(new IllegalStateException("upstream 502")));

        StepVerifier.create(service.streamResponses("Bearer test-secret", "127.0.0.1", request(true)))
                .expectErrorMessage("upstream 502")
                .verify();

        verify(settlement).release(reservation, "upstream 502");
        verify(credentials).recordFailure(eq(66L), any(IllegalStateException.class));
    }

    @Test
    void timeoutAndDisconnectRemainUnknownForReconciliation() {
        when(haoee.streamEvents(any(), anyString(), anyString(), any()))
                .thenReturn(Flux.concat(
                        Flux.just(event("response.created", "{\"type\":\"response.created\"}")),
                        Flux.error(new TimeoutException("read timed out"))));

        StepVerifier.create(service.streamResponses("Bearer test-secret", "127.0.0.1", request(true)))
                .expectNextCount(1)
                .expectError(TimeoutException.class)
                .verify();

        verify(settlement).markUnknown(reservation, "read timed out");
        verify(credentials).releaseUnknown(66L);
        verify(settlement, never()).release(any(), anyString());
    }

    @Test
    void clientCancellationMarksReservationUnknown() {
        when(haoee.streamEvents(any(), anyString(), anyString(), any())).thenReturn(Flux.never());

        StepVerifier.create(service.streamResponses("Bearer test-secret", "127.0.0.1", request(true)))
                .thenCancel()
                .verify();

        verify(settlement, timeout(1_000)).markUnknown(eq(reservation), contains("cancel"));
        verify(credentials, timeout(1_000)).releaseUnknown(66L);
    }

    @Test
    void nonStreamingResponsesPassesAuthorizationPathModelAndUsageToSettlement() {
        IdempotencyService.Claim claim = new IdempotencyService.Claim(null, false, false, null, null);
        when(idempotency.claim(eq("API_KEY"), eq(11L), eq("model.invoke:responses"), eq("idem"), any(), eq(false)))
                .thenReturn(claim);
        when(haoee.invoke(eq(channel), eq("gpt-5.4-pro-upstream"), eq("/v1/responses"), any(), any()))
                .thenReturn(Mono.just(objectMapper.createObjectNode()
                        .put("id", "resp_1").put("status", "completed")
                        .set("usage", objectMapper.createObjectNode()
                                .put("input_tokens", 8).put("output_tokens", 4))));

        StepVerifier.create(service.invoke("Bearer test-secret", "127.0.0.1", "responses",
                        "/v1/responses", request(false), "idem"))
                .assertNext(response -> assertThat(response.path("status").asText()).isEqualTo("completed"))
                .verifyComplete();

        verify(settlement).settle(eq(reservation), eq(12), anyLong(), contains("gpt-5.4-pro"));
        verify(idempotency).complete(eq(claim), eq(200), any(), eq("MODEL_RESPONSE"), anyString());
    }

    @Test
    void nonStreamingFailedResponseReleasesReservationAndPreservesReason() {
        IdempotencyService.Claim claim = new IdempotencyService.Claim(null, false, false, null, null);
        when(idempotency.claim(anyString(), anyLong(), anyString(), any(), any(), anyBoolean())).thenReturn(claim);
        when(haoee.invoke(any(), anyString(), anyString(), any(), any()))
                .thenReturn(Mono.just(objectMapper.createObjectNode()
                        .put("status", "failed")
                        .set("error", objectMapper.createObjectNode().put("message", "invalid upstream request"))));

        StepVerifier.create(service.invoke("Bearer test-secret", "127.0.0.1", "responses",
                        "/v1/responses", request(false), null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);
                    assertThat(error.getMessage()).contains("invalid upstream request");
                })
                .verify();

        verify(settlement).release(eq(reservation), contains("invalid upstream request"));
        verify(settlement, never()).settle(any(), anyInt(), anyLong(), anyString());
    }

    @Test
    void missingBearerAndBackgroundModeAreRejectedBeforeRouting() {
        assertThatThrownBy(() -> service.streamResponses(null, "127.0.0.1", request(true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Bearer authentication");

        ObjectNode background = request(false).put("background", true);
        assertThatThrownBy(() -> service.invoke("Bearer test-secret", "127.0.0.1", "responses",
                "/v1/responses", background, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("background Responses are not supported");
        verifyNoInteractions(haoee);
    }

    @Test
    void invalidBearerKeyIsRejectedBeforeRouting() {
        assertThatThrownBy(() -> service.streamResponses(
                "Bearer invalid-secret", "127.0.0.1", request(true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid API Key");
        verifyNoInteractions(haoee);
    }

    @Test
    void responsesProtocolMismatchHasNoCallableRoute() {
        when(mappings.selectList(any(Wrapper.class))).thenReturn(List.of());

        assertThatThrownBy(() -> service.streamResponses(
                "Bearer test-secret", "127.0.0.1", request(true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No compatible Haoee route");
        verifyNoInteractions(haoee);
    }

    private ObjectNode request(boolean stream) {
        return objectMapper.createObjectNode()
                .put("model", "gpt-5.4-pro")
                .put("input", "hello")
                .put("max_output_tokens", 16)
                .put("stream", stream);
    }

    private ServerSentEvent<String> event(String type, String data) {
        return ServerSentEvent.builder(data).event(type).build();
    }
}
