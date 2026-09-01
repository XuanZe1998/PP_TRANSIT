package com.transit.service;

import com.transit.mapper.UserMapper;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final JdbcTemplate jdbcTemplate;
    private final UserMapper userMapper;
    private final PersonalDataCryptoService personalDataCrypto;
    private final WalletBalanceService walletBalanceService;

    @Value("${billing.max-admin-adjustment:1000000000000}")
    private long maxAdminAdjustment;

    public List<Map<String, Object>> users() {
        List<Map<String,Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.id, u.username, u.email, u.phone, u.role, COALESCE(u.status, 'ACTIVE') AS status,
                       u.balance, u.invoice_enabled, u.created_at, u.group_id, COALESCE(u.account_type,'PERSONAL') account_type,
                       u.default_organization_id, o.name AS organization_name,
                       o.organization_type, om.member_role AS organization_role,
                       wa.account_type AS wallet_account_type, wa.balance AS wallet_balance,
                       wa.held_balance AS wallet_held_balance,
                       (SELECT COUNT(*) FROM organization_members omc
                         WHERE omc.user_id=u.id AND omc.status='ACTIVE') AS organization_count,
                       COALESCE(g.display_name, 'Default users') AS group_name,
                       COALESCE(g.price_ratio, 1) AS price_ratio,
                       (SELECT COUNT(*) FROM tokens t WHERE t.user_id = u.id) AS token_count,
                       (SELECT COUNT(*) FROM logs l WHERE l.user_id = u.id) AS request_count,
                       (SELECT h.encrypted_ip FROM login_ip_history h WHERE h.user_id=u.id ORDER BY h.last_seen_at DESC LIMIT 1) last_ip_encrypted,
                       (SELECT h.ip_preview FROM login_ip_history h WHERE h.user_id=u.id ORDER BY h.last_seen_at DESC LIMIT 1) last_ip_preview,
                       (SELECT h.last_seen_at FROM login_ip_history h WHERE h.user_id=u.id ORDER BY h.last_seen_at DESC LIMIT 1) last_login_ip_at,
                       (SELECT COUNT(*) FROM login_ip_history h WHERE h.user_id=u.id) login_ip_count
                FROM users u
                LEFT JOIN user_groups g ON g.id = u.group_id
                LEFT JOIN organizations o ON o.id=u.default_organization_id
                LEFT JOIN organization_members om ON om.organization_id=o.id AND om.user_id=u.id
                LEFT JOIN wallet_accounts wa ON wa.organization_id=o.id AND wa.user_id=u.id AND wa.status='ACTIVE'
                ORDER BY u.created_at DESC
                LIMIT 500
                """);
        rows.forEach(row -> {
            String revealed = personalDataCrypto.decrypt((String) row.remove("last_ip_encrypted"));
            row.put("last_login_ip", revealed == null ? row.get("last_ip_preview") : revealed);
        });
        return rows;
    }

    public List<Map<String, Object>> groups() {
        return jdbcTemplate.queryForList("SELECT id, name, display_name, price_ratio, monthly_quota, description, created_at FROM user_groups ORDER BY id");
    }

    public Map<String, Object> createGroup(Map<String, Object> request) {
        String name = stringValue(request, "name", "group-" + UUID.randomUUID().toString().substring(0, 8));
        String displayName = stringValue(request, "displayName", name);
        double priceRatio = doubleValue(request, "priceRatio", 1);
        long monthlyQuota = longValue(request, "monthlyQuota", 0);
        String description = stringValue(request, "description", "");
        if (!name.matches("[A-Za-z0-9._-]{2,80}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is invalid");
        }
        if (displayName.isBlank() || displayName.length() > 120 || description.length() > 500
                || !Double.isFinite(priceRatio) || priceRatio <= 0 || priceRatio > 100 || monthlyQuota < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User group values are invalid");
        }
        jdbcTemplate.update(
                "INSERT INTO user_groups(name, display_name, price_ratio, monthly_quota, description, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                name, displayName, priceRatio, monthlyQuota, description, LocalDateTime.now()
        );
        return Map.of("name", name, "displayName", displayName, "priceRatio", priceRatio, "monthlyQuota", monthlyQuota, "description", description);
    }

    public User updateUser(Long id, Map<String, Object> request) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        if (request.containsKey("role")) {
            String role = stringValue(request, "role", user.getRole()).toUpperCase();
            if (!List.of("USER", "ADMIN").contains(role)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported user role");
            }
            user.setRole(role);
        }
        if (request.containsKey("status")) {
            String status = stringValue(request, "status", user.getStatus()).toUpperCase();
            if (!List.of("ACTIVE", "SUSPENDED", "DISABLED").contains(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported user status");
            }
            user.setStatus(status);
        }
        if (request.containsKey("groupId")) {
            Long groupId = nullableLong(request.get("groupId"));
            if (groupId != null) {
                Long count = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM user_groups WHERE id = ?", Long.class, groupId);
                if (count == null || count == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User group does not exist");
                }
            }
            user.setGroupId(groupId);
        }
        if (request.containsKey("invoiceEnabled")) {
            user.setInvoiceEnabled(booleanValue(request.get("invoiceEnabled")));
        }
        // Balance mutations are intentionally forbidden here. They must use
        // adjustBalance so every change is atomic and receives a ledger entry.
        userMapper.updateById(user);
        return user;
    }

    @Transactional
    public Map<String, Object> adjustBalance(Long userId, long amount, String reason, Long adminId) {
        if (amount == 0 || amount == Long.MIN_VALUE
                || Math.abs(amount) > Math.max(1, maxAdminAdjustment)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Balance adjustment is out of range");
        }
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.length() < 3 || safeReason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "调账原因必须为 3–500 个字符");
        }
        long balanceAfter = walletBalanceService.adjust(userId, amount,
                "Adjustment would make the account balance negative").balance();
        jdbcTemplate.update(
                "INSERT INTO wallet_transactions(user_id, type, amount, balance_after, channel, remark, created_at) VALUES (?, 'ADJUSTMENT', ?, ?, 'admin', ?, ?)",
                userId, amount, balanceAfter, safeReason, LocalDateTime.now()
        );
        jdbcTemplate.update(
                "INSERT INTO user_adjustments(user_id, admin_id, amount, reason, balance_after, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                userId, adminId, amount, safeReason, balanceAfter, LocalDateTime.now()
        );
        return Map.of("userId", userId, "amount", amount, "balance", balanceAfter);
    }

    @Transactional
    public Map<String,Object> upgradeToEnterprise(Long userId, Map<String,Object> request) {
        User user = userMapper.selectById(userId);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        if ("ENTERPRISE".equalsIgnoreCase(user.getAccountType())) throw new ResponseStatusException(HttpStatus.CONFLICT, "该用户已是企业用户");
        String company = stringValue(request, "companyName", "").trim();
        String contact = stringValue(request, "contactName", user.getDisplayName()).trim();
        if (company.isBlank() || company.length() > 160 || contact.isBlank() || contact.length() > 80)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写有效的企业名称和联系人");
        Long organizationId = user.getDefaultOrganizationId();
        if (organizationId == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "用户个人组织不存在");
        Integer others = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND user_id<>? AND status='ACTIVE'", Integer.class, organizationId, userId);
        if (others != null && others > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "已有成员的个人组织不能直接升级");
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE organizations SET name=?,organization_type='COMPANY',updated_at=? WHERE id=? AND organization_type='PERSONAL'", company, now, organizationId);
        jdbcTemplate.update("UPDATE users SET account_type='ENTERPRISE',display_name=? WHERE id=?", contact, userId);
        jdbcTemplate.update("INSERT INTO enterprise_profiles(user_id,organization_id,company_name,contact_name,contact_phone,contact_email,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                userId, organizationId, company, contact, user.getPhone() == null ? "" : user.getPhone(), user.getEmail(), now, now);
        return Map.of("userId", userId, "accountType", "ENTERPRISE", "organizationId", organizationId, "companyName", company);
    }

    private String stringValue(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Long.parseLong(value.toString());
    }

    private double doubleValue(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value == null || value.toString().isBlank()) return fallback;
        return Double.parseDouble(value.toString());
    }

    private Long nullableLong(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value == null) return false;
        String text = value.toString().trim();
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invoiceEnabled must be a boolean");
    }
}
