package com.transit.service;
import com.transit.model.Channel;
import com.transit.mapper.ChannelMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.math.BigDecimal;
@Service @RequiredArgsConstructor
public class GatewayNativeOnboarding {
 private final AiApiBankCatalogService bank;
 private final NewApiCatalogClient client;
 private final AdminChannelService channels;
 private final ChannelMapper mapper;
 private final ChannelSecretService secrets;
 private final JdbcTemplate jdbc;
 private final GatewaySiteService sites;
 private final NewApiCatalogManagementService catalogs;
 private final GatewayPricingService pricing;
 private final GatewaySyncJobs jobs;
 private final Sub2ApiOnboardingService sub2api;
 private final TransactionTemplate tx;
 @SuppressWarnings("unchecked") public NewApiOnboardingService.Preview preview(String adapter,NewApiOnboardingService.Request request){
  if("sub2api".equals(adapter))return sub2api.preview(request);
  NewApiOnboardingService.secret(request.apiKey(),true);
  String base=base(adapter);String group=Objects.toString(request.upstreamGroup(),"");
  List<String> models;List<NewApiPricing.Group> groups;
  if(adapter.equals("aiapibank")){
   var all=bank.previewGroups();groups=all.stream().map(g->new NewApiPricing.Group(g.get("slug").toString(),g.get("name").toString(),BigDecimal.ONE)).toList();
   models=all.stream().filter(g->group.isBlank()||g.get("slug").equals(group)).flatMap(g->((List<String>)g.get("models")).stream()).distinct().toList();
  }else{models=client.haoeeModels(base,request.apiKey());groups=List.of(new NewApiPricing.Group("haoee","好易智算默认分组",BigDecimal.ONE));}
  var prices=models.stream().map(m->new NewApiPricing.Price(m,"PENDING","导入后通过站点适配器核验分组采购价", "UNKNOWN",null,null,null,null,null,null,null,null,null,null)).toList();
  return new NewApiOnboardingService.Preview(base,adapter.equals("aiapibank")?"AiAPIBank":"好易智算",models,groups,prices,"此适配器按整个分组导入；现有模型和售价保留，新增模型核验后集中发布",group);
 }
 public Map<String,Object> connect(String adapter,NewApiOnboardingService.Request request){
  if("sub2api".equals(adapter))return sub2api.connect(request);
  var preview=preview(adapter,request);if(preview.models().isEmpty()||request.upstreamGroup()==null||preview.groups().stream().noneMatch(g->g.name().equals(request.upstreamGroup())))throw new IllegalArgumentException("请选择有效上游分组");
  if(adapter.equals("aiapibank"))bank.discoverGroups();
  long id=tx.execute(status->{
   List<Long> ids=adapter.equals("aiapibank")?jdbc.queryForList("SELECT channel_id FROM aiapibank_provider_groups WHERE group_slug=?",Long.class,request.upstreamGroup()):jdbc.queryForList("SELECT id FROM channels WHERE source_code='haoee' ORDER BY id",Long.class);
   long channel;
   if(ids.isEmpty()){
    Channel created=channels.create(Channel.builder().name("好易智算").sourceCode("haoee").sourceName("好易智算").type("haoee").protocolType("multi").baseUrl(base(adapter)).apiKey(request.apiKey()).groupName("haoee").models(null).enabled(false).build());channel=created.getId();
   }else{channel=ids.get(0);jdbc.update("UPDATE channels SET api_key=?,health_status='UNTESTED' WHERE id=?",secrets.encrypt(request.apiKey()),channel);}
   sites.reconcile();catalogs.registerAdapters();pricing.initializeGroup(channel,NewApiOnboardingService.markup(request));
   jdbc.update("UPDATE upstream_catalog_sync SET sync_enabled=? WHERE channel_id=?",!Boolean.FALSE.equals(request.autoSync()),channel);
   return channel;
  });
  return Map.of("channelId",id,"jobId",jobs.enqueue(id));
 }
 private String base(String adapter){return switch(adapter){case "aiapibank"->"https://aiapibank.com";case "haoee"->"https://maas.haoee.com";default->throw new IllegalArgumentException("未知站点适配器");};}
}
