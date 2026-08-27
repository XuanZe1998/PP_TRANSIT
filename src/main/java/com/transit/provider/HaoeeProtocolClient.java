package com.transit.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.model.Channel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.client.MultipartBodyBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class HaoeeProtocolClient {
    private final WebClient webClient;

    public HaoeeProtocolClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> invoke(Channel channel, String providerModel, String path,
                                 HttpMethod method, JsonNode body) {
        String url = endpoint(channel, path);
        WebClient.RequestBodySpec request = webClient.method(method).uri(url)
                .header(HttpHeaders.AUTHORIZATION, HaoeeGateway.authorization(channel.getApiKey()))
                .header("ModelName", providerModel)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        return request.bodyValue(body).retrieve().bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> query(Channel channel, String providerModel, String path,
                                String method, String upstreamTaskId) {
        if ("GET".equalsIgnoreCase(method)) {
            String url = UriComponentsBuilder.fromUriString(endpoint(channel, path))
                    .queryParam("task_id", upstreamTaskId).build().encode().toUriString();
            return webClient.get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, HaoeeGateway.authorization(channel.getApiKey()))
                    .header("ModelName", providerModel)
                    .accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(JsonNode.class);
        }
        ObjectNode payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        payload.put("task_id", upstreamTaskId);
        payload.put("id", upstreamTaskId);
        return invoke(channel, providerModel, path, HttpMethod.POST, payload);
    }

    public Flux<String> stream(Channel channel, String providerModel, String path, JsonNode body) {
        return webClient.post().uri(endpoint(channel, path))
                .header(HttpHeaders.AUTHORIZATION, HaoeeGateway.authorization(channel.getApiKey()))
                .header("ModelName", providerModel)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body).retrieve().bodyToFlux(String.class);
    }

    public Mono<JsonNode> multipart(Channel channel, String providerModel, String path,
                                    String fileName, String contentType, byte[] file,
                                    java.util.Map<String, String> fields) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.asyncPart("file", Mono.just(file), byte[].class)
                .filename(fileName == null ? "audio.bin" : fileName)
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType));
        fields.forEach(parts::part);
        return webClient.post().uri(endpoint(channel, path))
                .header(HttpHeaders.AUTHORIZATION, HaoeeGateway.authorization(channel.getApiKey()))
                .header("ModelName", providerModel)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(parts.build()))
                .retrieve().bodyToMono(JsonNode.class);
    }

    private String endpoint(Channel channel, String path) {
        if (path == null || !path.startsWith("/") || path.contains("..") || path.contains("://")) {
            throw new IllegalArgumentException("Invalid Haoee endpoint path");
        }
        return HaoeeGateway.endpoint(channel, path);
    }
}
