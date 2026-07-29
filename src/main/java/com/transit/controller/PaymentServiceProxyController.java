package com.transit.controller;

import com.transit.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

/**
 * 代理转发控制器 — 将 /payment-service/api/** 请求转发到 Python Flask 微服务。
 * <p>
 * Python 服务默认运行在 localhost:5000，通过 PAYMENT_SERVICE_URL 环境变量可覆盖。
 * 此控制器仅限管理员访问，避免敏感接口暴露给普通用户。
 */
@Slf4j
@RestController
@RequestMapping("/payment-service/api")
@RequiredArgsConstructor
public class PaymentServiceProxyController {

    private static final String PUBLIC_PREFIX = "/payment-service/api";
    private static final String UPSTREAM_PREFIX = "/api";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "content-length", "host", "keep-alive",
            "proxy-authenticate", "proxy-authorization", "te",
            "trailer", "transfer-encoding", "upgrade"
    );

    private final CurrentUserService currentUserService;

    @Value("${payment-service.url:http://localhost:5000}")
    private String paymentServiceUrl;

    @Value("${payment-service.timeout-seconds:120}")
    private int timeoutSeconds;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 通用代理：GET /payment-service/api/regions 等
     */
    @GetMapping("/**")
    public ResponseEntity<String> proxyGet(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            HttpServletRequest request) {
        currentUserService.requireAdmin(authHeader);
        return forwardRequest(request);
    }

    /**
     * 通用代理：POST /payment-service/api/subscribe 等
     */
    @PostMapping("/**")
    public ResponseEntity<String> proxyPost(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @RequestBody String body,
            HttpServletRequest request) {
        currentUserService.requireAdmin(authHeader);
        return forwardRequest(request, body);
    }

    /**
     * SSE 代理：GET /payment-service/api/events/{taskId}
     * 返回 text/event-stream，需要较长的读取超时
     */
    @GetMapping(value = "/events/{taskId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> proxyEvents(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader,
            @PathVariable String taskId,
            HttpServletRequest request) {
        currentUserService.requireAdmin(authHeader);
        return forwardEventStream(request);
    }

    private ResponseEntity<String> forwardRequest(HttpServletRequest request) {
        return forwardRequest(request, null);
    }

    private ResponseEntity<String> forwardRequest(HttpServletRequest request, String body) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(targetUri(request))
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            copyRequestHeaders(request, builder);

            if (body != null && !body.isBlank()) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body));
                if (request.getHeader("Content-Type") == null) {
                    builder.header("Content-Type", "application/json");
                }
            } else {
                builder.GET();
            }

            HttpRequest httpRequest = builder.build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            var responseBuilder = ResponseEntity.status(response.statusCode());
            copyResponseHeaders(response, responseBuilder);

            return responseBuilder.body(response.body());

        } catch (Exception e) {
            log.error("代理转发到支付服务失败: {}", e.getMessage(), e);
            return ResponseEntity.status(502)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"支付服务不可用，请确认 Python 服务已启动\"}");
        }
    }

    private ResponseEntity<StreamingResponseBody> forwardEventStream(HttpServletRequest request) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(targetUri(request))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET();
            copyRequestHeaders(request, builder);

            HttpResponse<InputStream> upstream = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
                try (InputStream body = upstream.body()) {
                    String error = new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    log.warn("支付服务事件流返回 HTTP {}: {}", upstream.statusCode(), error);
                }
                return ResponseEntity.status(upstream.statusCode()).build();
            }

            StreamingResponseBody body = outputStream -> {
                try (InputStream inputStream = upstream.body()) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = inputStream.read(buffer)) >= 0) {
                        outputStream.write(buffer, 0, read);
                        outputStream.flush();
                    }
                } catch (Exception exception) {
                    log.debug("支付服务事件流已结束: {}", exception.getMessage());
                }
            };

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .body(body);
        } catch (Exception exception) {
            log.error("连接支付服务事件流失败: {}", exception.getMessage(), exception);
            return ResponseEntity.status(502).build();
        }
    }

    private URI targetUri(HttpServletRequest request) {
        String baseUrl = paymentServiceUrl == null ? "" : paymentServiceUrl.trim().replaceAll("/+$", "");
        String targetUrl = baseUrl + extractPath(request);
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            targetUrl += "?" + query;
        }
        return URI.create(targetUrl);
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (name.equalsIgnoreCase(HttpHeaders.AUTHORIZATION) || isHopByHop(name)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
    }

    private void copyResponseHeaders(HttpResponse<?> response, ResponseEntity.BodyBuilder builder) {
        response.headers().map().forEach((name, values) -> {
            if (!isHopByHop(name)) {
                values.forEach(value -> builder.header(name, value));
            }
        });
    }

    private boolean isHopByHop(String name) {
        return HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT));
    }

    private String extractPath(HttpServletRequest request) {
        String fullPath = request.getRequestURI();
        int idx = fullPath.indexOf(PUBLIC_PREFIX);
        if (idx >= 0) {
            String suffix = fullPath.substring(idx + PUBLIC_PREFIX.length());
            return UPSTREAM_PREFIX + (suffix.startsWith("/") ? suffix : "/" + suffix);
        }
        throw new IllegalArgumentException("Unexpected payment service proxy path");
    }
}
