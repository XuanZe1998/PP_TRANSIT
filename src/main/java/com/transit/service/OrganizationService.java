package com.transit.service;

import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.sql.Statement;
import com.transit.mapper.TokenMapper;
import com.transit.model.Token;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Service
@RequiredArgsConstructor
public class OrganizationService {
    private final JdbcTemplate jdbcTemplate;
    private final SecretHashService secretHashService;
    private final IdempotencyService idempotency;
    private final TokenMapper tokenMapper;
    private final ApiKeyService apiKeys;
    private final SecureRandom random = new SecureRandom();

    public List<Map<String, Object>> list(User user) {
        return jdbcTemplate.queryForList("""
                SELECT o.id, o.name, o.organization_type, o.status, om.member_role,
                       wa.balance, wa.held_balance, o.created_at
                FROM organization_members om
                JOIN organizations o ON o.id=om.organization_id
                LEFT JOIN wallet_accounts wa ON wa.organization_id=o.id AND wa.user_id=om.user_id
                WHERE om.user_id=? AND om.status='ACTIVE' ORDER BY o.created_at
                """, user.getId());
    }

    @Transactional
    public Map<String, Object> create(User owner, String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank() || normalized.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Organization name is required");
        }
        LocalDateTime now = LocalDateTime.now();
        KeyHolder organizationKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO organizations(name, organization_type, status, created_by, created_at, updated_at)
                    VALUES (?, 'COMPANY', 'ACTIVE', ?, ?, ?)
                    """, new String[]{"id"});
            statement.setString(1, normalized); statement.setLong(2, owner.getId());
            statement.setObject(3, now); statement.setObject(4, now);
            return statement;
        }, organizationKey);
        Long id = organizationKey.getKey().longValue();
        jdbcTemplate.update("""
                INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at)
                VALUES (?,?,'OWNER','ACTIVE',?)
                """, id, owner.getId(), now);
        long transferable = Math.max(0, owner.getBalance());
        jdbcTemplate.update("""
                INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at)
                VALUES (?,?,'TREASURY',?,'ACTIVE',?,?)
                """, id, owner.getId(), transferable, now, now);
        Long previousOrg = owner.getDefaultOrganizationId();
        if (previousOrg != null && transferable > 0) {
            jdbcTemplate.update("""
                    UPDATE wallet_accounts SET balance=0, version=version+1, updated_at=?
                    WHERE organization_id=? AND user_id=? AND balance=?
                    """, now, previousOrg, owner.getId(), transferable);
        }
        jdbcTemplate.update("UPDATE users SET default_organization_id=? WHERE id=?", id, owner.getId());
        return Map.of("id", id, "name", normalized, "role", "OWNER", "balance", transferable);
    }

    public List<Map<String, Object>> members(User caller, Long organizationId) {
        String role = currentRole(caller.getId(), organizationId);
        return jdbcTemplate.queryForList("""
                SELECT om.id, om.user_id, u.username, u.email, om.member_role, om.status,
                       wa.balance, wa.held_balance, om.joined_at
                FROM organization_members om JOIN users u ON u.id=om.user_id
                LEFT JOIN wallet_accounts wa ON wa.organization_id=om.organization_id AND wa.user_id=om.user_id
                WHERE om.organization_id=? AND om.status<>'REMOVED' AND (?=FALSE OR om.user_id=?) ORDER BY om.joined_at
                """, organizationId, "MEMBER".equals(role), caller.getId());
    }

    @Transactional
    public Object invite(User caller, Long organizationId, Map<String, Object> request, String key) {
        requireRole(caller.getId(), organizationId, "OWNER", "ORG_ADMIN");
        IdempotencyService.Claim claim = idempotency.claim("USER", caller.getId(),
                "organization.invite:" + organizationId, key, request, true);
        if (claim.replay()) return claim.response();
        try {
            String email = string(request.get("email")).toLowerCase();
            String role = string(request.getOrDefault("role", "MEMBER")).toUpperCase();
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required");
            }
            if (!List.of("ORG_ADMIN", "BILLING", "MEMBER").contains(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invitation role is invalid");
            }
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            LocalDateTime expires = LocalDateTime.now().plusHours(24);
            LocalDateTime created = LocalDateTime.now();
            KeyHolder invitationKey = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                        INSERT INTO organization_invitations
                        (organization_id,email,member_role,token_hash,status,invited_by,expires_at,created_at)
                        VALUES (?,?,?,?, 'PENDING',?,?,?)
                        """, new String[]{"id"});
                statement.setLong(1, organizationId); statement.setString(2, email); statement.setString(3, role);
                statement.setString(4, secretHashService.hash(token)); statement.setLong(5, caller.getId());
                statement.setObject(6, expires); statement.setObject(7, created);
                return statement;
            }, invitationKey);
            Long invitationId = invitationKey.getKey().longValue();
            Map<String, Object> result = Map.of("id", invitationId, "email", email, "role", role,
                    "expiresAt", expires, "invitationToken", token, "oneTimeSecret", true);
            idempotency.complete(claim, 201, result, "ORGANIZATION_INVITATION", invitationId);
            return result;
        } catch (RuntimeException error) {
            idempotency.fail(claim, error); throw error;
        }
    }

    @Transactional
    public Map<String, Object> accept(User user, String rawToken) {
        String hash = secretHashService.hash(rawToken);
        Map<String, Object> invitation = jdbcTemplate.queryForList("""
                SELECT * FROM organization_invitations WHERE token_hash=? FOR UPDATE
                """, hash).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found"));
        if (!"PENDING".equals(invitation.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invitation is no longer pending");
        }
        LocalDateTime expires = (LocalDateTime) invitation.get("expires_at");
        if (expires.isBefore(LocalDateTime.now())) {
            jdbcTemplate.update("UPDATE organization_invitations SET status='EXPIRED' WHERE id=?", invitation.get("id"));
            throw new ResponseStatusException(HttpStatus.GONE, "Invitation expired");
        }
        String expectedEmail = String.valueOf(invitation.get("email"));
        if (user.getEmail() == null || !expectedEmail.equalsIgnoreCase(user.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invitation email does not match this account");
        }
        long organizationId = ((Number) invitation.get("organization_id")).longValue();
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND user_id=?",
                Integer.class, organizationId, user.getId());
        if (existing == null || existing == 0) {
            jdbcTemplate.update("""
                    INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at)
                    VALUES (?,?,?,'ACTIVE',?)
                    """, organizationId, user.getId(), invitation.get("member_role"), LocalDateTime.now());
            jdbcTemplate.update("""
                    INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at)
                    VALUES (?,?,'MEMBER',0,'ACTIVE',?,?)
                    """, organizationId, user.getId(), LocalDateTime.now(), LocalDateTime.now());
        }
        jdbcTemplate.update("""
                UPDATE organization_invitations SET status='ACCEPTED',accepted_by=?,accepted_at=? WHERE id=?
                """, user.getId(), LocalDateTime.now(), invitation.get("id"));
        jdbcTemplate.update("UPDATE users SET default_organization_id=? WHERE id=?", organizationId, user.getId());
        return Map.of("organizationId", organizationId, "role", invitation.get("member_role"));
    }

    @Transactional
    public Object allocate(User owner, Long organizationId, Map<String, Object> request, String key, boolean reclaim) {
        requireRole(owner.getId(), organizationId, "OWNER");
        String operation = reclaim ? "organization.reclaim:" : "organization.allocate:";
        IdempotencyService.Claim claim = idempotency.claim("USER", owner.getId(), operation + organizationId,
                key, request, true);
        if (claim.replay()) return claim.response();
        try {
            long memberId = number(request.get("userId"));
            long amount = number(request.get("amount"));
            if (amount <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be positive");
            requireRole(memberId, organizationId, "ORG_ADMIN", "BILLING", "MEMBER");
            Map<String, Object> ownerWallet = lockWallet(organizationId, owner.getId());
            Map<String, Object> memberWallet = lockWallet(organizationId, memberId);
            long ownerBalance = ((Number) ownerWallet.get("balance")).longValue();
            long memberBalance = ((Number) memberWallet.get("balance")).longValue();
            long memberAfter;
            if (reclaim) {
                if (memberBalance < amount) {
                    throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                            "Employee allocation is insufficient for this reclaim");
                }
                memberAfter = memberBalance - amount;
            } else {
                Long allocated = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(SUM(wa.balance),0) FROM wallet_accounts wa
                        JOIN organization_members om ON om.organization_id=wa.organization_id
                          AND om.user_id=wa.user_id AND om.status='ACTIVE' AND om.member_role<>'OWNER'
                        WHERE wa.organization_id=? AND wa.status='ACTIVE'
                        """, Long.class, organizationId);
                long allocatedAfter = Math.addExact(allocated == null ? 0 : allocated, amount);
                if (allocatedAfter > ownerBalance) {
                    throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                            "Total employee allocations cannot exceed the enterprise treasury balance");
                }
                memberAfter = Math.addExact(memberBalance, amount);
            }
            jdbcTemplate.update("UPDATE wallet_accounts SET balance=?,version=version+1,updated_at=? WHERE id=?",
                    memberAfter, LocalDateTime.now(), memberWallet.get("id"));
            String tx = "wtx_" + UUID.randomUUID().toString().replace("-", "");
            ledger(tx, organizationId, memberWallet, reclaim ? "DEBIT" : "CREDIT", amount, memberAfter,
                    reclaim ? "RECLAIM" : "ALLOCATION");
            Map<String, Object> result = Map.of("transactionId", tx, "organizationId", organizationId,
                    "userId", memberId, "amount", amount, "reclaimed", reclaim,
                    "ownerBalance", ownerBalance, "memberBalance", memberAfter);
            idempotency.complete(claim, 200, result, "WALLET_TRANSFER", tx);
            return result;
        } catch (RuntimeException error) {
            idempotency.fail(claim, error); throw error;
        }
    }

    public List<Map<String, Object>> usage(User caller, Long organizationId, String start, String end) {
        String role = currentRole(caller.getId(), organizationId);
        boolean ownOnly = "MEMBER".equals(role);
        return jdbcTemplate.queryForList("""
                SELECT l.user_id, u.username, l.token_id, t.name token_name, l.source_code, l.model,
                       COUNT(*) request_count,
                       SUM(CASE WHEN l.status LIKE 'SUCCESS%' THEN 1 ELSE 0 END) success_count,
                       COALESCE(SUM(l.prompt_tokens),0) input_tokens,
                       COALESCE(SUM(l.completion_tokens),0) output_tokens,
                       COALESCE(SUM(l.cache_read_tokens),0) cache_hit_tokens,
                       COALESCE(SUM(l.cache_write_tokens),0) cache_write_tokens,
                       COALESCE(SUM(l.cache_miss_tokens),0) cache_miss_tokens,
                       COALESCE(SUM(l.total_amount),0) sale_amount,
                       COALESCE(SUM(l.cost_amount),0) cost_amount,
                       COALESCE(SUM(l.gross_profit),0) gross_profit
                FROM logs l LEFT JOIN users u ON u.id=l.user_id
                LEFT JOIN tokens t ON t.id=l.token_id
                WHERE l.organization_id=?
                  AND (?=FALSE OR l.user_id=?)
                  AND (? IS NULL OR l.created_at >= ?)
                  AND (? IS NULL OR l.created_at < ?)
                GROUP BY l.user_id,u.username,l.token_id,t.name,l.source_code,l.model
                ORDER BY sale_amount DESC
                """, organizationId, ownOnly, caller.getId(), start, start, end, end);
    }

    @Transactional
    public Map<String,Object> updateMember(User caller, Long organizationId, Long memberUserId, Map<String,Object> request) {
        requireRole(caller.getId(), organizationId, "OWNER", "ORG_ADMIN");
        String current = currentRole(memberUserId, organizationId);
        if ("OWNER".equals(current) && !Objects.equals(caller.getId(), memberUserId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能修改企业主账户");
        if (request.containsKey("role")) {
            String role = string(request.get("role")).toUpperCase();
            if (!List.of("ORG_ADMIN", "BILLING", "MEMBER").contains(role)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员角色无效");
            jdbcTemplate.update("UPDATE organization_members SET member_role=?,updated_at=? WHERE organization_id=? AND user_id=?", role, LocalDateTime.now(), organizationId, memberUserId);
        }
        if (request.containsKey("displayName")) {
            String name = string(request.get("displayName"));
            if (name.isBlank() || name.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员名称需为 1–80 个字符");
            jdbcTemplate.update("UPDATE users SET display_name=? WHERE id=?", name, memberUserId);
        }
        if (request.containsKey("status")) {
            String status = string(request.get("status")).toUpperCase();
            if (!List.of("ACTIVE", "SUSPENDED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "成员状态无效");
            jdbcTemplate.update("UPDATE organization_members SET status=?,updated_at=? WHERE organization_id=? AND user_id=?", status, LocalDateTime.now(), organizationId, memberUserId);
        }
        if (request.containsKey("walletQuota")) {
            long desired = number(request.get("walletQuota"));
            if (desired < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账户额度不能为负数");
            Map<String,Object> wallet = lockWallet(organizationId, memberUserId);
            long currentBalance = ((Number) wallet.get("balance")).longValue();
            if (desired != currentBalance) allocate(caller, organizationId, Map.of("userId", memberUserId, "amount", Math.abs(desired-currentBalance)), UUID.randomUUID().toString(), desired < currentBalance);
        }
        return members(caller, organizationId).stream().filter(row -> ((Number)row.get("user_id")).longValue()==memberUserId).findFirst().orElseThrow();
    }

    @Transactional
    public void removeMember(User caller, Long organizationId, Long memberUserId) {
        requireRole(caller.getId(), organizationId, "OWNER");
        if (Objects.equals(caller.getId(), memberUserId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "企业主不能删除自己");
        requireRole(memberUserId, organizationId, "ORG_ADMIN", "BILLING", "MEMBER");
        Map<String,Object> memberWallet = lockWallet(organizationId, memberUserId);
        long held = ((Number) memberWallet.get("held_balance")).longValue();
        if (held > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "成员存在预占额度，结算完成后才能删除");
        long balance = ((Number) memberWallet.get("balance")).longValue();
        if (balance > 0) allocate(caller, organizationId, Map.of("userId", memberUserId, "amount", balance), UUID.randomUUID().toString(), true);
        jdbcTemplate.update("UPDATE tokens SET enabled=FALSE WHERE organization_id=? AND user_id=?", organizationId, memberUserId);
        jdbcTemplate.update("UPDATE wallet_accounts SET status='REMOVED',updated_at=? WHERE organization_id=? AND user_id=?", LocalDateTime.now(), organizationId, memberUserId);
        jdbcTemplate.update("UPDATE organization_members SET status='REMOVED',removed_at=?,updated_at=? WHERE organization_id=? AND user_id=?", LocalDateTime.now(), LocalDateTime.now(), organizationId, memberUserId);
    }

    public List<Map<String,Object>> tokens(User caller, Long organizationId) {
        String role = currentRole(caller.getId(), organizationId);
        return tokenMapper.selectList(new LambdaQueryWrapper<Token>().eq(Token::getOrganizationId, organizationId)
                        .eq("MEMBER".equals(role), Token::getUserId, caller.getId()).orderByDesc(Token::getCreatedAt))
                .stream().map(apiKeys::view).toList();
    }

    public Map<String,Object> createToken(User caller, Long organizationId, Long memberUserId, Token request) {
        requireRole(caller.getId(), organizationId, "OWNER", "ORG_ADMIN");
        requireRole(memberUserId, organizationId, "OWNER", "ORG_ADMIN", "BILLING", "MEMBER");
        return apiKeys.issuedView(apiKeys.issueForOrganization(memberUserId, organizationId, request));
    }

    public Map<String,Object> updateToken(User caller, Long organizationId, Long tokenId, Token request) {
        requireRole(caller.getId(), organizationId, "OWNER", "ORG_ADMIN");
        Token token = tokenMapper.selectById(tokenId);
        if (token == null || !Objects.equals(token.getOrganizationId(), organizationId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API Key 不存在");
        return apiKeys.update(tokenId, token.getUserId(), request, false);
    }

    public void deleteToken(User caller, Long organizationId, Long tokenId) {
        requireRole(caller.getId(), organizationId, "OWNER", "ORG_ADMIN");
        Token token = tokenMapper.selectById(tokenId);
        if (token == null || !Objects.equals(token.getOrganizationId(), organizationId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API Key 不存在");
        apiKeys.delete(tokenId, token.getUserId());
    }

    private void requireRole(Long userId, Long organizationId, String... roles) {
        String role = currentRole(userId, organizationId);
        if (java.util.Arrays.stream(roles).noneMatch(role::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization permission denied");
        }
    }

    private String currentRole(Long userId, Long organizationId) {
        List<String> found = jdbcTemplate.queryForList("""
                SELECT member_role FROM organization_members
                WHERE organization_id=? AND user_id=? AND status='ACTIVE'
                """, String.class, organizationId, userId);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Organization permission denied");
        }
        return found.get(0);
    }

    private Map<String, Object> lockWallet(Long organizationId, Long userId) {
        return jdbcTemplate.queryForList("""
                SELECT id,user_id,balance,held_balance FROM wallet_accounts
                WHERE organization_id=? AND user_id=? AND status='ACTIVE' FOR UPDATE
                """, organizationId, userId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet account not found"));
    }

    private void ledger(String tx, Long orgId, Map<String, Object> wallet, String direction,
                        long amount, long after, String type) {
        jdbcTemplate.update("""
                INSERT INTO wallet_ledger_entries
                (transaction_id,wallet_account_id,organization_id,user_id,entry_type,direction,amount,balance_after,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, tx, wallet.get("id"), orgId, wallet.get("user_id"), type, direction, amount, after,
                LocalDateTime.now());
    }

    private String string(Object value) { return value == null ? "" : value.toString().trim(); }
    private long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(string(value)); }
        catch (NumberFormatException error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Numeric field is invalid"); }
    }
}
