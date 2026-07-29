package com.transit.config;

import com.transit.service.PlatformAuthenticationFilter;
import com.transit.service.OAuthService;
import com.transit.service.AdminAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import jakarta.servlet.DispatcherType;

@Configuration
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder(
            @Value("${security.password.bcrypt-strength:12}") int strength) {
        return new BCryptPasswordEncoder(Math.max(10, Math.min(15, strength)));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   OAuthService oauthService,
                                                   AdminAuthService adminAuthService,
                                                   CorsConfigurationSource corsConfigurationSource) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // The originating REQUEST dispatch has already passed
                        // authorization. Mono/Flux MVC return values continue
                        // through an ASYNC redispatch after controller work.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/auth/register", "/auth/login", "/auth/validate-identifier").permitAll()
                        .requestMatchers("/oauth/authorize", "/oauth/callback/**", "/oauth/token", "/oauth/refresh").permitAll()
                        .requestMatchers("/admin/auth/login").permitAll()
                        .requestMatchers("/webhooks/vmcard/**").permitAll()
                        .requestMatchers("/webhooks/anyipay").permitAll()
                        .requestMatchers("/public/**", "/ops/catalog", "/plus/products", "/platform/user/docs",
                                "/creative/catalog", "/creative/templates").permitAll()
                        .requestMatchers("/v1/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/admin/api/**", "/platform/admin/**", "/plus/admin/**",
                                "/service-orders/admin/**",
                                "/payment-service/api/**",
                                "/channels/**", "/tokens/**", "/mappings/**", "/ops/overview",
                                "/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/user/**", "/platform/user/**", "/plus/orders/**", "/service-orders/**",
                                "/service-07/**",
                                "/creative/tasks/**",
                                "/creative/prompt/**", "/creative/connections/**",
                                "/shopgpt/**", "/auth/logout", "/oauth/logout", "/oauth/revoke").hasAnyRole("USER", "ADMIN")
                        .requestMatchers("/admin/auth/logout").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(response, 401, "Authentication required"))
                        .accessDeniedHandler((request, response, exception) -> writeError(response, 403, "Insufficient permissions")))
                .addFilterBefore(new PlatformAuthenticationFilter(oauthService, adminAuthService),
                        UsernamePasswordAuthenticationFilter.class)
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(contentType -> { }))
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${security.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173,https://modelhub.qzz.io}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Requested-With"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse response, int status, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":{\"code\":\"" + status
                + "\",\"message\":\"" + message + "\",\"type\":\"authentication_error\"}}");
    }
}
