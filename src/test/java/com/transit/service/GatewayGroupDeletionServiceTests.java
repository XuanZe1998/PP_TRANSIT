package com.transit.service;

import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ModelPriceTierMapper;
import com.transit.model.Channel;
import com.transit.model.ModelMapping;
import com.transit.model.ModelPriceTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"new-api.sync.enabled=false","aiapibank.enabled=false"})
@Transactional
class GatewayGroupDeletionServiceTests {
    @Autowired GatewayGroupDeletionService deletion;
    @Autowired GatewaySiteService sites;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChannelMapper channels;
    @Autowired ModelMappingMapper models;
    @Autowired ModelPriceTierMapper tiers;
    long channel, site, model;

    @BeforeEach void setup() {
        var c=Channel.builder().name("delete-group-test").sourceCode("new-api").type("openai-compatible")
                .baseUrl("https://example.com").apiKey("test-placeholder").enabled(false).createdAt(LocalDateTime.now()).build();
        channels.insert(c); channel=c.getId(); sites.reconcile();
        site=jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,channel);
        var m=ModelMapping.builder().channelId(channel).publicModelName("delete-model-"+UUID.randomUUID())
                .channelModelName("delete-model").enabled(false).pricingStatus("PENDING").billingMode("DISABLED").build();
        models.insert(m); model=m.getId();
    }

    @Test void deletesDisabledGroupAndLocalDependenciesButRetainsSiteOtherGroupsAndHistory() {
        var other=Channel.builder().name("retained-group").sourceCode("new-api").type("openai-compatible").enabled(false).build();
        channels.insert(other);
        jdbc.update("INSERT INTO upstream_site_channels(channel_id,site_id) VALUES(?,?)",other.getId(),site);
        jdbc.update("INSERT INTO gateway_price_rules(scope_type,scope_id,mode) VALUES('SITE',?,'PERCENT'),('GROUP',?,'PERCENT'),('MODEL',?,'PERCENT')",site,channel,model);
        jdbc.update("INSERT INTO gateway_price_overrides(model_mapping_id,snapshot) VALUES(?,'{}')",model);
        jdbc.update("INSERT INTO gateway_publication_requests(model_mapping_id) VALUES(?)",model);
        tiers.insert(ModelPriceTier.builder().modelMappingId(model).tierName("base").build());
        jdbc.update("INSERT INTO aiapibank_provider_groups(external_group_id,channel_id,group_slug,group_name,platform) VALUES(?,?,?,?,?)",channel,channel,"delete-group-"+channel,"delete-group","test");
        long bankGroup=jdbc.queryForObject("SELECT id FROM aiapibank_provider_groups WHERE channel_id=?",Long.class,channel);
        jdbc.update("INSERT INTO aiapibank_model_offers(provider_group_id,model_mapping_id,upstream_model_name,public_model_name,platform) VALUES(?,?,?,?,?)",bankGroup,model,"delete-model","delete-offer-"+model,"test");
        long offer=jdbc.queryForObject("SELECT id FROM aiapibank_model_offers WHERE provider_group_id=?",Long.class,bankGroup);
        jdbc.update("INSERT INTO aiapibank_image_price_variants(model_offer_id,resolution_tier,max_edge_pixels) VALUES(?,'1K',1024)",offer);
        jdbc.update("INSERT INTO gateway_sync_jobs(id,channel_id,site_id,status,created_at) VALUES(?,?,?,'GROUP_REMOVED',?)","delete-history-"+channel,channel,site,LocalDateTime.now());

        deletion.deleteDisabled(channel);

        assertThat(channels.selectById(channel)).isNull();
        assertThat(models.selectById(model)).isNull();
        assertThat(channels.selectById(other.getId())).isNotNull();
        assertThat(count("upstream_sites","id",site)).isOne();
        assertThat(count("upstream_site_channels","channel_id",channel)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_price_rules WHERE (scope_type='GROUP' AND scope_id=?) OR (scope_type='MODEL' AND scope_id=?)",Integer.class,channel,model)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_price_rules WHERE scope_type='SITE' AND scope_id=?",Integer.class,site)).isOne();
        for(String table:new String[]{"gateway_price_overrides","gateway_publication_requests","model_price_tiers"}) assertThat(count(table,"model_mapping_id",model)).isZero();
        assertThat(count("aiapibank_provider_groups","channel_id",channel)).isZero();
        assertThat(count("aiapibank_model_offers","provider_group_id",bankGroup)).isZero();
        assertThat(count("aiapibank_image_price_variants","model_offer_id",offer)).isZero();
        assertThat(count("gateway_sync_jobs","channel_id",channel)).isOne();
        assertThat(count("gateway_sync_locks","channel_id",channel)).isZero();
        assertThat(count("gateway_site_sync_locks","site_id",site)).isZero();
    }

    @Test void activeGroupCannotBeDeleted() {
        jdbc.update("UPDATE channels SET enabled=TRUE WHERE id=?",channel);
        conflict("请先停用");
        assertThat(channels.selectById(channel)).isNotNull();
        assertThat(models.selectById(model)).isNotNull();
        assertThat(count("gateway_sync_locks","channel_id",channel)).isZero();
        assertThat(count("gateway_site_sync_locks","site_id",site)).isZero();
    }

    @Test void groupSyncLockPreventsDeletionAndIsNeverRemoved() {
        jdbc.update("INSERT INTO gateway_sync_locks(channel_id,job_id) VALUES(?,'existing-group-task')",channel);
        conflict("正在同步");
        assertThat(channels.selectById(channel)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT job_id FROM gateway_sync_locks WHERE channel_id=?",String.class,channel)).isEqualTo("existing-group-task");
        assertThat(count("gateway_site_sync_locks","site_id",site)).isZero();
    }

    @Test void siteDirectorySyncLockPreventsDeletionAndIsNeverRemoved() {
        jdbc.update("INSERT INTO gateway_site_sync_locks(site_id,job_id) VALUES(?,'existing-site-task')",site);
        conflict("正在同步");
        assertThat(channels.selectById(channel)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT job_id FROM gateway_site_sync_locks WHERE site_id=?",String.class,site)).isEqualTo("existing-site-task");
        assertThat(count("gateway_sync_locks","channel_id",channel)).isZero();
    }

    @Test void absentGroupReturnsNotFound() {
        assertThatThrownBy(()->deletion.deleteDisabled(Long.MAX_VALUE)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test void builtInChannelCannotBeDeletedEvenWhenDisabled() {
        jdbc.update("UPDATE channels SET source_code='nvidia' WHERE id=?",channel);
        conflict("内置渠道");
        assertThat(channels.selectById(channel)).isNotNull();
    }

    private void conflict(String message) {
        assertThatThrownBy(()->deletion.deleteDisabled(channel)).isInstanceOfSatisfying(ResponseStatusException.class,e->{
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getReason()).contains(message);
        });
    }
    private int count(String table,String field,long value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+field+"=?",Integer.class,value);
    }
}
