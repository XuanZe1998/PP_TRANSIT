package com.transit.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.model.Channel;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.transit.service.UpstreamProxyHttpClientFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.client.MultipartBodyBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class HaoeeProtocolClient {
    private final WebClient webClient;
    @Autowired(required = false) private UpstreamProxyHttpClientFactory proxyClients;

    public HaoeeProtocolClient(WebClient webClient) {
        this.webClient = webClient;
    }

    public Mono<JsonNode> invoke(Channel channel, String providerModel, String path,
                                 HttpMethod method, JsonNode body) {
        String url = endpoint(channel, path);
        WebClient.RequestBodySpec request = client(channel).method(method).uri(url)
                .header(HttpHeaders.AUTHORIZATION, authorization(channel))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
        applyProviderHeaders(request, channel, path);
        if (haoee(channel)) request.header("ModelName", providerModel);
        return request.bodyValue(body).retrieve().bodyToMono(JsonNode.class);
    }

    public Mono<JsonNode> query(Channel channel, String providerModel, String path,
                                String method, String upstreamTaskId) {
        if ("GET".equalsIgnoreCase(method)) {
            String url = UriComponentsBuilder.fromUriString(endpoint(channel, path))
                    .queryParam("task_id", upstreamTaskId).build().encode().toUriString();
            return client(channel).get().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, authorization(channel))
                    .headers(headers -> modelHeader(headers, channel, providerModel))
                    .accept(MediaType.APPLICATION_JSON).retrieve().bodyToMono(JsonNode.class);
        }
        ObjectNode payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        payload.put("task_id", upstreamTaskId);
        payload.put("id", upstreamTaskId);
        return invoke(channel, providerModel, path, HttpMethod.POST, payload);
    }

    public Flux<String> stream(Channel channel, String providerModel, String path, JsonNode body) {
        return client(channel).post().uri(endpoint(channel, path))
                .header(HttpHeaders.AUTHORIZATION, authorization(channel))
                .headers(headers -> modelHeader(headers, channel, providerModel))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body).retrieve().bodyToFlux(String.class);
    }

    /**
     * Decodes a Responses stream as real SSE frames so event names are not lost.
     * The returned events are forwarded unchanged by the public gateway.
     */
    public Flux<ServerSentEvent<String>> streamEvents(Channel channel, String providerModel,
                                                       String path, JsonNode body) {
        return client(channel).post().uri(endpoint(channel, path))
                .header(HttpHeaders.AUTHORIZATION, authorization(channel))
                .headers(headers -> modelHeader(headers, channel, providerModel))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {});
    }

    public Mono<JsonNode> multipart(Channel channel, String providerModel, String path,
                                    String fileName, String contentType, byte[] file,
                                    java.util.Map<String, String> fields) {
        return multipart(channel, providerModel, path, "file", fileName, contentType, file, fields);
    }

    public Mono<JsonNode> multipart(Channel channel, String providerModel, String path, String partName,
                                    String fileName, String contentType, byte[] file,
                                    java.util.Map<String, String> fields) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.asyncPart(partName, Mono.just(file), byte[].class)
                .filename(fileName == null ? "audio.bin" : fileName)
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType));
        fields.forEach(parts::part);
        return client(channel).post().uri(endpoint(channel, path))
                .header(HttpHeaders.AUTHORIZATION, authorization(channel))
                .headers(headers -> modelHeader(headers, channel, providerModel))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(org.springframework.web.reactive.function.BodyInserters.fromMultipartData(parts.build()))
                .retrieve().bodyToMono(JsonNode.class);
    }

    private String endpoint(Channel channel, String path) {
        if (path == null || !path.startsWith("/") || path.contains("..") || path.contains("://")) {
            throw new IllegalArgumentException("Invalid upstream endpoint path");
        }
        return HaoeeGateway.endpoint(channel, path);
    }

    private String authorization(Channel channel) {
        if (channel.getAuthContext() != null && channel.getAuthContext().oauth()) return "Bearer " + channel.getAuthContext().accessToken();
        return HaoeeGateway.authorization(channel.getApiKey());
    }

    private WebClient client(Channel channel) { return proxyClients == null || channel.getAuthContext() == null ? webClient : proxyClients.client(channel.getAuthContext().upstreamProxyId()); }

    private boolean haoee(Channel channel) {
        if ("aiapibank".equalsIgnoreCase(channel.getSourceCode())) return false;
        return !channel.isManaged()
                || "haoee".equalsIgnoreCase(channel.getSourceCode())
                || "haoee".equalsIgnoreCase(channel.getType())
                || "haoee-openai".equalsIgnoreCase(channel.getType());
    }

    private void modelHeader(HttpHeaders headers, Channel channel, String model) {
        if (haoee(channel)) headers.set("ModelName", model);
        if (aiApiBankAnthropic(channel)) {
            headers.set("x-api-key", ProviderAuthentication.secret(channel));
            headers.set("anthropic-version", "2023-06-01");
        }
    }

    private void applyProviderHeaders(WebClient.RequestHeadersSpec<?> request, Channel channel, String path) {
        if (aiApiBankAnthropic(channel) && path.startsWith("/v1/messages")) {
            request.header("x-api-key", ProviderAuthentication.secret(channel));
            request.header("anthropic-version", "2023-06-01");
        }
    }

    private boolean aiApiBankAnthropic(Channel channel) {
        return "aiapibank".equalsIgnoreCase(channel.getSourceCode())
                && "anthropic".equalsIgnoreCase(channel.getType());
    }
}
