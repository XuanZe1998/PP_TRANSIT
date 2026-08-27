package com.transit.controller;

import com.transit.service.TransitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatControllerStreamingTests {

    @Mock private TransitService transitService;

    @Test
    void streamFlagProducesActualServerSentEvents() throws Exception {
        ChatController controller = new ChatController(transitService);
        ReflectionTestUtils.setField(controller, "trustForwardedHeaders", false);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(transitService.chatCompletionsStream(anyString(), any(), anyString()))
                .thenReturn(Flux.just(
                        ServerSentEvent.builder("{\"object\":\"chat.completion.chunk\"}").build(),
                        ServerSentEvent.builder("[DONE]").build()));

        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"test-model","stream":true,
                                 "messages":[{"role":"user","content":"hello"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("chat.completion.chunk")))
                .andExpect(content().string(containsString("[DONE]")));
    }
}
