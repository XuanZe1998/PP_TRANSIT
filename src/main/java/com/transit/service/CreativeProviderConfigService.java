package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.CreativeProviderConfigMapper;
import com.transit.mapper.CreativeTaskMapper;
import com.transit.model.CreativeProviderConfig;
import com.transit.model.CreativeTask;
import com.transit.model.User;
import com.transit.service.creative.CreativeProviderAccess;
import com.transit.service.creative.CreativeVideoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreativeProviderConfigService {
    private static final int MAX_CONNECTIONS_PER_USER = 20;
    private static final int MAX_MODELS_PER_CONNECTION = 30;
    private static final String SEEDANCE = "seedance";
    private static final List<String> TERMINAL_STATUSES = List.of("SUCCEEDED", "FAILED", "CANCELLED");

    private final CreativeProviderConfigMapper configMapper;
    private final CreativeTaskMapper taskMapper;
    private final ChannelSecretService secretService;
    private final ChannelUrlPolicy channelUrlPolicy;
    private final ObjectMapper objectMapper;
    private final Collection<CreativeVideoProvider> providers;

    public List<Map<String, Object>> list(User user) {
        return configMapper.selectList(new LambdaQueryWrapper<CreativeProviderConfig>()
                        .eq(CreativeProviderConfig::getUserId, user.getId())
                        .orderByDesc(CreativeProviderConfig::getUpdatedAt))
                .stream()
                .map(this::view)
                .toList();
    }

    public Map<String, Object> create(User user, Map<String, Object> request) {
        Long count = configMapper.selectCount(new LambdaQueryWrapper<CreativeProviderConfig>()
                .eq(CreativeProviderConfig::getUserId, user.getId()));
        if (count >= MAX_CONNECTIONS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "每个用户最多保存 20 个模型连接");
        }
        if (!secretService.isConfigured()) {
            throw encryptionNotConfigured();
        }

        String providerKey = providerKey(request.get("provider"));
        String displayName = requiredText(request.get("displayName"), "连接名称", 160);
        String baseUrl = normalizeAndValidateBaseUrl(requiredText(request.get("baseUrl"), "Base URL", 1_000));
        List<String> models = models(request.get("models"));
        String defaultModel = defaultModel(request.get("defaultModel"), models);
        String apiKey = requiredText(request.get("apiKey"), "API Key", 2_000);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        CreativeProviderConfig config = CreativeProviderConfig.builder()
                .userId(user.getId())
                .providerKey(providerKey)
                .displayName(displayName)
                .baseUrl(baseUrl)
                .apiKey(encrypt(apiKey))
                .modelIdsJson(writeModels(models))
                .defaultModel(defaultModel)
                .enabled(bool(request.get("enabled"), true))
                .createdAt(now)
                .updatedAt(now)
                .build();
        configMapper.insert(config);
        return view(config);
    }

    public Map<String, Object> update(User user, Long id, Map<String, Object> request) {
        CreativeProviderConfig config = owned(user, id);
        String providerKey = providerKey(request.getOrDefault("provider", config.getProviderKey()));
        String displayName = requiredText(request.getOrDefault("displayName", config.getDisplayName()), "连接名称", 160);
        String baseUrl = normalizeAndValidateBaseUrl(requiredText(
                request.getOrDefault("baseUrl", config.getBaseUrl()), "Base URL", 1_000));
        List<String> models = request.containsKey("models") ? models(request.get("models")) : readModels(config.getModelIdsJson());
        String defaultModel = defaultModel(request.getOrDefault("defaultModel", config.getDefaultModel()), models);
        String newApiKey = text(request.get("apiKey"));

        if (StringUtils.hasText(newApiKey) && !secretService.isConfigured()) {
            throw encryptionNotConfigured();
        }
        config.setProviderKey(providerKey);
        config.setDisplayName(displayName);
        config.setBaseUrl(baseUrl);
        config.setModelIdsJson(writeModels(models));
        config.setDefaultModel(defaultModel);
        config.setEnabled(bool(request.get("enabled"), config.isEnabled()));
        if (StringUtils.hasText(newApiKey)) {
            config.setApiKey(encrypt(requiredText(newApiKey, "API Key", 2_000)));
        }
        config.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        configMapper.updateById(config);
        return view(config);
    }

    public void delete(User user, Long id) {
        CreativeProviderConfig config = owned(user, id);
        Long activeTasks = taskMapper.selectCount(new LambdaQueryWrapper<CreativeTask>()
                .eq(CreativeTask::getUserId, user.getId())
                .eq(CreativeTask::getProviderConfigId, config.getId())
                .notIn(CreativeTask::getStatus, TERMINAL_STATUSES));
        if (activeTasks > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该连接仍有生成中的任务，任务结束后才能删除");
        }
        configMapper.deleteById(config.getId());
    }

    public Map<String, Object> test(User user, Long id) {
        CreativeProviderAccess access = access(user, id, false);
        return provider(access.providerKey()).testConnection(access);
    }

    public CreativeProviderAccess access(User user, Long id, boolean requireEnabled) {
        CreativeProviderConfig config = owned(user, id);
        if (requireEnabled && !config.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所选模型连接已停用");
        }
        String apiKey;
        try {
            apiKey = secretService.decrypt(config.getApiKey());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "无法解密用户模型密钥，请检查服务端 security.data-encryption-key", exception);
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "所选模型连接尚未配置 API Key");
        }
        return new CreativeProviderAccess(
                config.getProviderKey(),
                config.getDisplayName(),
                config.getBaseUrl(),
                apiKey,
                config.getDefaultModel(),
                readModels(config.getModelIdsJson())
        );
    }

    private CreativeProviderConfig owned(User user, Long id) {
        if (id == null) {
            throw badRequest("connectionId is required");
        }
        CreativeProviderConfig config = configMapper.selectById(id);
        if (config == null || !user.getId().equals(config.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型连接不存在");
        }
        return config;
    }

    private CreativeVideoProvider provider(String key) {
        return providers.stream()
                .filter(item -> item.key().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "暂不支持该模型连接协议"));
    }

    private Map<String, Object> view(CreativeProviderConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", config.getId());
        result.put("provider", config.getProviderKey());
        result.put("displayName", config.getDisplayName());
        result.put("baseUrl", config.getBaseUrl());
        result.put("models", readModels(config.getModelIdsJson()));
        result.put("defaultModel", config.getDefaultModel());
        result.put("enabled", config.isEnabled());
        result.put("apiKeyConfigured", StringUtils.hasText(config.getApiKey()));
        result.put("apiKeyPreview", maskedApiKey(config.getApiKey()));
        result.put("createdAt", config.getCreatedAt());
        result.put("updatedAt", config.getUpdatedAt());
        return result;
    }

    private String maskedApiKey(String stored) {
        if (!StringUtils.hasText(stored)) return null;
        try {
            String value = secretService.decrypt(stored);
            if (!StringUtils.hasText(value) || value.length() <= 8) return "****";
            return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
        } catch (IllegalStateException exception) {
            return "****";
        }
    }

    private String normalizeAndValidateBaseUrl(String value) {
        String normalized = value.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(normalized);
            if (StringUtils.hasText(uri.getQuery()) || StringUtils.hasText(uri.getFragment())) {
                throw badRequest("Base URL 不能包含查询参数或锚点");
            }
            channelUrlPolicy.validate(normalized);
            return normalized;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.BAD_REQUEST) throw exception;
            throw badRequest("Base URL 必须是可访问的公网 HTTPS 地址");
        } catch (IllegalArgumentException exception) {
            throw badRequest("Base URL 格式不正确");
        }
    }

    private String providerKey(Object raw) {
        String key = text(raw);
        if (!StringUtils.hasText(key)) key = SEEDANCE;
        key = key.toLowerCase(Locale.ROOT);
        if (!SEEDANCE.equals(key)) {
            throw badRequest("当前用户自定义连接仅支持 Seedance/火山方舟兼容协议");
        }
        return key;
    }

    private List<String> models(Object raw) {
        List<?> source;
        if (raw instanceof List<?> list) {
            source = list;
        } else if (raw != null) {
            source = List.of(raw.toString().split("[,\\r\\n]+"));
        } else {
            source = List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : source) {
            String model = text(item);
            if (!StringUtils.hasText(model)) continue;
            if (model.length() > 160) throw badRequest("模型 ID 最多 160 个字符");
            unique.add(model);
        }
        if (unique.isEmpty()) throw badRequest("至少填写一个模型 ID");
        if (unique.size() > MAX_MODELS_PER_CONNECTION) {
            throw badRequest("每个连接最多配置 30 个模型");
        }
        return List.copyOf(unique);
    }

    private String defaultModel(Object raw, List<String> models) {
        String value = text(raw);
        if (!StringUtils.hasText(value)) value = models.get(0);
        if (!models.contains(value)) {
            throw badRequest("默认模型必须包含在模型 ID 列表中");
        }
        return value;
    }

    private String writeModels(List<String> models) {
        try {
            return objectMapper.writeValueAsString(models);
        } catch (JsonProcessingException exception) {
            throw badRequest("无法保存模型列表");
        }
    }

    private List<String> readModels(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> models = objectMapper.readValue(json, new TypeReference<>() { });
            return models == null ? List.of() : new ArrayList<>(models);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String encrypt(String apiKey) {
        try {
            return secretService.encrypt(apiKey);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "服务端尚未配置用户密钥加密，请设置 security.data-encryption-key", exception);
        }
    }

    private String requiredText(Object raw, String field, int maxLength) {
        String value = text(raw);
        if (!StringUtils.hasText(value)) throw badRequest(field + "不能为空");
        if (value.length() > maxLength) throw badRequest(field + "最多 " + maxLength + " 个字符");
        return value;
    }

    private String text(Object raw) {
        return raw == null ? null : raw.toString().trim();
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean value) return value;
        return Boolean.parseBoolean(raw.toString());
    }

    private ResponseStatusException encryptionNotConfigured() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "服务端尚未配置用户密钥加密，请设置 security.data-encryption-key");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
