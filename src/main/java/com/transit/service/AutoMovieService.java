package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.mapper.CreativeTaskMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.CreativeTask;
import com.transit.model.User;
import com.transit.service.creative.CreativeImageProvider;
import com.transit.service.creative.CreativeProviderAccess;
import com.transit.service.creative.OpenAiCreativeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoMovieService {
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private static final int MAX_SOURCE_CHARS = 200_000;
    private static final List<String> RATIOS = List.of("16:9", "9:16", "1:1", "4:3", "3:4");
    private static final List<String> RESOLUTIONS = List.of("480p", "720p", "1080p");
    private static final List<String> ACTIVE = List.of("SCRIPT_GENERATING", "VISUALS_GENERATING", "VIDEO_GENERATING", "COMPOSING");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OpenAiCreativeProvider openAiProvider;
    private final CreativeProviderConfigService connectionService;
    private final CreativeTaskService creativeTaskService;
    private final CreativeTaskMapper creativeTaskMapper;
    private final UserMapper userMapper;
    private final CreativeAssetStorage storage;
    private final MeterRegistry meterRegistry;
    private final CreativePlatformConfigService platformConfigs;
    private final FfmpegDiagnosticsService ffmpeg;
    private volatile long lastVideoPollAt;

    public Map<String, Object> catalog() {
        Map<String, Object> settings = platformConfigs.settings();
        return Map.of("enabled", boolSetting(settings, "autoMovieEnabled", false), "textConfigured", openAiProvider.isConfigured(),
                "imageConfigured", openAiProvider.isImageConfigured(), "videoConfigured", platformConfigs.platformAccess("VIDEO", false) != null,
                "storageConfigured", Boolean.TRUE.equals(storage.diagnostics().get("configured")), "ffmpegConfigured", ffmpeg.available(),
                "limits", Map.of("maxSourceBytes", longSetting(settings, "maxSourceBytes", MAX_SOURCE_BYTES),
                        "maxSourceCharacters", intSetting(settings, "maxSourceCharacters", MAX_SOURCE_CHARS),
                        "minDuration", intSetting(settings, "minDuration", 30), "maxDuration", intSetting(settings, "maxDuration", 90),
                        "maxShots", intSetting(settings, "maxShots", 12)));
    }

    @Transactional
    public Map<String, Object> create(User user, Map<String, Object> request) {
        requireEnabled();
        String source = required(request.get("sourceText"), "原文", intSetting(platformConfigs.settings(), "maxSourceCharacters", MAX_SOURCE_CHARS));
        return createInternal(user, source, request);
    }

    @Transactional
    public Map<String, Object> importText(User user, MultipartFile file, Map<String, Object> request) {
        requireEnabled();
        if (file == null || file.isEmpty()) throw badRequest("TXT 文件不能为空");
        long maxBytes = longSetting(platformConfigs.settings(), "maxSourceBytes", MAX_SOURCE_BYTES);
        int maxChars = intSetting(platformConfigs.settings(), "maxSourceCharacters", MAX_SOURCE_CHARS);
        if (file.getSize() > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "TXT 文件超过后台配置的大小限制");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".txt")) throw badRequest("只支持 .txt 文件");
        try {
            String source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(file.getBytes())).toString();
            if (source.length() > maxChars) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "原文超过后台配置的字符限制");
            return createInternal(user, source, request);
        } catch (CharacterCodingException e) { throw badRequest("TXT 必须使用 UTF-8 编码"); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取 TXT 文件", e); }
    }

    private Map<String, Object> createInternal(User user, String source, Map<String, Object> request) {
        if (!StringUtils.hasText(source)) throw badRequest("原文不能为空");
        if (!bool(request.get("rightsConfirmed"), false)) throw badRequest("请确认已获得原文、人物和品牌素材的使用授权");
        String title = optional(request.get("title"), "未命名自动成片", 160);
        Map<String, Object> settings = platformConfigs.settings();
        int duration = integer(request.get("targetDuration"), 60, intSetting(settings, "minDuration", 30), intSetting(settings, "maxDuration", 90), "目标时长");
        String ratio = choice(request.get("ratio"), "16:9", RATIOS, "画面比例");
        String resolution = choice(request.get("resolution"), "720p", RESOLUTIONS, "清晰度");
        Long textConnectionId = nullableLong(request.get("textConnectionId"));
        Long imageConnectionId = nullableLong(request.get("imageConnectionId"));
        Long videoConnectionId = nullableLong(request.get("videoConnectionId"));
        validateConnection(user, textConnectionId, "openai-chat", "文本");
        validateConnection(user, imageConnectionId, "openai-image", "图片");
        validateConnection(user, videoConnectionId, "seedance", "视频");
        LocalDateTime now = now();
        String sql = """
                INSERT INTO creative_projects(user_id,title,source_text,target_duration,ratio,resolution,style,language,
                generate_audio,text_connection_id,image_connection_id,video_connection_id,text_model,image_model,video_model,
                stage,status,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        Object[] values = {user.getId(), title, source.trim(), duration, ratio, resolution,
                optional(request.get("style"), "电影感", 160), optional(request.get("language"), "zh-CN", 40),
                bool(request.get("generateAudio"), true), textConnectionId,
                imageConnectionId, videoConnectionId,
                optional(request.get("textModel"), null, 160), optional(request.get("imageModel"), null, 160),
                optional(request.get("videoModel"), null, 160), "SOURCE", "DRAFT", 1, now, now};
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> { PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS); for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]); return statement; }, keys);
        Long id = keys.getKey() == null ? null : keys.getKey().longValue();
        if (id == null) throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "项目创建失败：数据库未返回 ID");
        return detail(user, id);
    }

    public List<Map<String, Object>> list(User user) {
        return jdbc.queryForList("SELECT id,title,target_duration,ratio,resolution,stage,status,version,final_video_url,cover_url,error_message,created_at,updated_at FROM creative_projects WHERE user_id=? ORDER BY updated_at DESC LIMIT 60", user.getId());
    }

    public Map<String, Object> detail(User user, Long id) {
        Map<String, Object> project = project(user, id);
        Map<String, Object> result = new LinkedHashMap<>(project);
        List<Map<String, Object>> scripts = jdbc.queryForList("SELECT id,version,summary,script_json,approved,model_key,created_at,updated_at FROM creative_scripts WHERE project_id=? ORDER BY version DESC LIMIT 1", id);
        if (!scripts.isEmpty()) {
            Map<String, Object> script = new LinkedHashMap<>(scripts.get(0));
            script.put("content", readMap(Objects.toString(script.remove("script_json"), "{}")));
            result.put("script", script);
        }
        result.put("assets", jdbc.queryForList("SELECT id,asset_type,temp_ref,name,description,prompt,image_url,source,status,error_message,updated_at FROM creative_assets WHERE project_id=? ORDER BY asset_type,id", id));
        List<Map<String, Object>> shots = jdbc.queryForList("SELECT id,shot_order,duration,dialogue,narration,video_prompt,character_refs_json,scene_ref,reference_urls_json,creative_task_id,status,video_url,thumbnail_url,error_message,updated_at FROM creative_shots WHERE project_id=? ORDER BY shot_order", id);
        shots.forEach(shot -> { shot.put("characterRefs", readList(Objects.toString(shot.remove("character_refs_json"), "[]"))); shot.put("referenceUrls", readList(Objects.toString(shot.remove("reference_urls_json"), "[]"))); });
        result.put("shots", shots);
        result.put("quote", quoteForProject(project));
        return result;
    }

    @Transactional
    public Map<String, Object> enqueueScript(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request);
        requireStatus(project, List.of("DRAFT", "FAILED", "SCRIPT_REVIEW"));
        reserve(user, project, "SCRIPT", quote(project, "SCRIPT"));
        updateProject(id, "SCRIPT", "SCRIPT_GENERATING", null);
        enqueue(id, "SCRIPT", Map.of());
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> updateScript(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request);
        requireStatus(project, List.of("SCRIPT_REVIEW", "FAILED"));
        JsonNode content = objectMapper.valueToTree(request.get("content"));
        validateScript(content);
        int version = ((Number) project.get("version")).intValue() + 1;
        jdbc.update("UPDATE creative_scripts SET version=?,summary=?,script_json=?,approved=FALSE,updated_at=? WHERE id=(SELECT id FROM (SELECT id FROM creative_scripts WHERE project_id=? ORDER BY version DESC LIMIT 1) s)",
                version, content.path("summary").asText(), json(content), now(), id);
        jdbc.update("UPDATE creative_projects SET version=?,title=?,updated_at=? WHERE id=?", version,
                optional(content.path("title").asText(), Objects.toString(project.get("title")), 160), now(), id);
        jdbc.update("UPDATE creative_shots SET status='STALE',updated_at=? WHERE project_id=? AND status<>'SUCCEEDED'", now(), id);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> approveScript(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); requireStatus(project, List.of("SCRIPT_REVIEW"));
        Map<String, Object> scriptRow = latestScript(id); JsonNode script = readTree(Objects.toString(scriptRow.get("script_json"), "{}")); validateScript(script);
        jdbc.update("UPDATE creative_scripts SET approved=TRUE,updated_at=? WHERE id=?", now(), scriptRow.get("id"));
        jdbc.update("DELETE FROM creative_assets WHERE project_id=?", id); jdbc.update("DELETE FROM creative_shots WHERE project_id=?", id);
        int version = ((Number) scriptRow.get("version")).intValue();
        for (JsonNode item : script.path("characters")) insertAsset(id, version, "CHARACTER", item);
        for (JsonNode item : script.path("scenes")) insertAsset(id, version, "SCENE", item);
        for (JsonNode shot : script.path("shots")) {
            jdbc.update("""
                    INSERT INTO creative_shots(project_id,script_version,shot_order,duration,dialogue,narration,video_prompt,
                    character_refs_json,scene_ref,reference_urls_json,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?, 'PENDING',?,?)
                    """, id, version, shot.path("order").asInt(), shot.path("duration").asInt(), shot.path("dialogue").asText(null),
                    shot.path("narration").asText(null), shot.path("videoPrompt").asText(), json(shot.path("characterRefs")),
                    shot.path("sceneRef").asText(null), "[]", now(), now());
        }
        updateProject(id, "VISUALS", "SCRIPT_REVIEW", null);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> enqueueVisuals(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); requireStatus(project, List.of("SCRIPT_REVIEW", "PARTIAL_FAILED", "VISUALS_REVIEW"));
        long pending = jdbc.queryForObject("SELECT COUNT(*) FROM creative_assets WHERE project_id=? AND status<>'SUCCEEDED'", Long.class, id);
        reserve(user, project, "VISUALS", connection(project, "image") == null ? pending * price("imagePrice", 5000) : 0);
        updateProject(id, "VISUALS", "VISUALS_GENERATING", null); enqueue(id, "VISUALS", Map.of()); return detail(user, id);
    }

    @Transactional
    public Map<String, Object> updateAsset(User user, Long id, Long assetId, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); requireStatus(project, List.of("VISUALS_REVIEW", "PARTIAL_FAILED", "SCRIPT_REVIEW"));
        ownedAsset(id, assetId);
        jdbc.update("UPDATE creative_assets SET name=?,description=?,prompt=?,updated_at=? WHERE id=?", required(request.get("name"), "名称", 160),
                optional(request.get("description"), null, 4000), optional(request.get("prompt"), null, 4000), now(), assetId);
        bumpVersion(id);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> uploadAsset(User user, Long id, Long assetId, MultipartFile file, int version) {
        Map<String, Object> project = project(user, id); checkVersion(project, Map.of("version", version)); ownedAsset(id, assetId);
        String url = storage.storeImage(user.getId(), id, file);
        jdbc.update("UPDATE creative_assets SET image_url=?,source='UPLOADED',status='SUCCEEDED',error_message=NULL,updated_at=? WHERE id=?", url, now(), assetId);
        invalidateShots(id); bumpVersion(id);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> regenerateAsset(User user, Long id, Long assetId, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); ownedAsset(id, assetId);
        jdbc.update("UPDATE creative_assets SET status='PENDING',error_message=NULL,updated_at=? WHERE id=?", now(), assetId);
        reserve(user, project, "VISUALS", connection(project, "image") == null ? price("imagePrice", 5000) : 0);
        updateProject(id, "VISUALS", "VISUALS_GENERATING", null); enqueue(id, "VISUALS", Map.of("assetId", assetId)); return detail(user, id);
    }

    @Transactional
    public Map<String, Object> approveVisuals(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); requireStatus(project, List.of("VISUALS_REVIEW", "SCRIPT_REVIEW"));
        Integer missing = jdbc.queryForObject("SELECT COUNT(*) FROM creative_assets WHERE project_id=? AND status<>'SUCCEEDED'", Integer.class, id);
        if (missing != null && missing > 0) throw conflict("仍有画像未完成");
        jdbc.update("UPDATE creative_projects SET stage='VIDEO',updated_at=? WHERE id=?", now(), id);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> updateShot(User user, Long id, Long shotId, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); ownedShot(id, shotId);
        int duration = integer(request.get("duration"), 5, 2, 15, "镜头时长");
        jdbc.update("UPDATE creative_shots SET duration=?,dialogue=?,narration=?,video_prompt=?,character_refs_json=?,scene_ref=?,status=CASE WHEN status='SUCCEEDED' THEN 'STALE' ELSE status END,updated_at=? WHERE id=?",
                duration, optional(request.get("dialogue"), null, 4000), optional(request.get("narration"), null, 4000), required(request.get("videoPrompt"), "视频提示词", 4000),
                json(request.getOrDefault("characterRefs", List.of())), optional(request.get("sceneRef"), null, 80), now(), shotId);
        bumpVersion(id);
        return detail(user, id);
    }

    @Transactional
    public Map<String, Object> enqueueVideos(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); requireStatus(project, List.of("VISUALS_REVIEW", "PARTIAL_FAILED", "SCRIPT_REVIEW"));
        if (!"VIDEO".equals(Objects.toString(project.get("stage")))) throw conflict("请先确认角色和场景画像");
        Integer missingAssets = jdbc.queryForObject("SELECT COUNT(*) FROM creative_assets WHERE project_id=? AND status<>'SUCCEEDED'", Integer.class, id);
        if (missingAssets != null && missingAssets > 0) throw conflict("请先完成并确认所有角色和场景画像");
        int totalSeconds = jdbc.queryForObject("SELECT COALESCE(SUM(duration),0) FROM creative_shots WHERE project_id=? AND status<>'SUCCEEDED'", Integer.class, id);
        reserve(user, project, "VIDEO", connection(project, "video") == null ? totalSeconds * price("videoSecondPrice", 2000) : 0);
        updateProject(id, "VIDEO", "VIDEO_GENERATING", null); enqueue(id, "VIDEO", Map.of()); return detail(user, id);
    }

    @Transactional
    public Map<String, Object> retryShot(User user, Long id, Long shotId, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request); Map<String, Object> shot = ownedShot(id, shotId);
        int duration = ((Number) shot.get("duration")).intValue(); reserve(user, project, "VIDEO", connection(project, "video") == null ? duration * price("videoSecondPrice", 2000) : 0);
        jdbc.update("UPDATE creative_shots SET status='PENDING',creative_task_id=NULL,error_message=NULL,updated_at=? WHERE id=?", now(), shotId);
        updateProject(id, "VIDEO", "VIDEO_GENERATING", null); enqueue(id, "VIDEO", Map.of("shotId", shotId)); return detail(user, id);
    }

    @Transactional
    public Map<String, Object> enqueueCompose(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request);
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM creative_shots WHERE project_id=? AND status<>'SUCCEEDED'", Integer.class, id);
        if (pending != null && pending > 0) throw conflict("全部镜头成功后才能合成");
        updateProject(id, "COMPOSE", "COMPOSING", null); enqueue(id, "COMPOSE", Map.of()); return detail(user, id);
    }

    @Transactional
    public Map<String, Object> cancel(User user, Long id, Map<String, Object> request) {
        Map<String, Object> project = project(user, id); checkVersion(project, request);
        jdbc.update("UPDATE creative_jobs SET status='CANCELLED',updated_at=? WHERE project_id=? AND status IN ('QUEUED','RUNNING')", now(), id);
        jdbc.update("UPDATE creative_projects SET status='CANCELLED',updated_at=? WHERE id=?", now(), id); releaseOpenReservations(id);
        return detail(user, id);
    }

    @Transactional
    public void delete(User user, Long id) {
        Map<String, Object> project = project(user, id); if (ACTIVE.contains(Objects.toString(project.get("status")))) throw conflict("项目正在运行，不能删除");
        jdbc.update("DELETE FROM creative_jobs WHERE project_id=?", id); jdbc.update("DELETE FROM creative_shots WHERE project_id=?", id);
        jdbc.update("DELETE FROM creative_assets WHERE project_id=?", id); jdbc.update("DELETE FROM creative_scripts WHERE project_id=?", id);
        jdbc.update("DELETE FROM creative_billing_reservations WHERE project_id=?", id); jdbc.update("DELETE FROM creative_projects WHERE id=?", id);
    }

    public Map<String, Object> quote(User user, Long id, String stage) {
        Map<String, Object> project = project(user, id); String normalized = required(stage, "阶段", 40).toUpperCase(Locale.ROOT);
        long amount = quote(project, normalized); return Map.of("stage", normalized, "estimatedAmount", amount, "currency", "CNY", "amountScale", 10000, "byok", amount == 0);
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void work() {
        if (!enabled()) return;
        int concurrency = intSetting(platformConfigs.settings(), "workerConcurrency", 2);
        List<Map<String, Object>> jobs = jdbc.queryForList("SELECT * FROM creative_jobs WHERE status='QUEUED' AND (next_run_at IS NULL OR next_run_at<=?) AND (lease_expires_at IS NULL OR lease_expires_at<?) ORDER BY id LIMIT " + Math.max(1, Math.min(20, concurrency)), now(), now());
        for (Map<String, Object> job : jobs) runJob(job);
    }

    @Scheduled(fixedDelay = 1000, initialDelay = 5000)
    public void pollVideos() {
        if (!enabled()) return;
        long current = System.currentTimeMillis();
        long interval = intSetting(platformConfigs.settings(), "pollIntervalMs", 5000);
        if (current - lastVideoPollAt < interval) return;
        lastVideoPollAt = current;
        for (Map<String, Object> project : jdbc.queryForList("SELECT * FROM creative_projects WHERE status='VIDEO_GENERATING'")) {
            User user = userMapper.selectById(((Number) project.get("user_id")).longValue()); if (user == null) continue;
            List<Map<String, Object>> shots = jdbc.queryForList("SELECT * FROM creative_shots WHERE project_id=? AND status IN ('QUEUED','RUNNING') AND creative_task_id IS NOT NULL", project.get("id"));
            for (Map<String, Object> shot : shots) {
                try {
                    Map<String, Object> task = creativeTaskService.refresh(user, ((Number) shot.get("creative_task_id")).longValue());
                    String status = Objects.toString(task.get("status"), "RUNNING");
                    jdbc.update("UPDATE creative_shots SET status=?,video_url=?,thumbnail_url=?,error_message=?,updated_at=? WHERE id=?", status,
                            task.get("videoUrl"), task.get("thumbnailUrl"), task.get("errorMessage"), now(), shot.get("id"));
                } catch (Exception e) { log.warn("Unable to poll auto-movie shot {}", shot.get("id"), e); }
            }
            long projectId = ((Number) project.get("id")).longValue();
            Integer providerActive = jdbc.queryForObject("SELECT COUNT(*) FROM creative_shots WHERE project_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, projectId);
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM creative_shots WHERE project_id=? AND status IN ('PENDING','STALE')", Integer.class, projectId);
            if ((providerActive == null || providerActive == 0) && pending != null && pending > 0) {
                Integer queuedJob = jdbc.queryForObject("SELECT COUNT(*) FROM creative_jobs WHERE project_id=? AND job_type='VIDEO' AND status IN ('QUEUED','RUNNING')", Integer.class, projectId);
                if (queuedJob == null || queuedJob == 0) enqueue(projectId, "VIDEO", Map.of());
            }
            finishVideoStage(projectId);
        }
    }

    private void runJob(Map<String, Object> job) {
        long id = ((Number) job.get("id")).longValue(); String owner = workerId();
        int claimed = jdbc.update("UPDATE creative_jobs SET status='RUNNING',lease_owner=?,lease_expires_at=?,attempts=attempts+1,updated_at=? WHERE id=? AND status='QUEUED'", owner, now().plusMinutes(5), now(), id);
        if (claimed != 1) return;
        long projectId = ((Number) job.get("project_id")).longValue();
        try {
            String type = Objects.toString(job.get("job_type")); Map<String, Object> payload = readMap(Objects.toString(job.get("payload_json"), "{}"));
            switch (type) { case "SCRIPT" -> generateScript(projectId); case "VISUALS" -> generateVisuals(projectId, payload); case "VIDEO" -> submitVideos(projectId, payload); case "COMPOSE" -> compose(projectId); default -> throw new IllegalStateException("Unknown job " + type); }
            jdbc.update("UPDATE creative_jobs SET status='SUCCEEDED',lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?", now(), id);
            meterRegistry.counter("creative.auto_movie.jobs", "type", type, "result", "success").increment();
        } catch (Exception e) {
            int attempts = ((Number) job.get("attempts")).intValue() + 1;
            boolean retry = attempts < intSetting(platformConfigs.settings(), "maxRetries", 3) && retryable(e);
            jdbc.update("UPDATE creative_jobs SET status=?,next_run_at=?,lease_owner=NULL,lease_expires_at=NULL,error_message=?,updated_at=? WHERE id=?",
                    retry ? "QUEUED" : "FAILED", retry ? now().plusSeconds((long) Math.pow(2, attempts) * 5) : null, safe(e), now(), id);
            if (!retry) { updateProject(projectId, null, "FAILED", safe(e)); releaseOpenReservations(projectId); }
            meterRegistry.counter("creative.auto_movie.jobs", "type", Objects.toString(job.get("job_type")), "result", retry ? "retry" : "failed").increment();
            log.warn("Auto-movie job {} failed", id, e);
        }
    }

    private void generateScript(long projectId) {
        Map<String, Object> p = projectById(projectId); User user = userMapper.selectById(((Number) p.get("user_id")).longValue());
        CreativeProviderAccess access = access(user, p, "text");
        JsonNode script = openAiProvider.generateScript(Objects.toString(p.get("source_text")), Objects.toString(p.get("title")),
                ((Number) p.get("target_duration")).intValue(), Objects.toString(p.get("ratio")), Objects.toString(p.get("style"), ""),
                Objects.toString(p.get("language"), "zh-CN"), Objects.toString(p.get("text_model"), null), access);
        normalizeScript(script, ((Number) p.get("target_duration")).intValue()); validateScript(script);
        int version = ((Number) p.get("version")).intValue();
        jdbc.update("DELETE FROM creative_scripts WHERE project_id=? AND version=?", projectId, version);
        jdbc.update("INSERT INTO creative_scripts(project_id,version,summary,script_json,approved,model_key,created_at,updated_at) VALUES (?,?,?,?,FALSE,?,?,?)",
                projectId, version, script.path("summary").asText(), json(script), selectedModel(p, access, "text"), now(), now());
        jdbc.update("UPDATE creative_projects SET title=?,stage='SCRIPT',status='SCRIPT_REVIEW',error_message=NULL,updated_at=? WHERE id=?",
                optional(script.path("title").asText(), Objects.toString(p.get("title")), 160), now(), projectId); settle(projectId, "SCRIPT", quote(p, "SCRIPT"));
    }

    private void generateVisuals(long projectId, Map<String, Object> payload) {
        Map<String, Object> p = projectById(projectId); User user = userMapper.selectById(((Number) p.get("user_id")).longValue()); CreativeProviderAccess access = access(user, p, "image");
        Object only = payload.get("assetId"); String sql = only == null ? "SELECT * FROM creative_assets WHERE project_id=? AND status<>'SUCCEEDED'" : "SELECT * FROM creative_assets WHERE project_id=? AND id=" + Long.parseLong(only.toString());
        List<Map<String, Object>> assets = jdbc.queryForList(sql, projectId); int success = 0;
        for (Map<String, Object> asset : assets) {
            try {
                CreativeImageProvider.GeneratedImage image = openAiProvider.generate(Objects.toString(asset.get("prompt")), Objects.toString(p.get("image_model"), null), access);
                String url;
                if (StringUtils.hasText(image.url())) {
                    HttpRequest request = HttpRequest.newBuilder(URI.create(image.url())).timeout(Duration.ofMinutes(2)).GET().build();
                    HttpResponse<byte[]> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("下载生成图片失败：HTTP " + response.statusCode());
                    url = storage.storeImage(user.getId(), projectId, response.body());
                } else url = storage.storeImage(user.getId(), projectId, image.bytes());
                jdbc.update("UPDATE creative_assets SET image_url=?,status='SUCCEEDED',error_message=NULL,updated_at=? WHERE id=?", url, now(), asset.get("id")); success++;
            } catch (Exception e) { jdbc.update("UPDATE creative_assets SET status='FAILED',error_message=?,updated_at=? WHERE id=?", safe(e), now(), asset.get("id")); }
        }
        if (success > 0) invalidateShots(projectId);
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM creative_assets WHERE project_id=? AND status<>'SUCCEEDED'", Integer.class, projectId);
        updateProject(projectId, "VISUALS", failed != null && failed > 0 ? "PARTIAL_FAILED" : "VISUALS_REVIEW", failed != null && failed > 0 ? "部分画像生成失败，可单独重试" : null);
        settle(projectId, "VISUALS", access == null ? success * price("imagePrice", 5000) : 0);
    }

    private void submitVideos(long projectId, Map<String, Object> payload) {
        Map<String, Object> p = projectById(projectId); User user = userMapper.selectById(((Number) p.get("user_id")).longValue());
        Object only = payload.get("shotId"); String sql = only == null ? "SELECT * FROM creative_shots WHERE project_id=? AND status NOT IN ('SUCCEEDED','QUEUED','RUNNING')" : "SELECT * FROM creative_shots WHERE project_id=? AND id=" + Long.parseLong(only.toString());
        List<Map<String, Object>> shots = jdbc.queryForList(sql.replace("status NOT IN ('SUCCEEDED','QUEUED','RUNNING')", "status IN ('PENDING','FAILED','STALE')"), projectId);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM creative_shots WHERE project_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, projectId);
        int capacity = Math.max(0, intSetting(platformConfigs.settings(), "videoConcurrency", 3) - (active == null ? 0 : active));
        if (shots.size() > capacity) shots = shots.subList(0, capacity);
        for (Map<String, Object> shot : shots) {
            List<String> refs = references(projectId, shot); Map<String, Object> request = new LinkedHashMap<>();
            request.put("provider", "seedance"); request.put("connectionId", p.get("video_connection_id")); request.put("model", p.get("video_model"));
            request.put("mode", "STORYBOARD"); request.put("prompt", shotPrompt(shot)); request.put("referenceImageUrls", refs);
            request.put("ratio", p.get("ratio")); request.put("resolution", p.get("resolution")); request.put("duration", shot.get("duration")); request.put("generateAudio", p.get("generate_audio")); request.put("projectName", p.get("title"));
            try {
                Map<String, Object> taskView = creativeTaskService.submit(user, request); Long taskId = ((Number) taskView.get("id")).longValue();
                CreativeTask task = creativeTaskMapper.selectById(taskId); task.setProjectId(projectId); task.setShotId(((Number) shot.get("id")).longValue()); task.setStage("VIDEO"); creativeTaskMapper.updateById(task);
                jdbc.update("UPDATE creative_shots SET creative_task_id=?,reference_urls_json=?,status=?,updated_at=? WHERE id=?", taskId, json(refs), taskView.get("status"), now(), shot.get("id"));
            } catch (Exception e) { jdbc.update("UPDATE creative_shots SET status='FAILED',error_message=?,updated_at=? WHERE id=?", safe(e), now(), shot.get("id")); }
        }
        finishVideoStage(projectId);
    }

    private void finishVideoStage(long projectId) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM creative_shots WHERE project_id=? AND status IN ('PENDING','QUEUED','RUNNING')", Integer.class, projectId);
        if (active != null && active > 0) return;
        Integer failed = jdbc.queryForObject("SELECT COUNT(*) FROM creative_shots WHERE project_id=? AND status<>'SUCCEEDED'", Integer.class, projectId);
        if (failed != null && failed > 0) { updateProject(projectId, "VIDEO", "PARTIAL_FAILED", "部分镜头生成失败，可单独重试"); settleVideo(projectId); return; }
        settleVideo(projectId); updateProject(projectId, "COMPOSE", "COMPOSING", null); enqueue(projectId, "COMPOSE", Map.of());
    }

    private void compose(long projectId) throws Exception {
        if (!ffmpeg.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "FFmpeg 未安装或缺少所需编码器/滤镜");
        Map<String, Object> project = projectById(projectId);
        List<Map<String, Object>> shots = jdbc.queryForList("SELECT video_url,shot_order,duration FROM creative_shots WHERE project_id=? AND status='SUCCEEDED' ORDER BY shot_order", projectId);
        if (shots.isEmpty()) throw conflict("没有可合成的镜头");
        Path dir = Files.createTempDirectory("auto-movie-");
        try {
            List<Path> clips = new ArrayList<>(); int index = 0;
            for (Map<String, Object> shot : shots) { Path clip = dir.resolve(String.format("%03d.mp4", ++index)); download(Objects.toString(shot.get("video_url")), clip); clips.add(clip); }
            Path output = dir.resolve("final.mp4");
            int[] dimensions = dimensions(Objects.toString(project.get("ratio")), Objects.toString(project.get("resolution")));
            boolean audio = Boolean.TRUE.equals(project.get("generate_audio")) || Boolean.TRUE.equals(project.get("GENERATE_AUDIO"));
            List<String> command = new ArrayList<>(List.of(ffmpeg.executable(), "-y"));
            for (Path clip : clips) { command.add("-i"); command.add(clip.toString()); }
            StringBuilder filter = new StringBuilder();
            for (int i = 0; i < clips.size(); i++) filter.append('[').append(i).append(":v]scale=").append(dimensions[0]).append(':').append(dimensions[1])
                    .append(":force_original_aspect_ratio=decrease,pad=").append(dimensions[0]).append(':').append(dimensions[1])
                    .append(":(ow-iw)/2:(oh-ih)/2:black,fps=30,format=yuv420p,settb=AVTB[v").append(i).append("];\n");
            String videoLabel = "v0", audioLabel = "0:a"; double elapsed = ((Number) shots.get(0).get("duration")).doubleValue();
            for (int i = 1; i < clips.size(); i++) {
                String nextVideo = "vx" + i; double offset = Math.max(0.01, elapsed - 0.25 * i);
                filter.append('[').append(videoLabel).append("][v").append(i).append("]xfade=transition=fade:duration=0.25:offset=")
                        .append(String.format(Locale.ROOT, "%.3f", offset)).append('[').append(nextVideo).append("];\n"); videoLabel = nextVideo;
                if (audio) { String nextAudio = "ax" + i; filter.append('[').append(audioLabel).append("][").append(i).append(":a]acrossfade=d=0.25:c1=tri:c2=tri[").append(nextAudio).append("];\n"); audioLabel = nextAudio; }
                elapsed += ((Number) shots.get(i).get("duration")).doubleValue();
            }
            String filterGraph = filter.toString().replaceFirst(";\\s*$", "");
            command.addAll(List.of("-filter_complex", filterGraph, "-map", "[" + videoLabel + "]"));
            if (audio) command.addAll(List.of("-map", clips.size() == 1 ? "0:a" : "[" + audioLabel + "]", "-c:a", "aac", "-ar", "48000"));
            command.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-r", "30", "-movflags", "+faststart", output.toString()));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream logOutput = new ByteArrayOutputStream(); process.getInputStream().transferTo(logOutput);
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.MINUTES) || process.exitValue() != 0) throw new IllegalStateException("FFmpeg 合成失败：" + logOutput.toString(StandardCharsets.UTF_8).lines().reduce((a,b)->b).orElse("未知错误"));
            long userId = ((Number) project.get("user_id")).longValue();
            String url = storage.storeVideo(userId, projectId, output); Map<String, Object> first = shots.get(0);
            jdbc.update("UPDATE creative_projects SET status='SUCCEEDED',stage='DONE',final_video_url=?,cover_url=(SELECT thumbnail_url FROM creative_shots WHERE project_id=? ORDER BY shot_order LIMIT 1),error_message=NULL,completed_at=?,updated_at=? WHERE id=?", url, projectId, now(), now(), projectId);
        } finally { try (var walk = Files.walk(dir)) { walk.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); } }
    }

    private void validateScript(JsonNode root) {
        if (root == null || !root.isObject()) throw badRequest("剧本必须是 JSON 对象");
        Map<String, Object> settings = platformConfigs.settings();
        int maxCharacters = intSetting(settings, "maxCharacters", 8);
        int maxScenes = intSetting(settings, "maxScenes", 8);
        int maxShots = intSetting(settings, "maxShots", 12);
        int minDuration = intSetting(settings, "minDuration", 30);
        int maxDuration = intSetting(settings, "maxDuration", 90);
        JsonNode characters = root.path("characters"), scenes = root.path("scenes"), shots = root.path("shots");
        if (!characters.isArray() || characters.size() > maxCharacters) throw badRequest("角色必须为数组且最多 " + maxCharacters + " 个");
        if (!scenes.isArray() || scenes.size() > maxScenes) throw badRequest("场景必须为数组且最多 " + maxScenes + " 个");
        if (!shots.isArray() || shots.size() < 1 || shots.size() > maxShots) throw badRequest("分镜必须为 1-" + maxShots + " 个");
        List<String> characterIds = new ArrayList<>(), sceneIds = new ArrayList<>();
        characters.forEach(n -> characterIds.add(requiredNode(n, "tempId", "角色 tempId"))); scenes.forEach(n -> sceneIds.add(requiredNode(n, "tempId", "场景 tempId")));
        int total = 0; int order = 0;
        for (JsonNode shot : shots) { if (shot.path("order").asInt() <= order) throw badRequest("分镜顺序必须递增"); order = shot.path("order").asInt(); int d = shot.path("duration").asInt(); if (d < 2 || d > 15) throw badRequest("单镜头时长必须为 2-15 秒"); total += d; requiredNode(shot, "videoPrompt", "视频提示词");
            for (JsonNode ref : shot.path("characterRefs")) if (!characterIds.contains(ref.asText())) throw badRequest("分镜引用了不存在的角色：" + ref.asText());
            String scene = shot.path("sceneRef").asText(); if (StringUtils.hasText(scene) && !sceneIds.contains(scene)) throw badRequest("分镜引用了不存在的场景：" + scene);
        }
        if (total < minDuration || total > maxDuration) throw badRequest("剧本总时长必须为 " + minDuration + "-" + maxDuration + " 秒");
    }

    private void normalizeScript(JsonNode root, int target) {
        if (!root.path("shots").isArray()) return; int total = 0; for (JsonNode shot : root.path("shots")) total += Math.max(2, Math.min(15, shot.path("duration").asInt(5)));
        if (total >= 30 && total <= 90) return; int count = root.path("shots").size(); if (count == 0) return; int each = Math.max(2, Math.min(15, target / count)); int remainder = target - each * count; int i = 0;
        for (JsonNode shot : root.path("shots")) { int value = each + (i++ == count - 1 ? remainder : 0); ((com.fasterxml.jackson.databind.node.ObjectNode) shot).put("duration", Math.max(2, Math.min(15, value))); }
    }

    private void insertAsset(long projectId, int version, String type, JsonNode item) {
        jdbc.update("INSERT INTO creative_assets(project_id,script_version,asset_type,temp_ref,name,description,prompt,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PENDING',?,?)",
                projectId, version, type, requiredNode(item, "tempId", "素材 tempId"), requiredNode(item, "name", "素材名称"), item.path("description").asText(null), requiredNode(item, "visualPrompt", "画像提示词"), now(), now());
    }

    private List<String> references(long projectId, Map<String, Object> shot) {
        List<String> refs = new ArrayList<>(); List<String> wanted = readList(Objects.toString(shot.get("character_refs_json"), "[]")); String scene = Objects.toString(shot.get("scene_ref"), ""); if (!scene.isBlank()) wanted.add(scene);
        for (String ref : wanted) { List<String> urls = jdbc.query("SELECT image_url FROM creative_assets WHERE project_id=? AND temp_ref=? AND status='SUCCEEDED'", (rs,n)->rs.getString(1), projectId, ref); if (!urls.isEmpty() && (urls.get(0).startsWith("https://") || urls.get(0).startsWith("http://") || urls.get(0).startsWith("asset://"))) refs.add(urls.get(0)); if (refs.size() == 4) break; }
        return refs;
    }

    private String shotPrompt(Map<String, Object> shot) { return Objects.toString(shot.get("video_prompt")) + optionalSuffix(" 对白：", shot.get("dialogue")) + optionalSuffix(" 旁白：", shot.get("narration")); }
    private String optionalSuffix(String prefix, Object value) { String text = Objects.toString(value, "").trim(); return text.isBlank() ? "" : prefix + text; }
    private CreativeProviderAccess access(User user, Map<String, Object> p, String capability) { Long id = connection(p, capability); return id == null ? null : connectionService.access(user, id, true); }
    private void validateConnection(User user, Long id, String expected, String label) { if (id == null) return; CreativeProviderAccess access = connectionService.access(user, id, true); if (!expected.equals(access.providerKey())) throw badRequest(label + "连接协议不匹配"); }
    private Long connection(Map<String, Object> p, String capability) { Object value = p.get(capability + "_connection_id"); return value instanceof Number n ? n.longValue() : null; }
    private String selectedModel(Map<String, Object> p, CreativeProviderAccess access, String capability) { String model = Objects.toString(p.get(capability + "_model"), ""); return !model.isBlank() ? model : access == null ? platformConfigs.defaultModel(capability.toUpperCase(Locale.ROOT)) : access.defaultModel(); }

    private Map<String, Object> project(User user, Long id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_projects WHERE id=? FOR UPDATE", id); if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自动成片项目不存在"); Map<String, Object> p = rows.get(0); if (!user.getId().equals(((Number) p.get("user_id")).longValue())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自动成片项目不存在"); return p; }
    private Map<String, Object> projectById(Long id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_projects WHERE id=?", id); if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "自动成片项目不存在"); return rows.get(0); }
    private Map<String, Object> latestScript(long id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_scripts WHERE project_id=? ORDER BY version DESC LIMIT 1", id); if (rows.isEmpty()) throw conflict("请先生成剧本"); return rows.get(0); }
    private Map<String, Object> ownedAsset(long projectId, long id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_assets WHERE project_id=? AND id=?", projectId, id); if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "画像不存在"); return rows.get(0); }
    private Map<String, Object> ownedShot(long projectId, long id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_shots WHERE project_id=? AND id=?", projectId, id); if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "镜头不存在"); return rows.get(0); }
    private void updateProject(long id, String stage, String status, String error) { jdbc.update("UPDATE creative_projects SET stage=COALESCE(?,stage),status=?,error_message=?,updated_at=? WHERE id=?", stage, status, error, now(), id); }
    private void bumpVersion(long id) { jdbc.update("UPDATE creative_projects SET version=version+1,updated_at=? WHERE id=?", now(), id); }
    private void invalidateShots(long projectId) { jdbc.update("UPDATE creative_shots SET status='STALE',updated_at=? WHERE project_id=? AND status='SUCCEEDED'", now(), projectId); }
    private void enqueue(long projectId, String type, Object payload) { jdbc.update("INSERT INTO creative_jobs(project_id,job_type,payload_json,status,next_run_at,created_at,updated_at) VALUES (?,?,?,'QUEUED',?,?,?)", projectId, type, json(payload), now(), now(), now()); }

    private Map<String, Object> quoteForProject(Map<String, Object> p) { return Map.of("script", quote(p, "SCRIPT"), "visuals", quote(p, "VISUALS"), "video", quote(p, "VIDEO"), "amountScale", 10000, "currency", "CNY"); }
    private long quote(Map<String, Object> p, String stage) { return switch (stage) { case "SCRIPT" -> connection(p, "text") == null ? price("scriptPrice", 10000) : 0; case "VISUALS" -> { Long count = jdbc.queryForObject("SELECT COUNT(*) FROM creative_assets WHERE project_id=? AND status<>'SUCCEEDED'", Long.class, p.get("id")); yield connection(p, "image") == null ? (count == null ? 0 : count * price("imagePrice", 5000)) : 0; } case "VIDEO" -> { Long seconds = jdbc.queryForObject("SELECT COALESCE(SUM(duration),0) FROM creative_shots WHERE project_id=? AND status<>'SUCCEEDED'", Long.class, p.get("id")); yield connection(p, "video") == null ? (seconds == null ? 0 : seconds * price("videoSecondPrice", 2000)) : 0; } default -> throw badRequest("不支持的报价阶段"); }; }

    private void reserve(User user, Map<String, Object> p, String stage, long amount) { if (amount <= 0) return; int updated = jdbc.update("UPDATE users SET balance=balance-? WHERE id=? AND balance>=? AND status='ACTIVE'", amount, user.getId(), amount); if (updated != 1) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "余额不足，无法冻结本阶段预估费用"); jdbc.update("INSERT INTO creative_billing_reservations(project_id,user_id,stage,estimated_amount,reserved_amount,status,created_at) VALUES (?,?,?,?,?,'RESERVED',?)", p.get("id"), user.getId(), stage, amount, amount, now()); }
    private void settle(long projectId, String stage, long actual) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_billing_reservations WHERE project_id=? AND stage=? AND status='RESERVED' ORDER BY id", projectId, stage); long remaining = Math.max(0, actual); for (Map<String, Object> row : rows) { long reserved = ((Number) row.get("reserved_amount")).longValue(); long charge = Math.min(reserved, remaining); long refund = reserved - charge; if (refund > 0) jdbc.update("UPDATE users SET balance=balance+? WHERE id=?", refund, row.get("user_id")); jdbc.update("UPDATE creative_billing_reservations SET actual_amount=?,status='SETTLED',settled_at=? WHERE id=?", charge, now(), row.get("id")); remaining -= charge; } }
    private void settleVideo(long projectId) { Map<String, Object> p = projectById(projectId); if (connection(p, "video") != null) { settle(projectId, "VIDEO", 0); return; } Long seconds = jdbc.queryForObject("SELECT COALESCE(SUM(duration),0) FROM creative_shots WHERE project_id=? AND status='SUCCEEDED'", Long.class, projectId); settle(projectId, "VIDEO", (seconds == null ? 0 : seconds) * price("videoSecondPrice", 2000)); }
    private void releaseOpenReservations(long projectId) { for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM creative_billing_reservations WHERE project_id=? AND status='RESERVED'", projectId)) { jdbc.update("UPDATE users SET balance=balance+? WHERE id=?", row.get("reserved_amount"), row.get("user_id")); jdbc.update("UPDATE creative_billing_reservations SET status='RELEASED',actual_amount=0,settled_at=? WHERE id=?", now(), row.get("id")); } }

    private void checkVersion(Map<String, Object> p, Map<String, Object> request) { int expected = integer(request.get("version"), -1, -1, Integer.MAX_VALUE, "版本"); if (expected < 0) throw badRequest("version 必填"); if (((Number) p.get("version")).intValue() != expected) throw conflict("项目已被更新，请刷新后重试"); }
    private void requireStatus(Map<String, Object> p, List<String> allowed) { String status = Objects.toString(p.get("status")); if (!allowed.contains(status)) throw conflict("当前项目状态不允许执行此操作：" + status); }
    private boolean retryable(Exception e) { if (e instanceof ResponseStatusException r) { int code = r.getStatusCode().value(); return code == 429 || code >= 500; } return e instanceof IOException; }
    private int[] dimensions(String ratio, String resolution) { int h = switch (resolution) { case "1080p" -> 1080; case "480p" -> 480; default -> 720; }; return switch (ratio) { case "9:16" -> new int[]{h, h * 16 / 9}; case "1:1" -> new int[]{h, h}; case "4:3" -> new int[]{h * 4 / 3, h}; case "3:4" -> new int[]{h, h * 4 / 3}; default -> new int[]{h * 16 / 9, h}; }; }
    private void download(String url, Path target) throws Exception { HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build(); HttpResponse<Path> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build().send(request, HttpResponse.BodyHandlers.ofFile(target)); if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IOException("下载镜头失败：HTTP " + response.statusCode()); }
    private String workerId() { return "worker-" + UUID.nameUUIDFromBytes(System.getProperty("user.dir").getBytes(StandardCharsets.UTF_8)); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private void requireEnabled() { if (!enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TXT 自动成片功能尚未启用"); }
    private boolean enabled() { return boolSetting(platformConfigs.settings(), "autoMovieEnabled", false); }
    private long price(String key, long fallback) { return longSetting(platformConfigs.settings(), key, fallback); }
    private boolean boolSetting(Map<String, Object> settings, String key, boolean fallback) { Object v=settings.get(key); return v == null ? fallback : v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString()); }
    private int intSetting(Map<String, Object> settings, String key, int fallback) { Object v=settings.get(key); return v instanceof Number n ? n.intValue() : fallback; }
    private long longSetting(Map<String, Object> settings, String key, long fallback) { Object v=settings.get(key); return v instanceof Number n ? n.longValue() : fallback; }
    private String required(Object raw, String field, int max) { String v = optional(raw, null, max); if (!StringUtils.hasText(v)) throw badRequest(field + "不能为空"); return v; }
    private String optional(Object raw, String fallback, int max) { String v = raw == null ? fallback : raw.toString().trim(); if (v != null && v.length() > max) throw badRequest("字段最多 " + max + " 个字符"); return StringUtils.hasText(v) ? v : fallback; }
    private String choice(Object raw, String fallback, List<String> allowed, String field) { String v = optional(raw, fallback, 40); if (!allowed.contains(v)) throw badRequest(field + "不支持"); return v; }
    private int integer(Object raw, int fallback, int min, int max, String field) { if (raw == null) return fallback; try { int v = raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString()); if (v < min || v > max) throw badRequest(field + "范围无效"); return v; } catch (NumberFormatException e) { throw badRequest(field + "必须是整数"); } }
    private Long nullableLong(Object raw) { if (raw == null || raw.toString().isBlank()) return null; try { long v = Long.parseLong(raw.toString()); return v > 0 ? v : null; } catch (NumberFormatException e) { throw badRequest("连接 ID 无效"); } }
    private boolean bool(Object raw, boolean fallback) { return raw == null ? fallback : raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString()); }
    private String requiredNode(JsonNode node, String field, String label) { String value = node.path(field).asText(); if (!StringUtils.hasText(value)) throw badRequest(label + "不能为空"); return value; }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw badRequest("无法序列化创作数据"); } }
    private JsonNode readTree(String value) { try { return objectMapper.readTree(value); } catch (JsonProcessingException e) { throw badRequest("剧本 JSON 无效"); } }
    private Map<String, Object> readMap(String value) { try { return objectMapper.readValue(value, new TypeReference<>() {}); } catch (Exception e) { return new LinkedHashMap<>(); } }
    private List<String> readList(String value) { try { List<String> v = objectMapper.readValue(value, new TypeReference<>() {}); return v == null ? new ArrayList<>() : new ArrayList<>(v); } catch (Exception e) { return new ArrayList<>(); } }
    private String safe(Exception e) { if (e instanceof ResponseStatusException r && StringUtils.hasText(r.getReason())) return r.getReason(); String m = e.getMessage(); return StringUtils.hasText(m) ? m.substring(0, Math.min(1800, m.length())) : "任务执行失败"; }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
