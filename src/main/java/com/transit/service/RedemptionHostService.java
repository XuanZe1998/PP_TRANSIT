package com.transit.service;

import com.transit.dto.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.IDN;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@Order(10)
public class RedemptionHostService implements ApplicationRunner {
    private static final Pattern DOMAIN = Pattern.compile("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z](?:[a-z0-9-]{0,61}[a-z0-9])$");
    private final JdbcTemplate jdbcTemplate;

    @Value("${service-orders.redemption-allowed-hosts:}")
    private String legacyHosts = "";

    public RedemptionHostService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AllowedHost(long id, String host, LocalDateTime createdAt) {}

    /** One-time transfer of the old config. After this, only the database is authoritative. */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Integer completed = jdbcTemplate.queryForObject(
                "SELECT completed FROM service_redemption_host_bootstrap WHERE id = 1 FOR UPDATE", Integer.class);
        if (completed == null || completed != 0) return;
        for (String entry : legacyHosts.split(",")) {
            if (entry.isBlank()) continue;
            String host = normalizeHost(entry);
            jdbcTemplate.update("INSERT INTO service_redemption_hosts (host) SELECT ? WHERE NOT EXISTS (SELECT 1 FROM service_redemption_hosts WHERE host = ?)", host, host);
        }
        jdbcTemplate.update("UPDATE service_redemption_host_bootstrap SET completed = 1 WHERE id = 1");
    }

    public PageResponse<AllowedHost> list(int page, int size, String query) {
        if (page < 1 || page > 1_000_000 || !List.of(10, 20, 50, 100).contains(size)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page or size");
        }
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() > 253) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is too long");
        long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_redemption_hosts WHERE INSTR(host, ?) > 0", Long.class, needle);
        List<AllowedHost> items = jdbcTemplate.query(
                "SELECT id, host, created_at FROM service_redemption_hosts WHERE INSTR(host, ?) > 0 ORDER BY host, id LIMIT ? OFFSET ?",
                (rs, index) -> new AllowedHost(rs.getLong("id"), rs.getString("host"), rs.getTimestamp("created_at").toLocalDateTime()),
                needle, size, (page - 1L) * size);
        PageResponse<AllowedHost> result = new PageResponse<>();
        result.setTotal(total); result.setPage(page); result.setSize(size); result.setItems(items);
        return result;
    }

    public AllowedHost add(String value) {
        String host = normalizeHost(value);
        try {
            jdbcTemplate.update("INSERT INTO service_redemption_hosts (host) VALUES (?)", host);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Redemption host already exists");
        }
        return jdbcTemplate.queryForObject("SELECT id, host, created_at FROM service_redemption_hosts WHERE host = ?",
                (rs, index) -> new AllowedHost(rs.getLong("id"), rs.getString("host"), rs.getTimestamp("created_at").toLocalDateTime()), host);
    }

    public void delete(long id) {
        if (id < 1 || jdbcTemplate.update("DELETE FROM service_redemption_hosts WHERE id = ?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Redemption host not found");
        }
    }

    public boolean allows(String host) {
        if (host == null) return false;
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_redemption_hosts WHERE host = ? OR ? LIKE CONCAT('%.', host)",
                Long.class, host.toLowerCase(Locale.ROOT), host.toLowerCase(Locale.ROOT)) > 0;
    }

    static String normalizeHost(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a redemption domain");
        }
        String host;
        try {
            host = IDN.toASCII(value.trim(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a domain only, without scheme, port or path");
        }
        if (!DOMAIN.matcher(host).matches() || host.equals("localhost") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a valid public domain only, without scheme, port or path");
        }
        return host;
    }
}
