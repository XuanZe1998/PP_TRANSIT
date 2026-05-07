package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.TokenMapper;
import com.transit.mapper.LogMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.Token;
import com.transit.model.Log;
import com.transit.provider.ProviderGateway;
import com.transit.provider.ProviderGatewayFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransitService {

    private final ModelMappingMapper modelMappingMapper;
    private final ChannelMapper channelMapper;
    private final TokenMapper tokenMapper;
    private final LogMapper logMapper;
    private final ProviderGatewayFactory providerGatewayFactory;

    public Mono<ChatResponse> chatCompletions(String authorization, ChatRequest request) {
        String tokenKey = extractToken(authorization);
        Token callerToken = validateToken(tokenKey);
        String publicModel = request.getModel();
        
        List<ModelMapping> mappings = modelMappingMapper.selectList(
            new LambdaQueryWrapper<ModelMapping>()
                .eq(ModelMapping::getPublicModelName, publicModel)
                .eq(ModelMapping::isEnabled, true)
                .orderByDesc(ModelMapping::getPriority)
        );

        if (mappings.isEmpty()) {
            return Mono.error(new RuntimeException("No available channel for model: " + publicModel));
        }

        ModelMapping mapping = mappings.get(0);
        Channel channel = channelMapper.selectById(mapping.getChannelId());
        
        if (channel == null || !channel.isEnabled()) {
            return Mono.error(new RuntimeException("Channel not found or disabled for model: " + publicModel));
        }

        ProviderGateway providerGateway = providerGatewayFactory.resolve(channel.getType());

        log.info("Routing request for {} to channel {} (provider: {}, model: {})",
                publicModel, channel.getName(), channel.getType(), mapping.getChannelModelName());

        return providerGateway.chatCompletions(channel, request, publicModel, mapping.getChannelModelName())
                .flatMap(resp -> {
                    int prompt = resp.getUsage() != null && resp.getUsage().getPromptTokens() != null ? resp.getUsage().getPromptTokens() : 0;
                    int completion = resp.getUsage() != null && resp.getUsage().getCompletionTokens() != null ? resp.getUsage().getCompletionTokens() : 0;
                    int total = resp.getUsage() != null && resp.getUsage().getTotalTokens() != null ? resp.getUsage().getTotalTokens() : (prompt + completion);
                    updateQuotaAndLog(callerToken, tokenKey, publicModel, prompt, completion, total, "SUCCESS");
                    return Mono.just(resp);
                })
                .onErrorResume(e -> {
                    updateQuotaAndLog(callerToken, tokenKey, publicModel, 0, 0, 0, "FAILED");
                    return Mono.error(e);
                });
    }

    private String extractToken(String authorization) {
        if (authorization == null || authorization.isEmpty()) {
            throw new RuntimeException("Missing Authorization header");
        }
        if (authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return authorization;
    }

    private Token validateToken(String tokenKey) {
        LambdaQueryWrapper<Token> q = new LambdaQueryWrapper<Token>()
                .eq(Token::getKey, tokenKey)
                .eq(Token::isEnabled, true);
        Token t = tokenMapper.selectOne(q);
        if (t == null) {
            throw new RuntimeException("Invalid token");
        }
        if (t.getTotalQuota() > 0 && t.getUsedQuota() >= t.getTotalQuota()) {
            throw new RuntimeException("Quota exceeded");
        }
        return t;
    }

    private void updateQuotaAndLog(Token t, String tokenKey, String model, int prompt, int completion, int total, String status) {
        if (Objects.equals(status, "SUCCESS") && total > 0) {
            long newUsed = t.getUsedQuota() + total;
            t.setUsedQuota(newUsed);
            tokenMapper.updateById(t);
        }
        Log logRow = Log.builder()
                .userId(t.getUserId())
                .tokenKey(tokenKey)
                .model(model)
                .promptTokens(prompt)
                .completionTokens(completion)
                .totalTokens(total)
                .cost((long) total)
                .status(status)
                .createdAt(java.time.LocalDateTime.now())
                .build();
        logMapper.insert(logRow);
    }
}
