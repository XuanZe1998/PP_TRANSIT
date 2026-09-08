package com.transit.service;
import com.transit.mapper.ChannelMapper;
import com.transit.mapper.ModelMappingMapper;
import com.transit.mapper.ModelPriceTierMapper;
import com.transit.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest(properties={"new-api.sync.enabled=false","aiapibank.enabled=false"}) @Transactional
class GatewayPricingServiceTests {
 @Autowired GatewayPricingService pricing;
 @Autowired GatewaySiteService sites;
 @Autowired GatewayPageService pages;
 @Autowired JdbcTemplate jdbc;
 @Autowired ChannelMapper channels;
 @Autowired ModelMappingMapper models;
 @Autowired ModelPriceTierMapper tiers;
 long channelId,modelId,siteId;
 @BeforeEach void setup(){
  Channel channel=Channel.builder().name("price-test").sourceCode("new-api").type("openai-compatible").baseUrl("https://example.com").apiKey("encrypted-placeholder").createdAt(LocalDateTime.now()).build();channels.insert(channel);channelId=channel.getId();sites.reconcile();
  siteId=jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,channelId);
  var model=ModelMapping.builder().publicModelName("price-model").channelModelName("price-model").channelId(channelId).pricingUnit("TOKEN").pricingStatus("VERIFIED").billingMode("PAID").billingEnabled(true).enabled(false)
   .inputCostPerMillion(new BigDecimal("2")).outputCostPerMillion(new BigDecimal("6")).cachedCostPerMillion(new BigDecimal("0.2"))
   .inputPricePerMillion(new BigDecimal("4")).outputPricePerMillion(new BigDecimal("12")).cachedPricePerMillion(new BigDecimal("0.4")).build();models.insert(model);modelId=model.getId();
  tiers.insert(ModelPriceTier.builder().modelMappingId(modelId).tierName("standard").sortOrder(0).costPriceUnit("M").salePriceUnit("M")
   .costInputPrice(new BigDecimal("2")).costOutputPrice(new BigDecimal("6")).costCacheWrite1hPrice(new BigDecimal("1.6"))
   .saleInputPrice(new BigDecimal("4")).saleOutputPrice(new BigDecimal("12")).saleCacheWrite1hPrice(new BigDecimal("3.2")).build());
 }
 void apply(GatewayPricingService.Rule rule){String id=pricing.preview(rule).get("id").toString();pricing.apply(id);}
 BigDecimal input(){return jdbc.queryForObject("SELECT input_price_per_million FROM model_mappings WHERE id=?",BigDecimal.class,modelId);}
 @Test @SuppressWarnings("unchecked") void tenPercentPreviewExplainsUnchangedAndShadowedPrices(){
  var rule=new GatewayPricingService.Rule("GROUP",channelId,"PERCENT",BigDecimal.TEN,Map.of());
  apply(rule);assertThat(input()).isEqualByComparingTo("2.2");
  var item=((List<Map<String,Object>>)pricing.preview(rule).get("changes")).get(0);
  assertThat(item.get("changed")).isEqualTo(false);
  assertThat(item.get("reason").toString()).contains("采购价加价");
  item=((List<Map<String,Object>>)pricing.preview(new GatewayPricingService.Rule("SITE",siteId,"PERCENT",new BigDecimal("30"),Map.of())).get("changes")).get(0);
  assertThat(item.get("reason").toString()).contains("更高优先级");
 }

 @Autowired ProviderModelCatalogService providers;
 @Autowired ChannelSecretService secrets;
 @Test void liveHaoeeRemovesRetiredAliasAndPricesWithoutTouchingOtherChannels(){
  Channel other=Channel.builder().name("other-provider").sourceCode("new-api").type("openai-compatible").baseUrl("https://example.org").createdAt(LocalDateTime.now()).build();channels.insert(other);
  var otherModel=ModelMapping.builder().channelId(other.getId()).channelModelName("claude-opus-4-7").publicModelName("claude-opus-4-7").build();models.insert(otherModel);
  jdbc.update("UPDATE channels SET source_code='haoee',group_name='haoee',api_key=?,models='claude-opus-4-7' WHERE id=?",secrets.encrypt("test-live-key"),channelId);
  jdbc.update("UPDATE model_mappings SET public_model_name='claude-opus-4-7',channel_model_name='claude-opus-4-8' WHERE id=?",modelId);
  pricing.protect(modelId);
  Object original=org.springframework.test.util.ReflectionTestUtils.getField(providers,"webClient");
  var client=org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->{
    assertThat(request.url().getPath()).isEqualTo("/v1/models/");
    return reactor.core.publisher.Mono.just(
    org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK).header("Content-Type","application/json")
      .body("{\"data\":[{\"id\":\"claude-opus-4-8\"}]}").build());}).build();
  org.springframework.test.util.ReflectionTestUtils.setField(providers,"webClient",client);
  try {
   providers.synchronizeHaoeeLive(channelId);
   assertThat(models.selectById(modelId)).isNull();
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_price_tiers WHERE model_mapping_id=?",Integer.class,modelId)).isZero();
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_price_overrides WHERE model_mapping_id=?",Integer.class,modelId)).isZero();
   assertThat(channels.selectById(channelId).getModels()).isEqualTo("claude-opus-4-8");
   assertThat(secrets.isEncrypted(channels.selectById(channelId).getApiKey())).isTrue();
   assertThat(models.selectById(otherModel.getId())).isNotNull();
   // Simulate an old installation whose previous sync wrote plaintext back.
   var legacy=channels.selectById(channelId);legacy.setApiKey("test-live-key");channels.updateById(legacy);
   providers.synchronizeHaoeeLive(channelId);
   assertThat(secrets.isEncrypted(channels.selectById(channelId).getApiKey())).isTrue();
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_mappings WHERE channel_id=? AND public_model_name='claude-opus-4-7'",Integer.class,channelId)).isZero();
  } finally {org.springframework.test.util.ReflectionTestUtils.setField(providers,"webClient",original);}
 }
 @Test void incompleteHaoeeSnapshotKeepsExistingModels(){
  jdbc.update("UPDATE channels SET source_code='haoee',api_key=? WHERE id=?",secrets.encrypt("test-live-key"),channelId);
  Object original=org.springframework.test.util.ReflectionTestUtils.getField(providers,"webClient");
  var client=org.springframework.web.reactive.function.client.WebClient.builder().exchangeFunction(request->reactor.core.publisher.Mono.just(
    org.springframework.web.reactive.function.client.ClientResponse.create(org.springframework.http.HttpStatus.OK).header("Content-Type","application/json")
      .body("{\"has_more\":true,\"data\":[{\"id\":\"other\"}]}").build())).build();
  org.springframework.test.util.ReflectionTestUtils.setField(providers,"webClient",client);
  try {assertThatThrownBy(()->providers.synchronizeHaoeeLive(channelId)).hasMessageContaining("unavailable");assertThat(models.selectById(modelId)).isNotNull();}
  finally {org.springframework.test.util.ReflectionTestUtils.setField(providers,"webClient",original);}
 }
 @Test void globalSiteGroupAndModelRulesResolveInOrder(){
  apply(new GatewayPricingService.Rule("SITE",siteId,"PERCENT",new BigDecimal("30"),Map.of()));assertThat(input()).isEqualByComparingTo("2.6");
  apply(new GatewayPricingService.Rule("GROUP",channelId,"PERCENT",new BigDecimal("50"),Map.of()));assertThat(input()).isEqualByComparingTo("3");
  apply(new GatewayPricingService.Rule("MODEL",modelId,"PERCENT",new BigDecimal("100"),Map.of()));assertThat(input()).isEqualByComparingTo("4");
  apply(new GatewayPricingService.Rule("MODEL",modelId,"INHERIT",BigDecimal.ZERO,Map.of()));assertThat(input()).isEqualByComparingTo("3");
 }
 @Test void fixedAmountAppliesIndependentlyToEachTierCost(){
  apply(new GatewayPricingService.Rule("MODEL",modelId,"FIXED",BigDecimal.ZERO,Map.of("TOKEN",new BigDecimal("0.5"))));
  assertThat(input()).isEqualByComparingTo("2.5");
  assertThat(jdbc.queryForObject("SELECT sale_cache_write_1h_price FROM model_price_tiers WHERE model_mapping_id=?",BigDecimal.class,modelId)).isEqualByComparingTo("2.1");
 }
 @Test void manualPricesSurviveAutomaticCostUpdatesUntilExplicitModelOverride(){
  pricing.protect(modelId);jdbc.update("UPDATE model_mappings SET input_cost_per_million=3,input_price_per_million=99 WHERE id=?",modelId);
  pricing.repriceAfterSync(modelId);assertThat(input()).isEqualByComparingTo("4");
  apply(new GatewayPricingService.Rule("SITE",siteId,"PERCENT",new BigDecimal("80"),Map.of()));assertThat(input()).isEqualByComparingTo("4");
  apply(new GatewayPricingService.Rule("MODEL",modelId,"INHERIT",BigDecimal.ZERO,Map.of()));assertThat(input()).isEqualByComparingTo("5.4");
 }
 @Test void stalePreviewCannotOverwriteChangedCostsAndCannotBeReused(){
  var rule=new GatewayPricingService.Rule("MODEL",modelId,"PERCENT",new BigDecimal("20"),Map.of());
  String preview=pricing.preview(rule).get("id").toString();jdbc.update("UPDATE model_mappings SET input_cost_per_million=7 WHERE id=?",modelId);
  assertThatThrownBy(()->pricing.apply(preview)).hasMessageContaining("409");assertThat(input()).isEqualByComparingTo("4");
  String current=pricing.preview(rule).get("id").toString();pricing.apply(current);
  assertThatThrownBy(()->pricing.apply(current)).hasMessageContaining("400");
 }
 @Test void pendingCostsAndFreePreviewKeepTheirExistingPrices(){
  jdbc.update("UPDATE model_mappings SET pricing_status='PENDING' WHERE id=?",modelId);
  apply(new GatewayPricingService.Rule("MODEL",modelId,"PERCENT",new BigDecimal("20"),Map.of()));assertThat(input()).isEqualByComparingTo("4");
  jdbc.update("UPDATE model_mappings SET pricing_status='VERIFIED',billing_mode='FREE_PREVIEW' WHERE id=?",modelId);
  pricing.repriceAfterSync(modelId);assertThat(input()).isEqualByComparingTo("4");assertThat(GatewayPricingService.sale(null,"PERCENT",BigDecimal.TEN)).isNull();
 }
 @Test void missingFixedUnitFailsBeforeWritingPrices(){assertThatThrownBy(()->pricing.preview(new GatewayPricingService.Rule("MODEL",modelId,"FIXED",BigDecimal.ZERO,Map.of("TASK",BigDecimal.ONE)))).hasMessageContaining("TOKEN");assertThat(input()).isEqualByComparingTo("4");}
 @Test void sitesKeepStableIdentityAndDifferentNewApiChannelsRemainSeparate(){
  sites.update(siteId,new GatewaySiteService.Display("renamed",null,null,null,null));sites.reconcile();
  assertThat(jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,channelId)).isEqualTo(siteId);
  assertThat(pages.query("SELECT id,name FROM upstream_sites WHERE id=? ORDER BY id",List.of(siteId),99,20,false).getPage()).isEqualTo(1);
 }

 @Autowired com.transit.controller.GatewayManagementController controller;
 @Autowired GatewaySyncJobs jobs;
 @Test void gatewayEndpointsKeepSiteFiltersTotalsAndNeverReturnKeys(){
  jdbc.update("INSERT INTO new_api_connections(channel_id,base_url,upstream_group,sale_markup,unit_usd) VALUES(?,?,?,?,?)",
   channelId,"https://example.com","actual-upstream-group",new BigDecimal("1.2"),BigDecimal.ONE);
  var user=org.mockito.Mockito.mock(CurrentUserService.class);
  var original=org.springframework.test.util.ReflectionTestUtils.getField(controller,"users");
  org.springframework.test.util.ReflectionTestUtils.setField(controller,"users",user);
  try {
   var groups=controller.groups("test",siteId,null,"",1,20,false);
   assertThat(groups.getTotal()).isEqualTo(1);
   assertThat(groups.getItems().get(0)).doesNotContainKeys("api_key","pricing_access_token");
   var modelPage=controller.models("test",siteId,null,"","",1,20,false);
   assertThat(modelPage.getTotal()).isEqualTo(1);
   assertThat(modelPage.getItems().get(0).get("group_name")).isEqualTo("actual-upstream-group");
   org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(user).requireAdmin("user");
   assertThatThrownBy(()->controller.groups("user",siteId,null,"",1,20,false)).hasMessageContaining("403");
  } finally {org.springframework.test.util.ReflectionTestUtils.setField(controller,"users",original);}
 }
 @Test void twoSyncRequestsShareOnePersistentJob(){
  String first=jobs.enqueue(channelId);assertThat(jobs.enqueue(channelId)).isEqualTo(first);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_sync_jobs WHERE channel_id=?",Integer.class,channelId)).isEqualTo(1);
 }
 @Test void addingGroupToExistingSiteChecksAddressAndKeepsStableId(){
  Channel second=Channel.builder().name("second-group").sourceCode("new-api").type("openai-compatible").baseUrl("https://example.com").createdAt(LocalDateTime.now()).build();channels.insert(second);
  sites.attach(second.getId(),siteId);sites.reconcile();
  assertThat(jdbc.queryForObject("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",Long.class,second.getId())).isEqualTo(siteId);
  jdbc.update("UPDATE channels SET base_url='https://different.example' WHERE id=?",second.getId());
  assertThatThrownBy(()->sites.attach(second.getId(),siteId)).hasMessageContaining("400");
 }
}
