package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.CreativeTaskMapper;
import com.transit.model.CreativeTask;
import com.transit.model.User;
import com.transit.service.creative.CreativeGenerationRequest;
import com.transit.service.creative.CreativeProviderAccess;
import com.transit.service.creative.CreativeProviderSubmission;
import com.transit.service.creative.CreativeProviderTaskState;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CreativeTaskService {
    private static final int MAX_PROMPT_LENGTH = 4_000;
    private static final int MAX_PROJECT_NAME_LENGTH = 160;
    private static final int MAX_REFERENCE_IMAGES = 9;
    private static final List<String> SUPPORTED_MODES = List.of(
            "TEXT_TO_VIDEO", "IMAGE_TO_VIDEO", "FIRST_LAST_FRAME", "STORYBOARD", "VIDEO_EXTEND");
    private static final List<String> SUPPORTED_RATIOS = List.of("16:9", "9:16", "1:1", "4:3", "3:4", "adaptive");
    private static final List<String> SUPPORTED_RESOLUTIONS = List.of("480p", "720p", "1080p");

    private final CreativeTaskMapper creativeTaskMapper;
    private final CreativeProviderConfigService creativeProviderConfigService;
    private final ObjectMapper objectMapper;
    private final Collection<CreativeVideoProvider> creativeVideoProviders;

    public Map<String, Object> catalog() {
        return Map.of(
                "providers", creativeVideoProviders.stream().map(CreativeVideoProvider::catalog).toList(),
                "supports", List.of("text_to_video", "image_to_video", "first_last_frame", "storyboard", "project_library"),
                "safeDefaults", Map.of("ratio", "16:9", "duration", 5, "resolution", "720p")
        );
    }

    public List<Map<String, Object>> templates() {
        return List.of(
                template("产品 15 秒广告", "STORYBOARD", "产品特写，干净的背景与柔和侧逆光。[0s-3s] 镜头缓慢推进展示材质；[3s-9s] 环绕产品一周；[9s-15s] 定格于品牌主视觉，留出字幕空间。", "16:9", "电商与品牌"),
                template("小红书竖屏开场", "TEXT_TO_VIDEO", "竖屏生活方式短片，清晨自然光，主角拿起一杯饮品后向镜头微笑，轻微手持跟拍，真实柔和色调，节奏轻快。", "9:16", "社媒种草"),
                template("绘本故事镜头", "STORYBOARD", "治愈绘本插画风，小狐狸在雨后森林中寻找发光的蘑菇。[0s-3s] 远景建立森林；[3s-7s] 小狐狸踏过水洼；[7s-10s] 发现发光蘑菇并露出惊喜表情。", "16:9", "故事与动画"),
                template("照片动起来", "IMAGE_TO_VIDEO", "保留主体外观与构图，人物轻轻眨眼并转向镜头，发丝被微风吹动，镜头缓慢推近，自然电影光。", "9:16", "图生视频")
        );
    }

    public Map<String, Object> submit(User user, Map<String, Object> request) {
        Long providerConfigId = optionalLong(request.get("connectionId"), "connectionId");
        CreativeProviderAccess providerAccess = providerConfigId == null
                ? null
                : creativeProviderConfigService.access(user, providerConfigId, true);
        String providerKey = providerAccess == null
                ? requiredText(string(request.get("provider"), "seedance"), "provider", 80).toLowerCase(Locale.ROOT)
                : providerAccess.providerKey();
        CreativeVideoProvider provider = provider(providerKey);
        if (providerAccess == null && !provider.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "平台默认模型尚未配置。请在“模型设置”中添加自己的 Seedance / 方舟连接。");
        }

        String mode = normalizeMode(string(request.get("mode"), "TEXT_TO_VIDEO"));
        String prompt = requiredText(string(request.get("prompt"), null), "prompt", MAX_PROMPT_LENGTH);
        String firstFrameUrl = optionalReferenceUrl(string(request.get("firstFrameUrl"), null), "firstFrameUrl");
        String inputLastFrameUrl = optionalReferenceUrl(string(request.get("lastFrameUrl"), null), "lastFrameUrl");
        List<String> references = referenceUrls(request.get("referenceImageUrls"));
        validateModeReferences(mode, firstFrameUrl, inputLastFrameUrl);
        String ratio = choice(string(request.get("ratio"), "16:9"), SUPPORTED_RATIOS, "ratio");
        int duration = wholeNumber(request.get("duration"), 5, 2, 15, "duration");
        String resolution = choice(string(request.get("resolution"), "720p"), SUPPORTED_RESOLUTIONS, "resolution");
        boolean generateAudio = bool(request.get("generateAudio"), true);
        String projectName = optionalText(string(request.get("projectName"), null), "projectName", MAX_PROJECT_NAME_LENGTH);
        String fallbackModel = providerAccess == null
                ? String.valueOf(provider.catalog().get("defaultModel"))
                : providerAccess.defaultModel();
        String model = requiredText(string(request.get("model"), fallbackModel), "model", 160);
        if (providerAccess != null && !providerAccess.models().contains(model)) {
            throw badRequest("所选模型不在该连接的模型列表中");
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        CreativeTask task = CreativeTask.builder()
                .userId(user.getId())
                .providerKey(providerKey)
                .providerConfigId(providerConfigId)
                .modelKey(model)
                .mode(mode)
                .projectName(projectName)
                .prompt(prompt)
                .firstFrameUrl(firstFrameUrl)
                .inputLastFrameUrl(inputLastFrameUrl)
                .referenceUrlsJson(writeJson(references))
                .optionsJson(writeJson(Map.of("ratio", ratio, "duration", duration, "resolution", resolution,
                        "generateAudio", generateAudio)))
                .status("QUEUED")
                .createdAt(now)
                .updatedAt(now)
                .build();
        creativeTaskMapper.insert(task);

        try {
            CreativeGenerationRequest providerRequest = new CreativeGenerationRequest(
                    model, mode, prompt, firstFrameUrl, inputLastFrameUrl, references, ratio, duration, resolution, generateAudio);
            CreativeProviderSubmission submitted = providerAccess == null
                    ? provider.submit(providerRequest)
                    : provider.submit(providerRequest, providerAccess);
            task.setProviderTaskId(submitted.providerTaskId());
            task.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            creativeTaskMapper.updateById(task);
            return view(task);
        } catch (ResponseStatusException exception) {
            markFailed(task, exception.getReason());
            throw exception;
        }
    }

    public List<Map<String, Object>> list(User user) {
        return creativeTaskMapper.selectList(new LambdaQueryWrapper<CreativeTask>()
                        .eq(CreativeTask::getUserId, user.getId())
                        .orderByDesc(CreativeTask::getCreatedAt)
                        .last("LIMIT 60"))
                .stream().map(this::view).toList();
    }

    public Map<String, Object> refresh(User user, Long id) {
        CreativeTask task = userTask(user, id);
        if (terminal(task.getStatus()) || !StringUtils.hasText(task.getProviderTaskId())) {
            return view(task);
        }
        CreativeVideoProvider provider = provider(task.getProviderKey());
        CreativeProviderTaskState state;
        if (task.getProviderConfigId() == null) {
            state = provider.fetch(task.getProviderTaskId());
        } else {
            CreativeProviderAccess access = creativeProviderConfigService.access(user, task.getProviderConfigId(), false);
            state = provider.fetch(task.getProviderTaskId(), access);
        }
        task.setStatus(state.status());
        task.setVideoUrl(state.videoUrl());
        task.setThumbnailUrl(state.thumbnailUrl());
        task.setOutputLastFrameUrl(state.lastFrameUrl());
        task.setErrorMessage(state.errorMessage());
        task.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (terminal(state.status())) {
            task.setCompletedAt(task.getUpdatedAt());
        }
        creativeTaskMapper.updateById(task);
        return view(task);
    }

    public Map<String, Object> promptAssist(Map<String, Object> request) {
        String intent = requiredText(string(request.get("prompt"), null), "prompt", 800);
        String mode = normalizeMode(string(request.get("mode"), "TEXT_TO_VIDEO"));
        String ratio = choice(string(request.get("ratio"), "16:9"), SUPPORTED_RATIOS, "ratio");
        String suggestion = "主体：" + intent + "。场景：补充清晰的时间、地点与环境细节。"
                + " 动作：先描述起始动作，再描述变化与收束。"
                + " 镜头：从全景建立画面，随后平滑推进或跟拍。"
                + " 视觉：电影级自然光、主体清晰、画面干净。"
                + " 输出比例 " + ratio + "。";
        if ("STORYBOARD".equals(mode)) {
            suggestion += " [0s-3s] 建立场景；[3s-7s] 主体动作；[7s-10s] 结果与情绪落点。";
        }
        return Map.of("prompt", suggestion, "tips", List.of(
                "先写主体和动作，再补镜头与光线，成功率更高。",
                "参考图用于锁定人物、产品或场景；请仅使用拥有授权的素材。",
                "第一次建议用 5 秒、720p 预览，满意后再提高规格。"
        ));
    }

    private CreativeVideoProvider provider(String providerKey) {
        return creativeVideoProviders.stream()
                .collect(Collectors.toMap(CreativeVideoProvider::key, Function.identity(), (left, right) -> left))
                .getOrDefault(providerKey, null) == null
                ? unsupportedProvider(providerKey)
                : creativeVideoProviders.stream().filter(item -> item.key().equals(providerKey)).findFirst().orElseThrow();
    }

    private CreativeVideoProvider unsupportedProvider(String providerKey) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "未找到创作供应商 “" + providerKey + "”。新增中转站时实现 CreativeVideoProvider 即可接入。");
    }

    private CreativeTask userTask(User user, Long id) {
        CreativeTask task = creativeTaskMapper.selectById(id);
        if (task == null || !user.getId().equals(task.getUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "创作任务不存在");
        }
        return task;
    }

    private Map<String, Object> view(CreativeTask task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", task.getId());
        result.put("provider", task.getProviderKey());
        result.put("connectionId", task.getProviderConfigId());
        result.put("model", task.getModelKey());
        result.put("mode", task.getMode());
        result.put("projectName", task.getProjectName());
        result.put("prompt", task.getPrompt());
        result.put("status", task.getStatus());
        result.put("firstFrameUrl", task.getFirstFrameUrl());
        result.put("lastFrameUrl", task.getInputLastFrameUrl());
        result.put("referenceImageUrls", readList(task.getReferenceUrlsJson()));
        result.put("options", readMap(task.getOptionsJson()));
        result.put("videoUrl", task.getVideoUrl());
        result.put("thumbnailUrl", task.getThumbnailUrl());
        result.put("outputLastFrameUrl", task.getOutputLastFrameUrl());
        result.put("errorMessage", task.getErrorMessage());
        result.put("createdAt", task.getCreatedAt());
        result.put("updatedAt", task.getUpdatedAt());
        return result;
    }

    private void markFailed(CreativeTask task, String message) {
        task.setStatus("FAILED");
        task.setErrorMessage(optionalText(message, "errorMessage", 2_000));
        task.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        task.setCompletedAt(task.getUpdatedAt());
        creativeTaskMapper.updateById(task);
    }

    private Map<String, Object> template(String title, String mode, String prompt, String ratio, String category) {
        return Map.of("title", title, "mode", mode, "prompt", prompt, "ratio", ratio, "category", category);
    }

    private String normalizeMode(String value) {
        String normalized = value == null ? "TEXT_TO_VIDEO" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_MODES.contains(normalized)) {
            throw badRequest("mode is not supported");
        }
        return normalized;
    }

    private void validateModeReferences(String mode, String firstFrameUrl, String lastFrameUrl) {
        if (("IMAGE_TO_VIDEO".equals(mode) || "FIRST_LAST_FRAME".equals(mode)) && !StringUtils.hasText(firstFrameUrl)) {
            throw badRequest("该模式需要提供首帧图片链接");
        }
        if ("FIRST_LAST_FRAME".equals(mode) && !StringUtils.hasText(lastFrameUrl)) {
            throw badRequest("首尾帧模式需要提供尾帧图片链接");
        }
    }

    private List<String> referenceUrls(Object raw) {
        if (!(raw instanceof List<?> items)) return List.of();
        if (items.size() > MAX_REFERENCE_IMAGES) throw badRequest("最多可添加 9 张参考图");
        List<String> urls = new ArrayList<>();
        for (Object item : items) {
            String url = optionalReferenceUrl(item == null ? null : item.toString(), "referenceImageUrls");
            if (StringUtils.hasText(url)) urls.add(url);
        }
        return urls;
    }

    private String optionalReferenceUrl(String value, String field) {
        String normalized = optionalText(value, field, 2_000);
        if (!StringUtils.hasText(normalized)) return null;
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && StringUtils.hasText(uri.getHost())) {
                return normalized;
            }
            if ("asset".equalsIgnoreCase(scheme) && StringUtils.hasText(uri.getHost())) {
                return normalized;
            }
        } catch (IllegalArgumentException ignored) {
            // Return the same safe validation error below.
        }
        throw badRequest(field + " must be an https/http URL or an authorized asset URI");
    }

    private String choice(String value, List<String> allowed, String field) {
        if (!allowed.contains(value)) throw badRequest(field + " is not supported");
        return value;
    }

    private int wholeNumber(Object value, int fallback, int minimum, int maximum, String field) {
        if (value == null) return fallback;
        try {
            int number = value instanceof Number numberValue ? numberValue.intValue() : Integer.parseInt(value.toString());
            if (number < minimum || number > maximum) throw badRequest(field + " must be between " + minimum + " and " + maximum);
            return number;
        } catch (NumberFormatException exception) {
            throw badRequest(field + " must be a whole number");
        }
    }

    private boolean bool(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean bool) return bool;
        return Boolean.parseBoolean(value.toString());
    }

    private Long optionalLong(Object value, String field) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            long id = value instanceof Number number ? number.longValue() : Long.parseLong(value.toString());
            if (id <= 0) throw badRequest(field + " must be a positive number");
            return id;
        } catch (NumberFormatException exception) {
            throw badRequest(field + " must be a number");
        }
    }

    private String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (!StringUtils.hasText(normalized)) throw badRequest(field + " is required");
        return normalized;
    }

    private String optionalText(String value, String field, int maxLength) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw badRequest(field + " must be at most " + maxLength + " characters");
        return normalized;
    }

    private String string(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法保存创作参数", exception);
        }
    }

    private List<String> readList(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private boolean terminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
