package com.transit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.service.AdminReportService;
import com.transit.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubscriptionNoticeControllerTests {
    @Test void persistsMultilineNoticeAndDisabledStateAndRejectsUnauthorizedOrInvalidWrites() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:notice-test;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE system_settings(setting_key VARCHAR(128) PRIMARY KEY, setting_value TEXT, description TEXT, updated_at TIMESTAMP)");
        var users = mock(CurrentUserService.class);
        var controller = new SubscriptionNoticeController(jdbc, new ObjectMapper(), new AdminReportService(jdbc), users);
        assertThat(controller.read().block().enabled()).isTrue();
        var notice = new SubscriptionNoticeController.Notice(true, "公告", "第一行\n<b>纯文本</b>");
        controller.save("admin", notice).block();
        assertThat(controller.read().block()).isEqualTo(notice);
        controller.save("admin", new SubscriptionNoticeController.Notice(false, "", "")).block();
        assertThat(controller.read().block().enabled()).isFalse();
        assertThatThrownBy(() -> controller.save("admin", new SubscriptionNoticeController.Notice(true, "", "x"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.save("admin", new SubscriptionNoticeController.Notice(true, "x", "x".repeat(3001)))).isInstanceOf(ResponseStatusException.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(users).requireAdmin("user");
        assertThatThrownBy(() -> controller.save("user", notice)).isInstanceOf(ResponseStatusException.class);
        assertThat(controller.read().block().enabled()).isFalse();
    }
}
