package com.transit.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.model.Channel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

@Component
public class OpenAiCompatibleGateway implements ProviderGateway {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "openai",
            "openai-compatible",
            "deepseek",
            "xai",
            "openrouter",
            "siliconflow",
            "aliyun-compatible",
            "tencent-compatible",
            "qwen",
            "kimi",
            "glm",
            "mistral",
            "meta",
            "nvidia"
    );
    private static final Set<String> NVIDIA_NON_STREAMING_MODELS = Set.of(
            "deepseek-ai/deepseek-v4-flash-0731",
            "google/diffusiongemma-26b-a4b-it",
            "google/gemma-4-31b-it",
            "minimaxai/minimax-m3",
            "mistralai/mistral-nemotron",
            "stepfun-ai/step-3.7-flash"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleGateway(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
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
        if ("nvidia".equalsIgnoreCase(channel.getType())) {
            if (NVIDIA_NON_STREAMING_MODELS.contains(providerModel)) {
                return nvidiaNonStreamingCompletion(channel, request, providerModel);
            }
            return nvidiaStreamingCompletion(channel, request, providerModel);
        }
        return webClient.post()
                .uri(chatCompletionsUrl(channel))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ChatResponse.class);
    }

    private Mono<ChatResponse> nvidiaNonStreamingCompletion(Channel channel, ChatRequest request,
                                                             String providerModel) {
        ObjectNode payload = objectMapper.valueToTree(request);
        removeNullFields(payload);
        payload.put("stream", false);
        return webClient.post()
                .uri(chatCompletionsUrl(channel))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(ChatResponse.class);
    }

    /**
     * NVIDIA NIM currently requires SSE for a number of catalog models even
     * when the caller wants a normal OpenAI non-streaming response. Consume the
     * upstream stream here and return one aggregated ChatResponse so the public
     * gateway contract does not change.
     */
    private Mono<ChatResponse> nvidiaStreamingCompletion(Channel channel, ChatRequest request, String providerModel) {
        ObjectNode payload = objectMapper.valueToTree(request);
        removeNullFields(payload);
        payload.put("stream", true);
        if (!payload.hasNonNull("temperature")) {
            payload.put("temperature", 1.0);
        }
        if (!payload.hasNonNull("top_p")) {
            payload.put("top_p", providerModel.startsWith("google/gemma-") ? 0.95 : 1.0);
        }
        if (providerModel.startsWith("google/gemma-")) {
            ObjectNode options = payload.putObject("chat_template_kwargs");
            options.put("enable_thinking", true);
        }
        if (providerModel.startsWith("z-ai/glm-")) {
            payload.put("seed", 42);
        }

        return webClient.post()
                .uri(chatCompletionsUrl(channel))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + channel.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data.trim()))
                .map(this::parseStreamChunk)
                .collect(NvidiaStreamAccumulator::new, NvidiaStreamAccumulator::add)
                .map(accumulator -> accumulator.toResponse(providerModel));
    }

    private void removeNullFields(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            java.util.List<String> nullFields = new ArrayList<>();
            object.fields().forEachRemaining(entry -> {
                if (entry.getValue() == null || entry.getValue().isNull()) {
                    nullFields.add(entry.getKey());
                } else {
                    removeNullFields(entry.getValue());
                }
            });
            object.remove(nullFields);
        } else if (node.isArray()) {
            node.forEach(this::removeNullFields);
        }
    }

    private JsonNode parseStreamChunk(String data) {
        String json = data.trim();
        if (json.startsWith("data:")) {
            json = json.substring(5).trim();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("NVIDIA returned an invalid SSE chunk", exception);
        }
    }

    private String chatCompletionsUrl(Channel channel) {
        String baseUrl = channel.getBaseUrl() == null ? "" : channel.getBaseUrl().replaceAll("/+$", "");
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        String type = channel.getType() == null ? "" : channel.getType().toLowerCase(Locale.ROOT);
        if ("deepseek".equals(type) || baseUrl.contains("api.deepseek.com")) {
            return baseUrl + "/chat/completions";
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/chat/completions";
        }
        return baseUrl + "/v1/chat/completions";
    }

    private final class NvidiaStreamAccumulator {
        private String id;
        private String object;
        private Long created;
        private String model;
        private Integer finishIndex = 0;
        private String finishReason;
        private ChatResponse.Usage usage;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();

        void add(JsonNode chunk) {
            if (chunk == null || chunk.isNull()) return;
            if (chunk.hasNonNull("id")) id = chunk.get("id").asText();
            if (chunk.hasNonNull("object")) object = chunk.get("object").asText();
            if (chunk.hasNonNull("created")) created = chunk.get("created").asLong();
            if (chunk.hasNonNull("model")) model = chunk.get("model").asText();
            if (chunk.hasNonNull("usage")) {
                usage = objectMapper.convertValue(chunk.get("usage"), ChatResponse.Usage.class);
            }
            JsonNode choices = chunk.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) return;
            JsonNode choice = choices.get(0);
            if (choice.hasNonNull("index")) finishIndex = choice.get("index").asInt();
            if (choice.hasNonNull("finish_reason")) finishReason = choice.get("finish_reason").asText();
            JsonNode delta = choice.get("delta");
            if (delta == null || !delta.isObject()) return;
            if (delta.hasNonNull("content")) content.append(delta.get("content").asText());
            if (delta.hasNonNull("reasoning_content")) reasoning.append(delta.get("reasoning_content").asText());
            if (delta.hasNonNull("reasoning")) reasoning.append(delta.get("reasoning").asText());
        }

        ChatResponse toResponse(String providerModel) {
            ChatResponse response = new ChatResponse();
            response.setId(id == null ? "chatcmpl-nvidia-aggregated" : id);
            response.setObject("chat.completion");
            response.setCreated(created == null ? Instant.now().getEpochSecond() : created);
            response.setModel(model == null ? providerModel : model);
            response.setUsage(usage);

            ChatResponse.Message message = new ChatResponse.Message();
            message.setRole("assistant");
            String finalReasoning = reasoning.toString();
            message.setReasoningContent(finalReasoning.isBlank() ? null : finalReasoning);
            message.setContent(!content.isEmpty() ? content.toString() : finalReasoning);
            ChatResponse.Choice choice = new ChatResponse.Choice();
            choice.setIndex(finishIndex);
            choice.setMessage(message);
            choice.setFinishReason(finishReason);
            response.setChoices(new ArrayList<>(java.util.List.of(choice)));
            return response;
        }
    }
}
