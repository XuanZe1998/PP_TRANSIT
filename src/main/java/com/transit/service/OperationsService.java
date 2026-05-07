package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.dto.OperationsOverview;
import com.transit.dto.ProviderCatalogItem;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.LogMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.TokenMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Channel;
import com.transit.model.Log;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class OperationsService {

    private final ChannelMapper channelMapper;
    private final ModelMappingMapper modelMappingMapper;
    private final TokenMapper tokenMapper;
    private final UserMapper userMapper;
    private final LogMapper logMapper;

    public OperationsOverview overview() {
        List<Channel> channels = channelMapper.selectList(null);
        List<Log> logs = logMapper.selectList(null);

        long successRequests = logs.stream().filter(log -> "SUCCESS".equalsIgnoreCase(log.getStatus())).count();
        long failedRequests = logs.stream().filter(log -> "FAILED".equalsIgnoreCase(log.getStatus())).count();
        long totalConsumedTokens = logs.stream().mapToLong(Log::getTotalTokens).sum();

        List<String> activeProviders = channels.stream()
                .filter(Channel::isEnabled)
                .map(Channel::getType)
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();

        return OperationsOverview.builder()
                .totalChannels(channels.size())
                .enabledChannels(channels.stream().filter(Channel::isEnabled).count())
                .totalMappings(modelMappingMapper.selectCount(null))
                .totalTokens(tokenMapper.selectCount(null))
                .totalUsers(userMapper.selectCount(null))
                .totalRequests(logs.size())
                .successRequests(successRequests)
                .failedRequests(failedRequests)
                .totalConsumedTokens(totalConsumedTokens)
                .activeProviders(activeProviders)
                .build();
    }

    public List<ProviderCatalogItem> providerCatalog() {
        return List.of(
                ProviderCatalogItem.builder()
                        .provider("OpenAI")
                        .providerType("openai")
                        .headline("旗舰通用模型与高兼容 OpenAI 接口，适合作为默认标准面。")
                        .endpointStyle("OpenAI Chat Completions")
                        .recommendedBaseUrl("https://api.openai.com")
                        .modelFamilies(List.of("GPT-5 family", "GPT-4.1 family", "o-series reasoning"))
                        .highlights(List.of("生态最成熟", "SDK 覆盖完整", "企业接入成本低"))
                        .build(),
                ProviderCatalogItem.builder()
                        .provider("Anthropic")
                        .providerType("anthropic")
                        .headline("长上下文与代码/文档生成表现稳定，适合作为高质量备份主路由。")
                        .endpointStyle("Anthropic Messages")
                        .recommendedBaseUrl("https://api.anthropic.com")
                        .modelFamilies(List.of("Claude 4 family"))
                        .highlights(List.of("长文本稳定", "企业风控接受度高", "适合分析与写作"))
                        .build(),
                ProviderCatalogItem.builder()
                        .provider("Google Gemini")
                        .providerType("gemini")
                        .headline("多模态与长上下文能力强，适合作为搜索、图文、长文任务供应商。")
                        .endpointStyle("Gemini generateContent")
                        .recommendedBaseUrl("https://generativelanguage.googleapis.com")
                        .modelFamilies(List.of("Gemini 2.5 family"))
                        .highlights(List.of("多模态成熟", "长上下文强", "谷歌生态集成方便"))
                        .build(),
                ProviderCatalogItem.builder()
                        .provider("DeepSeek")
                        .providerType("deepseek")
                        .headline("高性价比推理与中文场景能力强，适合作为成本优化层。")
                        .endpointStyle("OpenAI Chat Completions")
                        .recommendedBaseUrl("https://api.deepseek.com")
                        .modelFamilies(List.of("DeepSeek chat family", "DeepSeek reasoner family"))
                        .highlights(List.of("中文友好", "性价比高", "适合高并发"))
                        .build(),
                ProviderCatalogItem.builder()
                        .provider("xAI Grok")
                        .providerType("xai")
                        .headline("实时感强的通用模型族，可作为补充型外部供应商接入。")
                        .endpointStyle("OpenAI-compatible")
                        .recommendedBaseUrl("https://api.x.ai")
                        .modelFamilies(List.of("Grok family"))
                        .highlights(List.of("接入简单", "可作为外部冗余", "适合扩展供应商池"))
                        .build()
        ).stream().sorted(Comparator.comparing(ProviderCatalogItem::getProvider)).toList();
    }
}
