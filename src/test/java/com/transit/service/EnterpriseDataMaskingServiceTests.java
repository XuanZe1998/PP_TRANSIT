package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.dto.ChatRequest;
import com.transit.dto.ChatResponse;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseDataMaskingServiceTests {
    private JdbcTemplate jdbc;
    private EnterpriseDataMaskingService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:masking_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE enterprise_masking_policies(organization_id BIGINT PRIMARY KEY,enabled BOOLEAN,builtin_rules VARCHAR(500),custom_rules CLOB)");
        jdbc.execute("CREATE TABLE enterprise_masking_audits(id BIGINT AUTO_INCREMENT PRIMARY KEY,organization_id BIGINT,user_id BIGINT,token_id BIGINT,trace_id VARCHAR(80),category VARCHAR(80),hit_count INT,created_at TIMESTAMP)");
        jdbc.update("INSERT INTO enterprise_masking_policies VALUES (7,TRUE,'PHONE,EMAIL','[]')");
        service = new EnterpriseDataMaskingService(jdbc, new ObjectMapper());
    }

    @Test
    void sendsOnlyPlaceholdersUpstreamAndRestoresTheAnswerWithoutAuditingPlaintext() {
        ChatRequest.Message prompt = new ChatRequest.Message(); prompt.setRole("user");
        prompt.setContent("联系 alice@example.com 或 13800138000");
        ChatRequest request = new ChatRequest(); request.setMessages(List.of(prompt));

        EnterpriseDataMaskingService.MaskingContext context = service.mask(request, 7L, 11L, 13L, "trace-1");
        String masked = prompt.getContent().toString();
        assertThat(masked).contains("[[LNX_EMAIL_").contains("[[LNX_PHONE_")
                .doesNotContain("alice@example.com").doesNotContain("13800138000");

        ChatResponse.Message answer = new ChatResponse.Message(); answer.setContent(masked);
        ChatResponse.Choice choice = new ChatResponse.Choice(); choice.setMessage(answer);
        ChatResponse response = new ChatResponse(); response.setChoices(List.of(choice));
        service.restore(response, context);
        assertThat(answer.getContent()).contains("alice@example.com").contains("13800138000");

        assertThat(jdbc.queryForObject("SELECT SUM(hit_count) FROM enterprise_masking_audits", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT * FROM enterprise_masking_audits").toString())
                .doesNotContain("alice@example.com").doesNotContain("13800138000");
    }
}
