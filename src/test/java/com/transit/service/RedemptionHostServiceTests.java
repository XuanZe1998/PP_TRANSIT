package com.transit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedemptionHostServiceTests {
    private RedemptionHostService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("redemption-hosts-" + System.nanoTime() + ";MODE=MySQL")
                .addScript("classpath:db/migration/V37__service_redemption_hosts.sql")
                .build());
        service = new RedemptionHostService(jdbc);
    }

    @Test
    void mutationsTakeEffectImmediatelyAndListUsesDatabasePagination() {
        assertThat(service.allows("redeem.example.com")).isFalse();
        var added = service.add(" Redeem.Example.Com ");
        assertThat(added.host()).isEqualTo("redeem.example.com");
        assertThat(service.allows("sub.redeem.example.com")).isTrue();
        assertThat(service.allows("notredeem.example.com")).isFalse();
        assertThat(service.list(1, 10, "REDEEM").getTotal()).isEqualTo(1);
        assertThat(service.list(1, 10, "REDEEM").getItems()).hasSize(1);
        assertThat(service.list(2, 10, "").getItems()).isEmpty();
        assertThatThrownBy(() -> service.add("redeem.example.com")).isInstanceOf(ResponseStatusException.class);
        service.delete(added.id());
        assertThat(service.allows("redeem.example.com")).isFalse();
        assertThatThrownBy(() -> service.delete(added.id())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsUrlsLocalHostsAndInvalidPageSizes() {
        for (String value : new String[]{"https://redeem.example.com", "redeem.example.com:443", "localhost", "test.local", "test.internal", "example.com/path"}) {
            assertThatThrownBy(() -> service.add(value)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.list(1, 15, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(0, 10, null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void importsLegacyListOnlyOnceEvenAfterDeletion() throws Exception {
        ReflectionTestUtils.setField(service, "legacyHosts", " Redeem.Example.Com,redeem.example.com,other.example.com ");
        service.run(new DefaultApplicationArguments());
        assertThat(service.list(1, 10, "").getTotal()).isEqualTo(2);
        long id = service.list(1, 10, "redeem").getItems().get(0).id();
        service.delete(id);
        service.run(new DefaultApplicationArguments());
        assertThat(service.allows("redeem.example.com")).isFalse();
        assertThat(service.list(1, 10, "").getTotal()).isEqualTo(1);
    }
}
