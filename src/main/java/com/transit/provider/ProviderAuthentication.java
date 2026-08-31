package com.transit.provider;

import com.transit.model.Channel;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

final class ProviderAuthentication {
    private ProviderAuthentication() {}
    static String secret(Channel channel) {
        return channel.getAuthContext() == null ? channel.getApiKey() : channel.getAuthContext().bearerOrApiKey();
    }
    static WebClient.RequestBodySpec apply(WebClient.RequestBodySpec request, Channel channel, String apiKeyHeader) {
        if (channel.getAuthContext() != null && channel.getAuthContext().oauth()) return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + secret(channel));
        return request.header(apiKeyHeader, secret(channel));
    }
}
