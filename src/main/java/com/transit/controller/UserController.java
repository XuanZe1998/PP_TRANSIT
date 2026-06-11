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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final TokenMapper tokenMapper;
    private final LogMapper logMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final CurrentUserService currentUserService;

    @GetMapping("/profile")
    public Mono<User> getProfile(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.just(user);
    }

    @GetMapping("/tokens")
    public Flux<Token> getTokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getUserId, user.getId())));
    }

    @GetMapping("/logs")
    public Flux<Log> getLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = currentUserService.requireUser(authHeader);
        return Flux.fromIterable(logMapper.selectList(new LambdaQueryWrapper<Log>().eq(Log::getUserId, user.getId()).orderByDesc(Log::getCreatedAt)));
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

    @PostMapping("/wallet/recharge")
    public Mono<Map<String, Object>> recharge(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                              @RequestParam("amount") long amount) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            user.setBalance(user.getBalance() + amount * 100000);
            userMapper.updateById(user);
            Map<String, Object> result = new HashMap<>();
            result.put("balance", user.getBalance());
            return result;
        });
    }

    @PostMapping("/tokens")
    public Mono<Token> createUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @RequestBody Token req) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            Token token = new Token();
            token.setName(req.getName());
            token.setTotalQuota(req.getTotalQuota());
            token.setEnabled(true);
            token.setKey("sk-user-" + UUID.randomUUID().toString().replace("-", ""));
            token.setUserId(user.getId());
            tokenMapper.insert(token);
            return token;
        });
    }

    @PutMapping("/tokens/{id}")
    public Mono<Token> updateUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @PathVariable Long id,
                                       @RequestBody Token req) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromCallable(() -> {
            Token token = tokenMapper.selectById(id);
            if (token == null || !token.getUserId().equals(user.getId())) {
                throw new RuntimeException("Not found");
            }
            token.setName(req.getName());
            token.setTotalQuota(req.getTotalQuota());
            token.setEnabled(req.isEnabled());
            tokenMapper.updateById(token);
            return token;
        });
    }

    @DeleteMapping("/tokens/{id}")
    public Mono<Void> deleteUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                      @PathVariable Long id) {
        User user = currentUserService.requireUser(authHeader);
        return Mono.fromRunnable(() -> {
            Token token = tokenMapper.selectById(id);
            if (token != null && token.getUserId().equals(user.getId())) {
                tokenMapper.deleteById(id);
            }
        });
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

            Map<String, Object> stats = new HashMap<>();
            stats.put("balance", user.getBalance());
            stats.put("requestCount", logs.size());
            stats.put("totalTokensUsed", logs.stream().mapToLong(Log::getTotalTokens).sum());
            stats.put("successRequests", logs.stream().filter(log -> "SUCCESS".equalsIgnoreCase(log.getStatus())).count());
            stats.put("failedRequests", logs.stream().filter(log -> "FAILED".equalsIgnoreCase(log.getStatus())).count());
            stats.put("lastRequestAt", logs.isEmpty() ? null : logs.get(0).getCreatedAt());

            List<Map<String, Object>> tokenCards = tokens.stream().map(token -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", token.getId());
                item.put("name", token.getName());
                item.put("key", token.getKey());
                item.put("enabled", token.isEnabled());
                item.put("usedQuota", token.getUsedQuota());
                item.put("totalQuota", token.getTotalQuota());
                item.put("expiredAt", token.getExpiredAt());
                item.put("createdAt", token.getCreatedAt());
                long requestCount = logs.stream().filter(log -> token.getKey().equals(log.getTokenKey())).count();
                long tokenTotal = logs.stream().filter(log -> token.getKey().equals(log.getTokenKey())).mapToLong(Log::getTotalTokens).sum();
                item.put("requestCount", requestCount);
                item.put("totalTokens", tokenTotal);
                return item;
            }).toList();

            List<Map<String, Object>> recentLogs = logs.stream().limit(20).map(log -> {
                Map<String, Object> item = new HashMap<>();
                item.put("id", log.getId());
                item.put("tokenKey", log.getTokenKey());
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
            String selectedModel = (model == null || model.isBlank()) ? "deepseek-chat" : model;
            String baseUrl = "/api/v1/chat/completions";
            String prompt = "Write a concise summary of the latest request usage.";
            Map<String, String> examples = new HashMap<>();
            examples.put("curl",
                    "curl " + baseUrl + " \\\n" +
                            "  -H \"Content-Type: application/json\" \\\n" +
                            "  -H \"Authorization: Bearer " + token.getKey() + "\" \\\n" +
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
                            "    Authorization: 'Bearer " + token.getKey() + "'\n" +
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
                            "        'Authorization': 'Bearer " + token.getKey() + "'\n" +
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
}
