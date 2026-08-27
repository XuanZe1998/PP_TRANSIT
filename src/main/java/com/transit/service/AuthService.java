package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.UserMapper;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final OAuthService oauthService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationThrottle authenticationThrottle;
    private final TransactionTemplate transactionTemplate;
    private final VerificationCodeService verificationCodeService;
    private final AccountVerificationPolicy verificationPolicy;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^(1[3-9]\\d{9}|\\+[1-9]\\d{7,14})$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{10,}$");

    public Mono<Map<String, Object>> register(String identifier, String password) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> {
            String normalizedIdentifier = normalizeIdentifier(identifier);
            boolean emailIdentifier = isEmail(normalizedIdentifier);
            boolean phoneIdentifier = isPhone(normalizedIdentifier);
            if (!emailIdentifier && !phoneIdentifier) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Registration only supports a valid email address or phone number");
            }
            if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                    || !PASSWORD_PATTERN.matcher(password).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Password must be 10 to 72 bytes and contain letters and numbers");
            }

            if (findUserByIdentifier(normalizedIdentifier) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email or phone number already registered");
            }

            User user = User.builder()
                    .username(normalizedIdentifier)
                    .password(passwordEncoder.encode(password))
                    .email(emailIdentifier ? normalizedIdentifier : null)
                    .phone(phoneIdentifier ? normalizedIdentifier : null)
                    .authProvider("local")
                    .role("USER")
                    .balance(0)
                    .createdAt(LocalDateTime.now())
                    .build();
            try {
                userMapper.insert(user);
            } catch (DuplicateKeyException exception) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email or phone number already registered");
            }
            return oauthService.issueUserSession(user, "local");
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> register(String email, String emailCode, String phone, String phoneCode,
                                               String password, String displayName) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> {
            String normalizedEmail = normalizeIdentifier(email);
            String normalizedPhone = verificationPolicy.requiresPhone()
                    ? VerificationCodeService.normalizePhone(phone) : null;
            if (!isEmail(normalizedEmail) || (verificationPolicy.requiresPhone() && !isPhone(normalizedPhone))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        verificationPolicy.requiresPhone() ? "请填写有效的邮箱和手机号" : "请填写有效的邮箱");
            }
            validatePassword(password);
            if (findUserByIdentifier(normalizedEmail) != null
                    || (verificationPolicy.requiresPhone() && findUserByIdentifier(normalizedPhone) != null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱或手机号已注册");
            }
            verificationCodeService.consume("EMAIL", normalizedEmail, "REGISTER", emailCode);
            if (verificationPolicy.requiresPhone()) {
                verificationCodeService.consume("PHONE", normalizedPhone, "REGISTER", phoneCode);
            }
            LocalDateTime now = LocalDateTime.now();
            String name = displayName == null ? "" : displayName.trim();
            if (name.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "显示名称不能超过 80 个字符");
            User user = User.builder().username(normalizedEmail)
                    .displayName(name.isBlank() ? normalizedEmail.substring(0, normalizedEmail.indexOf('@')) : name)
                    .password(passwordEncoder.encode(password)).email(normalizedEmail).phone(normalizedPhone)
                    .emailVerifiedAt(now).phoneVerifiedAt(verificationPolicy.requiresPhone() ? now : null)
                    .locale("zh-CN").timezone("Asia/Shanghai")
                    .authProvider("local").role("USER").status("ACTIVE").balance(0)
                    .createdAt(now).lastLoginAt(now).build();
            try { userMapper.insert(user); }
            catch (DuplicateKeyException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱或手机号已注册"); }
            return oauthService.issueUserSession(user, "local");
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> login(String username, String password) {
        return Mono.fromCallable(() -> {
            String identifier = normalizeIdentifier(username);
            authenticationThrottle.checkAllowed(identifier);
            User user = findUserByIdentifier(identifier);
            if (user == null || user.getPassword() == null || user.getPassword().isBlank()
                    || password == null || !passwordEncoder.matches(password, user.getPassword())) {
                if (user == null) {
                    // Keep the missing-account path computationally close to a real BCrypt check.
                    passwordEncoder.encode(password == null ? "" : password);
                }
                authenticationThrottle.failure(identifier);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
            }
            if (!"ACTIVE".equalsIgnoreCase(Objects.toString(user.getStatus(), "ACTIVE"))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User account is not active");
            }
            authenticationThrottle.success(identifier);
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);
            return oauthService.issueUserSession(user, "local");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> logout(String accessToken) {
        return Mono.fromCallable(() -> {
            oauthService.revokeToken(accessToken);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Logged out successfully");
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Map<String, Object> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing refresh token");
        }
        return oauthService.refreshFirstPartySession(refreshToken.trim());
    }

    public Map<String, Object> validateIdentifier(String identifier) {
        String normalizedIdentifier = normalizeIdentifier(identifier);
        boolean email = isEmail(normalizedIdentifier);
        boolean phone = isPhone(normalizedIdentifier);
        Map<String, Object> result = new HashMap<>();
        result.put("identifier", normalizedIdentifier);
        result.put("valid", email || phone);
        result.put("type", email ? "email" : phone ? "phone" : "unknown");
        if (email || phone) {
            User existing = findUserByIdentifier(normalizedIdentifier);
            result.put("available", existing == null);
        } else {
            result.put("available", false);
        }
        return result;
    }

    private String normalizeIdentifier(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase();
    }

    private boolean isEmail(String identifier) {
        return EMAIL_PATTERN.matcher(identifier).matches();
    }

    private boolean isPhone(String identifier) {
        return PHONE_PATTERN.matcher(identifier).matches();
    }

    public void validatePassword(String password) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72
                || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Password must be 10 to 72 bytes and contain letters and numbers");
        }
    }

    private User findUserByIdentifier(String identifier) {
        User byUsername = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, identifier));
        if (byUsername != null) return byUsername;
        if (isEmail(identifier)) {
            return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, identifier));
        }
        if (isPhone(identifier)) {
            return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, identifier));
        }
        return null;
    }

}
