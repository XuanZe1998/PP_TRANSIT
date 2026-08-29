package com.transit.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ProviderCredentialMapper;
import com.transit.model.Channel;
import com.transit.model.ProviderCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderCredentialServiceTests {
    @Mock private ProviderCredentialMapper mapper;
    @Mock private ChannelMapper channels;
    @Mock private ChannelSecretService secrets;

    private ProviderCredentialService service;
    private Channel channel;

    @BeforeEach
    void setUp() {
        service = new ProviderCredentialService(mapper, channels, secrets);
        channel = Channel.builder().id(10L).enabled(true).build();
        when(secrets.decrypt(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void concurrencyLeasePreventsAnAccountFromBeingOversoldAndReleaseRestoresIt() {
        ProviderCredential first = account(1L, 10, 1, true, "*");
        ProviderCredential second = account(2L, 10, 1, true, "*");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));

        assertThat(service.select(channel, "gpt-5", null, false).id()).isEqualTo(1L);
        assertThat(service.select(channel, "gpt-5", null, false).id()).isEqualTo(2L);

        service.releaseUnknown(1L);
        assertThat(service.select(channel, "gpt-5", null, false).id()).isEqualTo(1L);
    }

    @Test
    void commissionTrafficSkipsAccountsWithoutAnExplicitReliableCostFlag() {
        ProviderCredential unreliable = account(1L, 100, 5, false, "*");
        ProviderCredential reliable = account(2L, 1, 5, true, "gpt-5");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(unreliable, reliable));

        ProviderCredentialService.SelectedCredential selected = service.select(channel, "gpt-5", "session-a", true);

        assertThat(selected.id()).isEqualTo(2L);
        assertThat(selected.secret()).isEqualTo("secret-2");
    }

    @Test
    void modelScopeExcludesIncompatibleAccounts() {
        ProviderCredential claudeOnly = account(1L, 100, 5, true, "claude-*");
        ProviderCredential exact = account(2L, 1, 5, true, "gpt-5,gpt-5-mini");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(claudeOnly, exact));

        assertThat(service.select(channel, "gpt-5", null, true).id()).isEqualTo(2L);
    }

    private ProviderCredential account(long id, int priority, int concurrency, boolean reliable, String scope) {
        return ProviderCredential.builder().id(id).channelId(10L).name("account-" + id)
                .secret("secret-" + id).priority(priority).weight(100).concurrencyLimit(concurrency)
                .enabled(true).healthStatus("HEALTHY").authType("API_KEY")
                .costReliable(reliable).modelScope(scope).build();
    }
}
