package com.transit.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.config.ModelProbeProperties;
import com.transit.dto.PageResponse;
import com.transit.mapper.ModelProbeTaskMapper;
import com.transit.model.ModelProbeTask;
import com.transit.model.User;

import lombok.extern.slf4j.Slf4j;

/**
 * Model-probe orchestration: persists task records and drives the Node.js
 * sidecar (BazaarLink LLMprobe-engine) asynchronously. The heavy probe run
 * happens outside the request thread; clients poll the task row for status.
 */
@Slf4j
@Service
public class ModelProbeService {

    private final ModelProbeTaskMapper mapper;
    private final ModelProbeProperties properties;
    private final ChannelSecretService secretService;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Self-reference so {@code @Async execute(...)} runs through the Spring
     * proxy. {@code @Lazy} breaks the self-injection cycle that would otherwise
     * prevent the bean from being constructed.
     */
    private final ModelProbeService self;

    @Value("${server.port:8089}")
    private int serverPort;

    private static final String PURPOSE = "model-probe";

    /** Lifecycle statuses. */
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    public ModelProbeService(ModelProbeTaskMapper mapper,
                             ModelProbeProperties properties,
                             ChannelSecretService secretService,
                             WebClient webClient,
                             ObjectMapper objectMapper,
                             @Lazy ModelProbeService self) {
        this.mapper = mapper;
        this.properties = properties;
        this.secretService = secretService;
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    public void ensureEnabled(boolean admin) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Model-probe is not enabled on this deployment");
        }
        if (admin && !properties.isAdminEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin model-probe is disabled");
        }
        if (!admin && !properties.isUserEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User model-probe is disabled");
        }
    }

    /**
     * Creates a task and schedules the probe run asynchronously.
     */
    public ModelProbeTask submit(User user, Map<String, Object> body, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key is required");
        }
        // Reuse an existing task with the same key to avoid duplicate probe runs.
        ModelProbeTask existing = mapper.selectOne(new LambdaQueryWrapper<ModelProbeTask>()
                .eq(ModelProbeTask::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            return existing;
        }

        String baseUrl = str(body.get("baseUrl"), "baseUrl");
        String apiKey = str(body.get("apiKey"), "apiKey");
        String modelId = str(body.get("modelId"), "modelId");
        String claimedModel = body.get("claimedModel") == null ? null : String.valueOf(body.get("claimedModel"));
        boolean includeOptional = Boolean.TRUE.equals(body.get("includeOptional"));

        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "baseUrl must start with http(s)://");
        }

        ModelProbeTask task = ModelProbeTask.builder()
                .idempotencyKey(idempotencyKey)
                .userId(user == null ? null : user.getId())
                .baseUrl(baseUrl)
                .apiKey(encryptSecret(apiKey))
                .modelId(modelId)
                .claimedModel(claimedModel)
                .includeOptional(includeOptional)
                .status(STATUS_SUBMITTED)
                .createdAt(LocalDateTime.now())
                .build();
        mapper.insert(task);

        // Fire-and-forget the probe run on the async executor.
        self.execute(task.getId());
        return task;
    }

    /**
     * Runs a submitted task against the sidecar. Executes on the async pool.
     */
    @Async
    public void execute(Long taskId) {
        ModelProbeTask task = mapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        mapper.updateById(task);

        String sidecarUrl = properties.getSidecarUrl();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("baseUrl", task.getBaseUrl());
            payload.put("apiKey", decryptSecret(task.getApiKey()));
            payload.put("modelId", task.getModelId());
            payload.put("claimedModel", task.getClaimedModel() == null ? "" : task.getClaimedModel());
            payload.put("includeOptional", task.isIncludeOptional());
            payload.put("timeoutMs", Math.min(properties.getTimeoutSeconds() * 1000, 900_000L));

            String response = webClient.post()
                    .uri(sidecarUrl + "/probe")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(properties.getTimeoutSeconds()));

            JsonNode root = objectMapper.readTree(response);
            if (root.hasNonNull("ok") && !root.path("ok").asBoolean()) {
                fail(task, root.path("error").asText("sidecar returned an error"));
                return;
            }
            JsonNode report = root.path("report");
            int score = report.path("score").asInt(0);
            int scoreMax = report.path("scoreMax").asInt(score);

            task.setStatus(STATUS_SUCCESS);
            task.setScore(score);
            task.setScoreMax(scoreMax);
            task.setReportJson(response);
            task.setErrorMessage(null);
            task.setCompletedAt(LocalDateTime.now());
            mapper.updateById(task);
            log.info("Model-probe task {} completed: score {}/{}", taskId, score, scoreMax);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "Unknown sidecar error"
                    : exception.getMessage().substring(0, Math.min(exception.getMessage().length(), 500));
            fail(task, message);
            log.error("Model-probe task {} failed: {}", taskId, message, exception);
        }
    }

    public ModelProbeTask get(Long id, Long userId) {
        ModelProbeTask task = mapper.selectById(id);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Probe task not found");
        }
        if (userId != null && !userId.equals(task.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your probe task");
        }
        return task;
    }

    public PageResponse<ModelProbeTask> list(Long userId, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.max(1, Math.min(100, size));
        LambdaQueryWrapper<ModelProbeTask> qw = new LambdaQueryWrapper<>();
        if (userId != null) {
            qw.eq(ModelProbeTask::getUserId, userId);
        }
        qw.orderByDesc(ModelProbeTask::getId);
        Page<ModelProbeTask> result = mapper.selectPage(new Page<>(p, s), qw);
        PageResponse<ModelProbeTask> resp = new PageResponse<>();
        resp.setTotal(result.getTotal());
        resp.setPage(p);
        resp.setSize(s);
        resp.setItems(result.getRecords());
        return resp;
    }

    /** View payload that never exposes the decrypted API key. */
    public Map<String, Object> redact(ModelProbeTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("status", task.getStatus());
        view.put("baseUrl", task.getBaseUrl());
        view.put("modelId", task.getModelId());
        view.put("claimedModel", task.getClaimedModel() == null ? "" : task.getClaimedModel());
        view.put("score", task.getScore() == null ? 0 : task.getScore());
        view.put("scoreMax", task.getScoreMax() == null ? 0 : task.getScoreMax());
        view.put("report", task.getReportJson());
        view.put("error", task.getErrorMessage());
        view.put("createdAt", task.getCreatedAt());
        view.put("startedAt", task.getStartedAt());
        view.put("completedAt", task.getCompletedAt());
        return view;
    }

    /** Marks a task FAILED with the given message. */
    private void fail(ModelProbeTask task, String error) {
        task.setStatus(STATUS_FAILED);
        task.setErrorMessage(error);
        task.setCompletedAt(LocalDateTime.now());
        mapper.updateById(task);
    }

    /** Idempotent failure path that reloads by id (used from catch). */
    private void fail(Long taskId, String error) {
        ModelProbeTask task = mapper.selectById(taskId);
        if (task != null) {
            fail(task, error);
        }
    }

    /**
     * Encrypts the API key before storage when the master key is configured;
     * otherwise keeps it plaintext so a probe can still run on deployments that
     * have not enabled purpose-bound encryption. Never throws on absent key.
     */
    private String encryptSecret(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (!secretService.isConfigured()) {
            return plaintext;
        }
        try {
            return secretService.encryptForPurpose(PURPOSE, plaintext);
        } catch (Exception exception) {
            log.warn("Model-probe secret encryption unavailable; storing plaintext: {}", exception.getMessage());
            return plaintext;
        }
    }

    private String decryptSecret(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (!secretService.isConfigured() || !secretService.isEncryptedForPurpose(PURPOSE, stored)) {
            return stored;
        }
        try {
            return secretService.decryptForPurpose(PURPOSE, stored);
        } catch (Exception exception) {
            return stored;
        }
    }

    private String str(Object value, String name) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " is required");
        }
        return String.valueOf(value).trim();
    }
}