package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.transit.mapper.OAuthClientMapper;
import com.transit.mapper.OAuthCodeMapper;
import com.transit.mapper.OAuthLoginStateMapper;
import com.transit.mapper.OAuthTokenMapper;
import com.transit.mapper.OAuthUserBindingMapper;
import com.transit.mapper.UserMapper;
import com.transit.model.OAuthClient;
import com.transit.model.OAuthCode;
import com.transit.model.OAuthLoginState;
import com.transit.model.OAuthToken;
import com.transit.model.OAuthUserBinding;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OAuthService {

    private final OAuthClientMapper clientMapper;
    private final OAuthCodeMapper codeMapper;
    private final OAuthTokenMapper tokenMapper;
    private final OAuthUserBindingMapper bindingMapper;
    private final OAuthLoginStateMapper loginStateMapper;
    private final UserMapper userMapper;
    private final WebClient webClient;
    private final SecretHashService secretHashService;
    private final AccountVerificationPolicy verificationPolicy;
    private final SecureRandom secureRandom = new SecureRandom();
    @Autowired(required = false)
    private AvatarStorageService avatarStorageService;

    @Value("${oauth.github.client-id:}")
    private String githubClientId;

    @Value("${oauth.github.client-secret:}")
    private String githubClientSecret;

    @Value("${oauth.github.redirect-uri:http://127.0.0.1:5173/oauth/callback/github}")
    private String githubRedirectUri;

    @Value("${oauth.github.authorize-uri:https://github.com/login/oauth/authorize}")
    private String githubAuthorizeUri;

    @Value("${oauth.github.token-uri:https://github.com/login/oauth/access_token}")
    private String githubTokenUri;

    @Value("${oauth.github.user-uri:https://api.github.com/user}")
    private String githubUserUri;

    @Value("${oauth.github.emails-uri:https://api.github.com/user/emails}")
    private String githubEmailsUri;

    @Value("${oauth.google.client-id:}")
    private String googleClientId;

    @Value("${oauth.google.client-secret:}")
    private String googleClientSecret;

    @Value("${oauth.google.redirect-uri:http://127.0.0.1:5173/oauth/callback/google}")
    private String googleRedirectUri;

    @Value("${oauth.google.authorize-uri:https://accounts.google.com/o/oauth2/v2/auth}")
    private String googleAuthorizeUri;

    @Value("${oauth.google.token-uri:https://oauth2.googleapis.com/token}")
    private String googleTokenUri;

    @Value("${oauth.google.user-uri:https://www.googleapis.com/oauth2/v2/userinfo}")
    private String googleUserUri;

    @Value("${oauth.state-expiry:600}")
    private long stateExpirySeconds;

    @Value("${oauth.token.access-token-expiry:900}")
    private long accessTokenExpiry;

    @Value("${oauth.token.refresh-token-expiry:604800}")
    private long refreshTokenExpiry;

    public AuthorizationStart beginAuthorization(String requestedProvider) {
        return beginAuthorization(requestedProvider, null);
    }

    public AuthorizationStart beginAuthorization(String requestedProvider, Long targetUserId) {
        String provider = normalizeProvider(requestedProvider);
        if (targetUserId != null) requireActiveUser(userMapper.selectById(targetUserId));
        String state = generateSecureToken();
        LocalDateTime now = LocalDateTime.now();
        loginStateMapper.insert(OAuthLoginState.builder()
                .stateHash(secretHashService.hash(state))
                .provider(provider)
                .targetUserId(targetUserId)
                .flowType(targetUserId == null ? "LOGIN" : "BIND")
                .expiresAt(now.plusSeconds(Math.max(60, stateExpirySeconds)))
                .createdAt(now)
                .build());
        cleanupLoginStates(now);
        return new AuthorizationStart(authorizeUrl(provider, state), state);
    }

    private String authorizeUrl(String provider, String state) {
        return switch (provider) {
            case "github" -> {
                requireConfigured("GitHub", githubClientId, githubClientSecret);
                yield UriComponentsBuilder.fromUriString(githubAuthorizeUri)
                        .queryParam("client_id", githubClientId)
                        .queryParam("redirect_uri", githubRedirectUri)
                        .queryParam("scope", "read:user user:email")
                        .queryParam("state", state)
                        .build().encode().toUriString();
            }
            case "google" -> {
                requireConfigured("Google", googleClientId, googleClientSecret);
                yield UriComponentsBuilder.fromUriString(googleAuthorizeUri)
                        .queryParam("client_id", googleClientId)
                        .queryParam("redirect_uri", googleRedirectUri)
                        .queryParam("scope", "openid email profile")
                        .queryParam("state", state)
                        .queryParam("response_type", "code")
                        .queryParam("prompt", "select_account")
                        .build().encode().toUriString();
            }
            default -> throw unsupportedProvider();
        };
    }

    public Mono<Map<String, Object>> handleCallback(String requestedProvider, String code, String state) {
        return Mono.fromCallable(() -> {
                    String provider = normalizeProvider(requestedProvider);
                    if (code == null || code.isBlank() || code.length() > 2048) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth authorization code is missing or invalid");
                    }
                    OAuthLoginState loginState = consumeLoginState(provider, state);
                    return switch (provider) {
                        case "github" -> exchangeGithubCode(code, loginState.getTargetUserId());
                        case "google" -> exchangeGoogleCode(code, loginState.getTargetUserId());
                        default -> throw unsupportedProvider();
                    };
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private OAuthLoginState consumeLoginState(String provider, String state) {
        if (state == null || state.isBlank() || state.length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state is missing or invalid");
        }
        LocalDateTime now = LocalDateTime.now();
        String stateHash = secretHashService.hash(state);
        OAuthLoginState loginState = loginStateMapper.selectOne(new LambdaQueryWrapper<OAuthLoginState>()
                .eq(OAuthLoginState::getStateHash, stateHash).eq(OAuthLoginState::getProvider, provider).last("LIMIT 1"));
        int updated = loginStateMapper.update(null,
                new LambdaUpdateWrapper<OAuthLoginState>()
                        .set(OAuthLoginState::getConsumedAt, now)
                        .eq(OAuthLoginState::getStateHash, stateHash)
                        .eq(OAuthLoginState::getProvider, provider)
                        .isNull(OAuthLoginState::getConsumedAt)
                        .gt(OAuthLoginState::getExpiresAt, now));
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state is expired, already used, or does not match");
        }
        return loginState;
    }

    private void cleanupLoginStates(LocalDateTime now) {
        loginStateMapper.delete(new LambdaQueryWrapper<OAuthLoginState>()
                .lt(OAuthLoginState::getExpiresAt, now.minusHours(1)));
    }

    private Map<String, Object> exchangeGithubCode(String code, Long targetUserId) {
        requireConfigured("GitHub", githubClientId, githubClientSecret);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", githubClientId);
        form.add("client_secret", githubClientSecret);
        form.add("code", code);
        form.add("redirect_uri", githubRedirectUri);
        Map<String, Object> response = postForm(githubTokenUri, form, "GitHub");
        String accessToken = requiredToken(response, "access_token", "GitHub");
        return processProviderUser("github", accessToken, targetUserId);
    }

    private Map<String, Object> exchangeGoogleCode(String code, Long targetUserId) {
        requireConfigured("Google", googleClientId, googleClientSecret);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", googleClientId);
        form.add("client_secret", googleClientSecret);
        form.add("code", code);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", googleRedirectUri);
        Map<String, Object> response = postForm(googleTokenUri, form, "Google");
        String accessToken = requiredToken(response, "access_token", "Google");
        return processProviderUser("google", accessToken, targetUserId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForm(String uri, MultiValueMap<String, String> form, String provider) {
        try {
            Map<String, Object> response = webClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null || response.containsKey("error")) {
                throw providerFailure(provider);
            }
            return response;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (WebClientResponseException exception) {
            throw providerFailure(provider);
        } catch (Exception exception) {
            throw providerFailure(provider);
        }
    }

    private String requiredToken(Map<String, Object> response, String key, String provider) {
        Object value = response.get(key);
        if (value == null || value.toString().isBlank()) {
            throw providerFailure(provider);
        }
        return value.toString();
    }

    private Map<String, Object> processProviderUser(String provider, String providerAccessToken, Long targetUserId) {
        Map<String, Object> userInfo = getUserInfo(provider, providerAccessToken);
        Object providerIdValue = userInfo.get("id") != null ? userInfo.get("id") : userInfo.get("sub");
        String providerUserId = providerIdValue == null ? "" : providerIdValue.toString().trim();
        if (providerUserId.isBlank() || providerUserId.length() > 255) {
            throw providerFailure(provider);
        }

        String email = normalizedEmail(userInfo.get("email"));
        String username = Objects.toString(userInfo.getOrDefault("login", userInfo.get("name")), "").trim();
        OAuthUserBinding binding = bindingMapper.selectOne(new LambdaQueryWrapper<OAuthUserBinding>()
                .eq(OAuthUserBinding::getProvider, provider)
                .eq(OAuthUserBinding::getProviderUserId, providerUserId));

        User user;
        if (binding != null && targetUserId != null && !Objects.equals(binding.getUserId(), targetUserId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该第三方账号已绑定其他平台账户");
        }
        if (binding == null) {
            user = targetUserId == null ? findOrCreateUserFromProvider(provider, providerUserId, email, username)
                    : userMapper.selectById(targetUserId);
            requireActiveUser(user);
            bindingMapper.insert(OAuthUserBinding.builder()
                    .userId(user.getId())
                    .provider(provider)
                    .providerUserId(providerUserId)
                    // This application only uses provider tokens to fetch identity data.
                    // Do not retain them after sign-in.
                    .accessToken(null)
                    .refreshToken(null)
                    .expiresAt(null)
                    .createdAt(LocalDateTime.now())
                    .build());
        } else {
            user = userMapper.selectById(binding.getUserId());
        }
        if (user != null) {
            LocalDateTime now = LocalDateTime.now();
            if (email != null && email.equalsIgnoreCase(Objects.toString(user.getEmail(), email)) && user.getEmailVerifiedAt() == null) user.setEmailVerifiedAt(now);
            if ((user.getDisplayName() == null || user.getDisplayName().isBlank()) && !username.isBlank()) user.setDisplayName(username.substring(0, Math.min(80, username.length())));
            if ((user.getAvatarPath() == null || user.getAvatarPath().isBlank()) && avatarStorageService != null) {
                String avatarUrl = Objects.toString(userInfo.get(provider.equals("github") ? "avatar_url" : "picture"), "");
                String host;
                try { host = java.net.URI.create(avatarUrl).getHost(); } catch(Exception ignored) { host = null; }
                if (host != null && (host.equals("avatars.githubusercontent.com") || host.endsWith(".googleusercontent.com"))) {
                    try { byte[] bytes=webClient.get().uri(avatarUrl).retrieve().bodyToMono(byte[].class).block(); user.setAvatarPath(avatarStorageService.storeRemote(bytes)); }
                    catch(Exception ignored) { /* Identity succeeds even if the optional avatar cache is unavailable. */ }
                }
            }
            user.setLastLoginAt(now); userMapper.updateById(user);
        }
        requireActiveUser(user);
        Map<String,Object> response = issueUserSession(user, "social:" + provider);
        response.put("oauthAction", targetUserId == null ? "LOGIN" : "BIND");
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getUserInfo(String provider, String accessToken) {
        try {
            return switch (provider) {
                case "github" -> {
                    Map<String, Object> userInfo = webClient.get()
                            .uri(githubUserUri)
                            .headers(headers -> {
                                headers.setBearerAuth(accessToken);
                                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                            })
                            .retrieve().bodyToMono(Map.class).block();
                    if (userInfo == null) throw providerFailure("GitHub");
                    if (normalizedEmail(userInfo.get("email")) == null) {
                        List<Map<String, Object>> emails = webClient.get()
                                .uri(githubEmailsUri)
                                .headers(headers -> {
                                    headers.setBearerAuth(accessToken);
                                    headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                                })
                                .retrieve().bodyToMono(List.class).block();
                        String verifiedEmail = emails == null ? null : emails.stream()
                                .filter(item -> Boolean.TRUE.equals(item.get("verified")))
                                .sorted((left, right) -> Boolean.compare(
                                        Boolean.TRUE.equals(right.get("primary")),
                                        Boolean.TRUE.equals(left.get("primary"))))
                                .map(item -> normalizedEmail(item.get("email")))
                                .filter(Objects::nonNull)
                                .findFirst().orElse(null);
                        userInfo.put("email", verifiedEmail);
                    }
                    yield userInfo;
                }
                case "google" -> {
                    Map<String, Object> userInfo = webClient.get()
                            .uri(googleUserUri)
                            .headers(headers -> headers.setBearerAuth(accessToken))
                            .retrieve().bodyToMono(Map.class).block();
                    if (userInfo == null || !Boolean.TRUE.equals(userInfo.get("verified_email"))) {
                        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google account email is not verified");
                    }
                    yield userInfo;
                }
                default -> throw unsupportedProvider();
            };
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw providerFailure(provider);
        }
    }

    private User findOrCreateUserFromProvider(String provider, String providerUserId, String email, String username) {
        if (email != null) {
            User existing = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
            if (existing != null) return existing;
        }
        String baseUsername = username.isBlank() ? provider + "_" + providerUserId : username;
        User user = User.builder()
                .username(uniqueUsername(baseUsername))
                .password("")
                .email(email)
                .displayName(username.isBlank() ? null : username.substring(0, Math.min(80, username.length())))
                .emailVerifiedAt(email == null ? null : LocalDateTime.now())
                .locale("zh-CN")
                .timezone("Asia/Shanghai")
                .authProvider(provider)
                .role("USER")
                .status("ACTIVE")
                .balance(0)
                .createdAt(LocalDateTime.now())
                .build();
        userMapper.insert(user);
        return user;
    }

    private String uniqueUsername(String baseUsername) {
        String normalized = baseUsername.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_@.-]", "_");
        if (normalized.isBlank()) normalized = "oauth_user";
        normalized = normalized.substring(0, Math.min(100, normalized.length()));
        String candidate = normalized;
        int suffix = 1;
        while (userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, candidate)) != null) {
            candidate = normalized.substring(0, Math.min(100, normalized.length())) + "_" + suffix++;
        }
        return candidate;
    }

    public Map<String, Object> exchangeToken(String clientId, String clientSecret, String code,
                                             String grantType, String redirectUri) {
        if (!"authorization_code".equals(grantType) && !"refresh_token".equals(grantType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported grant_type");
        }
        OAuthClient client = requireClient(clientId, clientSecret);
        if ("refresh_token".equals(grantType)) {
            return rotateRefreshToken(client, code);
        }

        OAuthCode authCode = findAuthorizationCode(code);
        if (authCode == null || authCode.getExpiresAt() == null || authCode.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired authorization code");
        }
        if (!Objects.equals(authCode.getClientId(), clientId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Authorization code client mismatch");
        }
        if (authCode.getRedirectUri() != null && !authCode.getRedirectUri().isBlank()
                && !Objects.equals(authCode.getRedirectUri(), redirectUri)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uri does not match authorization request");
        }
        codeMapper.deleteById(authCode.getId());
        User user = userMapper.selectById(authCode.getUserId());
        requireActiveUser(user);
        return issueUserSession(user, client.getClientId());
    }

    public Map<String, Object> refreshToken(String clientId, String clientSecret, String refreshToken) {
        return rotateRefreshToken(requireClient(clientId, clientSecret), refreshToken);
    }

    public Map<String, Object> refreshFirstPartySession(String rawRefreshToken) {
        OAuthToken token = findByRefreshToken(rawRefreshToken);
        String clientId = token == null ? "" : Objects.toString(token.getClientId(), "");
        if (token == null || Boolean.TRUE.equals(token.getRevoked()) || token.getExpiresAt() == null
                || token.getExpiresAt().isBefore(LocalDateTime.now())
                || !("local".equals(clientId) || clientId.startsWith("social:"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }
        User user = userMapper.selectById(token.getUserId());
        requireActiveUser(user);
        tokenMapper.deleteById(token.getId());
        return issueUserSession(user, clientId);
    }

    private Map<String, Object> rotateRefreshToken(OAuthClient client, String rawRefreshToken) {
        OAuthToken token = findByRefreshToken(rawRefreshToken);
        if (token == null || Boolean.TRUE.equals(token.getRevoked()) || token.getExpiresAt() == null
                || token.getExpiresAt().isBefore(LocalDateTime.now())
                || !Objects.equals(token.getClientId(), client.getClientId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired refresh token");
        }
        User user = userMapper.selectById(token.getUserId());
        requireActiveUser(user);
        tokenMapper.deleteById(token.getId());
        return issueUserSession(user, client.getClientId());
    }

    private OAuthClient requireClient(String clientId, String clientSecret) {
        OAuthClient client = clientMapper.selectOne(new LambdaQueryWrapper<OAuthClient>()
                .eq(OAuthClient::getClientId, clientId));
        if (client == null || !secretHashService.matches(clientSecret, client.getClientSecret())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials");
        }
        return client;
    }

    public void revokeToken(String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) return;
        OAuthToken token = findByAccessToken(rawAccessToken);
        if (token != null) {
            token.setRevoked(true);
            token.setRevokedAt(LocalDateTime.now());
            tokenMapper.updateById(token);
        }
    }

    public List<Map<String, Object>> listSessions(Long userId, String rawAccessToken) {
        OAuthToken current = findByAccessToken(rawAccessToken);
        return tokenMapper.selectList(new LambdaQueryWrapper<OAuthToken>()
                        .eq(OAuthToken::getUserId, userId).eq(OAuthToken::getRevoked, false)
                        .gt(OAuthToken::getExpiresAt, LocalDateTime.now()).orderByDesc(OAuthToken::getCreatedAt))
                .stream().map(token -> {
                    Map<String,Object> item=new java.util.LinkedHashMap<>(); item.put("id",token.getId());
                    item.put("client",token.getClientId()); item.put("device",Objects.toString(token.getDeviceName(), token.getClientId()));
                    item.put("createdAt",token.getCreatedAt()); item.put("lastActiveAt",token.getLastActiveAt());
                    item.put("current",current!=null&&Objects.equals(current.getId(),token.getId())); return item;
                }).toList();
    }

    public void revokeSession(Long userId, Long sessionId) {
        OAuthToken token=tokenMapper.selectById(sessionId);
        if(token==null||!Objects.equals(userId,token.getUserId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
        token.setRevoked(true);token.setRevokedAt(LocalDateTime.now());tokenMapper.updateById(token);
    }

    public void revokeOtherSessions(Long userId, String rawAccessToken) {
        OAuthToken current=findByAccessToken(rawAccessToken);
        LambdaUpdateWrapper<OAuthToken> update=new LambdaUpdateWrapper<OAuthToken>()
                .set(OAuthToken::getRevoked,true).set(OAuthToken::getRevokedAt,LocalDateTime.now())
                .eq(OAuthToken::getUserId,userId).eq(OAuthToken::getRevoked,false);
        if(current!=null)update.ne(OAuthToken::getId,current.getId());tokenMapper.update(null,update);
    }

    public Map<String, Object> validateToken(String rawAccessToken) {
        User user = getUserFromToken(rawAccessToken);
        return Map.of("userId", user.getId(), "username", user.getUsername(), "role", user.getRole());
    }

    public User getUserFromToken(String rawAccessToken) {
        OAuthToken token = findByAccessToken(rawAccessToken);
        if (token == null || Boolean.TRUE.equals(token.getRevoked()) || accessExpired(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        User user = userMapper.selectById(token.getUserId());
        requireActiveUser(user);
        return user;
    }

    public Map<String, Object> issueUserSession(User user, String clientId) {
        requireActiveUser(user);
        String accessToken = generateSecureToken();
        String refreshToken = generateSecureToken();
        LocalDateTime now = LocalDateTime.now();
        tokenMapper.insert(OAuthToken.builder()
                .accessToken(secretHashService.hash(accessToken))
                .refreshToken(secretHashService.hash(refreshToken))
                .tokenType("Bearer")
                .userId(user.getId())
                .clientId(clientId == null ? "" : clientId)
                .scope("all")
                .accessExpiresAt(now.plusSeconds(Math.max(60, accessTokenExpiry)))
                .expiresAt(now.plusSeconds(Math.max(accessTokenExpiry, refreshTokenExpiry)))
                .revoked(false)
                .deviceName(clientId == null || clientId.isBlank() ? "Web" : clientId)
                .lastActiveAt(now)
                .createdAt(now)
                .build());

        Map<String, Object> response = new HashMap<>();
        response.put("access_token", accessToken);
        response.put("refresh_token", refreshToken);
        response.put("token_type", "Bearer");
        response.put("expires_in", accessTokenExpiry);
        response.put("user_id", user.getId());
        response.put("username", user.getUsername());
        response.put("displayName", user.getDisplayName());
        response.put("avatarPath", user.getAvatarPath());
        response.put("accountComplete", verificationPolicy.isComplete(user));
        response.put("role", user.getRole());
        return response;
    }

    private OAuthToken findByAccessToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        String digest = secretHashService.hash(rawToken);
        OAuthToken token = tokenMapper.selectOne(new LambdaQueryWrapper<OAuthToken>()
                .eq(OAuthToken::getAccessToken, digest));
        if (token == null) {
            token = tokenMapper.selectOne(new LambdaQueryWrapper<OAuthToken>()
                    .eq(OAuthToken::getAccessToken, rawToken));
            if (token != null) {
                token.setAccessToken(digest);
                tokenMapper.updateById(token);
            }
        }
        return token;
    }

    private OAuthToken findByRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        String digest = secretHashService.hash(rawToken);
        OAuthToken token = tokenMapper.selectOne(new LambdaQueryWrapper<OAuthToken>()
                .eq(OAuthToken::getRefreshToken, digest));
        if (token == null) {
            token = tokenMapper.selectOne(new LambdaQueryWrapper<OAuthToken>()
                    .eq(OAuthToken::getRefreshToken, rawToken));
            if (token != null) {
                token.setRefreshToken(digest);
                tokenMapper.updateById(token);
            }
        }
        return token;
    }

    private OAuthCode findAuthorizationCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return null;
        OAuthCode code = codeMapper.selectOne(new LambdaQueryWrapper<OAuthCode>()
                .eq(OAuthCode::getCode, secretHashService.hash(rawCode)));
        if (code == null) {
            code = codeMapper.selectOne(new LambdaQueryWrapper<OAuthCode>().eq(OAuthCode::getCode, rawCode));
        }
        return code;
    }

    public String generateCode(String clientId, Long userId, String redirectUri) {
        if (clientId == null || clientId.isBlank() || userId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "client_id and user_id are required");
        }
        String code = generateSecureToken();
        codeMapper.insert(OAuthCode.builder()
                .code(secretHashService.hash(code))
                .clientId(clientId)
                .userId(userId)
                .redirectUri(redirectUri)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build());
        return code;
    }

    private boolean accessExpired(OAuthToken token) {
        LocalDateTime expiry = token.getAccessExpiresAt() == null ? token.getExpiresAt() : token.getAccessExpiresAt();
        return expiry == null || !expiry.isAfter(LocalDateTime.now());
    }

    private void requireConfigured(String provider, String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, provider + " OAuth is not configured");
        }
    }

    private void requireActiveUser(User user) {
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists");
        }
        if (!"ACTIVE".equalsIgnoreCase(Objects.toString(user.getStatus(), "ACTIVE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is not active");
        }
    }

    private String normalizedEmail(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        String email = value.toString().trim().toLowerCase(Locale.ROOT);
        return email.length() <= 255 ? email : null;
    }

    private String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!"github".equals(normalized) && !"google".equals(normalized)) throw unsupportedProvider();
        return normalized;
    }

    private ResponseStatusException unsupportedProvider() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported OAuth provider");
    }

    private ResponseStatusException providerFailure(String provider) {
        String normalized = provider == null || provider.isBlank() ? "OAuth provider" : provider;
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, normalized + " authentication failed");
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record AuthorizationStart(String url, String state) {
    }
}
