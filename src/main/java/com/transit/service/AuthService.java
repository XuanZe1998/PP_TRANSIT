package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.UserMapper;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.sql.Statement;

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
    private final JdbcTemplate jdbcTemplate;
    private final LoginIpService loginIps;
    private final LegalDocumentService legalDocuments;
    @Autowired(required = false)
    private AgentDistributionService agentDistributionService;
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
        return register(new Registration("PERSONAL", null, displayName, phone, email, emailCode,
                phoneCode, password, password, legalDocuments.termsVersion(), legalDocuments.privacyVersion(), true), "0.0.0.0");
    }

    public Mono<Map<String,Object>> register(Registration request, String clientIp) {
        return register(request, clientIp, null);
    }

    public Mono<Map<String,Object>> register(Registration request, String clientIp, String inviteCode) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> {
            String accountType = Objects.toString(request.accountType(), "PERSONAL").trim().toUpperCase();
            if (!List.of("PERSONAL", "ENTERPRISE").contains(accountType)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账户类型无效");
            String normalizedEmail = normalizeIdentifier(request.email());
            boolean phoneRequired = "ENTERPRISE".equals(accountType) || verificationPolicy.requiresPhone();
            String normalizedPhone = phoneRequired ? VerificationCodeService.normalizePhone(request.phone()) : null;
            if (!isEmail(normalizedEmail) || (phoneRequired && !isPhone(normalizedPhone)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, phoneRequired ? "请填写有效的邮箱和手机号" : "请填写有效的邮箱");
            validatePassword(request.password());
            boolean legacyPayload = request.confirmPassword() == null && request.termsVersion() == null
                    && request.privacyVersion() == null && !request.acceptedAgreements();
            if (request.confirmPassword() != null && !Objects.equals(request.password(), request.confirmPassword()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "两次输入的密码不一致");
            if (!legacyPayload) {
                if (!request.acceptedAgreements()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请阅读并接受用户协议和隐私政策");
                if (!legalDocuments.termsVersion().equals(request.termsVersion()) || !legalDocuments.privacyVersion().equals(request.privacyVersion()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "协议版本已更新，请重新阅读并接受");
            }
            if (findUserByIdentifier(normalizedEmail) != null
                    || (phoneRequired && findUserByIdentifier(normalizedPhone) != null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱或手机号已注册");
            }
            verificationCodeService.consume("EMAIL", normalizedEmail, "REGISTER", request.emailCode());
            if (verificationPolicy.requiresPhone() && "PERSONAL".equals(accountType)) {
                verificationCodeService.consume("PHONE", normalizedPhone, "REGISTER", request.phoneCode());
            }
            LocalDateTime now = LocalDateTime.now();
            String name = request.contactName() == null ? "" : request.contactName().trim();
            if (legacyPayload && name.isBlank()) name = normalizedEmail.substring(0, normalizedEmail.indexOf('@'));
            if (name.isBlank() || name.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系人或显示名称需为 1–80 个字符");
            String companyName = request.companyName() == null ? "" : request.companyName().trim();
            if ("ENTERPRISE".equals(accountType) && (companyName.isBlank() || companyName.length() > 160))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "企业名称需为 1–160 个字符");
            User user = User.builder().username(normalizedEmail)
                    .displayName(name).accountType(accountType)
                    .password(passwordEncoder.encode(request.password())).email(normalizedEmail).phone(normalizedPhone)
                    .emailVerifiedAt(now).phoneVerifiedAt(verificationPolicy.requiresPhone() && "PERSONAL".equals(accountType) ? now : null)
                    .locale("zh-CN").timezone("Asia/Shanghai")
                    .authProvider("local").role("USER").status("ACTIVE").balance(0)
                    .createdAt(now).lastLoginAt(now).build();
            try { userMapper.insert(user); }
            catch (DuplicateKeyException exception) { throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱或手机号已注册"); }
            long organizationId = createOrganization(user, accountType, companyName, now);
            user.setDefaultOrganizationId(organizationId);
            if ("ENTERPRISE".equals(accountType)) jdbcTemplate.update("INSERT INTO enterprise_profiles(user_id,organization_id,company_name,contact_name,contact_phone,contact_email,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    user.getId(), organizationId, companyName, name, normalizedPhone, normalizedEmail, now, now);
            String ipDigest = loginIps.digest(clientIp);
            if (!legacyPayload) legalDocuments.accept(user.getId(), request.termsVersion(), request.privacyVersion(), ipDigest);
            loginIps.trust(user.getId(), clientIp);
            if (agentDistributionService != null) agentDistributionService.bindByInvite(user.getId(), inviteCode, "REGISTER");
            return oauthService.issueUserSession(user, "local", ipDigest);
        })).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> login(String username, String password) { return login(username, password, "0.0.0.0"); }

    public Mono<Map<String, Object>> login(String username, String password, String clientIp) {
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
            if (!loginIps.isTrusted(user.getId(), clientIp)) return loginIps.createChallenge(user.getId(), user.getEmail(), clientIp);
            user.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(user);
            loginIps.touch(user.getId(), clientIp);
            Map<String,Object> session = oauthService.issueUserSession(user, "local", loginIps.digest(clientIp));
            session.put("agreementRequired", !legalDocuments.isCurrentAccepted(user.getId()));
            return session;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String,Object>> verifyLoginIp(String challengeId, String code, String clientIp) {
        return Mono.fromCallable(() -> transactionTemplate.execute(status -> {
            LoginIpService.VerifiedChallenge verified = loginIps.verify(challengeId, code, clientIp);
            User user = userMapper.selectById(verified.userId());
            if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "用户不可用");
            user.setLastLoginAt(LocalDateTime.now()); userMapper.updateById(user);
            Map<String,Object> session = oauthService.issueUserSession(user, "local", verified.ipDigest());
            session.put("agreementRequired", !legalDocuments.isCurrentAccepted(user.getId()));
            return session;
        })).subscribeOn(Schedulers.boundedElastic());
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

    private long createOrganization(User user, String accountType, String companyName, LocalDateTime now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO organizations(name,organization_type,status,created_by,created_at,updated_at) VALUES (?,?,'ACTIVE',?,?,?)", new String[]{"id"});
            statement.setString(1, "ENTERPRISE".equals(accountType) ? companyName : user.getDisplayName() + " 的个人组织");
            statement.setString(2, "ENTERPRISE".equals(accountType) ? "COMPANY" : "PERSONAL");
            statement.setLong(3, user.getId()); statement.setObject(4, now); statement.setObject(5, now); return statement;
        }, key);
        long organizationId = Objects.requireNonNull(key.getKey()).longValue();
        jdbcTemplate.update("INSERT INTO organization_members(organization_id,user_id,member_role,status,joined_at,updated_at) VALUES (?,?,'OWNER','ACTIVE',?,?)", organizationId, user.getId(), now, now);
        jdbcTemplate.update("INSERT INTO wallet_accounts(organization_id,user_id,account_type,balance,status,created_at,updated_at) VALUES (?,?, 'TREASURY',0,'ACTIVE',?,?)", organizationId, user.getId(), now, now);
        jdbcTemplate.update("UPDATE users SET default_organization_id=? WHERE id=?", organizationId, user.getId());
        return organizationId;
    }

    public record Registration(String accountType, String companyName, String contactName, String phone,
                               String email, String emailCode, String phoneCode, String password,
                               String confirmPassword, String termsVersion, String privacyVersion,
                               boolean acceptedAgreements) {}

}
