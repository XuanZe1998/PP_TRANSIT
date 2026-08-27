package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.transit.mapper.UserMapper;
import com.transit.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserProfileService {
    private final UserMapper userMapper;
    private final VerificationCodeService verificationCodeService;
    private final AvatarStorageService avatarStorageService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final OAuthService oauthService;
    private final JdbcTemplate jdbc;
    private final AccountVerificationPolicy verificationPolicy;

    public User updateBasics(User user, Map<String,Object> request) {
        String displayName = text(request.get("displayName"), 80);
        String locale = text(request.get("locale"), 20);
        String timezone = text(request.get("timezone"), 80);
        if (timezone != null) { try { ZoneId.of(timezone); } catch(Exception e) { throw bad("时区无效"); } }
        if (displayName != null) user.setDisplayName(displayName);
        if (locale != null && !locale.matches("[A-Za-z]{2,3}([-_][A-Za-z]{2,8})?")) throw bad("语言偏好无效");
        if (locale != null) user.setLocale(locale); if (timezone != null) user.setTimezone(timezone);
        userMapper.updateById(user); return user;
    }

    public String updateAvatar(User user, MultipartFile file) {
        String path=avatarStorageService.store(file); user.setAvatarPath(path); userMapper.updateById(user); return path;
    }

    @Transactional
    public User updateContacts(User user, String email, String emailCode, String phone, String phoneCode) {
        String normalizedEmail=email==null?null:email.trim().toLowerCase(Locale.ROOT), normalizedPhone=phone==null?null:VerificationCodeService.normalizePhone(phone);
        if(normalizedEmail!=null&&!normalizedEmail.equals(user.getEmail())){verificationCodeService.consume("EMAIL",normalizedEmail,"CHANGE",emailCode);user.setEmail(normalizedEmail);user.setEmailVerifiedAt(LocalDateTime.now());}
        if(normalizedPhone!=null&&!normalizedPhone.equals(user.getPhone())){verificationCodeService.consume("PHONE",normalizedPhone,"CHANGE",phoneCode);user.setPhone(normalizedPhone);user.setPhoneVerifiedAt(LocalDateTime.now());}
        try{userMapper.updateById(user);}catch(DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"邮箱或手机号已被绑定");}return user;
    }

    public void changePassword(User user, String currentPassword, String newPassword) {
        if(user.getPassword()!=null&&!user.getPassword().isBlank()&&!passwordEncoder.matches(currentPassword==null?"":currentPassword,user.getPassword()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"当前密码错误");
        authService.validatePassword(newPassword);user.setPassword(passwordEncoder.encode(newPassword));userMapper.updateById(user);
    }

    public List<Map<String,Object>> oauthBindings(Long userId){return jdbc.queryForList("SELECT id,provider,created_at createdAt FROM oauth_user_bindings WHERE user_id=? ORDER BY created_at",userId);}
    @Transactional
    public void unlinkOAuth(User user, String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("google|github")) throw bad("不支持的 OAuth 提供方");
        Integer bindingCount = jdbc.queryForObject("SELECT COUNT(*) FROM oauth_user_bindings WHERE user_id=?", Integer.class, user.getId());
        boolean hasPassword = user.getPassword() != null && !user.getPassword().isBlank();
        if (!hasPassword && (bindingCount == null || bindingCount <= 1)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先设置密码或绑定另一种登录方式");
        }
        int deleted = jdbc.update("DELETE FROM oauth_user_bindings WHERE user_id=? AND LOWER(provider)=?", user.getId(), normalized);
        if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到该 OAuth 绑定");
    }
    public void requireComplete(User user){verificationPolicy.requireComplete(user,"进行此操作");}
    public List<Map<String,Object>> sessions(User user,String rawToken){return oauthService.listSessions(user.getId(),rawToken);}
    public void revokeSession(User user,Long id){oauthService.revokeSession(user.getId(),id);}
    public void revokeOthers(User user,String rawToken){oauthService.revokeOtherSessions(user.getId(),rawToken);}
    private String text(Object value,int max){if(value==null)return null;String v=value.toString().trim();if(v.isEmpty()||v.length()>max)throw bad("字段内容无效");return v;}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
