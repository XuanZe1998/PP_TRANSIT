package com.transit.service;

import com.transit.model.User;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminAuditServiceTests {

    @Test
    void boundsLargeChannelSnapshotsWithoutSplittingUnicodeCharacters() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AdminAuditService service = new AdminAuditService(jdbcTemplate);
        User admin = User.builder().id(7L).username("admin").build();
        String largeSnapshot = "模型😀".repeat(2_000);

        service.record(admin, "UPDATE_CHANNEL", "CHANNEL", 19L,
                null, largeSnapshot, "127.0.0.1");

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), arguments.capture());
        String storedSnapshot = (String) arguments.getValue()[6];
        assertThat(storedSnapshot.codePointCount(0, storedSnapshot.length())).isEqualTo(4_000);
        assertThat(storedSnapshot).endsWith("...[truncated]");
    }
}
