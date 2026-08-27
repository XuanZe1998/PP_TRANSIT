package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SensitiveWordServiceTests {
    private JdbcTemplate jdbc;
    private SensitiveWordService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:sensitive_words_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sensitive_words(id BIGINT AUTO_INCREMENT PRIMARY KEY,term VARCHAR(255),category VARCHAR(80),
                match_mode VARCHAR(24),action VARCHAR(24),scope_type VARCHAR(24),scope_id BIGINT,note VARCHAR(500),
                enabled BOOLEAN,created_at DATETIME,updated_at DATETIME)
                """);
        jdbc.execute("""
                CREATE TABLE security_events(id BIGINT AUTO_INCREMENT PRIMARY KEY,trace_id VARCHAR(80),sensitive_word_id BIGINT,
                category VARCHAR(80),matched_term VARCHAR(255),action VARCHAR(24),organization_id BIGINT,user_id BIGINT,
                token_id BIGINT,model VARCHAR(180),created_at DATETIME)
                """);
        jdbc.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(100))");
        service = new SensitiveWordService(jdbc);
    }

    @Test
    void disabledTemplatesNeverMatchAndPreviewDoesNotWriteEvents() {
        service.save(Map.of("term", "测试禁词", "category", "模板", "enabled", false));
        assertTrue(service.preview(Map.of("text", "包含测试禁词的文本")).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM security_events", Integer.class));
    }

    @Test
    void normalizesUnicodeAndRecordsOnlyMatchMetadataForWarnings() throws Exception {
        service.save(Map.of("term", "API KEY", "category", "凭证", "action", "WARN", "enabled", true));
        service.scanJson("req_warn", 8L, 9L, 10L, "model-a",
                objectMapper.readTree("{\"input\":\"ＡＰＩ　ＫＥＹ should stay private\"}"));
        Map<String, Object> event = jdbc.queryForMap("SELECT * FROM security_events WHERE trace_id='req_warn'");
        assertEquals("API KEY", event.get("MATCHED_TERM"));
        assertEquals("WARN", event.get("ACTION"));
        assertFalse(event.containsKey("PROMPT"));
    }

    @Test
    void organizationScopedBlockRejectsOnlyMatchingOrganization() throws Exception {
        service.save(Map.of("term", "block-me", "category", "业务", "action", "BLOCK",
                "scopeType", "ORGANIZATION", "scopeId", 22, "enabled", true));
        service.scanJson("req_other", 21L, 1L, 2L, "model-a", objectMapper.readTree("{\"input\":\"block-me\"}"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () ->
                service.scanJson("req_block", 22L, 1L, 2L, "model-a", objectMapper.readTree("{\"input\":\"block-me\"}")));
        assertEquals(403, error.getStatusCode().value());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM security_events WHERE trace_id='req_block'", Integer.class));
    }

    @Test
    void exactModeDoesNotMatchLongerText() {
        service.save(Map.of("term", "exact", "category", "业务", "matchMode", "EXACT", "action", "WARN", "enabled", true));
        assertTrue(service.preview(Map.of("text", "not exact text")).isEmpty());
        assertEquals(1, service.preview(Map.of("text", "ＥＸＡＣＴ")).size());
    }
}
