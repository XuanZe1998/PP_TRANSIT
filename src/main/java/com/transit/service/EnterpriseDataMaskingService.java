package com.transit.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class EnterpriseDataMaskingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private static final Map<String,String> BUILTINS = Map.of(
            "PHONE", "(?:(?:\\+?86[- ]?)?1[3-9][0-9]{9})",
            "EMAIL", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}",
            "CN_ID", "[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx]",
            "BANK_CARD", "\\b[1-9][0-9]{15,18}\\b",
            "API_KEY", "(?:sk|key|token|api)[-_][A-Za-z0-9_-]{16,}"
    );
    private static final String DEFAULT_RULES = "PHONE,EMAIL,CN_ID,BANK_CARD,API_KEY";

    public Map<String,Object> getPolicy(Long userId, Long organizationId) {
        requireOwner(userId, organizationId);
        var rows = jdbc.queryForList("SELECT enabled,builtin_rules,custom_rules,updated_at FROM enterprise_masking_policies WHERE organization_id=?", organizationId);
        if (rows.isEmpty()) return Map.of("organizationId", organizationId, "enabled", false,
                "builtinRules", List.of(DEFAULT_RULES.split(",")), "customRules", List.of());
        Map<String,Object> row = rows.get(0); Map<String,Object> out = new LinkedHashMap<>();
        out.put("organizationId", organizationId); out.put("enabled", row.get("enabled"));
        out.put("builtinRules", csv(Objects.toString(row.get("builtin_rules"), DEFAULT_RULES)));
        out.put("customRules", parseRules(Objects.toString(row.get("custom_rules"), "[]")));
        out.put("updatedAt", row.get("updated_at")); return out;
    }

    @Transactional
    public Map<String,Object> savePolicy(Long userId, Long organizationId, Map<String,Object> request) {
        requireOwner(userId, organizationId);
        boolean enabled = Boolean.TRUE.equals(request.get("enabled"));
        List<String> builtin = request.get("builtinRules") instanceof List<?> values
                ? values.stream().map(String::valueOf).map(String::toUpperCase).distinct().toList() : csv(DEFAULT_RULES);
        if (builtin.size() > BUILTINS.size() || builtin.stream().anyMatch(v -> !BUILTINS.containsKey(v)))
            throw bad("内置脱敏规则无效");
        List<Map<String,String>> custom = normalizeRules(request.get("customRules"));
        String json;
        try { json = mapper.writeValueAsString(custom); } catch (Exception e) { throw bad("自定义规则无法保存"); }
        LocalDateTime now = LocalDateTime.now();
        int changed = jdbc.update("UPDATE enterprise_masking_policies SET enabled=?,builtin_rules=?,custom_rules=?,updated_by=?,updated_at=? WHERE organization_id=?",
                enabled, String.join(",", builtin), json, userId, now, organizationId);
        if (changed == 0) jdbc.update("INSERT INTO enterprise_masking_policies(organization_id,enabled,builtin_rules,custom_rules,updated_by,updated_at) VALUES (?,?,?,?,?,?)",
                organizationId, enabled, String.join(",", builtin), json, userId, now);
        return getPolicy(userId, organizationId);
    }

    public MaskingContext mask(ChatRequest request, Long organizationId, Long userId, Long tokenId, String traceId) {
        if (organizationId == null) return MaskingContext.disabled();
        var rows = jdbc.queryForList("SELECT enabled,builtin_rules,custom_rules FROM enterprise_masking_policies WHERE organization_id=?", organizationId);
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.get(0).get("enabled"))) return MaskingContext.disabled();
        Map<String,Object> row = rows.get(0); List<Rule> rules = new ArrayList<>();
        csv(Objects.toString(row.get("builtin_rules"), DEFAULT_RULES)).forEach(name -> rules.add(new Rule(name, Pattern.compile(BUILTINS.get(name)))));
        parseRules(Objects.toString(row.get("custom_rules"), "[]")).forEach(rule -> rules.add(new Rule("CUSTOM:" + rule.get("name"), Pattern.compile(rule.get("pattern")))));
        MaskingContext context = new MaskingContext(new LinkedHashMap<>(), new LinkedHashMap<>());
        if (request.getMessages() != null) request.getMessages().forEach(message -> message.setContent(maskValue(message.getContent(), rules, context)));
        context.counts.forEach((category, count) -> jdbc.update("INSERT INTO enterprise_masking_audits(organization_id,user_id,token_id,trace_id,category,hit_count,created_at) VALUES (?,?,?,?,?,?,?)",
                organizationId, userId, tokenId, traceId, category, count, LocalDateTime.now()));
        return context;
    }

    public void restore(ChatResponse response, MaskingContext context) {
        if (response == null || context == null || context.replacements.isEmpty() || response.getChoices() == null) return;
        response.getChoices().forEach(choice -> {
            if (choice == null || choice.getMessage() == null) return;
            choice.getMessage().setContent(restoreText(choice.getMessage().getContent(), context));
            choice.getMessage().setReasoning(restoreText(choice.getMessage().getReasoning(), context));
            choice.getMessage().setReasoningContent(restoreText(choice.getMessage().getReasoningContent(), context));
        });
    }

    @SuppressWarnings("unchecked")
    private Object maskValue(Object value, List<Rule> rules, MaskingContext context) {
        if (value instanceof String text) return maskText(text, rules, context);
        if (value instanceof List<?> list) return list.stream().map(v -> maskValue(v, rules, context)).toList();
        if (value instanceof Map<?,?> mapValue) {
            Map<String,Object> copy = new LinkedHashMap<>();
            mapValue.forEach((key, child) -> copy.put(String.valueOf(key), maskValue(child, rules, context))); return copy;
        }
        return value;
    }

    private String maskText(String text, List<Rule> rules, MaskingContext context) {
        String result = text;
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern.matcher(result); StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                String original = matcher.group();
                String placeholder = "[[LNX_" + rule.category.replace(':','_') + "_" + UUID.randomUUID().toString().replace("-", "") + "]]";
                context.replacements.put(placeholder, original); context.counts.merge(rule.category, 1, Integer::sum);
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(placeholder));
            }
            matcher.appendTail(buffer); result = buffer.toString();
        }
        return result;
    }

    private String restoreText(String value, MaskingContext context) {
        if (value == null) return null; String result = value;
        for (Map.Entry<String,String> item : context.replacements.entrySet()) result = result.replace(item.getKey(), item.getValue());
        return result;
    }

    private List<Map<String,String>> normalizeRules(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        if (list.size() > 20) throw bad("自定义规则最多 20 条"); List<Map<String,String>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?,?> map)) throw bad("自定义规则格式无效");
            String name = Objects.toString(map.get("name"), "").trim(), pattern = Objects.toString(map.get("pattern"), "").trim();
            if (!name.matches("[A-Za-z0-9_\\u4e00-\\u9fa5-]{1,40}") || pattern.isBlank() || pattern.length() > 200) throw bad("自定义规则名称或长度无效");
            try { Pattern.compile(pattern); } catch (RuntimeException e) { throw bad("规则 " + name + " 不是有效的 RE2 表达式"); }
            out.add(Map.of("name", name, "pattern", pattern));
        }
        return out;
    }

    private List<Map<String,String>> parseRules(String json) {
        try { return mapper.readValue(json, new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); }
    }
    private List<String> csv(String value) { return Arrays.stream(value.split(",")).map(String::trim).filter(BUILTINS::containsKey).distinct().toList(); }
    private void requireOwner(Long userId, Long orgId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND user_id=? AND member_role='OWNER' AND status='ACTIVE'", Integer.class, orgId, userId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅企业主可配置数据安全服务");
        String type = jdbc.queryForObject("SELECT organization_type FROM organizations WHERE id=?", String.class, orgId);
        if (!"COMPANY".equalsIgnoreCase(type)) throw bad("数据安全服务仅支持企业组织");
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private record Rule(String category, Pattern pattern) {}
    public static final class MaskingContext {
        private final Map<String,String> replacements; private final Map<String,Integer> counts;
        private MaskingContext(Map<String,String> replacements, Map<String,Integer> counts) { this.replacements=replacements; this.counts=counts; }
        static MaskingContext disabled() { return new MaskingContext(Map.of(), Map.of()); }
    }
}
