package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.OAuthTokenMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.OAuthToken;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final OAuthTokenMapper tokenMapper;
    private final OAuthService oauthService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    public Mono<Map<String, Object>> register(String username, String password, String email) {
        return Mono.fromCallable(() -> {
            if (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username)) != null) {
                throw new RuntimeException("User already exists");
            }
            User user = User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(password))
                    .email(email)
                    .role("USER")
                    .balance(0)
                    .createdAt(LocalDateTime.now())
                    .build();
            userMapper.insert(user);

            Map<String, Object> result = createTokenResponse(user);
            return result;
        });
    }

    public Mono<Map<String, Object>> login(String username, String password) {
        return Mono.fromCallable(() -> {
            User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
            if (user == null || user.getPassword().isEmpty() || !passwordEncoder.matches(password, user.getPassword())) {
                throw new RuntimeException("Invalid username or password");
            }

            return createTokenResponse(user);
        });
    }

    public Mono<Map<String, Object>> logout(String accessToken) {
        return Mono.fromCallable(() -> {
            oauthService.revokeToken(accessToken);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Logged out successfully");
            return result;
        });
    }

    private Map<String, Object> createTokenResponse(User user) {
        String accessToken = generateSecureToken();
        String refreshToken = generateSecureToken();

        long accessTokenExpiry = 900;
        long refreshTokenExpiry = 604800;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime accessExpires = now.plusSeconds(accessTokenExpiry);
        LocalDateTime refreshExpires = now.plusSeconds(refreshTokenExpiry);

        OAuthToken token = OAuthToken.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .userId(user.getId())
                .clientId("local")
                .scope("all")
                .expiresAt(refreshExpires)
                .revoked(false)
                .createdAt(now)
                .build();
        tokenMapper.insert(token);

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", accessTokenExpiry);
        response.put("user_id", user.getId());
        response.put("username", user.getUsername());
        response.put("role", user.getRole());
        return response;
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
