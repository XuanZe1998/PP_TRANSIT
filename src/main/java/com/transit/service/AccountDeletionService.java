package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Hard deletion is deliberately restricted to unused, zero-value personal accounts. */
@Service
public class AccountDeletionService {
    private static final Set<String> USER_ROWS = Set.of(
            "oauth_codes", "oauth_tokens", "oauth_user_bindings",
            "legal_acceptances", "login_ip_history", "login_ip_challenges",
            "organization_members", "wallet_accounts");
    private static final Set<String> ORG_ROWS = Set.of("organization_members", "wallet_accounts");
    private static final List<String> USER_DELETE_ORDER = List.of(
            "oauth_codes", "oauth_tokens", "oauth_user_bindings",
            "login_ip_challenges", "login_ip_history", "legal_acceptances");

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder passwords;
    private final boolean enabled;

    public AccountDeletionService(JdbcTemplate jdbc, BCryptPasswordEncoder passwords,
                                  @Value("${security.account-deletion.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.enabled = enabled;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleteUnusedPersonalAccount(long userId, String password) {
        // Keep destructive self-service deletion unavailable until retention, concurrent writes,
        // and production database constraints have been independently reviewed.
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "账号自助删除暂未开放");
        List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, password, email, phone, avatar_path, role, status, account_type, balance, default_organization_id FROM users WHERE id=? FOR UPDATE", userId);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号已失效");
        Map<String, Object> user = users.get(0);
        String hash = (String) user.get("password");
        if (hash == null || hash.isBlank() || password == null || password.isBlank()
                || !passwords.matches(password, hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请使用当前密码重新验证；仅支持密码账号");
        }
        if (!"USER".equalsIgnoreCase(String.valueOf(user.get("role")))
                || !"ACTIVE".equalsIgnoreCase(String.valueOf(user.get("status")))
                || !"PERSONAL".equalsIgnoreCase(String.valueOf(user.get("account_type")))
                || user.get("avatar_path") != null || ((Number) user.get("balance")).longValue() != 0) {
            throw conflict();
        }
        Object orgValue = user.get("default_organization_id");
        if (!(orgValue instanceof Number)) throw conflict();
        long orgId = ((Number) orgValue).longValue();
        List<Map<String, Object>> orgs = jdbc.queryForList(
                "SELECT id FROM organizations WHERE id=? AND created_by=? AND organization_type='PERSONAL' FOR UPDATE", orgId, userId);
        if (orgs.size() != 1 || count("SELECT COUNT(*) FROM organizations WHERE created_by=?", userId) != 1
                || count("SELECT COUNT(*) FROM organization_members WHERE user_id=? AND organization_id<>?", userId, orgId) != 0
                || count("SELECT COUNT(*) FROM organization_members WHERE organization_id=? AND user_id<>?", orgId, userId) != 0
                || count("SELECT COUNT(*) FROM users WHERE default_organization_id=? AND id<>?", orgId, userId) != 0) {
            throw conflict();
        }
        List<Map<String, Object>> wallets = jdbc.queryForList(
                "SELECT balance, held_balance FROM wallet_accounts WHERE organization_id=? AND user_id=? FOR UPDATE", orgId, userId);
        if (wallets.size() != 1 || ((Number) wallets.get(0).get("balance")).longValue() != 0
                || ((Number) wallets.get(0).get("held_balance")).longValue() != 0
                || count("SELECT COUNT(*) FROM wallet_accounts WHERE organization_id=? AND user_id<>?", orgId, userId) != 0
                || count("SELECT COUNT(*) FROM wallet_accounts WHERE user_id=? AND organization_id<>?", userId, orgId) != 0) {
            throw conflict();
        }
        // Inspect the deployed schema, not only the current source tree. New user/org references
        // must be reviewed before this endpoint can ever delete their records.
        for (Reference ref : references()) {
            boolean allowed = ("users".equals(ref.table) && "default_organization_id".equals(ref.column))
                    || ("organizations".equals(ref.table) && "created_by".equals(ref.column))
                    || ("user_id".equals(ref.column) && USER_ROWS.contains(ref.table))
                    || ("organization_id".equals(ref.column) && ORG_ROWS.contains(ref.table));
            if (!allowed) {
                long subject = ref.organization ? orgId : userId;
                if (count("SELECT COUNT(*) FROM `" + ref.table + "` WHERE `" + ref.column + "`=?", subject) != 0) throw conflict();
            }
        }
        for (String table : USER_DELETE_ORDER) jdbc.update("DELETE FROM `" + table + "` WHERE user_id=?", userId);
        jdbc.update("DELETE FROM organization_members WHERE organization_id=? AND user_id=?", orgId, userId);
        jdbc.update("DELETE FROM wallet_accounts WHERE organization_id=? AND user_id=? AND balance=0 AND held_balance=0", orgId, userId);
        if (jdbc.update("DELETE FROM organizations WHERE id=? AND created_by=?", orgId, userId) != 1) throw conflict();
        // Verification attempts are addressed by recipient rather than user ID.
        if (user.get("email") != null) jdbc.update("DELETE FROM user_verification_codes WHERE channel='EMAIL' AND recipient=?", user.get("email"));
        if (user.get("phone") != null) jdbc.update("DELETE FROM user_verification_codes WHERE channel='PHONE' AND recipient=?", user.get("phone"));
        if (jdbc.update("DELETE FROM users WHERE id=? AND balance=0", userId) != 1) throw conflict();
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private List<Reference> references() {
        return jdbc.execute((ConnectionCallback<List<Reference>>) connection -> {
            DatabaseMetaData meta = connection.getMetaData();
            List<Reference> refs = new ArrayList<>();
            try (ResultSet tables = meta.getTables(connection.getCatalog(), connection.getSchema(), "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                    if (!safe(table)) throw conflict();
                    try (ResultSet columns = meta.getColumns(connection.getCatalog(), connection.getSchema(), tables.getString("TABLE_NAME"), "%")) {
                        while (columns.next()) {
                            String column = columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
                            if (!safe(column)) throw conflict();
                            boolean org = column.equals("organization_id") || column.endsWith("_organization_id")
                                    || column.equals("default_organization_id") || column.equals("org_id") || column.endsWith("_org_id");
                            boolean user = column.equals("user_id") || column.endsWith("_user_id")
                                    || Set.of("created_by", "updated_by", "invited_by", "accepted_by", "deleted_by").contains(column);
                            if (org || user) refs.add(new Reference(table, column, org));
                        }
                    }
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot inspect account references", exception);
            }
            return refs;
        });
    }

    private boolean safe(String identifier) { return identifier.matches("[a-z][a-z0-9_]*"); }
    private ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "账号含余额、交易或其他关联数据，不能自动彻底删除；请联系管理员处理");
    }
    private record Reference(String table, String column, boolean organization) {}
}
