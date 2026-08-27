package com.transit.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class IdempotencyRequiredFilter extends OncePerRequestFilter {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,160}");
    private static final List<Pattern> REQUIRED_POSTS = List.of(
            Pattern.compile("^/v1/tasks/?$"),
            Pattern.compile("^/organizations/[^/]+/(invitations|allocations|allocations/reclaim)/?$"),
            Pattern.compile("^/platform/user/recharge-orders/?$"),
            Pattern.compile("^/platform/user/wallet/redeem/?$"),
            Pattern.compile("^/payment-intents/[^/]+/start/?$"),
            Pattern.compile("^/admin/payment-intents/[^/]+/refund/?$"),
            Pattern.compile("^/admin/payment/(refund|close|transfer)/?$"),
            Pattern.compile("^/service-orders/?$"),
            Pattern.compile("^/creative/tasks/?$"),
            Pattern.compile("^/creative/auto-movie/projects/[^/]+/(script/generate|visuals/generate|videos/generate|compose)/?$")
    );
    private final boolean enforce;

    public IdempotencyRequiredFilter(@Value("${gateway.idempotency.enforce:true}") boolean enforce) {
        this.enforce = enforce;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (enforce && "POST".equalsIgnoreCase(request.getMethod()) && required(request.getRequestURI())) {
            String key = request.getHeader("Idempotency-Key");
            if (key == null || !KEY.matcher(key).matches()) {
                response.setStatus(400);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\",\"message\":\"A valid Idempotency-Key header is required\"}}");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean required(String uri) {
        return REQUIRED_POSTS.stream().anyMatch(pattern -> pattern.matcher(uri).matches());
    }
}
