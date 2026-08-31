package com.transit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Builds a request client for an administrator-selected, validated outbound proxy. */
@Component
@RequiredArgsConstructor
public class UpstreamProxyHttpClientFactory {
    private final WebClient direct;
    private final JdbcTemplate jdbc;
    private final ChannelSecretService secrets;

    public WebClient client(Long proxyId) {
        if (proxyId == null) return direct;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT protocol,host,port,encrypted_auth,enabled,expires_at FROM upstream_proxies WHERE id=?", proxyId);
        if (rows.isEmpty()) throw new IllegalStateException("Configured upstream proxy does not exist");
        Map<String, Object> row = rows.get(0);
        if (!truth(row.get("enabled")) || expired(row.get("expires_at"))) throw new IllegalStateException("Configured upstream proxy is disabled or expired");
        String protocol = String.valueOf(row.get("protocol")), host = String.valueOf(row.get("host"));
        int port = ((Number) row.get("port")).intValue();
        String auth = row.get("encrypted_auth") == null ? null : secrets.decrypt(String.valueOf(row.get("encrypted_auth")));
        HttpClient client = HttpClient.create().proxy(spec -> {
            ProxyProvider.Builder builder = spec.type("SOCKS5".equalsIgnoreCase(protocol) ? ProxyProvider.Proxy.SOCKS5 : ProxyProvider.Proxy.HTTP).host(host).port(port);
            if (auth != null && !auth.isBlank()) {
                int split = auth.indexOf(':'); String user = split < 0 ? auth : auth.substring(0, split); String password = split < 0 ? "" : auth.substring(split + 1);
                builder.username(user).password(ignored -> password);
            }
        });
        return direct.mutate().clientConnector(new ReactorClientHttpConnector(client)).build();
    }

    private boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0; }
    private boolean expired(Object value) { if (value instanceof LocalDateTime time) return !time.isAfter(LocalDateTime.now()); if (value instanceof java.sql.Timestamp time) return !time.toLocalDateTime().isAfter(LocalDateTime.now()); return false; }
}
