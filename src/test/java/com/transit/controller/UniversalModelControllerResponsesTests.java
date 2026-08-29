package com.transit.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.transit.service.UniversalModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class UniversalModelControllerResponsesTests {
    @Mock private UniversalModelService service;

    @Test
    @SuppressWarnings("unchecked")
    void streamResponsesUsesSseHeadersAndPreservesNamedEvents() {
        when(service.streamResponses(anyString(), anyString(), any()))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"type\":\"response.created\"}")
                                .event("response.created").build(),
                        ServerSentEvent.builder("{\"type\":\"response.completed\"}")
                                .event("response.completed").build()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        ResponseEntity<?> response = new UniversalModelController(service).responses(
                "Bearer sk-test", null,
                JsonNodeFactory.instance.objectNode().put("model", "gpt-5.4-pro")
                        .put("input", "hello").put("stream", true), request).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        StepVerifier.create((Flux<ServerSentEvent<String>>) response.getBody())
                .assertNext(event -> assertThat(event.event()).isEqualTo("response.created"))
                .assertNext(event -> assertThat(event.event()).isEqualTo("response.completed"))
                .verifyComplete();
    }

    @Test
    void nonStreamingResponsesKeepsJsonInvocationPathAndIdempotencyKey() throws Exception {
        when(service.invoke(anyString(), anyString(), eq("responses"), eq("/v1/responses"), any(), eq("idem-1")))
                .thenReturn(Mono.just(JsonNodeFactory.instance.objectNode()
                        .put("id", "resp_1").put("status", "completed")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UniversalModelController(service)).build();

        MvcResult pending = mvc.perform(post("/v1/responses")
                        .header("Authorization", "Bearer sk-test")
                        .header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-5.3-codex","input":"hello","stream":false}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("completed"));
        verify(service).invoke(anyString(), anyString(), eq("responses"), eq("/v1/responses"), any(), eq("idem-1"));
    }
}
