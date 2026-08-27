package com.transit.provider;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.model.Channel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Dedicated adapter for the Haoee MaaS OpenAI-compatible chat surface. */
@Component
public class HaoeeGateway implements ProviderGateway {
    private final WebClient webClient;

    public HaoeeGateway(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String providerType) {
        return "haoee".equalsIgnoreCase(providerType) || "haoee-openai".equalsIgnoreCase(providerType);
    }

    @Override
    public Mono<ChatResponse> chatCompletions(Channel channel, ChatRequest request,
                                              String publicModel, String providerModel) {
        request.setModel(providerModel);
        request.setStream(false);
        return webClient.post()
                .uri(endpoint(channel, "/v1/chat/completions"))
                .header(HttpHeaders.AUTHORIZATION, authorization(channel.getApiKey()))
                .header("ModelName", providerModel)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class);
    }

    static String endpoint(Channel channel, String fallback) {
        String base = channel.getBaseUrl() == null ? "" : channel.getBaseUrl().replaceAll("/+$", "");
        if (base.endsWith(fallback)) return base;
        if (base.endsWith("/v1") && fallback.startsWith("/v1/")) return base + fallback.substring(3);
        return base + fallback;
    }

    static String authorization(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value : "Bearer " + value;
    }
}
