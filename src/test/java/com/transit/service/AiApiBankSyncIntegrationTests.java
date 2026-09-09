package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
@Transactional
class AiApiBankSyncIntegrationTests {
    @MockitoSpyBean private AiApiBankCatalogService catalog;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GatewaySiteService sites;
    @Autowired private NewApiCatalogManagementService management;
    @Autowired private GatewaySyncJobs jobs;
    @Autowired private com.transit.controller.GatewayManagementController controller;

    private ObjectNode snapshot(long... ids) {
        ObjectNode root=json.createObjectNode().put("code",0);
        var groups=root.putObject("data").putArray("groups");
        for(long id:ids)groups.addObject().put("id",id).put("name","group-"+id).put("platform","openai").putArray("models");
        return root;
    }
    private long channel(long externalId){return jdbc.queryForObject("SELECT channel_id FROM aiapibank_provider_groups WHERE external_group_id=?",Long.class,externalId);}

    @Test void publicSnapshotPreservesMissingGroupsPricesAndAddsUnpublishedGroups(){
        doReturn(snapshot(65,32)).when(catalog).fetchCatalog();catalog.discoverGroups();sites.reconcile();management.registerAdapters();
        long removed=channel(65),retained=channel(32);
        jdbc.update("INSERT INTO model_mappings(channel_id,public_model_name,channel_model_name,enabled) VALUES(?,'removed-route','removed-model',TRUE)",removed);
        long mapping=jdbc.queryForObject("SELECT id FROM model_mappings WHERE channel_id=?",Long.class,removed);
        jdbc.update("INSERT INTO model_price_tiers(model_mapping_id,tier_name,sort_order) VALUES(?,'standard',0)",mapping);
        jdbc.update("INSERT INTO gateway_price_overrides(model_mapping_id,snapshot) VALUES(?,'{}')",mapping);
        jdbc.update("INSERT INTO gateway_price_rules(scope_type,scope_id,mode,amount) VALUES('GROUP',?,'PERCENT',10)",removed);
        doReturn(snapshot(32,67)).when(catalog).fetchCatalog();
        var result=catalog.discoverGroups();
        assertThat(result.deleted()).isZero();assertThat(result.added()).isEqualTo(1);
        assertThat(channel(32)).isEqualTo(retained);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE id=?",Integer.class,removed)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_price_tiers WHERE model_mapping_id=?",Integer.class,mapping)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_price_overrides WHERE model_mapping_id=?",Integer.class,mapping)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT enabled FROM channels WHERE id=?",Boolean.class,channel(67))).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_id=?",Integer.class,channel(67))).isZero();
        assertThat(catalog.discoverGroups().added()).isZero();
    }
    @Test void incompleteOrFailedCatalogNeverDeletesGroups(){
        doReturn(snapshot(65)).when(catalog).fetchCatalog();catalog.discoverGroups();long original=channel(65);
        var partial=snapshot(32);partial.withObject("/data").put("has_more",true);doReturn(partial).when(catalog).fetchCatalog();
        assertThatThrownBy(()->catalog.discoverGroups()).hasMessageContaining("不完整");
        assertThat(channel(65)).isEqualTo(original);
        doReturn(snapshot()).when(catalog).fetchCatalog();assertThatThrownBy(()->catalog.discoverGroups()).hasMessageContaining("拒绝覆盖");
        assertThat(channel(65)).isEqualTo(original);
    }
    @Test void discoveryPreservesGroupsWithRunningModelSync(){
        doReturn(snapshot(65)).when(catalog).fetchCatalog();catalog.discoverGroups();sites.reconcile();
        long removed=channel(65);String running=jobs.enqueue(removed);
        doReturn(snapshot(32)).when(catalog).fetchCatalog();assertThat(catalog.discoverGroups().deleted()).isZero();
        assertThat(channel(65)).isEqualTo(removed);
        assertThat(jdbc.queryForObject("SELECT job_id FROM gateway_sync_locks WHERE channel_id=?",String.class,removed)).isEqualTo(running);
    }
    @Test void siteDiscoveryUsesOnlyModelPlazaAndPreservesUnlistedGroups(){
        doReturn(snapshot(32,99)).when(catalog).fetchCatalog();catalog.discoverGroups();sites.reconcile();
        long site=jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,channel(32));
        long preserved=channel(99);
        jdbc.update("INSERT INTO gateway_account_credentials(site_id,access_token,user_agent) VALUES(?,?,'obsolete-browser')",site,"obsolete-token");
        doReturn(snapshot(32,67)).when(catalog).fetchCatalog();
        var result=catalog.discoverGroups(site);
        assertThat(result.seen()).isEqualTo(2);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.deleted()).isZero();
        assertThat(channel(99)).isEqualTo(preserved);
        assertThat(jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,channel(67))).isEqualTo(site);
    }

    @Test void siteDiscoveryJobsArePersistentAndDeduplicated(){
        doReturn(snapshot(32)).when(catalog).fetchCatalog();catalog.discoverGroups();sites.reconcile();
        long site=jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,channel(32));
        String first=jobs.enqueueGroups(site);assertThat(jobs.enqueueGroups(site)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT job_type FROM gateway_sync_jobs WHERE id=?",String.class,first)).isEqualTo("GROUPS");
        assertThat(jdbc.queryForObject("SELECT channel_id FROM gateway_sync_jobs WHERE id=?",Long.class,first)).isNull();
    }
    @Test void savingKeyQueuesVerificationAndOnlySuccessfulFirstCatalogPublishes(){
        var root=snapshot(67);
        var model=((com.fasterxml.jackson.databind.node.ArrayNode)root.path("data").path("groups").get(0).path("models")).addObject().put("name","chat-model");
        model.putObject("pricing").put("input_price",0.000001).put("output_price",0.000002);
        doReturn(root).when(catalog).fetchCatalog();catalog.discoverGroups();sites.reconcile();management.registerAdapters();
        long id=channel(67);
        jdbc.update("""
                INSERT INTO upstream_display_mappings(channel_id,public_code,public_name,enabled)
                VALUES (?,'group-67','AAB',TRUE)
                """,id);
        Object original=org.springframework.test.util.ReflectionTestUtils.getField(controller,"users");
        org.springframework.test.util.ReflectionTestUtils.setField(controller,"users",org.mockito.Mockito.mock(CurrentUserService.class));
        try {
            controller.group("admin",id,new com.transit.controller.GatewayManagementController.GroupSettings(null,true,true,null,null,null,null,null,true));
            assertThat(jdbc.queryForObject("SELECT enabled FROM channels WHERE id=?",Boolean.class,id)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_display_mappings WHERE channel_id=?",Integer.class,id)).isZero();
            controller.group("admin",id,new com.transit.controller.GatewayManagementController.GroupSettings(null,true,true,"test-key",null,null,null,null,true));
            assertThat(jdbc.queryForObject("SELECT enabled FROM channels WHERE id=?",Boolean.class,id)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_sync_jobs WHERE channel_id=? AND status='QUEUED'",Integer.class,id)).isEqualTo(1);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(controller,"users",original);}
        Object oldClient=org.springframework.test.util.ReflectionTestUtils.getField(catalog,"webClient");
        var client=org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->reactor.core.publisher.Mono.just(
            org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK).header("Content-Type","application/json")
                .body(request.url().getPath().endsWith("/models")?"{\"data\":[{\"id\":\"chat-model\"}]}":"{\"data\":{\"resolved_rate_multiplier\":1}}").build())).build();
        org.springframework.test.util.ReflectionTestUtils.setField(catalog,"webClient",client);
        try {catalog.syncChannel(id);}
        finally {org.springframework.test.util.ReflectionTestUtils.setField(catalog,"webClient",oldClient);}
        assertThat(jdbc.queryForObject("SELECT enabled FROM channels WHERE id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT enabled FROM model_mappings WHERE channel_id=?",Boolean.class,id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_display_mappings WHERE channel_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT s.public_name FROM upstream_sites s JOIN upstream_site_channels sc ON sc.site_id=s.id
                WHERE sc.channel_id=?
                """,String.class,id)).isEqualTo("AiAPIBank");
    }

    @Test
    void missingCredentialsCreateDisabledGroupChannelsWithoutPublishingModels() {
        ObjectNode root = json.createObjectNode().put("code", 0);
        ObjectNode group = root.putObject("data").putArray("groups").addObject()
                .put("id", 32).put("name", "GPT低价分组").put("description", "低价")
                .put("platform", "openai").put("subscription_type", "standard")
                .put("rate_multiplier", 0.07).put("peak_rate_enabled", false);
        ObjectNode model = group.putArray("models").addObject().put("name", "gpt-5.6").put("platform", "openai");
        model.putObject("pricing").put("billing_mode", "token").put("input_price", 0.000005).put("output_price", 0.00003);
        model.putObject("official_pricing").put("input_price", 0.000005).put("output_price", 0.00003);
        doReturn(root).when(catalog).fetchCatalog();

        AiApiBankCatalogService.SyncResult result = catalog.sync(false);

        assertThat(result.groupsSeen()).isEqualTo(1);
        assertThat(result.credentialsMissing()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aiapibank_provider_groups", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM aiapibank_provider_groups WHERE credential_status='CREDENTIAL_MISSING'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE source_code='aiapibank' AND enabled=FALSE", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE public_model_name LIKE 'aiapibank/%'", Integer.class)).isZero();
    }
}
