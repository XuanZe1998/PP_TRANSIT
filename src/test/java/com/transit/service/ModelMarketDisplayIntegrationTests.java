package com.transit.service;

import com.transit.dto.PublicModel;
import com.transit.mapper.ModelMappingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ModelMarketDisplayIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelMarketDisplayService displayService;
    @Autowired ModelMappingMapper mapper;
    @Autowired SqlSessionTemplate sqlSession;

    @Test
    void displayPriorityIsIndependentFromRoutingPriorityAndBreaksTiesByName() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String alpha = "display-alpha-" + suffix;
        String beta = "display-beta-" + suffix;
        jdbc.update("""
                INSERT INTO channels(name,type,base_url,api_key,enabled,health_status)
                VALUES (?, 'openai', 'https://provider.example.com', 'encrypted-key', TRUE, 'HEALTHY')
                """, "display-channel-" + suffix);
        Long channelId = jdbc.queryForObject("SELECT id FROM channels WHERE name=?", Long.class, "display-channel-" + suffix);
        mapping(alpha, channelId, 900);
        mapping(beta, channelId, 1);

        Map<String, Object> updated = displayService.update(Map.of("items", List.of(
                Map.of("publicName", alpha, "displayPriority", 40),
                Map.of("publicName", beta, "displayPriority", 80))));
        assertThat(updated.get("updated")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT priority FROM model_mappings WHERE public_model_name=?", Integer.class, alpha)).isEqualTo(900);
        assertThat(jdbc.queryForObject("SELECT priority FROM model_mappings WHERE public_model_name=?", Integer.class, beta)).isEqualTo(1);

        List<String> names = mapper.findPublicModelsPagedByPriority(null, null, 1000, 0).stream()
                .map(PublicModel::getPublicName).filter(name -> name.endsWith(suffix)).toList();
        assertThat(names).containsExactly(beta, alpha);

        displayService.update(Map.of("items", List.of(
                Map.of("publicName", alpha, "displayPriority", 80),
                Map.of("publicName", beta, "displayPriority", 80))));
        assertThat(jdbc.queryForList("""
                SELECT public_model_name,display_priority FROM model_market_display_settings
                WHERE public_model_name IN (?,?) ORDER BY public_model_name
                """, alpha, beta)).extracting(row -> ((Number) row.get("display_priority")).intValue())
                .containsExactly(80, 80);
        sqlSession.clearCache();
        List<PublicModel> tied = mapper.findPublicModelsPagedByPriority(null, null, 1000, 0).stream()
                .filter(model -> model.getPublicName().endsWith(suffix)).toList();
        assertThat(tied).extracting(PublicModel::getDisplayPriority).containsExactly(80, 80);
        names = tied.stream().map(PublicModel::getPublicName).toList();
        assertThat(names).containsExactly(alpha, beta);
    }

    private void mapping(String publicName, long channelId, int routingPriority) {
        jdbc.update("""
                INSERT INTO model_mappings(public_model_name,channel_model_name,channel_id,priority,enabled,
                                           billing_enabled,input_price_per_million,output_price_per_million)
                VALUES (?,?,?,?,TRUE,TRUE,1,2)
                """, publicName, publicName + "-upstream", channelId, routingPriority);
    }
}
