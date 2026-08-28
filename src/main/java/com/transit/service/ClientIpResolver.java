package com.transit.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ClientIpResolver {
    private final Set<String> trustedProxies;

    public ClientIpResolver(@Value("${security.trusted-proxies:127.0.0.1,::1}") String configured) {
        trustedProxies = Arrays.stream(configured.split(",")).map(String::trim)
                .filter(v -> !v.isBlank()).map(this::normalize).collect(Collectors.toUnmodifiableSet());
    }

    public String resolve(HttpServletRequest request) {
        String remote = normalize(request.getRemoteAddr());
        if (trustedProxies.contains(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String candidate = forwarded.split(",", 2)[0].trim();
                if (isValid(candidate)) return normalize(candidate);
            }
            String realIp = request.getHeader("X-Real-IP");
            if (isValid(realIp)) return normalize(realIp);
        }
        return remote.isBlank() ? "0.0.0.0" : remote;
    }

    private boolean isValid(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || value.contains("%")) return false;
        String candidate = value.trim();
        if (candidate.contains(":")) {
            if (!candidate.matches("[0-9A-Fa-f:.]+")) return false;
        } else {
            if (!candidate.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return false;
            for (String part : candidate.split("\\.")) {
                try { if (Integer.parseInt(part) > 255) return false; }
                catch (NumberFormatException invalid) { return false; }
            }
        }
        try { InetAddress.getByName(candidate); return true; }
        catch (Exception ignored) { return false; }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        try { return InetAddress.getByName(value.trim()).getHostAddress(); }
        catch (Exception ignored) { return value.trim(); }
    }
}
