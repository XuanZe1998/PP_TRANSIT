package com.transit.controller;
import com.transit.service.*;
import com.transit.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
@RestController @RequestMapping("/admin/api/gateway") @RequiredArgsConstructor
public class GatewayManagementController {
 private final CurrentUserService users;
 private final GatewaySiteService sites;
 private final GatewayPageService pages;
 private final GatewaySyncJobs jobs;
 private final GatewayPricingService pricing;
 private final GatewayPublicationService publications;
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private final AdminAuditService audit;
 private final AdminChannelService channelService;
 private final com.transit.mapper.ModelMappingMapper modelMapper;
 private void admin(String auth){users.requireAdmin(auth);}
 private final ChannelSecretService secrets;
 private final GatewayNativeOnboarding nativeOnboarding;
 private final ModelPriceTierService modelTiers;
 private final AiApiBankAccountSessionService aiApiBankSessions;
 @PostMapping("/onboard/{adapter}/preview")public Object nativePreview(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable String adapter,@RequestBody NewApiOnboardingService.Request body){admin(auth);return nativeOnboarding.preview(adapter,body);}
 @PostMapping("/onboard/{adapter}/connect")public Object nativeConnect(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable String adapter,@RequestBody NewApiOnboardingService.Request body){admin(auth);var result=nativeOnboarding.connect(adapter,body);audit.record(users.requireAdmin(auth),"CONNECT_UPSTREAM","CHANNEL",null,null,Map.of("adapter",adapter),null);return result;}
 @GetMapping("/summary") public Map<String,Object> summary(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestParam(required=false)Long siteId,@RequestParam(required=false)Long groupId){
  admin(auth);List<Object> args=new ArrayList<>();String where=filters(siteId,groupId,"c.id",args);
  return jdbc.queryForMap("SELECT COUNT(*) models,COALESCE(SUM(CASE WHEN mm.enabled=TRUE THEN 1 ELSE 0 END),0) published,COALESCE(SUM(CASE WHEN mm.pricing_status<>'VERIFIED' OR mm.pricing_status IS NULL THEN 1 ELSE 0 END),0) pending_price,COALESCE(SUM(CASE WHEN mm.enabled=FALSE AND mm.pricing_status='VERIFIED' THEN 1 ELSE 0 END),0) pending_publish FROM model_mappings mm JOIN channels c ON c.id=mm.channel_id JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_sites s ON s.id=sc.site_id WHERE 1=1"+where,args.toArray());
 }
 @GetMapping("/sites") public PageResponse<Map<String,Object>> sites(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,
    @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean all,@RequestParam(defaultValue="")String query){
  admin(auth);return pages.query("""
   SELECT s.*, (SELECT COUNT(*) FROM upstream_site_channels sc WHERE sc.site_id=s.id) group_count,
    CASE WHEN a.encrypted_refresh_token IS NOT NULL AND a.encrypted_refresh_token<>'' THEN TRUE ELSE FALSE END account_authenticated,
    COALESCE(a.auth_status,'ANONYMOUS') account_auth_status,a.account_email_preview,
    a.last_authenticated_at account_last_authenticated_at,a.last_error account_auth_error
   FROM upstream_sites s LEFT JOIN gateway_account_credentials a ON a.site_id=s.id
   WHERE s.name LIKE ? AND s.adapter<>'nvidia' ORDER BY s.id
   """,List.of("%"+query+"%"),page,size,all);
 }
 @GetMapping("/groups") public PageResponse<Map<String,Object>> groups(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,
    @RequestParam(required=false)Long siteId,@RequestParam(required=false)Long groupId,@RequestParam(defaultValue="")String query,
    @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean all){
  admin(auth);List<Object> args=new ArrayList<>();String where=filters(siteId,groupId,"c.id",args);
  args.add("%"+query+"%");
  return pages.query("""
   SELECT c.id,c.name,c.source_code,c.enabled,c.health_status,s.id site_id,s.name site_name,
    COALESCE(n.upstream_group,g.group_name,c.group_name) group_name,
    CASE WHEN c.api_key IS NOT NULL AND c.api_key<>'' THEN TRUE ELSE FALSE END credential_configured,
    COALESCE(x.sync_enabled,n.sync_enabled,u.sync_enabled,FALSE) sync_enabled,
    COALESCE(j.status,x.sync_status,n.sync_status,u.sync_status,'PENDING') sync_status,
    COALESCE(j.message,x.last_message,n.last_message,u.last_message,'历史原因未记录') message,
    j.error_code,j.http_status,j.suggestion,j.phase,j.created_at,j.finished_at,
    COALESCE(s.public_name,d.public_name,'平台智能路由') public_name,d.id display_mapping_id,
    COALESCE(s.public_code,CONCAT('site-',s.id)) public_code,
    COALESCE(s.badge_text,d.badge_text) badge_text,COALESCE(s.badge_color,d.badge_color) badge_color,
    s.public_name site_public_name,s.public_code site_public_code,s.badge_text site_badge_text,s.badge_color site_badge_color,
    (SELECT COUNT(*) FROM model_mappings mm WHERE mm.channel_id=c.id) model_count
   FROM channels c JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_sites s ON s.id=sc.site_id
   LEFT JOIN new_api_connections n ON n.channel_id=c.id
   LEFT JOIN sub2api_connections x ON x.channel_id=c.id
   LEFT JOIN upstream_catalog_sync u ON u.channel_id=c.id
   LEFT JOIN aiapibank_provider_groups g ON g.channel_id=c.id
   LEFT JOIN upstream_display_mappings d ON d.channel_id=c.id AND d.enabled=TRUE
   LEFT JOIN gateway_sync_jobs j ON j.id=(SELECT jj.id FROM gateway_sync_jobs jj WHERE jj.channel_id=c.id ORDER BY jj.created_at DESC,jj.id DESC LIMIT 1)
   WHERE 1=1
   """+where+" AND c.name LIKE ? ORDER BY c.id",args,page,size,all);
 }
 private String filters(Long site,Long group,String groupColumn,List<Object> args){String sql="";if(site!=null){sql+=" AND s.id=?";args.add(site);}if(group!=null){sql+=" AND "+groupColumn+"=?";args.add(group);}return sql;}
 @PutMapping("/sites/{id}") public void site(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id,@RequestBody GatewaySiteService.Display body){admin(auth);sites.update(id,body);audit.record(users.requireAdmin(auth),"UPDATE_GATEWAY_SITE","SITE",id,null,Map.of("updated",true),null);}
 public record GroupSettings(String name,Boolean enabled,Boolean syncEnabled,@com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String apiKey,String publicName,String publicCode,String badgeText,String badgeColor,boolean inheritDisplay){}
 @PutMapping("/groups/{id}") public void group(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id,@RequestBody GroupSettings body){
  admin(auth);
  boolean bank=jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE id=? AND source_code='aiapibank'",Integer.class,id)==1;
  boolean sub2api=jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE id=? AND source_code='sub2api'",Integer.class,id)==1;
  boolean newKey=body.apiKey()!=null&&!body.apiKey().isBlank();
  tx.executeWithoutResult(status->{
   if(body.name()!=null){if(body.name().isBlank()||body.name().length()>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分组名称不正确");jdbc.update("UPDATE channels SET name=? WHERE id=?",body.name(),id);}
   if(body.enabled()!=null)jdbc.update("UPDATE channels SET enabled=? WHERE id=?",body.enabled(),id);
   if(body.syncEnabled()!=null){jdbc.update("UPDATE new_api_connections SET sync_enabled=? WHERE channel_id=?",body.syncEnabled(),id);jdbc.update("UPDATE sub2api_connections SET sync_enabled=? WHERE channel_id=?",body.syncEnabled(),id);jdbc.update("UPDATE upstream_catalog_sync SET sync_enabled=? WHERE channel_id=?",body.syncEnabled(),id);}
   if(body.apiKey()!=null&&!body.apiKey().isBlank()) {
    if(body.apiKey().length()>8192||body.apiKey().chars().anyMatch(Character::isISOControl))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Key 格式不正确");
    jdbc.update("UPDATE channels SET api_key=?,health_status='UNTESTED' WHERE id=?",secrets.encrypt(body.apiKey().trim()),id);
    if(sub2api){jdbc.update("UPDATE sub2api_connections SET sync_status='PENDING',last_message='凭据已更新，待重新读取 Key 可见目录' WHERE channel_id=?",id);jdbc.update("UPDATE sub2api_model_state SET missing_count=0 WHERE channel_id=?",id);}
   }
   // Public route identity belongs to the site. Group settings only manage
   // credentials, synchronization and availability.
   jdbc.update("DELETE FROM upstream_display_mappings WHERE channel_id=?",id);
   if(bank) {
    if(newKey)jdbc.update("UPDATE aiapibank_provider_groups SET credential_status='VERIFYING',sync_status='PENDING' WHERE channel_id=?",id);
    if(newKey||jdbc.queryForObject("SELECT COUNT(*) FROM aiapibank_provider_groups g JOIN channels c ON c.id=g.channel_id WHERE c.id=? AND g.credential_status='READY' AND c.api_key IS NOT NULL AND c.api_key<>''",Integer.class,id)==0)
     jdbc.update("UPDATE channels SET enabled=FALSE WHERE id=?",id);
   }
  });audit.record(users.requireAdmin(auth),"UPDATE_GATEWAY_GROUP","CHANNEL",id,null,Map.of("updated",true),null);
  if((bank||sub2api)&&newKey)jobs.enqueue(id);
 }
 @PostMapping("/sites/{id}/sync-groups") public Map<String,Object> syncGroups(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id){admin(auth);return Map.of("jobId",jobs.enqueueGroups(id));}
 public record AiApiBankAuthorization(
   String email,
   @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String password,
   @com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String totpCode) {}
 @PostMapping("/sites/{id}/aiapibank/authorize") public Map<String,Object> authorizeAiApiBank(
   @RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id,@RequestBody AiApiBankAuthorization body){
  admin(auth);var result=aiApiBankSessions.authorize(id,body.email(),body.password(),body.totpCode());
  var response=new LinkedHashMap<String,Object>();response.put("authenticated",result.authenticated());
  response.put("requiresTwoFactor",result.requiresTwoFactor());response.put("accountEmailPreview",result.accountEmailPreview());
  response.put("status",result.status());
  if(result.authenticated())response.put("jobId",jobs.enqueueGroups(id));
  audit.record(users.requireAdmin(auth),"AUTHORIZE_AIAPIBANK_CATALOG","SITE",id,null,
    Map.of("status",result.status()),null);return response;
 }
 @DeleteMapping("/sites/{id}/aiapibank/authorization") public void clearAiApiBankAuthorization(
   @RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id){
  admin(auth);aiApiBankSessions.clear(id);audit.record(users.requireAdmin(auth),
    "CLEAR_AIAPIBANK_CATALOG_AUTHORIZATION","SITE",id,null,Map.of("cleared",true),null);
 }
 @GetMapping("/jobs/{id}") public Map<String,Object> job(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable String id){admin(auth);var rows=jdbc.queryForList("SELECT * FROM gateway_sync_jobs WHERE id=?",id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"同步任务不存在");return rows.get(0);}
 @PostMapping("/groups/{id}/test") public Map<String,Object> test(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id){admin(auth);return channelService.test(id);}
 public record Selection(Long siteId,Long groupId){}
 @PostMapping("/sync") public Map<String,Object> sync(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody Selection body){
  admin(auth);sites.reconcile();List<Object> args=new ArrayList<>();String where=filters(body.siteId(),body.groupId(),"c.id",args);
  if(body.groupId()==null) {
   List<Map<String,Object>> results=new ArrayList<>();
   for(Long site:jdbc.queryForList("SELECT id FROM upstream_sites WHERE adapter='aiapibank'"+(body.siteId()==null?"":" AND id=?"),Long.class,body.siteId()==null?new Object[0]:new Object[]{body.siteId()}))
    results.add(Map.of("siteId",site,"jobId",jobs.enqueueGroups(site)));
   where+=" AND c.source_code<>'aiapibank'";
   for(var row:jdbc.queryForList("SELECT c.id FROM channels c JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_sites s ON s.id=sc.site_id WHERE c.source_code<>'nvidia'"+where,args.toArray())) {
    long channel=((Number)row.get("id")).longValue();results.add(Map.of("groupId",channel,"jobId",jobs.enqueue(channel)));
   }
   return Map.of("jobs",results);
  }
  List<Map<String,Object>> result=new ArrayList<>();for(var row:jdbc.queryForList("SELECT c.id FROM channels c JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_sites s ON s.id=sc.site_id WHERE c.source_code<>'nvidia'"+where,args.toArray())) {
   long id=((Number)row.get("id")).longValue();try{result.add(Map.of("groupId",id,"jobId",jobs.enqueue(id)));}catch(Exception e){result.add(Map.of("groupId",id,"error",GatewaySyncJobs.classify(e).message()));}
  }return Map.of("jobs",result);
 }
 @GetMapping("/jobs") public PageResponse<Map<String,Object>> records(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,
   @RequestParam(required=false)Long siteId,@RequestParam(required=false)Long groupId,@RequestParam(defaultValue="")String status,
   @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean all){
  admin(auth);List<Object> args=new ArrayList<>();String where=filters(siteId,groupId,"j.channel_id",args);
  if(!status.isBlank()){where+=" AND j.status=?";args.add(status);}
  return pages.query("SELECT j.*,s.name site_name,CASE WHEN j.job_type='GROUPS' THEN '上游分组目录' ELSE c.name END group_name FROM gateway_sync_jobs j LEFT JOIN upstream_sites s ON s.id=j.site_id LEFT JOIN channels c ON c.id=j.channel_id WHERE 1=1"+where+" ORDER BY j.created_at DESC,j.id",args,page,size,all);
 }
 @GetMapping("/models") public PageResponse<Map<String,Object>> models(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,
   @RequestParam(required=false)Long siteId,@RequestParam(required=false)Long groupId,@RequestParam(defaultValue="")String state,@RequestParam(defaultValue="")String query,
   @RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean all){
  admin(auth);
  if(state.equals("pending-import")) {
   List<Object> parameters=new ArrayList<>();String selection=filters(siteId,groupId,"c.id",parameters);parameters.add("%"+query+"%");
   return pages.query("""
    SELECT c.id channel_id,s.name site_name,COALESCE(n.upstream_group,g.group_name,c.group_name) group_name,p.upstream_model_name public_model_name,
           p.upstream_model_name, 'PENDING' pricing_status, FALSE enabled, FALSE manual_price
    FROM (SELECT channel_id,upstream_model_name FROM new_api_model_state WHERE model_mapping_id IS NULL
          UNION SELECT channel_id,upstream_model_name FROM sub2api_model_state WHERE model_mapping_id IS NULL
          UNION SELECT c.id channel_id,p.upstream_model_name FROM provider_models p JOIN channels c ON c.source_code=p.source_code
          WHERE p.verification_status<>'RETIRED' AND NOT EXISTS(SELECT 1 FROM model_mappings m WHERE m.channel_id=c.id AND m.channel_model_name=p.upstream_model_name)) p
    JOIN channels c ON c.id=p.channel_id JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_sites s ON s.id=sc.site_id
    LEFT JOIN new_api_connections n ON n.channel_id=c.id
    LEFT JOIN aiapibank_provider_groups g ON g.channel_id=c.id
    WHERE 1=1
    """+selection+" AND p.upstream_model_name LIKE ? ORDER BY c.id,p.upstream_model_name",parameters,page,size,all);
  }
  List<Object> args=new ArrayList<>();String where=filters(siteId,groupId,"mm.channel_id",args);
  args.add("%"+query+"%");String stateSql=switch(state){case "pending-price"->" AND (mm.pricing_status<>'VERIFIED' OR mm.pricing_status IS NULL)";case "published"->" AND mm.enabled=TRUE";case "pending-publish"->" AND mm.enabled=FALSE AND mm.pricing_status='VERIFIED'";case "disabled"->" AND mm.enabled=FALSE";default->"";};
  return pages.query("""
   SELECT mm.*,s.name site_name,COALESCE(n.upstream_group,g.group_name,c.group_name) group_name,sc.site_id,
    CASE WHEN o.model_mapping_id IS NOT NULL THEN TRUE ELSE FALSE END manual_price,
    pr.status publication_status,pr.reason publication_reason,pr.next_attempt_at publication_next_attempt_at
   FROM model_mappings mm JOIN channels c ON c.id=mm.channel_id
   JOIN upstream_site_channels sc ON sc.channel_id=c.id JOIN upstream_sites s ON s.id=sc.site_id
   LEFT JOIN new_api_connections n ON n.channel_id=c.id
   LEFT JOIN aiapibank_provider_groups g ON g.channel_id=c.id
   LEFT JOIN gateway_price_overrides o ON o.model_mapping_id=mm.id
   LEFT JOIN gateway_publication_requests pr ON pr.model_mapping_id=mm.id WHERE 1=1
   """+where+" AND mm.public_model_name LIKE ?"+stateSql+" ORDER BY mm.id",args,page,size,all);
 }
 public record ImportItem(long channelId,String model){}
 @PostMapping("/models/import") public List<Map<String,Object>> importModels(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody List<ImportItem> body){
  admin(auth);if(body.size()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"每批最多200个模型");
  List<Map<String,Object>> results=new ArrayList<>();for(var item:body){
   try {
    Long mappingId=tx.execute(status->{
     var c=jdbc.queryForList("SELECT id,models,source_code FROM channels WHERE id=? FOR UPDATE",item.channelId());if(c.isEmpty())throw new IllegalArgumentException("分组不存在");
     int known=jdbc.queryForObject("SELECT COUNT(*) FROM new_api_model_state WHERE channel_id=? AND upstream_model_name=?",Integer.class,item.channelId(),item.model());
     known+=jdbc.queryForObject("SELECT COUNT(*) FROM sub2api_model_state WHERE channel_id=? AND upstream_model_name=?",Integer.class,item.channelId(),item.model());
     known+=jdbc.queryForObject("SELECT COUNT(*) FROM provider_models p JOIN channels c ON c.source_code=p.source_code WHERE c.id=? AND p.upstream_model_name=?",Integer.class,item.channelId(),item.model());
     if(known==0)throw new IllegalArgumentException("不在已读取目录中");
     var existing=jdbc.queryForList("SELECT id FROM model_mappings WHERE channel_id=? AND channel_model_name=?",item.channelId(),item.model());if(!existing.isEmpty())return ((Number)existing.get(0).get("id")).longValue();
     String models=Objects.toString(c.get(0).get("models"),"");String expanded=models.isBlank()?item.model():models+"\n"+item.model();if(expanded.length()>2000)throw new IllegalArgumentException("分组模型容量不足");
     var model="sub2api".equals(c.get(0).get("source_code"))?Sub2ApiOnboardingService.draft(item.model()):com.transit.model.ModelMapping.builder().channelModelName(item.model()).publicModelName(item.model()).enabled(false).billingEnabled(false).billingMode("DISABLED").pricingStatus("PENDING").pricingMessage("待同步采购价和核验").build();model.setChannelId(item.channelId());modelMapper.insert(model);
     jdbc.update("UPDATE channels SET models=? WHERE id=?",expanded,item.channelId());
     jdbc.update("UPDATE new_api_model_state SET model_mapping_id=? WHERE channel_id=? AND upstream_model_name=?",model.getId(),item.channelId(),item.model());
     jdbc.update("UPDATE sub2api_model_state SET model_mapping_id=? WHERE channel_id=? AND upstream_model_name=?",model.getId(),item.channelId(),item.model());return model.getId();
    });results.add(Map.of("id",mappingId,"success",true,"reason","已导入，待核验发布"));jobs.enqueue(item.channelId());
   }catch(Exception e){results.add(Map.of("id",item.channelId(),"success",false,"reason",e instanceof IllegalArgumentException?Objects.toString(e.getMessage(),"导入失败"):"导入失败，请刷新目录后重试"));}
  }return results;
 }
 @GetMapping("/tests")public PageResponse<Map<String,Object>> tests(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestParam(required=false)Long siteId,@RequestParam(required=false)Long groupId,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int size,@RequestParam(defaultValue="false")boolean all){
  admin(auth);List<Object> args=new ArrayList<>();String where=filters(siteId,groupId,"j.channel_id",args);
  return pages.query("SELECT j.id,j.channel_id,j.status,j.model_name,j.latency_ms,j.exit_code,j.error_message,j.tested_at created_at,j.tested_at finished_at,CASE WHEN j.status='SUCCESS' THEN '测试完成' ELSE CONCAT('连通性测试失败：',COALESCE(NULLIF(j.error_message,''),j.status)) END message,s.name site_name,c.name group_name,'TEST' phase FROM channel_test_logs j LEFT JOIN channels c ON c.id=j.channel_id LEFT JOIN upstream_site_channels sc ON sc.channel_id=c.id LEFT JOIN upstream_sites s ON s.id=sc.site_id WHERE 1=1"+where+" ORDER BY j.tested_at DESC,j.id DESC",args,page,size,all);
 }
 public record Publish(List<Long> ids){}
 @PostMapping("/models/publish") public List<Map<String,Object>> publish(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody Publish body){
  admin(auth);if(body.ids()==null||body.ids().size()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"每批最多200个模型");
  List<Map<String,Object>> results=new ArrayList<>();for(long id:new LinkedHashSet<>(body.ids())) {
   results.add(publications.request(id));
  }audit.record(users.requireAdmin(auth),"BATCH_PUBLISH_MODELS","MODEL",null,null,results,null);return results;
 }
 @DeleteMapping("/models/{id}/publication") public void cancelPublication(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id){admin(auth);publications.cancel(id);audit.record(users.requireAdmin(auth),"CANCEL_MODEL_PUBLICATION","MODEL",id,null,Map.of("cancelled",true),null);}
 @GetMapping("/pricing/rules")public GatewayPricingService.Rule configured(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestParam String scope,@RequestParam long scopeId){admin(auth);return pricing.configured(scope,scopeId);}
 @GetMapping("/models/{id}/configuration")public com.transit.model.ModelMapping configuration(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long id){admin(auth);var mapping=modelMapper.selectById(id);if(mapping==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"模型不存在");modelTiers.attach(List.of(mapping));return mapping;}
 @PostMapping("/pricing/preview") public Map<String,Object> preview(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody GatewayPricingService.Rule body){admin(auth);return pricing.preview(body);}
 public record Apply(String previewId){}
 @PostMapping("/pricing/apply") public void apply(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@RequestBody Apply body){admin(auth);pricing.apply(body.previewId());audit.record(users.requireAdmin(auth),"APPLY_GATEWAY_PRICING","PRICE_RULE",body.previewId(),null,Map.of("applied",true),null);}
 @GetMapping("/pricing/{modelId}")public Map<String,Object> rule(@RequestHeader(HttpHeaders.AUTHORIZATION)String auth,@PathVariable long modelId){admin(auth);return pricing.describe(modelId);}
}
