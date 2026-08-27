package com.transit.service;

import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final OAuthService oauthService;
    private final AdminAuthService adminAuthService;

    public User requireUser(String authHeader) {
        String token = extractBearerToken(authHeader);
        return oauthService.getUserFromToken(token);
    }

    public User requireAdmin(String authHeader) {
        String token = extractBearerToken(authHeader);
        try {
            var admin = adminAuthService.getAdminFromToken(token);
            return User.builder()
                    .id(admin.getId())
                    .username(admin.getUsername())
                    .role("ADMIN")
                    .build();
        } catch (ResponseStatusException ignored) {
            // Fall back to legacy ADMIN users stored in the users table.
        }

        User user = oauthService.getUserFromToken(token);
        if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin access required");
        }
        return user;
    }

    public String extractBearerToken(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }
        if (!authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Authorization header");
        }
        return authHeader.substring(7);
    }
}
