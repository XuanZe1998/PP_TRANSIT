package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UpstreamProxyService {
    private final JdbcTemplate jdbc;
    private final ChannelSecretService secrets;

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id,name,protocol,host,port,fallback_proxy_id,direct_fallback,enabled,expires_at,latency_ms,health_status,last_checked_at,created_at,updated_at FROM upstream_proxies ORDER BY id DESC");
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request) {
        ProxyInput input = validate(request);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO upstream_proxies(name,protocol,host,port,encrypted_auth,fallback_proxy_id,direct_fallback,enabled,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, input.name()); statement.setString(2, input.protocol()); statement.setString(3, input.host()); statement.setInt(4, input.port());
            statement.setString(5, input.auth() == null ? null : secrets.encrypt(input.auth()));
            if (input.fallbackId() == null) statement.setObject(6, null); else statement.setLong(6, input.fallbackId());
            statement.setBoolean(7, input.directFallback()); statement.setBoolean(8, input.enabled()); statement.setObject(9, input.expiresAt());
            statement.setObject(10, LocalDateTime.now()); statement.setObject(11, LocalDateTime.now()); return statement;
        }, key);
        return get(Objects.requireNonNull(key.getKey()).longValue());
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, Object> request) {
        get(id);
        ProxyInput input = validate(request);
        String auth = input.auth() == null || input.auth().contains("****") ? null : secrets.encrypt(input.auth());
        jdbc.update("UPDATE upstream_proxies SET name=?,protocol=?,host=?,port=?,encrypted_auth=COALESCE(?,encrypted_auth),fallback_proxy_id=?,direct_fallback=?,enabled=?,expires_at=?,updated_at=? WHERE id=?",
                input.name(), input.protocol(), input.host(), input.port(), auth, input.fallbackId(), input.directFallback(), input.enabled(), input.expiresAt(), LocalDateTime.now(), id);
        return get(id);
    }

    public Map<String, Object> test(long id) {
        Map<String, Object> proxy = get(id);
        String host = String.valueOf(proxy.get("host"));
        int port = ((Number) proxy.get("port")).intValue();
        validatePublicHost(host);
        long started = System.nanoTime();
        String status = "HEALTHY";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
        } catch (Exception error) {
            status = "UNAVAILABLE";
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        jdbc.update("UPDATE upstream_proxies SET health_status=?,latency_ms=?,last_checked_at=?,updated_at=? WHERE id=?",
                status, latency, LocalDateTime.now(), LocalDateTime.now(), id);
        return get(id);
    }

    private Map<String, Object> get(long id) {
        return jdbc.queryForList("SELECT id,name,protocol,host,port,fallback_proxy_id,direct_fallback,enabled,expires_at,latency_ms,health_status,last_checked_at,created_at,updated_at FROM upstream_proxies WHERE id=?", id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "出站代理不存在"));
    }

    private ProxyInput validate(Map<String, Object> request) {
        String name = text(request.get("name"));
        String protocol = text(request.get("protocol")).toUpperCase(Locale.ROOT);
        String host = text(request.get("host"));
        int port = request.get("port") instanceof Number n ? n.intValue() : 0;
        if (name.isBlank() || name.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代理名称无效");
        if (!List.of("HTTP", "HTTPS", "SOCKS5").contains(protocol)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 HTTP、HTTPS 和 SOCKS5");
        if (port < 1 || port > 65535) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代理端口无效");
        validatePublicHost(host);
        Long fallback = request.get("fallbackProxyId") instanceof Number n ? n.longValue() : null;
        LocalDateTime expires = request.get("expiresAt") == null || text(request.get("expiresAt")).isBlank() ? null : LocalDateTime.parse(text(request.get("expiresAt")));
        return new ProxyInput(name, protocol, host, port, request.get("auth") == null ? null : text(request.get("auth")), fallback,
                Boolean.TRUE.equals(request.get("directFallback")), !Boolean.FALSE.equals(request.get("enabled")), expires);
    }

    static void validatePublicHost(String host) {
        if (host == null || host.isBlank() || host.length() > 255 || host.contains("://") || host.contains("/") || host.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代理主机格式无效");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new IllegalArgumentException("unresolved");
            for (InetAddress address : addresses) {
                byte[] bytes = address.getAddress();
                boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || uniqueLocalV6) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "禁止使用本机、私网、链路本地或组播代理地址");
                }
            }
        } catch (ResponseStatusException known) { throw known; }
        catch (Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "代理主机无法安全解析"); }
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private record ProxyInput(String name, String protocol, String host, int port, String auth, Long fallbackId,
                              boolean directFallback, boolean enabled, LocalDateTime expiresAt) {}
}
