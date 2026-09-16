package com.transit.service;

import com.transit.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContactMethodService {
    private static final List<Integer> PAGE_SIZES = List.of(10, 20, 50, 100);
    private static final int ALL_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public PageResponse<Map<String, Object>> page(int page, int size, boolean all, String query) {
        if (page < 1 || page > 1_000_000) throw bad("页码超出允许范围");
        if (!all && !PAGE_SIZES.contains(size)) throw bad("每页数量仅支持 10、20、50、100");
        String needle = query == null ? "" : query.trim();
        if (needle.length() > 120) throw bad("搜索内容不能超过 120 个字符");

        String where = needle.isBlank() ? "" : " WHERE LOWER(channel) LIKE ? OR LOWER(contact_number) LIKE ?";
        Object[] filterArgs = needle.isBlank() ? new Object[0] : new Object[]{like(needle), like(needle)};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM contact_methods" + where, Long.class, filterArgs);
        long count = total == null ? 0 : total;
        if (all && count > ALL_LIMIT) throw bad("联系方式超过 200 条，不能显示全部");

        int effectiveSize = all ? Math.max(1, (int) count) : size;
        int effectivePage = all ? 1 : page;
        int offset;
        try {
            offset = Math.multiplyExact(effectivePage - 1, effectiveSize);
        } catch (ArithmeticException exception) {
            throw bad("分页参数超出允许范围");
        }
        Object[] args = new Object[filterArgs.length + 2];
        System.arraycopy(filterArgs, 0, args, 0, filterArgs.length);
        args[args.length - 2] = effectiveSize;
        args[args.length - 1] = offset;
        List<Map<String, Object>> items = query("""
                SELECT id, channel, contact_number AS number, created_at, updated_at
                FROM contact_methods
                """ + where + " ORDER BY id ASC LIMIT ? OFFSET ?", args);

        PageResponse<Map<String, Object>> result = new PageResponse<>();
        result.setTotal(count);
        result.setPage(effectivePage);
        result.setSize(effectiveSize);
        result.setItems(items);
        return result;
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = query("""
                SELECT id, channel, contact_number AS number, created_at, updated_at
                FROM contact_methods WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "联系方式不存在");
        return rows.get(0);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        ContactInput input = validate(request);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO contact_methods(channel, contact_number, created_at, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setString(1, input.channel());
            statement.setString(2, input.number());
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new IllegalStateException("新增联系方式后未返回 ID");
        return find(id.longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> request) {
        find(id);
        ContactInput input = validate(request);
        jdbc.update("""
                UPDATE contact_methods
                SET channel = ?, contact_number = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, input.channel(), input.number(), id);
        return find(id);
    }

    @Transactional
    public Map<String, Object> delete(long id) {
        Map<String, Object> before = find(id);
        jdbc.update("DELETE FROM contact_methods WHERE id = ?", id);
        return before;
    }

    private ContactInput validate(Map<String, Object> request) {
        String channel = text(request, "channel");
        String number = text(request, "number");
        if (channel.isBlank()) throw bad("请填写联系方式渠道");
        if (number.isBlank()) throw bad("请填写联系号码");
        if (channel.codePointCount(0, channel.length()) > 80) throw bad("渠道不能超过 80 个字符");
        if (number.codePointCount(0, number.length()) > 240) throw bad("号码不能超过 240 个字符");
        return new ContactInput(channel, number);
    }

    private String text(Map<String, Object> request, String key) {
        Object value = request == null ? null : request.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String like(String query) {
        return "%" + query.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.query(sql, (result, rowNumber) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", result.getLong("id"));
            row.put("channel", result.getString("channel"));
            row.put("number", result.getString("number"));
            row.put("created_at", result.getTimestamp("created_at"));
            row.put("updated_at", result.getTimestamp("updated_at"));
            return row;
        }, args);
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record ContactInput(String channel, String number) {}
}
