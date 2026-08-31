package com.transit.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.model.Channel;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.transit.service.UpstreamProxyHttpClientFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
public class AnthropicGateway implements ProviderGateway {

    private final WebClient webClient;
    @Autowired(required = false) private UpstreamProxyHttpClientFactory proxyClients;

    public AnthropicGateway(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String providerType) {
        return providerType != null
                && ("anthropic".equalsIgnoreCase(providerType)
                || "deepseek-anthropic".equalsIgnoreCase(providerType));
    }

    @Override
    public Mono<ChatResponse> chatCompletions(Channel channel, ChatRequest request, String publicModel, String providerModel) {
        AnthropicRequest payload = toAnthropicRequest(request, providerModel);
        WebClient.RequestBodySpec call = client(channel).post()
                .uri(channel.getBaseUrl() + "/v1/messages")
                .header("anthropic-version", "2023-06-01");
        return ProviderAuthentication.apply(call, channel, "x-api-key")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(AnthropicResponse.class)
                .map(response -> toChatResponse(response, providerModel));
    }

    private AnthropicRequest toAnthropicRequest(ChatRequest request, String providerModel) {
        AnthropicRequest payload = new AnthropicRequest();
        payload.setModel(providerModel);
        payload.setStream(request.isStream());
        payload.setTemperature(request.getTemperature());
        payload.setTopP(request.getTopP());
        payload.setMaxTokens(request.getMaxTokens() == null ? 4096 : request.getMaxTokens());
        String systemPrompt = ProviderMessageSupport.extractSystemPrompt(request.getMessages());
        payload.setSystem(systemPrompt.isBlank() ? null : systemPrompt);
        payload.setMessages(ProviderMessageSupport.nonSystemMessages(request.getMessages()).stream()
                .map(message -> {
                    AnthropicMessage item = new AnthropicMessage();
                    item.setRole(normalizeRole(message.getRole()));
                    AnthropicContent content = new AnthropicContent();
                    content.setType("text");
                    content.setText(ProviderMessageSupport.toPlainText(message.getContent()));
                    item.setContent(List.of(content));
                    return item;
                })
                .toList());
        return payload;
    }

    private WebClient client(Channel channel) { return proxyClients == null || channel.getAuthContext() == null ? webClient : proxyClients.client(channel.getAuthContext().upstreamProxyId()); }

    private ChatResponse toChatResponse(AnthropicResponse response, String providerModel) {
        ChatResponse result = new ChatResponse();
        result.setId(response.getId());
        result.setObject("chat.completion");
        result.setCreated(Instant.now().getEpochSecond());
        result.setModel(providerModel);

        ChatResponse.Message message = new ChatResponse.Message();
        message.setRole("assistant");
        message.setContent(response.getContent() == null ? "" : response.getContent().stream()
                .map(AnthropicContent::getText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(""));

        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(response.getStopReason());
        result.setChoices(List.of(choice));

        ChatResponse.Usage usage = new ChatResponse.Usage();
        if (response.getUsage() != null) {
            int uncachedInput = response.getUsage().getInputTokens() == null ? 0 : response.getUsage().getInputTokens();
            int output = response.getUsage().getOutputTokens() == null ? 0 : response.getUsage().getOutputTokens();
            int cacheRead = response.getUsage().getCacheReadInputTokens() == null ? 0 : response.getUsage().getCacheReadInputTokens();
            int cacheWrite = response.getUsage().getCacheCreationInputTokens() == null ? 0 : response.getUsage().getCacheCreationInputTokens();
            int prompt = Math.addExact(uncachedInput, Math.addExact(cacheRead, cacheWrite));
            usage.setPromptTokens(prompt);
            usage.setCompletionTokens(output);
            usage.setCacheReadInputTokens(cacheRead);
            usage.setCacheCreationInputTokens(cacheWrite);
            usage.setTotalTokens(Math.addExact(prompt, output));
        }
        result.setUsage(usage);
        return result;
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        String normalized = role.toLowerCase(Locale.ROOT);
        if ("assistant".equals(normalized)) {
            return "assistant";
        }
        return "user";
    }

    @Data
    private static class AnthropicRequest {
        private String model;
        private String system;
        private List<AnthropicMessage> messages;
        private boolean stream;
        private Double temperature;
        @JsonProperty("top_p")
        private Double topP;
        @JsonProperty("max_tokens")
        private Integer maxTokens;
    }

    @Data
    private static class AnthropicMessage {
        private String role;
        private List<AnthropicContent> content;
    }

    @Data
    private static class AnthropicContent {
        private String type;
        private String text;
    }

    @Data
    private static class AnthropicResponse {
        private String id;
        private List<AnthropicContent> content;
        @JsonProperty("stop_reason")
        private String stopReason;
        private AnthropicUsage usage;
    }

    @Data
    private static class AnthropicUsage {
        @JsonProperty("input_tokens")
        private Integer inputTokens;
        @JsonProperty("output_tokens")
        private Integer outputTokens;
        @JsonProperty("cache_read_input_tokens")
        private Integer cacheReadInputTokens;
        @JsonProperty("cache_creation_input_tokens")
        private Integer cacheCreationInputTokens;
    }
}
