package com.transit.controller;

import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.service.ClientIpResolver;
import com.transit.service.TransitService;
import com.transit.service.UniversalModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class AnthropicMessagesController {
    private final TransitService transit;
    private final ClientIpResolver clientIps;
    @Autowired(required = false) private UniversalModelService universal;
    @Autowired(required = false) private ObjectMapper json;

    @PostMapping(value = "/v1/messages")
    public Mono<ResponseEntity<?>> create(@RequestHeader(value="x-api-key", required=false) String apiKey,
                                           @RequestHeader(value="anthropic-version", required=false) String version,
                                           @RequestBody Map<String,Object> body, HttpServletRequest servletRequest) {
        if (apiKey == null || apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少 x-api-key");
        if (!"2023-06-01".equals(version)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "anthropic-version 必须为 2023-06-01");
        boolean stream = Boolean.TRUE.equals(body.get("stream"));
        if (universal != null && universal.hasHaoeeRoute(Objects.toString(body.get("model"), ""), "messages")) {
            ObjectNode raw = json.valueToTree(body);
            if (stream) return Mono.just(ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noCache()).body(universal.streamProtocol("Bearer " + apiKey.trim(),
                            clientIps.resolve(servletRequest), "messages", "/v1/messages", raw)));
            return universal.invoke("Bearer " + apiKey.trim(), clientIps.resolve(servletRequest), "messages", "/v1/messages", raw, servletRequest.getHeader("Idempotency-Key"))
                    .map(value -> (ResponseEntity<?>) ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(value));
        }
        ChatRequest request = convert(body);
        return transit.chatCompletions("Bearer " + apiKey.trim(), request, clientIps.resolve(servletRequest))
                .map(response -> stream ? ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(events(response))
                        : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(message(response)));
    }

    @PostMapping(value = "/v1/messages/count_tokens")
    public Mono<ResponseEntity<JsonNode>> countTokens(@RequestHeader(value="x-api-key", required=false) String apiKey,
            @RequestHeader(value="anthropic-version", required=false) String version, @RequestBody ObjectNode body, HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少 x-api-key");
        if (!"2023-06-01".equals(version)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "anthropic-version 必须为 2023-06-01");
        return universal.invoke("Bearer " + apiKey.trim(), clientIps.resolve(request), "count-tokens", "/v1/messages/count_tokens", body,
                request.getHeader("Idempotency-Key")).map(ResponseEntity::ok);
    }

    private ChatRequest convert(Map<String,Object> body) {
        String model = Objects.toString(body.get("model"), "").trim();
        int maxTokens;
        try { maxTokens = ((Number) body.get("max_tokens")).intValue(); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "max_tokens 为必填整数"); }
        if (model.isBlank() || maxTokens < 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "model 和正数 max_tokens 为必填项");
        ChatRequest out = new ChatRequest(); out.setModel(model); out.setMaxTokens(maxTokens); out.setStream(false);
        if (body.get("temperature") instanceof Number n) out.setTemperature(n.doubleValue());
        if (body.get("top_p") instanceof Number n) out.setTopP(n.doubleValue());
        List<ChatRequest.Message> messages = new ArrayList<>();
        String system = extractText(body.get("system"));
        if (!system.isBlank()) messages.add(message("system", system));
        if (!(body.get("messages") instanceof List<?> input) || input.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 不能为空");
        for (Object value : input) {
            if (!(value instanceof Map<?,?> row)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "messages 格式无效");
            String role = Objects.toString(row.get("role"), "");
            if (!List.of("user", "assistant").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Anthropic 消息角色仅支持 user/assistant");
            messages.add(message(role, extractText(row.get("content"))));
        }
        out.setMessages(messages); return out;
    }

    private ChatRequest.Message message(String role, String content) { ChatRequest.Message m = new ChatRequest.Message(); m.setRole(role); m.setContent(content); return m; }
    private String extractText(Object value) {
        if (value == null) return ""; if (value instanceof String text) return text;
        if (value instanceof List<?> blocks) {
            StringBuilder out = new StringBuilder();
            for (Object block : blocks) {
                if (!(block instanceof Map<?,?> map) || !"text".equals(map.get("type")))
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前兼容入口仅支持 Anthropic text 内容块");
                if (!out.isEmpty()) out.append('\n'); out.append(Objects.toString(map.get("text"), ""));
            }
            return out.toString();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容格式无效");
    }

    private Map<String,Object> message(ChatResponse response) {
        String text = response.getChoices() == null || response.getChoices().isEmpty() || response.getChoices().get(0).getMessage() == null
                ? "" : Objects.toString(response.getChoices().get(0).getMessage().getContent(), "");
        String stop = response.getChoices() == null || response.getChoices().isEmpty() ? "end_turn" :
                "length".equals(response.getChoices().get(0).getFinishReason()) ? "max_tokens" : "end_turn";
        Map<String,Object> usage = Map.of("input_tokens", response.getUsage()==null?0:Objects.requireNonNullElse(response.getUsage().getPromptTokens(),0),
                "output_tokens", response.getUsage()==null?0:Objects.requireNonNullElse(response.getUsage().getCompletionTokens(),0));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", Objects.toString(response.getId(), "msg_" + Instant.now().toEpochMilli()));
        result.put("type", "message"); result.put("role", "assistant");
        result.put("model", Objects.toString(response.getModel(), ""));
        result.put("content", List.of(Map.of("type","text","text",text)));
        result.put("stop_reason", stop); result.put("stop_sequence", null); result.put("usage", usage);
        return result;
    }

    private Flux<ServerSentEvent<Map<String,Object>>> events(ChatResponse response) {
        Map<String,Object> complete = message(response); String id = complete.get("id").toString();
        @SuppressWarnings("unchecked") String text = ((Map<String,Object>)((List<?>)complete.get("content")).get(0)).get("text").toString();
        return Flux.just(
                event("message_start", Map.of("type","message_start","message",Map.of("id",id,"type","message","role","assistant","model",complete.get("model"),"content",List.of(),"stop_reason","","stop_sequence","","usage",Map.of("input_tokens",((Map<?,?>)complete.get("usage")).get("input_tokens"),"output_tokens",0)))),
                event("content_block_start", Map.of("type","content_block_start","index",0,"content_block",Map.of("type","text","text",""))),
                event("content_block_delta", Map.of("type","content_block_delta","index",0,"delta",Map.of("type","text_delta","text",text))),
                event("content_block_stop", Map.of("type","content_block_stop","index",0)),
                event("message_delta", Map.of("type","message_delta","delta",Map.of("stop_reason",complete.get("stop_reason"),"stop_sequence",""),"usage",Map.of("output_tokens",((Map<?,?>)complete.get("usage")).get("output_tokens")))),
                event("message_stop", Map.of("type","message_stop"))
        );
    }
    private ServerSentEvent<Map<String,Object>> event(String name, Map<String,Object> data) { return ServerSentEvent.<Map<String,Object>>builder(data).event(name).build(); }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> anthropicError(ResponseStatusException error) {
        HttpStatusCode status = error.getStatusCode();
        String type = switch (status.value()) {
            case 400, 405, 413, 422 -> "invalid_request_error";
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "api_error";
        };
        String message = error.getReason() == null ? "请求处理失败" : error.getReason();
        return ResponseEntity.status(status).body(Map.of("type", "error", "error", Map.of("type", type, "message", message)));
    }
}
