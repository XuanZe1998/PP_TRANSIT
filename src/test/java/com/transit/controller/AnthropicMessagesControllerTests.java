package com.transit.controller;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.service.ClientIpResolver;
import com.transit.service.TransitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AnthropicMessagesControllerTests {
    @Mock TransitService transit;
    @Mock ClientIpResolver clientIps;

    @Test
    void convertsMessagesAndReturnsAnthropicResponseShape() throws Exception {
        ChatResponse.Message message = new ChatResponse.Message(); message.setRole("assistant"); message.setContent("hello");
        ChatResponse.Choice choice = new ChatResponse.Choice(); choice.setMessage(message); choice.setFinishReason("stop");
        ChatResponse.Usage usage = new ChatResponse.Usage(); usage.setPromptTokens(3); usage.setCompletionTokens(2);
        ChatResponse response = new ChatResponse(); response.setId("msg_1"); response.setModel("public-model"); response.setChoices(List.of(choice)); response.setUsage(usage);
        when(clientIps.resolve(any())).thenReturn("203.0.113.7");
        when(transit.chatCompletions(eq("Bearer sk-test"), any(), eq("203.0.113.7"))).thenReturn(Mono.just(response));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnthropicMessagesController(transit, clientIps)).build();

        MvcResult pending = mvc.perform(post("/v1/messages").header("x-api-key", "sk-test")
                        .header("anthropic-version", "2023-06-01").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"public-model\",\"max_tokens\":32,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("message"))
                .andExpect(jsonPath("$.content[0].text").value("hello"))
                .andExpect(jsonPath("$.usage.input_tokens").value(3));

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(transit).chatCompletions(eq("Bearer sk-test"), request.capture(), eq("203.0.113.7"));
        assertThat(request.getValue().getModel()).isEqualTo("public-model");
        assertThat(request.getValue().getMessages().get(0).getContent()).isEqualTo("hi");
    }

    @Test
    void returnsAnthropicAuthenticationErrors() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AnthropicMessagesController(transit, clientIps)).build();
        mvc.perform(post("/v1/messages").header("anthropic-version", "2023-06-01")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("error"))
                .andExpect(jsonPath("$.error.type").value("authentication_error"));
    }
}
