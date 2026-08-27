package com.transit.service;

import com.transit.model.Admin;
import com.transit.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;

public class PlatformAuthenticationFilter extends OncePerRequestFilter {
    private final OAuthService oauthService;
    private final AdminAuthService adminAuthService;

    public PlatformAuthenticationFilter(OAuthService oauthService, AdminAuthService adminAuthService) {
        this.oauthService = oauthService;
        this.adminAuthService = adminAuthService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ") && !request.getRequestURI().startsWith("/v1/")) {
            String token = header.substring(7).trim();
            if (!token.isBlank()) authenticate(token);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Admin admin = adminAuthService.getAdminFromToken(token);
            setAuthentication(admin, admin.getUsername(), "ROLE_ADMIN");
            return;
        } catch (ResponseStatusException ignored) {
            // A user token is checked next. Invalid credentials remain anonymous.
        }
        try {
            User user = oauthService.getUserFromToken(token);
            String role = "ADMIN".equalsIgnoreCase(user.getRole()) ? "ROLE_ADMIN" : "ROLE_USER";
            setAuthentication(user, user.getUsername(), role);
        } catch (ResponseStatusException ignored) {
            SecurityContextHolder.clearContext();
        }
    }

    private void setAuthentication(Object principal, String name, String authority) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(authority)));
        authentication.setDetails(name);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
