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
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
public class GeminiGateway implements ProviderGateway {

    private final WebClient webClient;
    @Autowired(required = false) private UpstreamProxyHttpClientFactory proxyClients;

    public GeminiGateway(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String providerType) {
        return providerType != null && ("gemini".equalsIgnoreCase(providerType) || "google".equalsIgnoreCase(providerType));
    }

    @Override
    public Mono<ChatResponse> chatCompletions(Channel channel, ChatRequest request, String publicModel, String providerModel) {
        GeminiRequest payload = toGeminiRequest(request);
        WebClient.RequestBodySpec call = client(channel).post()
                .uri(UriComponentsBuilder.fromUriString(channel.getBaseUrl())
                        .pathSegment("v1beta", "models", providerModel + ":generateContent")
                        .build().encode().toUri());
        return ProviderAuthentication.apply(call, channel, "x-goog-api-key")
                // Keeping credentials out of the URL prevents them from leaking
                // through access logs, proxies, browser history, and traces.
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(GeminiResponse.class)
                .map(response -> toChatResponse(response, providerModel));
    }

    private GeminiRequest toGeminiRequest(ChatRequest request) {
        GeminiRequest payload = new GeminiRequest();
        String systemPrompt = ProviderMessageSupport.extractSystemPrompt(request.getMessages());
        if (!systemPrompt.isBlank()) {
            GeminiContent instruction = new GeminiContent();
            GeminiPart instructionPart = new GeminiPart();
            instructionPart.setText(systemPrompt);
            instruction.setParts(List.of(instructionPart));
            payload.setSystemInstruction(instruction);
        }
        payload.setContents(ProviderMessageSupport.nonSystemMessages(request.getMessages()).stream()
                .map(message -> {
                    GeminiContent content = new GeminiContent();
                    content.setRole(normalizeRole(message.getRole()));
                    GeminiPart part = new GeminiPart();
                    part.setText(ProviderMessageSupport.toPlainText(message.getContent()));
                    content.setParts(List.of(part));
                    return content;
                })
                .toList());
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.setTemperature(request.getTemperature());
        config.setTopP(request.getTopP());
        config.setMaxOutputTokens(request.getMaxTokens());
        payload.setGenerationConfig(config);
        return payload;
    }

    private WebClient client(Channel channel) { return proxyClients == null || channel.getAuthContext() == null ? webClient : proxyClients.client(channel.getAuthContext().upstreamProxyId()); }

    private ChatResponse toChatResponse(GeminiResponse response, String providerModel) {
        ChatResponse result = new ChatResponse();
        result.setId("gemini-" + Instant.now().toEpochMilli());
        result.setObject("chat.completion");
        result.setCreated(Instant.now().getEpochSecond());
        result.setModel(providerModel);

        String content = "";
        String finishReason = null;
        if (response.getCandidates() != null && !response.getCandidates().isEmpty()) {
            GeminiCandidate first = response.getCandidates().get(0);
            finishReason = first.getFinishReason();
            if (first.getContent() != null && first.getContent().getParts() != null) {
                content = first.getContent().getParts().stream()
                        .map(GeminiPart::getText)
                        .filter(text -> text != null && !text.isBlank())
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
            }
        }

        ChatResponse.Message message = new ChatResponse.Message();
        message.setRole("assistant");
        message.setContent(content);

        ChatResponse.Choice choice = new ChatResponse.Choice();
        choice.setIndex(0);
        choice.setMessage(message);
        choice.setFinishReason(finishReason);
        result.setChoices(List.of(choice));

        ChatResponse.Usage usage = new ChatResponse.Usage();
        if (response.getUsageMetadata() != null) {
            usage.setPromptTokens(response.getUsageMetadata().getPromptTokenCount());
            usage.setCompletionTokens(response.getUsageMetadata().getCandidatesTokenCount());
            Integer prompt = response.getUsageMetadata().getPromptTokenCount();
            Integer completion = response.getUsageMetadata().getCandidatesTokenCount();
            usage.setTotalTokens((prompt == null ? 0 : prompt) + (completion == null ? 0 : completion));
        }
        result.setUsage(usage);
        return result;
    }

    private String normalizeRole(String role) {
        if (role == null) {
            return "user";
        }
        return "assistant".equalsIgnoreCase(role) ? "model" : "user";
    }

    @Data
    private static class GeminiRequest {
        private GeminiContent systemInstruction;
        private List<GeminiContent> contents;
        private GeminiGenerationConfig generationConfig;
    }

    @Data
    private static class GeminiContent {
        private String role;
        private List<GeminiPart> parts;
    }

    @Data
    private static class GeminiPart {
        private String text;
    }

    @Data
    private static class GeminiGenerationConfig {
        private Double temperature;
        @JsonProperty("topP")
        private Double topP;
        @JsonProperty("maxOutputTokens")
        private Integer maxOutputTokens;
    }

    @Data
    private static class GeminiResponse {
        private List<GeminiCandidate> candidates;
        private GeminiUsageMetadata usageMetadata;
    }

    @Data
    private static class GeminiCandidate {
        private GeminiContent content;
        private String finishReason;
    }

    @Data
    private static class GeminiUsageMetadata {
        private Integer promptTokenCount;
        private Integer candidatesTokenCount;
    }
}
