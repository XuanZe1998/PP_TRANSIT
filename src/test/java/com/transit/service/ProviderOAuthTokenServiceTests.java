package com.transit.service;

import com.transit.mapper.ProviderCredentialMapper;
import com.transit.model.ProviderCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProviderOAuthTokenServiceTests {
    @Mock ProviderCredentialMapper mapper;
    @Mock OAuthCredentialBundleService bundles;
    @Mock UpstreamOAuthProviderRegistry registry;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String,String> values;
    @Mock JdbcTemplate jdbc;
    @Mock UpstreamOAuthProvider provider;
    private ProviderOAuthTokenService service;
    private ProviderCredential account;

    @BeforeEach
    void setUp() {
        service = new ProviderOAuthTokenService(mapper, bundles, registry, redis, jdbc);
        account = ProviderCredential.builder().id(9L).channelId(2L).platform("CODEX").authType("OAUTH")
                .credentialBundle("encrypted-old").tokenVersion(7).enabled(true).entitlementStatus("ACTIVE")
                .oauthExpiresAt(LocalDateTime.now().minusMinutes(1)).build();
    }

    @Test
    void refreshUsesDistributedLockAndDatabaseCasVersion() {
        UpstreamOAuthProvider.OAuthToken old = new UpstreamOAuthProvider.OAuthToken("old", "refresh", Instant.now(), "scope", "Bearer", Map.of());
        UpstreamOAuthProvider.OAuthToken fresh = new UpstreamOAuthProvider.OAuthToken("new", "refresh-2", Instant.now().plusSeconds(3600), "scope", "Bearer", Map.of());
        ProviderCredential updated = ProviderCredential.builder().id(9L).platform("CODEX").authType("OAUTH").tokenVersion(8).credentialBundle("encrypted-new").build();
        when(redis.opsForValue()).thenReturn(values); when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(true);
        when(mapper.selectById(9L)).thenReturn(account, account, updated); when(bundles.decrypt("encrypted-old")).thenReturn(old);
        when(registry.require("CODEX")).thenReturn(provider); when(provider.refresh("refresh", null)).thenReturn(fresh);
        when(bundles.encrypt(fresh)).thenReturn("encrypted-new"); when(mapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.forceRefresh(9L).getTokenVersion()).isEqualTo(8);
        verify(mapper).update(isNull(), argThat(wrapper -> wrapper.getSqlSegment().contains("token_version")));
        verify(jdbc).update(contains("provider_account_events"), any(Object[].class));
    }

    @Test
    void refreshFailsClosedWhenAnotherInstanceOwnsTheLockAndNoNewVersionIsVisible() {
        when(redis.opsForValue()).thenReturn(values); when(values.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);
        when(mapper.selectById(9L)).thenReturn(account, account);
        assertThatThrownBy(() -> service.forceRefresh(9L)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("其他实例刷新");
        verifyNoInteractions(bundles, provider);
    }
}
