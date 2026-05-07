package com.transit.provider;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.model.Channel;
import reactor.core.publisher.Mono;

public interface ProviderGateway {
    boolean supports(String providerType);

    Mono<ChatResponse> chatCompletions(Channel channel, ChatRequest request, String publicModel, String providerModel);
}
