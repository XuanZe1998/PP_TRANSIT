package com.transit.service;

import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModelIdentityService {
    public static final int RANK_INFERRED = 100;
    public static final int RANK_MAPPING = 200;
    public static final int RANK_PROVIDER_CATALOG = 300;
    public static final int RANK_VERIFIED_PROVIDER = 400;

    private final JdbcTemplate jdbc;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void synchronizeExistingMappings() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT mm.public_model_name,mm.channel_model_name,mm.vendor,mm.capability,
                       mm.input_modalities,mm.output_modalities,mm.protocols,c.id channel_id,
                       COALESCE(c.source_code,c.type) source_code,g.platform group_platform,
                       pm.vendor provider_vendor,pm.capability provider_capability,
                       pm.input_modalities provider_inputs,pm.output_modalities provider_outputs,
                       pm.protocols provider_protocols,pm.verification_status
                FROM model_mappings mm JOIN channels c ON c.id=mm.channel_id
                LEFT JOIN aiapibank_provider_groups g ON g.channel_id=c.id
                LEFT JOIN provider_models pm ON LOWER(pm.source_code)=LOWER(COALESCE(c.source_code,c.type))
                    AND LOWER(pm.upstream_model_name)=LOWER(mm.channel_model_name)
                """);
        int synchronizedCount = 0;
        for (Map<String, Object> row : rows) {
            String source = text(row, "source_code", "other");
            String upstream = text(row, "channel_model_name", text(row, "public_model_name", ""));
            if (upstream.isBlank()) continue;
            boolean verified = "AVAILABLE".equalsIgnoreCase(text(row, "verification_status", ""));
            boolean catalog = "aiapibank".equalsIgnoreCase(source) || value(row, "provider_vendor") != null;
            registerAlias(source, upstream, identityName(source, text(row, "public_model_name", upstream), upstream),
                    first(row, "provider_vendor", "group_platform", "vendor"),
                    first(row, "provider_capability", "capability"),
                    first(row, "provider_inputs", "input_modalities"),
                    first(row, "provider_outputs", "output_modalities"),
                    first(row, "provider_protocols", "protocols"),
                    verified ? RANK_VERIFIED_PROVIDER : catalog ? RANK_PROVIDER_CATALOG : RANK_MAPPING);
            synchronizedCount++;
        }
        log.info("Model marketplace identities synchronized: mappings={}", synchronizedCount);
    }

    @Transactional
    public String register(Channel channel, ModelMapping mapping, String catalogPublisher, int rank) {
        if (channel == null || mapping == null) return null;
        String source = Objects.toString(channel.getSourceCode(), channel.getType());
        String upstream = mapping.getChannelModelName();
        return registerAlias(source, upstream, identityName(source, mapping.getPublicModelName(), upstream),
                useful(catalogPublisher) ? catalogPublisher : mapping.getVendor(), mapping.getCapability(),
                mapping.getInputModalities(), mapping.getOutputModalities(), mapping.getProtocols(), rank);
    }

    @Transactional
    public String register(String sourceCode, String upstreamModelName, String publisherHint,
                           String capability, String inputs, String outputs, String protocols, int rank) {
        return registerAlias(sourceCode, upstreamModelName, upstreamModelName, publisherHint,
                capability, inputs, outputs, protocols, rank);
    }

    private String registerAlias(String sourceCode, String upstreamModelName, String canonicalModelName,
                           String publisherHint, String capability, String inputs, String outputs,
                           String protocols, int rank) {
        String source = normalize(sourceCode);
        String upstream = normalize(upstreamModelName);
        String canonical = normalize(canonicalModelName);
        if (source.isBlank() || upstream.isBlank() || canonical.isBlank()) return null;
        String publisher = publisherCode(publisherHint, upstream);
        String inferredKey = publisher + ":" + canonicalName(canonical, publisher);
        Alias alias = existingAliasRecord(source, upstream);
        String key = alias == null ? inferredKey : alias.key();
        if (alias == null) {
            jdbc.update("""
                    INSERT INTO model_identity_aliases(source_code,upstream_model_name,comparison_key,explicit_override,created_at,updated_at)
                    VALUES (?,?,?,FALSE,?,?)
                    """, source, upstream, inferredKey, LocalDateTime.now(), LocalDateTime.now());
        } else if (!alias.explicitOverride() && !alias.key().equals(inferredKey)) {
            key = inferredKey;
            jdbc.update("""
                    UPDATE model_identity_aliases SET comparison_key=?,updated_at=?
                    WHERE source_code=? AND upstream_model_name=? AND explicit_override=FALSE
                    """, key, LocalDateTime.now(), source, upstream);
        }
        String safeCapability = normalizeCapability(capability, upstream);
        String safeInputs = useful(inputs) ? inputs.trim().toLowerCase(Locale.ROOT)
                : Set.of("vision", "image", "video").contains(safeCapability) ? "text,image"
                : "transcription".equals(safeCapability) ? "audio" : "text";
        String safeOutputs = useful(outputs) ? outputs.trim().toLowerCase(Locale.ROOT)
                : switch (safeCapability) {
                    case "image", "video", "music" -> safeCapability;
                    case "speech" -> "audio";
                    case "embedding", "rerank" -> "vector";
                    default -> "text";
                };
        String safeProtocols = useful(protocols) ? protocols.trim().toLowerCase(Locale.ROOT)
                : defaultProtocol(safeCapability);
        String displayName = canonicalName(canonical, publisher);
        List<Integer> currentRanks = jdbc.queryForList(
                "SELECT metadata_rank FROM model_catalog_identities WHERE comparison_key=?", Integer.class, key);
        if (currentRanks.isEmpty()) {
            jdbc.update("""
                    INSERT INTO model_catalog_identities(comparison_key,display_name,publisher_code,publisher_name,
                    category,capability,input_modalities,output_modalities,protocols,metadata_rank,metadata_source,
                    created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, key, displayName, publisher, publisherName(publisher), category(safeCapability, safeInputs, safeOutputs),
                    safeCapability, safeInputs, safeOutputs, safeProtocols, rank, source, LocalDateTime.now(), LocalDateTime.now());
        } else if (rank >= currentRanks.get(0)) {
            jdbc.update("""
                    UPDATE model_catalog_identities SET display_name=?,publisher_code=?,publisher_name=?,category=?,
                    capability=?,input_modalities=?,output_modalities=?,protocols=?,metadata_rank=?,metadata_source=?,updated_at=?
                    WHERE comparison_key=?
                    """, displayName, publisher, publisherName(publisher), category(safeCapability, safeInputs, safeOutputs),
                    safeCapability, safeInputs, safeOutputs, safeProtocols, rank, source, LocalDateTime.now(), key);
        }
        return key;
    }

    public String inferredKey(String sourceCode, String upstreamModelName, String publisherHint) {
        return inferredKey(sourceCode, upstreamModelName, publisherHint, upstreamModelName);
    }

    public String inferredKey(String sourceCode, String upstreamModelName, String publisherHint, String canonicalModelName) {
        String source = normalize(sourceCode);
        String upstream = normalize(upstreamModelName);
        String existing = existingAlias(source, upstream);
        if (existing != null) return existing;
        String publisher = publisherCode(publisherHint, upstream);
        return publisher + ":" + canonicalName(normalize(canonicalModelName), publisher);
    }

    public String canonicalKey(String modelName, String publisherHint) {
        String canonical = normalize(modelName);
        String publisher = publisherCode(publisherHint, canonical);
        return publisher + ":" + canonicalName(canonical, publisher);
    }

    private String existingAlias(String source, String upstream) {
        Alias alias = existingAliasRecord(source, upstream);
        return alias == null ? null : alias.key();
    }

    private Alias existingAliasRecord(String source, String upstream) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT comparison_key,explicit_override FROM model_identity_aliases
                WHERE source_code=? AND upstream_model_name=? LIMIT 1
                """, source, upstream);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        Object override = value(row, "explicit_override");
        boolean explicit = override instanceof Boolean flag ? flag
                : override instanceof Number number ? number.intValue() != 0
                : Boolean.parseBoolean(Objects.toString(override, "false"));
        return new Alias(text(row, "comparison_key", ""), explicit);
    }

    private String identityName(String source, String publicName, String upstream) {
        return "aiapibank".equalsIgnoreCase(source) ? upstream
                : useful(publicName) ? publicName : upstream;
    }

    private record Alias(String key, boolean explicitOverride) { }

    public static String publisherCode(String hint, String modelName) {
        String hinted = normalize(hint);
        if (Set.of("openai", "anthropic", "google", "meta", "mistral", "deepseek", "xai", "zai",
                "moonshot", "alibaba", "nvidia", "minimax", "baai", "stepfun").contains(hinted)) return hinted;
        if ("grok".equals(hinted)) return "xai";
        if (Set.of("zhipu", "glm").contains(hinted)) return "zai";
        if (Set.of("kimi", "moonshotai").contains(hinted)) return "moonshot";
        if (Set.of("qwen", "dashscope").contains(hinted)) return "alibaba";
        String name = normalize(modelName);
        if (name.contains("claude")) return "anthropic";
        if (name.contains("deepseek")) return "deepseek";
        if (name.contains("grok")) return "xai";
        if (name.contains("gemini") || name.contains("gemma") || name.startsWith("google/")) return "google";
        if (name.contains("llama") || name.startsWith("meta/")) return "meta";
        if (name.contains("mistral")) return "mistral";
        if (name.contains("qwen")) return "alibaba";
        if (name.contains("glm") || name.startsWith("z-ai/")) return "zai";
        if (name.contains("kimi") || name.startsWith("moonshot/")) return "moonshot";
        if (name.contains("minimax")) return "minimax";
        if (name.startsWith("nvidia/") || name.contains("nemotron")) return "nvidia";
        if (name.startsWith("baai/") || name.contains("bge-")) return "baai";
        if (name.startsWith("stepfun/") || name.startsWith("step-")) return "stepfun";
        if (name.startsWith("gpt-") || name.startsWith("openai/") || name.contains("codex")) return "openai";
        return "unknown";
    }

    public static String publisherName(String code) {
        return switch (normalize(code)) {
            case "openai" -> "OpenAI"; case "anthropic" -> "Anthropic"; case "google" -> "Google";
            case "meta" -> "Meta"; case "mistral" -> "Mistral AI"; case "deepseek" -> "DeepSeek AI";
            case "xai" -> "xAI"; case "zai" -> "Z.ai"; case "moonshot" -> "Moonshot";
            case "alibaba" -> "Qwen"; case "nvidia" -> "NVIDIA"; case "minimax" -> "MiniMax";
            case "baai" -> "BAAI"; case "stepfun" -> "StepFun"; default -> "未声明";
        };
    }

    public static String category(String capability, String inputs, String outputs) {
        String cap = normalize(capability);
        String in = normalize(inputs); String out = normalize(outputs);
        if ("video".equals(cap) || contains(out, "video")) return "video";
        if ("image".equals(cap) || contains(out, "image")) return "image";
        if (Set.of("speech", "transcription", "music", "audio").contains(cap)
                || contains(in, "audio") || contains(out, "audio") || contains(out, "music")) return "audio";
        if ("vision".equals(cap) || (contains(in, "text") && contains(in, "image"))) return "multimodal";
        if (Set.of("embedding", "rerank").contains(cap) || contains(out, "vector")) return "vector";
        return "language";
    }

    private static String canonicalName(String upstream, String publisher) {
        int slash = upstream.indexOf('/');
        if (slash <= 0) return upstream;
        String prefix = upstream.substring(0, slash).replace("-ai", "");
        Set<String> accepted = switch (publisher) {
            case "openai" -> Set.of("openai"); case "anthropic" -> Set.of("anthropic");
            case "google" -> Set.of("google"); case "meta" -> Set.of("meta");
            case "deepseek" -> Set.of("deepseek", "deepseekai"); case "xai" -> Set.of("xai");
            case "moonshot" -> Set.of("moonshot", "moonshotai"); case "nvidia" -> Set.of("nvidia");
            case "alibaba" -> Set.of("qwen", "alibaba"); default -> Set.of();
        };
        return accepted.contains(prefix) ? upstream.substring(slash + 1) : upstream;
    }

    private static String normalizeCapability(String value, String model) {
        String cap = normalize(value);
        if (useful(cap) && !"unknown".equals(cap) && !"aiapibank".equals(cap)) return cap;
        String name = normalize(model);
        if (name.contains("image") || name.contains("seedream")) return "image";
        if (name.contains("video") || name.contains("veo") || name.contains("kling")) return "video";
        if (name.contains("embed")) return "embedding";
        if (name.contains("rerank")) return "rerank";
        if (name.contains("vision") || name.contains("-vl")) return "vision";
        if (name.contains("reason") || name.contains("o1") || name.contains("o3")) return "reasoning";
        return "text";
    }

    private static String defaultProtocol(String capability) {
        return switch (capability) {
            case "image" -> "images"; case "video" -> "tasks"; case "embedding" -> "embeddings";
            case "rerank" -> "reranks"; case "speech" -> "audio-speech";
            case "transcription" -> "audio-transcriptions"; default -> "chat-completions";
        };
    }

    private String first(Map<String, Object> row, String... keys) {
        for (String key : keys) { String text = text(row, key, ""); if (useful(text) && !"unknown".equalsIgnoreCase(text)) return text; }
        return "";
    }
    private Object value(Map<String, Object> row, String key) { for (var entry : row.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue(); return null; }
    private String text(Map<String, Object> row, String key, String fallback) { Object value=value(row,key); return value==null?fallback:value.toString().trim(); }
    private static boolean contains(String csv, String value) { return List.of(csv.split("[,\\s]+")).contains(value); }
    private static boolean useful(String value) { return value != null && !value.isBlank(); }
    private static String normalize(String value) { return Normalizer.normalize(Objects.toString(value, "").trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
}
