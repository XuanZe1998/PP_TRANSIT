package com.transit.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

@Configuration
public class WebClientConfig {
    @Bean(destroyMethod = "dispose")
    public ConnectionProvider upstreamConnectionProvider(
            @Value("${gateway.http.max-connections:200}") int maxConnections,
            @Value("${gateway.http.pending-acquire-max-count:500}") int pendingAcquireMaxCount,
            @Value("${gateway.http.pending-acquire-timeout-ms:10000}") long pendingAcquireTimeoutMs) {
        return ConnectionProvider.builder("gateway-upstream")
                .maxConnections(Math.max(10, maxConnections))
                .pendingAcquireMaxCount(Math.max(0, pendingAcquireMaxCount))
                .pendingAcquireTimeout(Duration.ofMillis(Math.max(100, pendingAcquireTimeoutMs)))
                .maxIdleTime(Duration.ofSeconds(60))
                .maxLifeTime(Duration.ofMinutes(10))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder,
                               ConnectionProvider upstreamConnectionProvider,
                               @Value("${gateway.http.connect-timeout-ms:10000}") int connectTimeoutMs,
                               @Value("${gateway.http.response-timeout-seconds:90}") long responseTimeoutSeconds,
                               @Value("${gateway.http.max-in-memory-bytes:8388608}") int maxInMemoryBytes) {
        HttpClient client = HttpClient.create(upstreamConnectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(1_000, connectTimeoutMs))
                .responseTimeout(Duration.ofSeconds(Math.max(3, responseTimeoutSeconds)));
        return builder
                .clientConnector(new ReactorClientHttpConnector(client))
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize(Math.max(1_048_576, maxInMemoryBytes)))
                .build();
    }
}
