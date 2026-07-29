package com.transit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.TokenMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Log;
import com.transit.model.ModelMapping;
import com.transit.model.Token;
import com.transit.model.User;
import com.transit.service.CurrentUserService;
import com.transit.service.ApiKeyService;
import com.transit.service.TransitService;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final TokenMapper tokenMapper;
    private final LogMapper logMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final CurrentUserService currentUserService;
    private final JdbcTemplate jdbcTemplate;
    private final ApiKeyService apiKeyService;
    private final TransitService transitService;

    @Value("${billing.default-max-output-tokens:4096}")
    private int playgroundMaxOutputTokens;

    @GetMapping("/profile")
    public Mono<Map<String, Object>> getProfile(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId());
        profile.put("username", user.getUsername());
        profile.put("email", user.getEmail());
        profile.put("phone", user.getPhone());
        profile.put("role", user.getRole());
        profile.put("status", user.getStatus());
        profile.put("authProvider", user.getAuthProvider());
        profile.put("createdAt", user.getCreatedAt());
        return Mono.just(profile);
    }

    @GetMapping("/tokens")
    public Flux<Map<String, Object>> getTokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(apiKeyService.listForUser(user.getId()));
    }

    @GetMapping("/logs")
    public Flux<Map<String, Object>> getLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(logMapper.selectList(new LambdaQueryWrapper<Log>()
                        .eq(Log::getUserId, user.getId()).orderByDesc(Log::getCreatedAt))
                .stream().map(this::logView).toList());
    }

    @GetMapping("/stats")
    public Mono<Map<String, Object>> getStats(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("balance", user.getBalance());
            
            Long totalTokensUsed = logMapper.selectList(new LambdaQueryWrapper<Log>().eq(Log::getUserId, user.getId()))
                    .stream().mapToLong(Log::getTotalTokens).sum();
            Long requestCount = logMapper.selectCount(new LambdaQueryWrapper<Log>().eq(Log::getUserId, user.getId()));
            
            stats.put("totalTokensUsed", totalTokensUsed);
            stats.put("requestCount", requestCount);
            return stats;
        });
    }

    @GetMapping("/billing/logs")
    public Flux<Map<String, Object>> billingLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                 @RequestParam(value = "model", required = false) String model,
                                                 @RequestParam(value = "tokenId", required = false) Long tokenId,
                                                 @RequestParam(value = "startDate", required = false) String startDate,
                                                 @RequestParam(value = "endDate", required = false) String endDate) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(queryBillingLogs(user.getId(), model, tokenId, startDate, endDate));
    }

    @GetMapping("/billing/summary")
    public Flux<Map<String, Object>> billingSummary(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                    @RequestParam(value = "model", required = false) String model,
                                                    @RequestParam(value = "tokenId", required = false) Long tokenId,
                                                    @RequestParam(value = "startDate", required = false) String startDate,
                                                    @RequestParam(value = "endDate", required = false) String endDate) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(queryBillingSummary(user.getId(), model, tokenId, startDate, endDate));
    }

    @PostMapping("/wallet/recharge")
    public Mono<Map<String, Object>> recharge(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                              @RequestParam("amount") long amount) {
        currentUserService.requireUser(authHeader);
        return Mono.error(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "Direct balance recharge is disabled; use a verified payment or redeem code"
        ));
    }

    @PostMapping("/tokens")
    public Mono<Map<String, Object>> createUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                     @RequestBody Token req) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> apiKeyService.issuedView(apiKeyService.issue(user.getId(), req)));
    }

    @PutMapping("/tokens/{id}")
    public Mono<Map<String, Object>> updateUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                     @PathVariable Long id,
                                                     @RequestBody Token req) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> apiKeyService.update(id, user.getId(), req, false));
    }

    @DeleteMapping("/tokens/{id}")
    public Mono<Void> deleteUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                      @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromRunnable(() -> apiKeyService.delete(id, user.getId()));
    }

    @GetMapping("/dashboard")
    public Mono<Map<String, Object>> getDashboard(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            List<Token> tokens = tokenMapper.selectList(new LambdaQueryWrapper<Token>()
                    .eq(Token::getUserId, user.getId())
                    .orderByDesc(Token::getCreatedAt));
            List<Log> logs = logMapper.selectList(new LambdaQueryWrapper<Log>()
                    .eq(Log::getUserId, user.getId())
                    .orderByDesc(Log::getCreatedAt));
            List<ModelMapping> mappings = modelMappingMapper.selectList(new LambdaQueryWrapper<ModelMapping>()
                    .eq(ModelMapping::isEnabled, true)
                    .orderByDesc(ModelMapping::getPriority));
            LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
            LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();

            Map<String, Object> stats = new HashMap<>();
            stats.put("balance", user.getBalance());
            stats.put("tokenCount", tokens.size());
            stats.put("enabledTokenCount", tokens.stream().filter(Token::isEnabled).count());
            stats.put("requestCount", logs.size());
            stats.put("todayRequestCount", logs.stream()
                    .filter(log -> log.getCreatedAt() != null && !log.getCreatedAt().isBefore(todayStart))
                    .count());
            stats.put("totalTokensUsed", logs.stream().mapToLong(Log::getTotalTokens).sum());
            stats.put("monthlyAmount", logs.stream()
                    .filter(log -> log.getCreatedAt() != null && !log.getCreatedAt().isBefore(monthStart))
                    .mapToLong(Log::getTotalAmount)
                    .sum());
            stats.put("successRequests", logs.stream().filter(log -> "SUCCESS".equalsIgnoreCase(log.getStatus())).count());
            stats.put("failedRequests", logs.stream().filter(log -> "FAILED".equalsIgnoreCase(log.getStatus())).count());
            stats.put("lastRequestAt", logs.isEmpty() ? null : logs.get(0).getCreatedAt());

            List<Map<String, Object>> tokenCards = tokens.stream().map(token -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", token.getId());
                item.put("name", token.getName());
                item.put("keyPreview", apiKeyService.keyPreview(token));
                item.put("enabled", token.isEnabled());
                item.put("usedQuota", token.getUsedQuota());
                item.put("totalQuota", token.getTotalQuota());
                item.put("expiredAt", token.getExpiredAt());
                item.put("createdAt", token.getCreatedAt());
                long requestCount = logs.stream().filter(log -> logBelongsToToken(log, token)).count();
                long tokenTotal = logs.stream().filter(log -> logBelongsToToken(log, token)).mapToLong(Log::getTotalTokens).sum();
                item.put("requestCount", requestCount);
                item.put("totalTokens", tokenTotal);
                return item;
            }).toList();

            List<Map<String, Object>> recentLogs = logs.stream().limit(20).map(log -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", log.getId());
                item.put("tokenId", log.getTokenId());
                item.put("model", log.getModel());
                item.put("promptTokens", log.getPromptTokens());
                item.put("completionTokens", log.getCompletionTokens());
                item.put("totalTokens", log.getTotalTokens());
                item.put("status", log.getStatus());
                item.put("createdAt", log.getCreatedAt());
                return item;
            }).toList();

            List<String> models = mappings.stream()
                    .map(ModelMapping::getPublicModelName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .sorted()
                    .toList();

            Map<String, Object> payload = new HashMap<>();
            payload.put("stats", stats);
            payload.put("tokens", tokenCards);
            payload.put("recentLogs", recentLogs);
            payload.put("models", models);
            payload.put("serverTime", LocalDateTime.now());
            return payload;
        });
    }

    @PostMapping("/playground")
    public Mono<ChatResponse> playground(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                         @RequestBody Map<String, Object> request) {
        User user = currentUserService.requireUser(authHeader);
        Set<String> allowedFields = Set.of("tokenId", "model", "prompt");
        if (request.keySet().stream().anyMatch(key -> !allowedFields.contains(key))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Playground accepts only tokenId, model and prompt; billing values are server controlled");
        }
        Long tokenId;
        try {
            tokenId = Long.valueOf(String.valueOf(request.get("tokenId")));
        } catch (Exception exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "tokenId is required");
        }
        Token token = tokenMapper.selectById(tokenId);
        if (token == null || token.getUserId() == null || !token.getUserId().equals(user.getId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "API Key not found");
        }

        String model = request.get("model") == null ? "" : request.get("model").toString().trim();
        String prompt = request.get("prompt") == null ? "" : request.get("prompt").toString().trim();
        if (prompt.isBlank() || prompt.length() > 20000) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Prompt must contain 1 to 20000 characters");
        }

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel(model);
        chatRequest.setStream(false);
        chatRequest.setMaxTokens(Math.max(64, Math.min(16_384, playgroundMaxOutputTokens)));
        ChatRequest.Message message = new ChatRequest.Message();
        message.setRole("user");
        message.setContent(prompt);
        chatRequest.setMessages(List.of(message));
        return transitService.chatCompletions(token, chatRequest, null);
    }

    @GetMapping("/tokens/{id}/examples")
    public Mono<Map<String, String>> getTokenExamples(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                                      @PathVariable Long id,
                                                      @RequestParam(value = "model", required = false) String model) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            Token token = tokenMapper.selectById(id);
            if (token == null || !token.getUserId().equals(user.getId())) {
                throw new RuntimeException("Token not found");
            }
            String selectedModel = (model == null || model.isBlank()) ? "YOUR_MODEL" : model;
            String baseUrl = "/api/v1/chat/completions";
            String apiKey = "YOUR_API_KEY";
            String prompt = "Write a concise summary of the latest request usage.";
            Map<String, String> examples = new HashMap<>();
            examples.put("curl",
                    "curl " + baseUrl + " \\\n" +
                            "  -H \"Content-Type: application/json\" \\\n" +
                            "  -H \"Authorization: Bearer " + apiKey + "\" \\\n" +
                            "  -d '{\n" +
                            "    \"model\": \"" + selectedModel + "\",\n" +
                            "    \"messages\": [{\"role\": \"user\", \"content\": \"" + prompt + "\"}],\n" +
                            "    \"stream\": false\n" +
                            "  }'");
            examples.put("javascript",
                    "const response = await fetch('" + baseUrl + "', {\n" +
                            "  method: 'POST',\n" +
                            "  headers: {\n" +
                            "    'Content-Type': 'application/json',\n" +
                            "    Authorization: 'Bearer " + apiKey + "'\n" +
                            "  },\n" +
                            "  body: JSON.stringify({\n" +
                            "    model: '" + selectedModel + "',\n" +
                            "    messages: [{ role: 'user', content: '" + prompt + "' }],\n" +
                            "    stream: false\n" +
                            "  })\n" +
                            "});\n" +
                            "const data = await response.json();\n" +
                            "console.log(data);");
            examples.put("python",
                    "import requests\n\n" +
                            "response = requests.post(\n" +
                            "    '" + baseUrl + "',\n" +
                            "    headers={\n" +
                            "        'Content-Type': 'application/json',\n" +
                            "        'Authorization': 'Bearer " + apiKey + "'\n" +
                            "    },\n" +
                            "    json={\n" +
                            "        'model': '" + selectedModel + "',\n" +
                            "        'messages': [{'role': 'user', 'content': '" + prompt + "'}],\n" +
                            "        'stream': False\n" +
                            "    }\n" +
                            ")\n" +
                            "print(response.json())");
            examples.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            return examples;
        });
    }

    private List<Map<String, Object>> queryBillingLogs(Long userId, String model, Long tokenId, String startDate, String endDate) {
        List<Object> params = new ArrayList<>();
        params.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.trace_id, l.token_key, t.id AS token_id, t.name AS token_name,
                       l.model, l.channel_id, l.prompt_tokens, l.completion_tokens, l.cached_tokens,
                       l.input_amount, l.output_amount, l.cached_amount, l.total_amount,
                       l.latency_ms, l.status, l.error_message, l.created_at
                FROM logs l
                 LEFT JOIN tokens t ON t.id = l.token_id OR (l.token_id IS NULL AND t.`key` = l.token_key)
                WHERE l.user_id = ?
                """);
        appendBillingFilters(sql, params, model, tokenId, startDate, endDate);
        sql.append(" ORDER BY l.created_at DESC LIMIT 500");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    private List<Map<String, Object>> queryBillingSummary(Long userId, String model, Long tokenId, String startDate, String endDate) {
        List<Object> params = new ArrayList<>();
        params.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT l.model,
                       COUNT(*) AS request_count,
                       SUM(CASE WHEN l.status = 'SUCCESS' THEN 1 ELSE 0 END) AS success_count,
                       COALESCE(SUM(l.prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(l.completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(l.cached_tokens), 0) AS cached_tokens,
                       COALESCE(SUM(l.input_amount), 0) AS input_amount,
                       COALESCE(SUM(l.output_amount), 0) AS output_amount,
                       COALESCE(SUM(l.cached_amount), 0) AS cached_amount,
                       COALESCE(SUM(l.total_amount), 0) AS total_amount
                FROM logs l
                 LEFT JOIN tokens t ON t.id = l.token_id OR (l.token_id IS NULL AND t.`key` = l.token_key)
                WHERE l.user_id = ?
                """);
        appendBillingFilters(sql, params, model, tokenId, startDate, endDate);
        sql.append(" GROUP BY l.model ORDER BY total_amount DESC, request_count DESC");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    private void appendBillingFilters(StringBuilder sql, List<Object> params, String model, Long tokenId, String startDate, String endDate) {
        if (model != null && !model.isBlank()) {
            sql.append(" AND l.model = ?");
            params.add(model);
        }
        if (tokenId != null) {
            sql.append(" AND t.id = ?");
            params.add(tokenId);
        }
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND l.created_at >= ?");
            params.add(startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND l.created_at <= ?");
            params.add(endDate);
        }
    }

    private boolean logBelongsToToken(Log log, Token token) {
        if (log.getTokenId() != null) return Objects.equals(log.getTokenId(), token.getId());
        return token.getKey() != null && token.getKey().equals(log.getTokenKey());
    }

    private Map<String, Object> logView(Log log) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", log.getId());
        item.put("traceId", log.getTraceId());
        item.put("tokenId", log.getTokenId());
        item.put("model", log.getModel());
        item.put("channelId", log.getChannelId());
        item.put("promptTokens", log.getPromptTokens());
        item.put("completionTokens", log.getCompletionTokens());
        item.put("cachedTokens", log.getCachedTokens());
        item.put("totalTokens", log.getTotalTokens());
        item.put("totalAmount", log.getTotalAmount());
        item.put("status", log.getStatus());
        item.put("latencyMs", log.getLatencyMs());
        item.put("errorMessage", log.getErrorMessage());
        item.put("createdAt", log.getCreatedAt());
        return item;
    }
}
