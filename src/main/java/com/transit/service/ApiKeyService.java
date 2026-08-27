package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.TokenMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.Token;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ApiKeyService {
    private final TokenMapper tokenMapper;
    private final SecretHashService secretHashService;
    private final UserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public IssuedApiKey issue(Long ownerId, Token request) {
        if (ownerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An API Key must have an owner");
        }
        com.transit.model.User owner = userMapper.selectById(ownerId);
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API Key owner does not exist");
        }
        validate(request, null);
        String secret = generateSecret();
        List<String> grants = requestedModels(request);
        boolean allowAll = explicitAllowAll(request);
        requireAuthorizationScope(allowAll, grants);
        String legacyScope = allowAll ? "*" : String.join(",", grants);
        Token token = Token.builder()
                .key(secretHashService.hash(secret))
                .keyPrefix(preview(secret))
                .userId(ownerId)
                .organizationId(owner.getDefaultOrganizationId())
                .name(normalizeName(request.getName()))
                .usedQuota(0)
                .totalQuota(request.getTotalQuota())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .expiredAt(request.getExpiredAt())
                .allowedModels(legacyScope)
                .allowAllModels(allowAll)
                .ipWhitelist(normalizeOptional(request.getIpWhitelist(), 1000))
                .description(normalizeOptional(request.getDescription(), 500))
                .build();
        tokenMapper.insert(token);
        synchronizeModels(token.getId(), allowAll, grants);
        token.setAllowedModelIds(grants);
        return new IssuedApiKey(token, secret);
    }

    public Token findBySecret(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank() || rawSecret.length() > 255) return null;
        String digest = secretHashService.hash(rawSecret);
        Token token = tokenMapper.selectOne(new LambdaQueryWrapper<Token>().eq(Token::getKey, digest));
        if (token == null) {
            token = tokenMapper.selectOne(new LambdaQueryWrapper<Token>().eq(Token::getKey, rawSecret));
            if (token != null) {
                token.setKey(digest);
                token.setKeyPrefix(preview(rawSecret));
                tokenMapper.updateById(token);
            }
        }
        return token;
    }

    public List<Map<String, Object>> listForUser(Long userId) {
        return tokenMapper.selectList(new LambdaQueryWrapper<Token>()
                        .eq(Token::getUserId, userId)
                        .orderByDesc(Token::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public List<Map<String, Object>> listAll() {
        return tokenMapper.selectList(new LambdaQueryWrapper<Token>().orderByDesc(Token::getCreatedAt))
                .stream().map(this::view).toList();
    }

    public Map<String, Object> update(Long id, Long requiredOwnerId, Token request, boolean allowOwnerChange) {
        Token current = tokenMapper.selectById(id);
        if (current == null || (requiredOwnerId != null && !Objects.equals(current.getUserId(), requiredOwnerId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API Key not found");
        }
        validate(request, current);
        if (allowOwnerChange && request.getUserId() != null) current.setUserId(request.getUserId());
        if (current.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An API Key must have an owner");
        }
        if (userMapper.selectById(current.getUserId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API Key owner does not exist");
        }
        current.setName(normalizeName(request.getName()));
        current.setTotalQuota(request.getTotalQuota());
        current.setEnabled(request.isEnabled());
        current.setExpiredAt(request.getExpiredAt());
        List<String> grants = requestedModels(request);
        boolean omittedScope = !request.isAllowAllModels() && grants.isEmpty()
                && (request.getAllowedModels() == null || request.getAllowedModels().isBlank());
        boolean allowAll = omittedScope && current.isAllowAllModels() || explicitAllowAll(request);
        if (omittedScope && !current.isAllowAllModels()) {
            grants = allowedModelIds(current.getId());
        }
        requireAuthorizationScope(allowAll, grants);
        current.setAllowedModels(allowAll ? "*" : String.join(",", grants));
        current.setAllowAllModels(allowAll);
        current.setIpWhitelist(normalizeOptional(request.getIpWhitelist(), 1000));
        current.setDescription(normalizeOptional(request.getDescription(), 500));
        tokenMapper.updateById(current);
        synchronizeModels(current.getId(), allowAll, grants);
        current.setAllowedModelIds(grants);
        return view(current);
    }

    public void delete(Long id, Long requiredOwnerId) {
        Token token = tokenMapper.selectById(id);
        if (token == null || (requiredOwnerId != null && !Objects.equals(token.getUserId(), requiredOwnerId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API Key not found");
        }
        jdbcTemplate.update("DELETE FROM api_key_models WHERE token_id = ?", id);
        tokenMapper.deleteById(id);
    }

    public Map<String, Object> issuedView(IssuedApiKey issued) {
        Map<String, Object> result = view(issued.token());
        result.put("secret", issued.secret());
        result.put("oneTimeSecret", true);
        return result;
    }

    public Map<String, Object> view(Token token) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", token.getId());
        result.put("userId", token.getUserId());
        result.put("organizationId", token.getOrganizationId());
        result.put("name", token.getName());
        result.put("keyPreview", keyPreview(token));
        result.put("usedQuota", token.getUsedQuota());
        result.put("totalQuota", token.getTotalQuota());
        result.put("enabled", token.isEnabled());
        result.put("createdAt", token.getCreatedAt());
        result.put("expiredAt", token.getExpiredAt());
        result.put("allowedModels", token.getAllowedModels());
        result.put("allowAllModels", token.isAllowAllModels());
        result.put("allowedModelIds", allowedModelIds(token.getId()));
        result.put("ipWhitelist", token.getIpWhitelist());
        result.put("description", token.getDescription());
        return result;
    }

    public boolean modelAllowed(Token token, String requestedModel) {
        if (token == null || requestedModel == null) return false;
        if (token.isAllowAllModels()) return true;
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM api_key_models
                WHERE token_id = ? AND LOWER(model_name) = LOWER(?)
                """, Integer.class, token.getId(), requestedModel);
        if (count != null && count > 0) return true;
        // Rolling-upgrade compatibility before the structured grant backfill.
        String legacy = token.getAllowedModels();
        if (legacy == null || legacy.isBlank()) return true;
        return java.util.Arrays.stream(legacy.split(","))
                .map(String::trim).anyMatch(value -> "*".equals(value) || value.equalsIgnoreCase(requestedModel));
    }

    public List<String> allowedModelIds(Long tokenId) {
        if (tokenId == null) return List.of();
        return jdbcTemplate.queryForList(
                "SELECT model_name FROM api_key_models WHERE token_id = ? ORDER BY model_name",
                String.class, tokenId);
    }

    private List<String> requestedModels(Token request) {
        List<String> structured = request.getAllowedModelIds() == null ? List.of() : request.getAllowedModelIds();
        if (!structured.isEmpty()) return normalizeModels(structured);
        String legacy = request.getAllowedModels();
        if (legacy == null || legacy.isBlank() || "*".equals(legacy.trim())) return List.of();
        return normalizeModels(java.util.Arrays.asList(legacy.split(",")));
    }

    private boolean explicitAllowAll(Token request) {
        return request.isAllowAllModels()
                || "*".equals(request.getAllowedModels() == null ? null : request.getAllowedModels().trim());
    }

    private void requireAuthorizationScope(boolean allowAll, List<String> grants) {
        if (!allowAll && (grants == null || grants.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select at least one authorized model or explicitly allow all models");
        }
    }

    private List<String> normalizeModels(List<String> values) {
        List<String> result = values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isBlank()).distinct().toList();
        if (result.size() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many model grants");
        for (String model : result) {
            if (!model.matches("[A-Za-z0-9._:/-]{1,160}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Model grant is invalid");
            }
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM model_mappings WHERE public_model_name = ? AND enabled = TRUE",
                    Integer.class, model);
            if (exists == null || exists == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown model grant: " + model);
            }
        }
        return result;
    }

    private void synchronizeModels(Long tokenId, boolean allowAll, List<String> models) {
        jdbcTemplate.update("DELETE FROM api_key_models WHERE token_id = ?", tokenId);
        if (!allowAll) {
            models.forEach(model -> jdbcTemplate.update(
                    "INSERT INTO api_key_models(token_id, model_name) VALUES (?, ?)", tokenId, model));
        }
    }

    public String keyPreview(Token token) {
        if (token.getKeyPrefix() != null && !token.getKeyPrefix().isBlank()) return token.getKeyPrefix();
        String stored = token.getKey();
        if (stored == null || stored.isBlank() || secretHashService.isHashed(stored)) return "sk-at-****";
        return preview(stored);
    }

    private void validate(Token request, Token current) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API Key configuration is required");
        if (request.getTotalQuota() < 0 || request.getTotalQuota() > 1_000_000_000_000L) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalQuota is out of range");
        }
        if (current != null && request.getTotalQuota() > 0 && request.getTotalQuota() < current.getUsedQuota()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalQuota cannot be below already used quota");
        }
        if (request.getExpiredAt() != null && !request.getExpiredAt().isAfter(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiredAt must be in the future");
        }
        normalizeName(request.getName());
        normalizeOptional(request.getAllowedModels(), 1000);
        normalizeOptional(request.getIpWhitelist(), 1000);
        normalizeOptional(request.getDescription(), 500);
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) normalized = "API Key";
        if (normalized.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API Key name is too long");
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "API Key field is too long");
        return normalized;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return "sk-at-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String preview(String secret) {
        if (secret.length() <= 16) return "sk-at-****";
        return secret.substring(0, Math.min(12, secret.length())) + "…" + secret.substring(secret.length() - 4);
    }

    public record IssuedApiKey(Token token, String secret) {
    }
}
