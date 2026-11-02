package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Official public marketing API. Credentials are only sent to the fixed API origin. */
@Service
@RequiredArgsConstructor
public class XiaoyunqueService {
    private static final String ORIGIN = "https://xyq.jianying.com";
    private final JdbcTemplate jdbc;
    private final ChannelSecretService secrets;
    private final ObjectMapper json;
    private final WebClient webClient;

    public Map<String, Object> settings(User user) {
        return Map.of("configured", !jdbc.queryForList("SELECT user_id FROM creative_xyq_credentials WHERE user_id=?", user.getId()).isEmpty());
    }

    public Map<String, Object> saveKey(User user, String key) {
        key = required(key, "Access Key", 4000);
        if (key.chars().anyMatch(Character::isISOControl)) throw bad("Access Key 格式无效");
        if (!secrets.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "请先配置服务端 security.data-encryption-key");
        String encrypted = secrets.encrypt(key);
        jdbc.update("INSERT INTO creative_xyq_credentials(user_id,access_key) VALUES (?,?) ON DUPLICATE KEY UPDATE access_key=?,updated_at=CURRENT_TIMESTAMP", user.getId(), encrypted, encrypted);
        return Map.of("configured", true);
    }

    public void deleteKey(User user) {
        jdbc.update("DELETE FROM creative_xyq_credentials WHERE user_id=?", user.getId());
        // Task snapshots remain encrypted so submitted jobs can still be queried.
    }

    public JsonNode balance(User user) {
        return post(key(user), "/api/biz/v1/skill/get_credit_balance", Map.of()).path("data");
    }

    public Map<String, Object> upload(User user, MultipartFile file) {
        if (file.isEmpty() || file.getSize() > 5L * 1024 * 1024) throw bad("素材必须非空且不超过5MB");
        String key = key(user);
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (java.io.IOException e) { throw bad("读取素材失败"); }
        var parts = new LinkedMultiValueMap<String, Object>();
        String filename = Objects.toString(file.getOriginalFilename(), "asset").replaceAll("[^\\p{L}\\p{N}._-]", "_");
        var fileHeaders = new HttpHeaders();
        try { fileHeaders.setContentType(MediaType.parseMediaType(Objects.toString(file.getContentType(), "application/octet-stream"))); }
        catch (IllegalArgumentException e) { throw bad("素材类型无效"); }
        parts.add("file", new HttpEntity<>(new ByteArrayResource(bytes) { @Override public String getFilename() { return filename; } }, fileHeaders));
        JsonNode result = response(webClient.post().uri(ORIGIN + "/api/biz/v1/skill/upload_file")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key).accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(BodyInserters.fromMultipartData(parts)));
        String id = required(result.path("data").path("pippit_asset_id").asText(), "素材 ID", 160);
        jdbc.update("INSERT INTO creative_xyq_assets(id,user_id,key_fingerprint) VALUES (?,?,?) ON DUPLICATE KEY UPDATE key_fingerprint=?", id, user.getId(), fingerprint(key), fingerprint(key));
        return Map.of("id", id, "name", filename);
    }

    public Map<String, Object> submit(User user, JsonNode request) {
        String key = key(user);
        String message = required(request.path("message").asText(), "创作要求", 10000);
        JsonNode settings = request.path("general_agent_settings");
        validateSettings(settings);
        List<String> assets = new ArrayList<>();
        JsonNode inputAssets = request.path("asset_ids");
        if (!inputAssets.isMissingNode() && !inputAssets.isArray()) throw bad("素材列表无效");
        if (inputAssets.size() > 20) throw bad("每次最多20个素材");
        for (JsonNode asset : inputAssets) {
            String id = required(asset.asText(), "素材 ID", 160);
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM creative_xyq_assets WHERE user_id=? AND id=? AND key_fingerprint=?", Integer.class, user.getId(), id, fingerprint(key));
            if (count == null || count == 0) throw bad("素材不属于当前用户或 Access Key，请重新上传");
            assets.add(id);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message); body.put("asset_ids", assets); body.put("general_agent_settings", settings);
        String continuation = request.path("continuationId").asText("");
        if (!continuation.isBlank()) {
            Map<String, Object> previous = owned(user, continuation);
            if (!key.equals(secrets.decrypt((String) previous.get("access_key")))) throw bad("续写必须使用原 Access Key");
            body.put("thread_id", required(Objects.toString(previous.get("thread_id"), ""), "会话 ID", 160));
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO creative_xyq_runs(id,user_id,access_key,message,state) VALUES (?,?,?,?,?)", id, user.getId(), secrets.encrypt(key), message, "SUBMITTING");
        try {
            JsonNode result = post(key, "/api/biz/v1/agent/submit_marketing_run", body).path("data");
            String thread = required(result.path("run").path("thread_id").asText(), "上游会话 ID", 160);
            String run = required(result.path("run").path("run_id").asText(), "上游任务 ID", 160);
            jdbc.update("UPDATE creative_xyq_runs SET thread_id=?,run_id=?,state=?,result_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", thread, run, result.path("run").path("state").asText("1"), result.toString(), id);
        } catch (RuntimeException e) {
            // A timeout may occur after the provider accepted the request. Never retry submission automatically.
            jdbc.update("UPDATE creative_xyq_runs SET state='UNKNOWN',updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "提交结果未确认，请先在小云雀检查任务，避免重复扣积分；本地记录：" + id);
        }
        return view(owned(user, id));
    }

    static void validateSettings(JsonNode s) {
        if (!s.isObject()) throw bad("生成设置无效");
        required(s.path("video_model").asText(), "视频模型", 160);
        if (!s.path("ratio").isIntegralNumber() || !s.path("ratio").canConvertToInt() || !Set.of(2,3,4,5,6).contains(s.path("ratio").intValue())) throw bad("画面比例无效");
        for (String field : List.of("duration_start", "duration_end")) {
            if (!s.path(field).isIntegralNumber() || !s.path(field).canConvertToInt() || s.path(field).intValue() < 1) throw bad("视频时长必须为正整数");
        }
        if (s.path("duration_start").intValue() > s.path("duration_end").intValue()) throw bad("时长下限不能大于上限");
        if (!s.path("show_subtitle").isBoolean()) throw bad("字幕开关无效");
        if (!Set.of("480p", "720p", "1080p").contains(s.path("video_resolution").asText())) throw bad("清晰度无效");
        Set<String> allowed = Set.of("video_model", "ratio", "duration_start", "duration_end", "show_subtitle", "video_resolution");
        s.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) throw bad("不支持的生成设置"); });
    }

    public Map<String, Object> refresh(User user, String id) {
        Map<String, Object> row = owned(user, id);
        if (row.get("run_id") == null) return view(row);
        JsonNode data = post(secrets.decrypt((String) row.get("access_key")), "/api/biz/v1/agent/query_generate_video_result", Map.of("thread_id", row.get("thread_id"), "run_id", row.get("run_id"))).path("data");
        JsonNode previous = (JsonNode) view(row).get("result");
        if (data.isObject() && !data.has("web_thread_link") && previous.has("web_thread_link")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) data).set("web_thread_link", previous.get("web_thread_link"));
        }
        jdbc.update("UPDATE creative_xyq_runs SET state=?,result_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", data.path("run_state").asText("0"), data.toString(), id, user.getId());
        return view(owned(user, id));
    }

    public Map<String, Object> list(User user, int page, int size, boolean all, String query) {
        if (page < 1 || !Set.of(10,20,50,100).contains(size)) throw bad("分页参数无效");
        String q = Objects.toString(query, "").trim();
        if (q.length() > 160) throw bad("搜索最多160个字符");
        String pattern = "%" + q.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String where = " WHERE user_id=? AND message LIKE ? ESCAPE '!'";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM creative_xyq_runs" + where, Long.class, user.getId(), pattern);
        long count = total == null ? 0 : total;
        if (all && count > 200) throw bad("超过200条，请分页或缩小筛选");
        int limit = all ? 200 : size;
        int current = (int) Math.min(page, Math.max(1, (count + limit - 1) / limit));
        var rows = jdbc.queryForList("SELECT * FROM creative_xyq_runs" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", user.getId(), pattern, limit, (long)(current - 1) * limit);
        return Map.of("total", count, "page", current, "size", limit, "items", rows.stream().map(this::view).toList());
    }

    public Map<String, Object> media(User user, String id, int page, int size, boolean all) {
        Map<String, Object> run = owned(user, id);
        if (page < 1 || !Set.of(10,20,50,100).contains(size)) throw bad("分页参数无效");
        List<Map<String,String>> items = new ArrayList<>();
        if ("3".equals(run.get("state"))) {
            JsonNode data = (JsonNode) view(run).get("result");
            for (String type : List.of("video", "image")) for (JsonNode value : data.path(type + "_urls")) {
                String url = value.asText("");
                if (safeUrl(url)) items.add(Map.of("type", type, "url", url));
            }
        }
        if (all && items.size() > 200) throw bad("超过200条，请分页查看");
        int limit = all ? 200 : size;
        int current = Math.min(page, Math.max(1, (items.size() + limit - 1) / limit));
        int from = Math.min((current-1)*limit, items.size());
        return Map.of("total", items.size(), "page", current, "size", limit, "items", items.subList(from, Math.min(from+limit, items.size())));
    }

    private static boolean safeUrl(String url) {
        try { var uri = java.net.URI.create(url); return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null; }
        catch (IllegalArgumentException e) { return false; }
    }

    private Map<String, Object> owned(User user, String id) {
        var rows = jdbc.queryForList("SELECT * FROM creative_xyq_runs WHERE id=? AND user_id=?", id, user.getId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在");
        return rows.get(0);
    }
    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.remove("access_key"); result.remove("user_id");
        Object raw = result.remove("result_json");
        try { result.put("result", raw == null ? json.createObjectNode() : json.readTree(raw.toString())); }
        catch (Exception e) { result.put("result", json.createObjectNode()); }
        return result;
    }
    private String key(User user) {
        var keys = jdbc.queryForList("SELECT access_key FROM creative_xyq_credentials WHERE user_id=?", String.class, user.getId());
        if (keys.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请先配置小云雀 Access Key");
        return secrets.decrypt(keys.get(0));
    }
    private JsonNode post(String key, String path, Object body) {
        return response(webClient.post().uri(ORIGIN + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + key)
                .accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).bodyValue(body));
    }
    private JsonNode response(WebClient.RequestHeadersSpec<?> request) {
        try {
            JsonNode result = request.retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(60));
            if (result == null || !"0".equals(result.path("ret").asText())) {
                // Never expose arbitrary upstream text which could contain credentials.
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "小云雀拒绝请求，请检查 Access Key、积分、模型权限和参数");
            }
            // Keep only the documented result fields; arbitrary upstream fields never reach storage or the browser.
            var clean = json.createObjectNode();
            clean.put("ret", 0);
            var data = clean.putObject("data");
            JsonNode source = result.path("data");
            for (String field : List.of("pippit_asset_id", "total_remain_amount", "thread_id", "run_id", "run_state", "web_thread_link")) {
                if (source.has(field) && source.get(field).isValueNode()) data.set(field, source.get(field));
            }
            if (source.path("run").isObject()) {
                var run = data.putObject("run");
                for (String field : List.of("thread_id", "run_id", "state")) if(source.path("run").has(field)) run.set(field, source.path("run").get(field));
            }
            for (String field : List.of("video_urls", "image_urls")) {
                var urls = data.putArray(field);
                if (source.path(field).isArray()) for (JsonNode value : source.get(field)) if(value.isTextual() && safeUrl(value.asText())) urls.add(value.asText());
            }
            if (source.path("fail_reason").isObject()) data.putObject("fail_reason").put("message", "小云雀生成失败，请在小云雀会话查看原因");
            return clean;
        } catch (ResponseStatusException e) { throw e; }
        catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "小云雀请求失败，请稍后查询状态"); }
    }
    private static String fingerprint(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法计算密钥指纹"); }
    }
    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw bad(label + "不能为空且最多" + max + "个字符");
        return value.trim();
    }
    private static ResponseStatusException bad(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
