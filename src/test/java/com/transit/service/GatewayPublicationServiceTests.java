package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"new-api.sync.enabled=false","aiapibank.enabled=false"})
@Transactional
class GatewayPublicationServiceTests {
    @Autowired GatewayPublicationService publication;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelMapper channels;
    @Autowired ModelMappingMapper models;
    long channel, model;

    @BeforeEach void setup() {
        var c = Channel.builder().name("auto-publish-test").type("openai-compatible").sourceCode("new-api")
                .baseUrl("https://example.com").apiKey("test-placeholder").enabled(true).healthStatus("HEALTHY").build();
        channels.insert(c); channel=c.getId();
        var m=ModelMapping.builder().channelId(channel).publicModelName("auto-model").channelModelName("auto-model")
                .enabled(false).pricingStatus("PENDING").pricingMessage("等待上游报价").billingEnabled(false)
                .billingMode("DISABLED").pricingUnit("TOKEN").inputPricePerMillion(BigDecimal.ZERO).outputPricePerMillion(BigDecimal.ZERO).build();
        models.insert(m);model=m.getId();
    }

    void priceReady() {
        jdbc.update("UPDATE model_mappings SET pricing_status='VERIFIED',billing_enabled=TRUE,billing_mode='PAID',input_price_per_million=2,output_price_per_million=6 WHERE id=?",model);
    }
    String status(){return jdbc.queryForObject("SELECT status FROM gateway_publication_requests WHERE model_mapping_id=?",String.class,model);}

    @Test void pendingRequestSurvivesUntilPriceIsReadyAndDoesNotPublishOtherModels() {
        assertThat(publication.request(model)).containsEntry("queued",true).containsEntry("success",false);
        publication.request(model);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_publication_requests WHERE model_mapping_id=?",Integer.class,model)).isOne();
        publication.completeReady(channel);
        assertThat(models.selectById(model).isEnabled()).isFalse();
        priceReady(); publication.completeReady(channel);
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE id=?", Boolean.class, model)).isTrue();
        assertThat(status()).isEqualTo("PUBLISHED");
        publication.completeReady(channel);
        assertThat(status()).isEqualTo("PUBLISHED");
    }

    @Test void cancelledIntentNeverPublishesOnLaterSync() {
        publication.request(model);publication.cancel(model);priceReady();publication.completeReady(channel);
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE id=?", Boolean.class, model)).isFalse();
        assertThat(status()).isEqualTo("CANCELLED");
    }

    @Test void keyHealthBillingAndNonzeroSaleRemainRequired() {
        priceReady();
        for(String change:List.of("api_key=''", "enabled=FALSE", "health_status='UNTESTED'")) {
            jdbc.update("UPDATE channels SET api_key='test-placeholder',enabled=TRUE,health_status='HEALTHY' WHERE id=?",channel);
            jdbc.update("UPDATE channels SET "+change+" WHERE id=?",channel);
            assertThat(publication.request(model)).containsEntry("queued",true);
        }
        jdbc.update("UPDATE channels SET health_status='HEALTHY' WHERE id=?",channel);
        jdbc.update("UPDATE model_mappings SET output_price_per_million=0 WHERE id=?",model);
        assertThat(publication.request(model)).containsEntry("queued",true);
        priceReady();assertThat(publication.request(model)).containsEntry("success",true);
    }

    @Test void missingUpstreamModelDoesNotRepublishFromStalePrices() {
        priceReady();
        jdbc.update("INSERT INTO new_api_model_state(channel_id,upstream_model_name,model_mapping_id,missing_count) VALUES(?,?,?,1)",channel,"auto-model",model);
        assertThat(publication.request(model)).containsEntry("queued",true);
        jdbc.update("UPDATE new_api_model_state SET missing_count=0 WHERE channel_id=?",channel);
        publication.completeReady(channel);
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE id=?", Boolean.class, model)).isTrue();
    }

    @Test void previouslyRequestedPublicationRecoversAfterTemporaryPriceFailure() {
        priceReady();publication.request(model);
        jdbc.update("UPDATE model_mappings SET enabled=FALSE,pricing_status='PENDING' WHERE id=?",model);
        publication.afterSync(channel);
        assertThat(status()).isEqualTo("WAITING");
        priceReady();publication.afterSync(channel);
        assertThat(status()).isEqualTo("PUBLISHED");
        publication.cancel(model);
        jdbc.update("UPDATE model_mappings SET enabled=FALSE,pricing_status='PENDING' WHERE id=?",model);
        publication.afterSync(channel);
        assertThat(status()).isEqualTo("CANCELLED");
    }
}
