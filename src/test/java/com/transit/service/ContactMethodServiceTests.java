package com.transit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContactMethodServiceTests {
    private JdbcTemplate jdbc;
    private ContactMethodService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("contacts-" + System.nanoTime() + ";MODE=MySQL")
                .build());
        jdbc.execute("""
                CREATE TABLE contact_methods (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    channel VARCHAR(80) NOT NULL,
                    contact_number VARCHAR(240) NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        service = new ContactMethodService(jdbc);
    }

    @Test
    void createsUpdatesSearchesAndDeletesContactMethods() {
        Map<String, Object> created = service.create(Map.of("channel", " 微信 ", "number", " wx-linknux "));
        long id = ((Number) created.get("id")).longValue();
        assertThat(created).containsEntry("channel", "微信").containsEntry("number", "wx-linknux");

        service.create(Map.of("channel", "QQ", "number", "123456"));
        var page = service.page(1, 10, false, "linknux");
        assertThat(page.getTotal()).isOne();
        assertThat(page.getItems()).extracting(row -> row.get("channel")).containsExactly("微信");

        Map<String, Object> updated = service.update(id, Map.of("channel", "企业微信", "number", "Linknux-Support"));
        assertThat(updated).containsEntry("channel", "企业微信").containsEntry("number", "Linknux-Support");

        assertThat(service.delete(id)).containsEntry("channel", "企业微信");
        assertThatThrownBy(() -> service.find(id)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void enforcesPageSizesAndAllLimit() {
        assertThatThrownBy(() -> service.page(1, 15, false, null))
                .isInstanceOf(ResponseStatusException.class);

        List<Object[]> batch = new ArrayList<>();
        for (int index = 0; index < 201; index++) batch.add(new Object[]{"渠道" + index, "号码" + index});
        jdbc.batchUpdate("INSERT INTO contact_methods(channel, contact_number) VALUES (?, ?)", batch);

        assertThatThrownBy(() -> service.page(1, 20, true, null))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.page(2, 20, false, null).getItems()).hasSize(20);
    }

    @Test
    void requiresBothVisibleFields() {
        assertThatThrownBy(() -> service.create(Map.of("channel", "微信", "number", "")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.create(Map.of("channel", "", "number", "123")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
