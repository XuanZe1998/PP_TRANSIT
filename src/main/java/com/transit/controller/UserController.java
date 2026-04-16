package com.transit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.TokenMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Log;
import com.transit.model.Token;
import com.transit.model.User;
import com.transit.service.OAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;
    private final TokenMapper tokenMapper;
    private final LogMapper logMapper;
    private final OAuthService oauthService;

    private User getUserFromToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            return oauthService.getUserFromToken(token);
        }
        throw new RuntimeException("Unauthorized");
    }

    @GetMapping("/profile")
    public Mono<User> getProfile(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = getUserFromToken(authHeader);
        return Mono.just(user);
    }

    @GetMapping("/tokens")
    public Flux<Token> getTokens(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = getUserFromToken(authHeader);
        return Flux.fromIterable(tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getUserId, user.getId())));
    }

    @GetMapping("/logs")
    public Flux<Log> getLogs(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = getUserFromToken(authHeader);
        return Flux.fromIterable(logMapper.selectList(new LambdaQueryWrapper<Log>().eq(Log::getUserId, user.getId()).orderByDesc(Log::getCreatedAt)));
    }

    @GetMapping("/stats")
    public Mono<Map<String, Object>> getStats(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        User user = getUserFromToken(authHeader);
        
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
        User user = getUserFromToken(authHeader);
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
        User user = getUserFromToken(authHeader);
        return Mono.fromCallable(() -> {
            Token token = new Token();
            token.setName(req.getName());
            token.setTotalQuota(req.getTotalQuota());
            token.setEnabled(true);
            token.setKey(req.getKey() == null || req.getKey().isEmpty()
                    ? "sk-" + UUID.randomUUID().toString().replace("-", "")
                    : req.getKey());
            token.setUserId(user.getId());
            tokenMapper.insert(token);
            return token;
        });
    }

    @PutMapping("/tokens/{id}")
    public Mono<Token> updateUserToken(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
                                       @PathVariable Long id,
                                       @RequestBody Token req) {
        User user = getUserFromToken(authHeader);
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
        User user = getUserFromToken(authHeader);
        return Mono.fromRunnable(() -> {
            Token token = tokenMapper.selectById(id);
            if (token != null && token.getUserId().equals(user.getId())) {
                tokenMapper.deleteById(id);
            }
        });
    }
}
