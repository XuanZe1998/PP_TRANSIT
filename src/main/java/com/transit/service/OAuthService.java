package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.OAuthClientMapper;
import com.transit.mapper.OAuthCodeMapper;
import com.transit.mapper.OAuthTokenMapper;
import com.transit.mapper.OAuthUserBindingMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.OAuthClient;
import com.transit.model.OAuthCode;
import com.transit.model.OAuthToken;
import com.transit.model.OAuthUserBinding;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private final OAuthClientMapper clientMapper;
    private final OAuthCodeMapper codeMapper;
    private final OAuthTokenMapper tokenMapper;
    private final OAuthUserBindingMapper bindingMapper;
    private final UserMapper userMapper;

    @Value("${oauth.github.client-id:}")
    private String githubClientId;

    @Value("${oauth.github.client-secret:}")
    private String githubClientSecret;

    @Value("${oauth.github.redirect-uri:http://localhost:8080/oauth/callback/github}")
    private String githubRedirectUri;

    @Value("${oauth.google.client-id:}")
    private String googleClientId;

    @Value("${oauth.google.client-secret:}")
    private String googleClientSecret;

    @Value("${oauth.google.redirect-uri:http://localhost:8080/oauth/callback/google}")
    private String googleRedirectUri;

    @Value("${oauth.token.access-token-expiry:900}")
    private long accessTokenExpiry;

    @Value("${oauth.token.refresh-token-expiry:604800}")
    private long refreshTokenExpiry;

    private static final WebClient webClient = WebClient.create();
    private final SecureRandom secureRandom = new SecureRandom();

    public String getAuthorizeUrl(String provider, String state) {
        return switch (provider.toLowerCase()) {
            case "github" -> String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=read:user&state=%s",
                githubClientId, githubRedirectUri, state
            );
            case "google" -> String.format(
                "https://accounts.google.com/o/oauth2/v2/auth?client_id=%s&redirect_uri=%s&scope=openid%%20email%%20profile&state=%s&response_type=code",
                googleClientId, googleRedirectUri, state
            );
            default -> throw new RuntimeException("Unsupported provider: " + provider);
        };
    }

    public Mono<Map<String, Object>> handleCallback(String provider, String code, String state) {
        return Mono.fromCallable(() -> switch (provider.toLowerCase()) {
            case "github" -> exchangeGithubCode(code);
            case "google" -> exchangeGoogleCode(code);
            default -> throw new RuntimeException("Unsupported provider: " + provider);
        });
    }

    private Map<String, Object> exchangeGithubCode(String code) {
        Map<String, String> params = new HashMap<>();
        params.put("client_id", githubClientId);
        params.put("client_secret", githubClientSecret);
        params.put("code", code);

        Map<String, Object> response = webClient.post()
            .uri("https://github.com/login/oauth/access_token")
            .bodyValue(params)
            .header("Accept", "application/json")
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        if (response == null || response.containsKey("error")) {
            throw new RuntimeException("Failed to exchange code: " + (response != null ? response.get("error_description") : "unknown"));
        }

        String accessToken = (String) response.get("access_token");
        return processProviderUser("github", accessToken);
    }

    private Map<String, Object> exchangeGoogleCode(String code) {
        Map<String, String> params = new HashMap<>();
        params.put("client_id", googleClientId);
        params.put("client_secret", googleClientSecret);
        params.put("code", code);
        params.put("grant_type", "authorization_code");
        params.put("redirect_uri", googleRedirectUri);

        Map<String, Object> response = webClient.post()
            .uri("https://oauth2.googleapis.com/token")
            .bodyValue(params)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        if (response == null || response.containsKey("error")) {
            throw new RuntimeException("Failed to exchange code: " + (response != null ? response.get("error_description") : "unknown"));
        }

        String accessToken = (String) response.get("access_token");
        String refreshToken = (String) response.get("refresh_token");
        return processProviderUserWithRefresh("google", accessToken, refreshToken, (Integer) response.get("expires_in"));
    }

    private Map<String, Object> processProviderUser(String provider, String accessToken) {
        return processProviderUserWithRefresh(provider, accessToken, null, null);
    }

    private Map<String, Object> processProviderUserWithRefresh(String provider, String accessToken, String refreshToken, Integer expiresIn) {
        Map<String, Object> userInfo = getUserInfo(provider, accessToken);
        String providerUserId = (String) userInfo.get("id");
        String email = (String) userInfo.get("email");
        String username = (String) userInfo.get("login");

        OAuthUserBinding binding = bindingMapper.selectOne(
            new LambdaQueryWrapper<OAuthUserBinding>()
                .eq(OAuthUserBinding::getProvider, provider)
                .eq(OAuthUserBinding::getProviderUserId, providerUserId)
        );

        User user;
        if (binding == null) {
            user = createUserFromProvider(provider, providerUserId, email, username);
            binding = OAuthUserBinding.builder()
                .userId(user.getId())
                .provider(provider)
                .providerUserId(providerUserId)
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresAt(refreshToken != null ? LocalDateTime.now().plusSeconds(refreshTokenExpiry) : null)
                .createdAt(LocalDateTime.now())
                .build();
            bindingMapper.insert(binding);
        } else {
            user = userMapper.selectById(binding.getUserId());
            binding.setAccessToken(accessToken);
            if (refreshToken != null) binding.setRefreshToken(refreshToken);
            if (expiresIn != null) binding.setExpiresAt(LocalDateTime.now().plusSeconds(expiresIn));
            bindingMapper.updateById(binding);
        }

        return createTokenResponse(user);
    }

    private Map<String, Object> getUserInfo(String provider, String accessToken) {
        return switch (provider.toLowerCase()) {
            case "github" -> {
                Map<String, Object> userInfo = webClient.get()
                    .uri("https://api.github.com/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
                String email = (String) userInfo.get("email");
                if (email == null) {
                    Map<String, Object> emails = webClient.get()
                        .uri("https://api.github.com/user/emails")
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();
                }
                yield userInfo;
            }
            case "google" -> {
                Map<String, Object> userInfo = webClient.get()
                    .uri("https://www.googleapis.com/oauth2/v2/userinfo")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
                yield userInfo;
            }
            default -> throw new RuntimeException("Unsupported provider");
        };
    }

    private User createUserFromProvider(String provider, String providerUserId, String email, String username) {
        User user = User.builder()
            .username(username != null ? username : (provider + "_" + providerUserId))
            .password("")
            .email(email != null ? email : "")
            .role("USER")
            .balance(0)
            .createdAt(LocalDateTime.now())
            .build();
        userMapper.insert(user);
        return user;
    }

    public Map<String, Object> exchangeToken(String clientId, String clientSecret, String code, String grantType) {
        if (!"authorization_code".equals(grantType) && !"refresh_token".equals(grantType)) {
            throw new RuntimeException("Unsupported grant_type");
        }

        OAuthClient client = clientMapper.selectOne(
            new LambdaQueryWrapper<OAuthClient>().eq(OAuthClient::getClientId, clientId)
        );
        if (client == null || !client.getClientSecret().equals(clientSecret)) {
            throw new RuntimeException("Invalid client credentials");
        }

        if ("authorization_code".equals(grantType)) {
            OAuthCode authCode = codeMapper.selectOne(
                new LambdaQueryWrapper<OAuthCode>().eq(OAuthCode::getCode, code)
            );
            if (authCode == null || authCode.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("Invalid or expired code");
            }
            if (!authCode.getClientId().equals(clientId)) {
                throw new RuntimeException("Code mismatch client_id");
            }

            codeMapper.deleteById(authCode.getId());
            User user = userMapper.selectById(authCode.getUserId());
            return createTokenResponse(user);
        } else {
            OAuthToken token = tokenMapper.selectOne(
                new LambdaQueryWrapper<OAuthToken>().eq(OAuthToken::getRefreshToken, code)
            );
            if (token == null || token.getRevoked()) {
                throw new RuntimeException("Invalid refresh token");
            }
            if (token.getExpiresAt().isBefore(LocalDateTime.now())) {
                throw new RuntimeException("Expired refresh token");
            }

            User user = userMapper.selectById(token.getUserId());
            return createTokenResponse(user);
        }
    }

    public Map<String, Object> refreshToken(String clientId, String clientSecret, String refreshToken) {
        OAuthClient client = clientMapper.selectOne(
            new LambdaQueryWrapper<OAuthClient>().eq(OAuthClient::getClientId, clientId)
        );
        if (client == null || !client.getClientSecret().equals(clientSecret)) {
            throw new RuntimeException("Invalid client credentials");
        }

        OAuthToken token = tokenMapper.selectOne(
            new LambdaQueryWrapper<OAuthToken>().eq(OAuthToken::getRefreshToken, refreshToken)
        );
        if (token == null || token.getRevoked()) {
            throw new RuntimeException("Invalid refresh token");
        }

        User user = userMapper.selectById(token.getUserId());
        tokenMapper.deleteById(token.getId());
        return createTokenResponse(user);
    }

    public void revokeToken(String accessToken) {
        OAuthToken token = tokenMapper.selectOne(
            new LambdaQueryWrapper<OAuthToken>().eq(OAuthToken::getAccessToken, accessToken)
        );
        if (token != null) {
            token.setRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
            tokenMapper.updateById(token);
        }
    }

    public Map<String, Object> validateToken(String accessToken) {
        OAuthToken token = tokenMapper.selectOne(
            new LambdaQueryWrapper<OAuthToken>().eq(OAuthToken::getAccessToken, accessToken)
        );
        if (token == null || token.getRevoked() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid or expired token");
        }

        User user = userMapper.selectById(token.getUserId());
        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
    }

    public User getUserFromToken(String accessToken) {
        OAuthToken token = tokenMapper.selectOne(
            new LambdaQueryWrapper<OAuthToken>().eq(OAuthToken::getAccessToken, accessToken)
        );
        if (token == null || token.getRevoked() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Invalid or expired token");
        }
        return userMapper.selectById(token.getUserId());
    }

    private Map<String, Object> createTokenResponse(User user) {
        String accessToken = generateSecureToken();
        String refreshToken = generateSecureToken();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime accessExpires = now.plusSeconds(accessTokenExpiry);
        LocalDateTime refreshExpires = now.plusSeconds(refreshTokenExpiry);

        OAuthToken token = OAuthToken.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .userId(user.getId())
            .clientId("")
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

    public String generateCode(String clientId, Long userId, String redirectUri) {
        String code = UUID.randomUUID().toString().replace("-", "");
        OAuthCode authCode = OAuthCode.builder()
            .code(code)
            .clientId(clientId)
            .userId(userId)
            .redirectUri(redirectUri)
            .expiresAt(LocalDateTime.now().plusMinutes(10))
            .build();
        codeMapper.insert(authCode);
        return code;
    }
}
