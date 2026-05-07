package com.transit.provider;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.model.Channel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Set;

@Component
public class OpenAiCompatibleGateway implements ProviderGateway {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "openai",
            "deepseek",
            "xai",
            "openrouter",
            "siliconflow",
            "aliyun-compatible",
            "tencent-compatible"
    );

    private final WebClient webClient;

    public OpenAiCompatibleGateway(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public boolean supports(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return true;
        }
        return SUPPORTED_TYPES.contains(providerType.toLowerCase(Locale.ROOT));
    }

    @Override
    public Mono<ChatResponse> chatCompletions(Channel channel, ChatRequest request, String publicModel, String providerModel) {
        request.setModel(providerModel);
        return webClient.post()
                .uri(channel.getBaseUrl() + "/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class);
    }
}
