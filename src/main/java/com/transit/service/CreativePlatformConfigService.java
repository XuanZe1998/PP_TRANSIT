package com.transit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.User;
import com.transit.service.creative.CreativeProviderAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CreativePlatformConfigService {
    public static final List<String> CAPABILITIES = List.of("TEXT", "IMAGE", "VIDEO");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ChannelSecretService secretService;
    private final ChannelUrlPolicy urlPolicy;
    private final WebClient webClient;

    public List<Map<String, Object>> connections() {
        return jdbc.queryForList("SELECT * FROM creative_platform_connections ORDER BY capability,id")
                .stream().map(this::connectionView).toList();
    }

    @Transactional
    public Map<String, Object> createConnection(User admin, Map<String, Object> request) {
        requireEncryption();
        String capability = capability(request.get("capability"));
        String provider = provider(request.get("provider"), capability);
        String name = required(request.get("displayName"), "连接名称", 160);
        String baseUrl = validatedUrl(required(request.get("baseUrl"), "Base URL", 1000));
        List<String> models = models(request.get("models"));
        String defaultModel = defaultModel(request.get("defaultModel"), models);
        String apiKey = required(request.get("apiKey"), "API Key", 4000);
        boolean enabled = bool(request.get("enabled"), true);
        boolean makeDefault = bool(request.get("isDefault"), true);
        if (enabled && makeDefault) clearDefault(capability, null);
        jdbc.update("""
                INSERT INTO creative_platform_connections(capability,provider_key,display_name,base_url,api_key,
                  model_ids_json,default_model,enabled,is_default,default_slot,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, capability, provider, name, baseUrl, secretService.encrypt(apiKey), json(models), defaultModel,
                enabled, enabled && makeDefault, enabled && makeDefault ? capability : null, now(), now());
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM creative_platform_connections", Long.class);
        audit(admin, "CREATE", "CONNECTION", id, List.of("capability", "provider", "displayName", "baseUrl", "models", "defaultModel", "enabled", "isDefault", "apiKey"));
        return connection(id);
    }

    @Transactional
    public Map<String, Object> updateConnection(User admin, Long id, Map<String, Object> request) {
        Map<String, Object> existing = rawConnection(id);
        int expectedVersion = integer(request.get("version"), "version", 1, Integer.MAX_VALUE);
        if (((Number) existing.get("version")).intValue() != expectedVersion) throw conflict();
        String capability = capability(request.getOrDefault("capability", existing.get("capability")));
        String provider = provider(request.getOrDefault("provider", existing.get("provider_key")), capability);
        String name = required(request.getOrDefault("displayName", existing.get("display_name")), "连接名称", 160);
        String baseUrl = validatedUrl(required(request.getOrDefault("baseUrl", existing.get("base_url")), "Base URL", 1000));
        List<String> models = request.containsKey("models") ? models(request.get("models")) : readModels(existing.get("model_ids_json"));
        String defaultModel = defaultModel(request.getOrDefault("defaultModel", existing.get("default_model")), models);
        boolean enabled = bool(request.get("enabled"), Boolean.TRUE.equals(existing.get("enabled")));
        boolean makeDefault = bool(request.get("isDefault"), Boolean.TRUE.equals(existing.get("is_default")));
        String encryptedKey = String.valueOf(existing.get("api_key"));
        if (bool(request.get("clearApiKey"), false)) encryptedKey = null;
        else if (StringUtils.hasText(text(request.get("apiKey")))) {
            requireEncryption();
            encryptedKey = secretService.encrypt(required(request.get("apiKey"), "API Key", 4000));
        }
        if (enabled && makeDefault) clearDefault(capability, id);
        int updated = jdbc.update("""
                UPDATE creative_platform_connections SET capability=?,provider_key=?,display_name=?,base_url=?,api_key=?,
                  model_ids_json=?,default_model=?,enabled=?,is_default=?,default_slot=?,version=version+1,updated_at=?
                WHERE id=? AND version=?
                """, capability, provider, name, baseUrl, encryptedKey, json(models), defaultModel, enabled,
                enabled && makeDefault, enabled && makeDefault ? capability : null, now(), id, expectedVersion);
        if (updated != 1) throw conflict();
        audit(admin, "UPDATE", "CONNECTION", id, safeChangedFields(request));
        return connection(id);
    }

    @Transactional
    public void deleteConnection(User admin, Long id) {
        rawConnection(id);
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM creative_tasks WHERE provider_config_id IS NULL AND status IN ('QUEUED','RUNNING')", Long.class);
        if (active != null && active > 0 && Boolean.TRUE.equals(rawConnection(id).get("is_default"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "平台默认连接仍有运行中的创作任务");
        }
        jdbc.update("DELETE FROM creative_platform_connections WHERE id=?", id);
        audit(admin, "DELETE", "CONNECTION", id, List.of());
    }

    public CreativeProviderAccess platformAccess(String requestedCapability, boolean required) {
        String capability = capability(requestedCapability);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM creative_platform_connections
                 WHERE capability=? AND enabled=TRUE AND is_default=TRUE ORDER BY id DESC LIMIT 1
                """, capability);
        if (rows.isEmpty()) {
            if (required) throw new ResponseStatusException(HttpStatus.CONFLICT, capability + " 平台默认模型尚未配置");
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String apiKey;
        try { apiKey = secretService.decrypt(text(row.get("api_key"))); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法解密平台模型密钥，请检查部署根密钥", e); }
        if (!StringUtils.hasText(apiKey)) {
            if (required) throw new ResponseStatusException(HttpStatus.CONFLICT, capability + " 平台默认连接缺少 API Key");
            return null;
        }
        return new CreativeProviderAccess(text(row.get("provider_key")), text(row.get("display_name")),
                text(row.get("base_url")), apiKey, text(row.get("default_model")), readModels(row.get("model_ids_json")));
    }

    public String defaultModel(String capability) {
        CreativeProviderAccess access = platformAccess(capability, false);
        return access == null ? "" : access.defaultModel();
    }

    public boolean encryptionConfigured() { return secretService.isConfigured(); }

    public Map<String, Object> settings() {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM creative_runtime_settings WHERE id=1");
        Map<String, Object> view = new LinkedHashMap<>();
        row.forEach((key, value) -> view.put(camel(key), value));
        return view;
    }

    @Transactional
    public Map<String, Object> updateSettings(User admin, Map<String, Object> request) {
        Map<String, Object> current = settings();
        int version = integer(request.get("version"), "version", 1, Integer.MAX_VALUE);
        if (((Number) current.get("version")).intValue() != version) throw conflict();
        int requestedMinDuration = integer(request.getOrDefault("minDuration", current.get("minDuration")), "最短时长", 1, 600);
        int requestedMaxDuration = integer(request.getOrDefault("maxDuration", current.get("maxDuration")), "最长时长", 1, 600);
        if (requestedMinDuration > requestedMaxDuration) throw bad("最短时长不能大于最长时长");
        int updated = jdbc.update("""
                UPDATE creative_runtime_settings SET auto_movie_enabled=?,script_price=?,image_price=?,video_second_price=?,
                  worker_concurrency=?,video_concurrency=?,max_retries=?,poll_interval_ms=?,max_source_bytes=?,
                  max_source_characters=?,max_image_bytes=?,max_characters=?,max_scenes=?,max_shots=?,min_duration=?,
                  max_duration=?,version=version+1,updated_at=? WHERE id=1 AND version=?
                """,
                bool(request.get("autoMovieEnabled"), bool(current.get("autoMovieEnabled"), false)),
                longNumber(request.getOrDefault("scriptPrice", current.get("scriptPrice")), "剧本价格", 0, Long.MAX_VALUE),
                longNumber(request.getOrDefault("imagePrice", current.get("imagePrice")), "图片价格", 0, Long.MAX_VALUE),
                longNumber(request.getOrDefault("videoSecondPrice", current.get("videoSecondPrice")), "视频每秒价格", 0, Long.MAX_VALUE),
                integer(request.getOrDefault("workerConcurrency", current.get("workerConcurrency")), "Worker 并发", 1, 20),
                integer(request.getOrDefault("videoConcurrency", current.get("videoConcurrency")), "视频并发", 1, 20),
                integer(request.getOrDefault("maxRetries", current.get("maxRetries")), "重试次数", 0, 10),
                integer(request.getOrDefault("pollIntervalMs", current.get("pollIntervalMs")), "轮询间隔", 1000, 60000),
                longNumber(request.getOrDefault("maxSourceBytes", current.get("maxSourceBytes")), "TXT 大小", 1, 10 * 1024 * 1024),
                integer(request.getOrDefault("maxSourceCharacters", current.get("maxSourceCharacters")), "TXT 字符数", 1, 1_000_000),
                longNumber(request.getOrDefault("maxImageBytes", current.get("maxImageBytes")), "图片大小", 1, 100 * 1024 * 1024),
                integer(request.getOrDefault("maxCharacters", current.get("maxCharacters")), "角色数", 1, 32),
                integer(request.getOrDefault("maxScenes", current.get("maxScenes")), "场景数", 1, 32),
                integer(request.getOrDefault("maxShots", current.get("maxShots")), "镜头数", 1, 24),
                requestedMinDuration, requestedMaxDuration, now(), version);
        if (updated != 1) throw conflict();
        audit(admin, "UPDATE", "RUNTIME_SETTINGS", 1, safeChangedFields(request));
        return settings();
    }

    public StorageConfig storage(boolean revealSecrets) {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM creative_storage_configs WHERE id=1");
        String access = text(row.get("access_key"));
        String secret = text(row.get("secret_key"));
        if (revealSecrets) {
            try { access = secretService.decrypt(access); secret = secretService.decrypt(secret); }
            catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "无法解密 S3 凭据，请检查部署根密钥", e); }
        }
        return new StorageConfig(text(row.get("endpoint")), text(row.get("region_name")), text(row.get("bucket_name")),
                text(row.get("public_base_url")), access, secret, Boolean.TRUE.equals(row.get("path_style")),
                ((Number) row.get("signed_url_seconds")).intValue(), Boolean.TRUE.equals(row.get("enabled")),
                ((Number) row.get("version")).intValue());
    }

    public Map<String, Object> storageView() {
        StorageConfig c = storage(false);
        return Map.ofEntries(Map.entry("storageType", "S3"), Map.entry("endpoint", nvl(c.endpoint())),
                Map.entry("region", nvl(c.region())), Map.entry("bucket", nvl(c.bucket())),
                Map.entry("publicBaseUrl", nvl(c.publicBaseUrl())), Map.entry("pathStyle", c.pathStyle()),
                Map.entry("signedUrlSeconds", c.signedUrlSeconds()), Map.entry("enabled", c.enabled()),
                Map.entry("accessKeyConfigured", StringUtils.hasText(c.accessKey())), Map.entry("accessKeyPreview", mask(c.accessKey())),
                Map.entry("secretKeyConfigured", StringUtils.hasText(c.secretKey())), Map.entry("secretKeyPreview", mask(c.secretKey())),
                Map.entry("version", c.version()));
    }

    @Transactional
    public Map<String, Object> updateStorage(User admin, Map<String, Object> request) {
        StorageConfig old = storage(false);
        int version = integer(request.get("version"), "version", 1, Integer.MAX_VALUE);
        if (old.version() != version) throw conflict();
        String endpoint = validatedUrl(required(request.getOrDefault("endpoint", old.endpoint()), "S3 Endpoint", 1000));
        String publicUrl = validatedHttpsUrl(required(request.getOrDefault("publicBaseUrl", old.publicBaseUrl()), "公开 HTTPS 地址", 1000));
        String region = required(request.getOrDefault("region", old.region()), "Region", 120);
        String bucket = required(request.getOrDefault("bucket", old.bucket()), "Bucket", 255);
        String access = old.accessKey(), secret = old.secretKey();
        if (bool(request.get("clearAccessKey"), false)) access = null;
        else if (StringUtils.hasText(text(request.get("accessKey")))) { requireEncryption(); access = secretService.encrypt(required(request.get("accessKey"), "Access Key", 4000)); }
        if (bool(request.get("clearSecretKey"), false)) secret = null;
        else if (StringUtils.hasText(text(request.get("secretKey")))) { requireEncryption(); secret = secretService.encrypt(required(request.get("secretKey"), "Secret Key", 4000)); }
        int updated = jdbc.update("""
                UPDATE creative_storage_configs SET endpoint=?,region_name=?,bucket_name=?,public_base_url=?,access_key=?,
                  secret_key=?,path_style=?,signed_url_seconds=?,enabled=?,version=version+1,updated_at=? WHERE id=1 AND version=?
                """, endpoint, region, bucket, publicUrl, access, secret,
                bool(request.get("pathStyle"), old.pathStyle()),
                integer(request.getOrDefault("signedUrlSeconds", old.signedUrlSeconds()), "签名时长", 60, 86400),
                bool(request.get("enabled"), old.enabled()), now(), version);
        if (updated != 1) throw conflict();
        audit(admin, "UPDATE", "STORAGE", 1, safeChangedFields(request));
        return storageView();
    }

    public Map<String, Object> testConnection(Long id) {
        CreativeProviderAccess access = accessForConnection(id);
        long started = System.nanoTime();
        String base = access.baseUrl().replaceAll("/+$", "");
        String endpoint = "VIDEO".equals(capabilityOf(id))
                ? (base.endsWith("/api/v3") ? base + "/contents/generations/tasks?page_num=1&page_size=1" : base + "/api/v3/contents/generations/tasks?page_num=1&page_size=1")
                : base + (base.endsWith("/v1") ? "/models" : "/v1/models");
        try {
            webClient.get().uri(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + access.apiKey()).retrieve().toBodilessEntity().block();
            return Map.of("ok", true, "latencyMs", (System.nanoTime() - started) / 1_000_000, "message", "连接成功");
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型连接测试失败", e);
        }
    }

    private CreativeProviderAccess accessForConnection(Long id) {
        Map<String, Object> row = rawConnection(id);
        String key = secretService.decrypt(text(row.get("api_key")));
        if (!StringUtils.hasText(key)) throw new ResponseStatusException(HttpStatus.CONFLICT, "连接缺少 API Key");
        return new CreativeProviderAccess(text(row.get("provider_key")), text(row.get("display_name")), text(row.get("base_url")), key,
                text(row.get("default_model")), readModels(row.get("model_ids_json")));
    }

    private String capabilityOf(Long id) { return text(rawConnection(id).get("capability")); }
    private Map<String, Object> connection(Long id) { return connectionView(rawConnection(id)); }
    private Map<String, Object> rawConnection(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM creative_platform_connections WHERE id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台模型连接不存在");
        return rows.get(0);
    }
    private Map<String, Object> connectionView(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.get("id")); result.put("capability", row.get("capability"));
        result.put("provider", row.get("provider_key")); result.put("displayName", row.get("display_name"));
        result.put("baseUrl", row.get("base_url")); result.put("models", readModels(row.get("model_ids_json")));
        result.put("defaultModel", row.get("default_model")); result.put("enabled", row.get("enabled"));
        result.put("isDefault", row.get("is_default")); result.put("version", row.get("version"));
        String stored = text(row.get("api_key"));
        result.put("apiKeyConfigured", StringUtils.hasText(stored)); result.put("apiKeyPreview", mask(stored));
        result.put("createdAt", row.get("created_at")); result.put("updatedAt", row.get("updated_at"));
        return result;
    }
    private void clearDefault(String capability, Long exceptId) {
        if (exceptId == null) jdbc.update("UPDATE creative_platform_connections SET is_default=FALSE,default_slot=NULL WHERE capability=?", capability);
        else jdbc.update("UPDATE creative_platform_connections SET is_default=FALSE,default_slot=NULL WHERE capability=? AND id<>?", capability, exceptId);
    }
    private void audit(User admin, String action, String target, Object id, List<String> fields) {
        jdbc.update("INSERT INTO creative_config_audit(admin_user_id,action_name,target_type,target_id,changed_fields_json,created_at) VALUES (?,?,?,?,?,?)",
                admin == null ? null : admin.getId(), action, target, id == null ? null : id.toString(), json(fields), now());
    }
    private List<String> safeChangedFields(Map<String, Object> request) {
        return request.keySet().stream().filter(k -> !"version".equals(k)).map(k -> switch (k) {
            case "apiKey", "accessKey", "secretKey" -> k + "Changed"; default -> k;
        }).toList();
    }
    private String provider(Object raw, String capability) {
        String value = required(raw, "协议", 80).toLowerCase(Locale.ROOT);
        List<String> allowed = switch (capability) { case "TEXT" -> List.of("openai-chat"); case "IMAGE" -> List.of("openai-image"); default -> List.of("seedance"); };
        if (!allowed.contains(value)) throw bad("能力与协议不匹配");
        return value;
    }
    private String capability(Object raw) {
        String value = required(raw, "能力", 20).toUpperCase(Locale.ROOT);
        if (!CAPABILITIES.contains(value)) throw bad("能力仅支持 TEXT、IMAGE、VIDEO");
        return value;
    }
    private List<String> models(Object raw) {
        List<?> input = raw instanceof List<?> list ? list : raw == null ? List.of() : List.of(raw.toString().split("[,\\r\\n]+"));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Object item : input) { String v = text(item); if (StringUtils.hasText(v)) { if (v.length() > 160) throw bad("模型 ID 过长"); out.add(v); } }
        if (out.isEmpty() || out.size() > 30) throw bad("模型列表需要 1–30 项");
        return new ArrayList<>(out);
    }
    private String defaultModel(Object raw, List<String> models) { String value = text(raw); if (!StringUtils.hasText(value)) value = models.get(0); if (!models.contains(value)) throw bad("默认模型必须在模型列表中"); return value; }
    private List<String> readModels(Object json) { try { return objectMapper.readValue(text(json), new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
    private String json(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw bad("配置无法序列化"); } }
    private String validatedUrl(String value) { try { URI uri=URI.create(value.replaceAll("/+$", "")); if (uri.getQuery()!=null || uri.getFragment()!=null) throw bad("URL 不能包含查询参数或锚点"); urlPolicy.validate(uri.toString()); return uri.toString(); } catch (IllegalArgumentException e) { throw bad("URL 格式不正确"); } }
    private String validatedHttpsUrl(String value) { String result=validatedUrl(value); if (!result.toLowerCase(Locale.ROOT).startsWith("https://")) throw bad("公开地址必须使用 HTTPS"); return result; }
    private void requireEncryption() { if (!secretService.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "部署根密钥尚未配置"); }
    private String required(Object raw, String field, int max) { String v=text(raw); if (!StringUtils.hasText(v)) throw bad(field+"不能为空"); if (v.length()>max) throw bad(field+"最多 "+max+" 个字符"); return v; }
    private String text(Object raw) { return raw == null ? null : raw.toString().trim(); }
    private boolean bool(Object raw, boolean fallback) { return raw == null ? fallback : raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString()); }
    private int integer(Object raw, String field, int min, int max) { long v=longNumber(raw,field,min,max); return (int)v; }
    private long longNumber(Object raw, String field, long min, long max) { try { long v=raw instanceof Number n?n.longValue():Long.parseLong(text(raw)); if(v<min||v>max) throw bad(field+"超出范围"); return v; } catch(NumberFormatException e){ throw bad(field+"必须是整数"); } }
    private String camel(String key) { StringBuilder b=new StringBuilder(); boolean upper=false; for(char c:key.toCharArray()){ if(c=='_'){upper=true;} else {b.append(upper?Character.toUpperCase(c):c);upper=false;} } return b.toString(); }
    private String mask(String stored) { return StringUtils.hasText(stored) ? "****" : ""; }
    private String nvl(String value) { return value == null ? "" : value; }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"配置已被其他管理员修改，请刷新后重试"); }

    public record StorageConfig(String endpoint, String region, String bucket, String publicBaseUrl,
                                String accessKey, String secretKey, boolean pathStyle,
                                int signedUrlSeconds, boolean enabled, int version) { }
}
