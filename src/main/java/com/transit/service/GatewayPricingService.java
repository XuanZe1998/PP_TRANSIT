package com.transit.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.*;
import java.time.LocalDateTime;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
@Service @RequiredArgsConstructor
public class GatewayPricingService {
 @org.springframework.context.annotation.Bean @org.springframework.core.annotation.Order(2)
 org.springframework.boot.ApplicationRunner migrateGatewayPrices(){return args -> migrate();}
 private final JdbcTemplate jdbc;
 private final ObjectMapper json;
 @Value("${aiapibank.sale-markup:1.10}") private BigDecimal bankMarkup;
 private static final Map<String,String> MODEL=Map.of("input_price_per_million","input_cost_per_million","output_price_per_million","output_cost_per_million","cached_price_per_million","cached_cost_per_million","sale_unit_price","cost_unit_price");
 private static final List<String> DIMENSIONS=List.of("input","output","cache_read","cache_write","cache_write_1h","image_input","image_output","per_request");
 public record Rule(String scope,long scopeId,String mode,BigDecimal amount,Map<String,BigDecimal> fixedAmounts) {}
 private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("价格序列化失败",e);}}
 @SuppressWarnings("unchecked") private Map<String,Object> decode(String value){try{return json.readValue(value,Map.class);}catch(Exception e){throw new IllegalStateException("价格配置无效",e);}}
 private static BigDecimal number(Object value){return value==null?null:new BigDecimal(value.toString());}
 private static long id(Object value){return ((Number)value).longValue();}
 public static BigDecimal sale(BigDecimal cost,String mode,BigDecimal amount) {
  if(cost==null)return null;
  if(amount==null||amount.signum()<0||amount.compareTo(new BigDecimal("1000000"))>0)throw new IllegalArgumentException("加价金额不正确");
  return ("PERCENT".equals(mode)?cost.multiply(BigDecimal.ONE.add(amount.movePointLeft(2))):cost.add(amount)).setScale(8,RoundingMode.HALF_UP);
 }
 @EventListener(ApplicationReadyEvent.class) @Transactional
 public void migrate() {
  if(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_price_rules WHERE scope_type='GLOBAL'",Integer.class)>0)return;
  put(new Rule("GLOBAL",0,"PERCENT",new BigDecimal("20"),Map.of()));
  for(var c:jdbc.queryForList("SELECT c.id,c.source_code,n.sale_markup FROM channels c LEFT JOIN new_api_connections n ON n.channel_id=c.id WHERE c.source_code<>'nvidia'")) {
   BigDecimal ratio=number(c.get("sale_markup"));
   if(ratio==null&&"aiapibank".equals(c.get("source_code")))ratio=bankMarkup;
   if(ratio!=null)put(new Rule("GROUP",id(c.get("id")),"PERCENT",ratio.subtract(BigDecimal.ONE).movePointRight(2).max(BigDecimal.ZERO),Map.of()));
  }
  for(var model:models(new Rule("GLOBAL",0,"PERCENT",BigDecimal.ZERO,Map.of()),false)) {
   if("FREE_PREVIEW".equals(model.get("billing_mode")))continue;
   long modelId=id(model.get("id"));
   var effective=effective(model,null);
   var proposed=calculate(model,effective);
   boolean same=MODEL.keySet().stream().allMatch(k->equal(model.get(k),proposed.get(k)));
   for(var tier:tiers(modelId,false))for(String d:DIMENSIONS)if(!equal(tier.get("sale_"+d+"_price"),tierSale(tier,d,model,effective)))same=false;
   if(!same)protect(modelId);
  }
 }
 @Transactional public void initializeGroup(long channel,BigDecimal markup){
  if(markup!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM gateway_price_rules WHERE scope_type='GROUP' AND scope_id=?",Integer.class,channel)==0)
   put(new Rule("GROUP",channel,"PERCENT",markup.subtract(BigDecimal.ONE).movePointRight(2).max(BigDecimal.ZERO),Map.of()));
 }
 private boolean equal(Object a,Object b){return a==null?b==null:b!=null&&number(a).compareTo(number(b))==0;}
 private List<Map<String,Object>> tiers(long model,boolean lock){return jdbc.queryForList("SELECT * FROM model_price_tiers WHERE model_mapping_id=? ORDER BY sort_order,id"+(lock?" FOR UPDATE":""),model);}
 private List<Map<String,Object>> models(Rule rule,boolean lock) {
  String where=switch(rule.scope()){case "GLOBAL"->"1=1";case "SITE"->"mm.channel_id IN (SELECT channel_id FROM upstream_site_channels WHERE site_id=?)";case "GROUP"->"mm.channel_id=?";case "MODEL"->"mm.id=?";default->throw bad("规则范围不正确");};
  String sql="SELECT mm.* FROM model_mappings mm WHERE "+where+" ORDER BY mm.id"+(lock?" FOR UPDATE":"");
  return jdbc.queryForList(sql,rule.scope().equals("GLOBAL")?new Object[0]:new Object[]{rule.scopeId()});
 }
 private Rule from(Map<String,Object> row){
  Map<String,BigDecimal> fixed=new LinkedHashMap<>();
  if(row.get("fixed_amounts")!=null)decode(row.get("fixed_amounts").toString()).forEach((k,v)->fixed.put(k,number(v)));
  return new Rule(row.get("scope_type").toString(),id(row.get("scope_id")),row.get("mode").toString(),number(row.get("amount")),fixed);
 }
 public Map<String,Object> describe(long modelId){var model=jdbc.queryForMap("SELECT * FROM model_mappings WHERE id=?",modelId);Rule rule=effective(model,null);return Map.of("rule",rule,"manual",!jdbc.queryForList("SELECT model_mapping_id FROM gateway_price_overrides WHERE model_mapping_id=?",modelId).isEmpty());}
 public Rule configured(String scope,long scopeId){
  var rows=jdbc.queryForList("SELECT * FROM gateway_price_rules WHERE scope_type=? AND scope_id=?",scope,scopeId);
  return rows.isEmpty()?new Rule(scope,scopeId,"INHERIT",BigDecimal.ZERO,Map.of()):from(rows.get(0));
 }
 private Rule effective(Map<String,Object> model,Rule proposed) {
  long modelId=id(model.get("id")),channel=id(model.get("channel_id"));
  var sites=jdbc.queryForList("SELECT site_id FROM upstream_site_channels WHERE channel_id=?",channel);
  long site=sites.isEmpty()?-1:id(sites.get(0).get("site_id"));
  for(var scope:List.of(new Object[]{"MODEL",modelId},new Object[]{"GROUP",channel},new Object[]{"SITE",site},new Object[]{"GLOBAL",0L})) {
   if(proposed!=null&&proposed.scope().equals(scope[0])&&proposed.scopeId()==(Long)scope[1]){if(!proposed.mode().equals("INHERIT"))return proposed;continue;}
   var rows=jdbc.queryForList("SELECT * FROM gateway_price_rules WHERE scope_type=? AND scope_id=?",scope);
   if(!rows.isEmpty())return from(rows.get(0));
  }
  return new Rule("GLOBAL",0,"PERCENT",new BigDecimal("20"),Map.of());
 }
 private BigDecimal markup(Rule r,String unit) {
  if(r.mode().equals("PERCENT"))return r.amount();
  BigDecimal amount=r.fixedAmounts()==null?null:r.fixedAmounts().get(unit);
  if(amount==null)throw bad("请配置 "+unit+" 的固定加价金额");return amount;
 }
 private Map<String,Object> calculate(Map<String,Object> model,Rule r) {
  Map<String,Object> next=new LinkedHashMap<>();String unit=Objects.toString(model.get("pricing_unit"),"TOKEN").toUpperCase(Locale.ROOT);
  for(var pair:MODEL.entrySet()) {
   boolean perRequest=pair.getKey().equals("sale_unit_price");
   if(perRequest==unit.equals("TOKEN")){next.put(pair.getKey(),model.get(pair.getKey()));continue;}
   next.put(pair.getKey(),sale(number(model.get(pair.getValue())),r.mode(),markup(r,unit)));
  }
  return next;
 }
 private BigDecimal tierSale(Map<String,Object> tier,String dimension,Map<String,Object> model,Rule rule){
  String unit=Objects.toString(model.get("pricing_unit"),"TOKEN").toUpperCase(Locale.ROOT);
  if(dimension.equals("per_request")==unit.equals("TOKEN"))return number(tier.get("sale_"+dimension+"_price"));
  BigDecimal value=number(tier.get("cost_"+dimension+"_price"));
  if(value==null)return null;
  BigDecimal amount=markup(rule,unit);
  if(rule.mode().equals("FIXED")&&"KB".equals(tier.get("cost_price_unit")))amount=amount.divide(new BigDecimal("1000"),8,RoundingMode.HALF_UP);
  return sale(value,rule.mode(),amount);
 }
 @Transactional public void protect(long modelId) {
  var model=jdbc.queryForMap("SELECT * FROM model_mappings WHERE id=?",modelId);
  Map<String,Object> snapshot=new LinkedHashMap<>();Map<String,Object> prices=new LinkedHashMap<>();MODEL.keySet().forEach(k->prices.put(k,model.get(k)));snapshot.put("model",prices);
  snapshot.put("tiers",tiers(modelId,false).stream().map(t->{Map<String,Object> v=new LinkedHashMap<>();v.put("sort_order",t.get("sort_order"));v.put("max_context_tokens",t.get("max_context_tokens"));for(String d:DIMENSIONS)v.put("sale_"+d+"_price",t.get("sale_"+d+"_price"));return v;}).toList());
  jdbc.update("DELETE FROM gateway_price_overrides WHERE model_mapping_id=?",modelId);
  jdbc.update("INSERT INTO gateway_price_overrides(model_mapping_id,snapshot) VALUES(?,?)",modelId,encode(snapshot));
 }
 @Transactional public void repriceAfterSync(long modelId) {
  var model=jdbc.queryForMap("SELECT * FROM model_mappings WHERE id=?",modelId);
  if("FREE_PREVIEW".equals(model.get("billing_mode"))||!"VERIFIED".equals(model.get("pricing_status")))return;
  var overrides=jdbc.queryForList("SELECT snapshot FROM gateway_price_overrides WHERE model_mapping_id=?",modelId);
  if(!overrides.isEmpty()){restore(modelId,decode(overrides.get(0).get("snapshot").toString()));return;}
  applyModel(model,effective(model,null));
 }
 @SuppressWarnings("unchecked") private void restore(long modelId,Map<String,Object> snapshot) {
  updatePrices("model_mappings",modelId,(Map<String,Object>)snapshot.get("model"));
  List<Map<String,Object>> old=(List<Map<String,Object>>)snapshot.get("tiers");
  for(var tier:tiers(modelId,true)){
   var match=old.stream().filter(t->Objects.toString(t.get("sort_order"),"").equals(Objects.toString(tier.get("sort_order"),""))&&Objects.toString(t.get("max_context_tokens"),"").equals(Objects.toString(tier.get("max_context_tokens"),""))).findFirst();
   if(match.isEmpty()){jdbc.update("UPDATE model_mappings SET enabled=FALSE,pricing_status='PENDING',pricing_message='上游阶梯已变化，请重新核验手工售价' WHERE id=?",modelId);continue;}
   Map<String,Object> values=new LinkedHashMap<>(match.get());values.remove("sort_order");values.remove("max_context_tokens");updatePrices("model_price_tiers",id(tier.get("id")),values);
  }
 }
 private void updatePrices(String table,long id,Map<String,Object> values){
  List<Object> parameters=new ArrayList<>();List<String> fields=new ArrayList<>();values.forEach((key,value)->{
   if(!MODEL.containsKey(key)&&!key.equals("sale_price_unit")&&DIMENSIONS.stream().noneMatch(d->key.equals("sale_"+d+"_price")))throw bad("未知价格字段");
   fields.add(key+"=?");parameters.add(value);
  });parameters.add(id);
  jdbc.update("UPDATE "+table+" SET "+String.join(",",fields)+" WHERE id=?",parameters.toArray());
 }
 private void applyModel(Map<String,Object> model,Rule rule){
  long modelId=id(model.get("id"));updatePrices("model_mappings",modelId,calculate(model,rule));
  for(var tier:tiers(modelId,true)){Map<String,Object> values=new LinkedHashMap<>();for(String d:DIMENSIONS)values.put("sale_"+d+"_price",tierSale(tier,d,model,rule));values.put("sale_price_unit",tier.get("cost_price_unit"));updatePrices("model_price_tiers",id(tier.get("id")),values);}
 }
 private void validate(Rule r){
  if(!Set.of("GLOBAL","SITE","GROUP","MODEL").contains(r.scope())||!Set.of("PERCENT","FIXED","INHERIT").contains(r.mode()))throw bad("规则类型不正确");
  if(!r.scope().equals("GLOBAL")) {
   String table=switch(r.scope()){case "SITE"->"upstream_sites";case "GROUP"->"channels";case "MODEL"->"model_mappings";default->throw bad("规则范围不正确");};
   if(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",Integer.class,r.scopeId())!=1)throw bad("规则对象不存在");
  }
  if(r.scope().equals("GLOBAL")&&(r.scopeId()!=0||r.mode().equals("INHERIT")))throw bad("全局规则不可继承");
  if(r.amount()==null||r.amount().signum()<0||r.amount().compareTo(new BigDecimal("1000000"))>0||r.amount().scale()>8)throw bad("加价范围为0–1000000，最多8位小数");
  if(r.fixedAmounts()!=null)for(var amount:r.fixedAmounts().values())if(amount==null||amount.signum()<0||amount.scale()>8||amount.compareTo(new BigDecimal("1000000"))>0)throw bad("固定加价金额不正确");
 }
 private void put(Rule r){
  if(r.mode().equals("INHERIT")){jdbc.update("DELETE FROM gateway_price_rules WHERE scope_type=? AND scope_id=?",r.scope(),r.scopeId());return;}
  int changed=jdbc.update("UPDATE gateway_price_rules SET mode=?,amount=?,fixed_amounts=?,version=version+1 WHERE scope_type=? AND scope_id=?",r.mode(),r.amount(),encode(r.fixedAmounts()==null?Map.of():r.fixedAmounts()),r.scope(),r.scopeId());
  if(changed==0)jdbc.update("INSERT INTO gateway_price_rules(scope_type,scope_id,mode,amount,fixed_amounts) VALUES(?,?,?,?,?)",r.scope(),r.scopeId(),r.mode(),r.amount(),encode(r.fixedAmounts()==null?Map.of():r.fixedAmounts()));
 }
 private String fingerprint(Rule rule,boolean lock){
  List<Object> snapshot=new ArrayList<>();snapshot.add(jdbc.queryForList("SELECT * FROM gateway_price_rules ORDER BY scope_type,scope_id"+(lock?" FOR UPDATE":"")));
  for(var m:models(rule,lock)){snapshot.add(m);snapshot.add(tiers(id(m.get("id")),lock));snapshot.add(jdbc.queryForList("SELECT * FROM gateway_price_overrides WHERE model_mapping_id=?"+(lock?" FOR UPDATE":""),m.get("id")));}
  try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(snapshot).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
 }
 public Map<String,Object> preview(Rule rule){
  validate(rule);String fingerprint=fingerprint(rule,false);List<Map<String,Object>> changes=new ArrayList<>();
  for(var model:models(rule,false)){
   long modelId=id(model.get("id"));boolean manual=!jdbc.queryForList("SELECT model_mapping_id FROM gateway_price_overrides WHERE model_mapping_id=?",modelId).isEmpty();
   boolean skip="FREE_PREVIEW".equals(model.get("billing_mode"))||!"VERIFIED".equals(model.get("pricing_status"))||(manual&&!rule.scope().equals("MODEL"));
   var r=effective(model,rule);Map<String,Object> item=new LinkedHashMap<>();item.put("id",modelId);item.put("model",model.get("public_model_name"));item.put("unit",model.get("pricing_unit"));item.put("skip",skip);item.put("reason",skip?(manual?"保留手工售价":"免费或采购价格未核验"):"应用 "+r.scope()+" 规则");
   Map<String,Object> before=new LinkedHashMap<>();MODEL.keySet().forEach(k->before.put(k,model.get(k)));item.put("before",before);var after=skip?before:calculate(model,r);item.put("after",after);
   Map<String,Object> delta=new LinkedHashMap<>();MODEL.keySet().forEach(k->delta.put(k,before.get(k)==null||after.get(k)==null?null:number(after.get(k)).subtract(number(before.get(k)))));item.put("delta",delta);
   List<Map<String,Object>> tierChanges=new ArrayList<>();for(var tier:tiers(modelId,false)){
    Map<String,Object> t=new LinkedHashMap<>();t.put("name",tier.get("tier_name"));t.put("maxContextTokens",tier.get("max_context_tokens"));
    Map<String,Object> oldTier=new LinkedHashMap<>(),newTier=new LinkedHashMap<>();for(String d:DIMENSIONS){oldTier.put(d,tier.get("sale_"+d+"_price"));newTier.put(d,skip?tier.get("sale_"+d+"_price"):tierSale(tier,d,model,r));}t.put("before",oldTier);t.put("after",newTier);tierChanges.add(t);
   }item.put("tiers",tierChanges);
   boolean changed=MODEL.keySet().stream().anyMatch(k->!equal(before.get(k),after.get(k)))
       ||tierChanges.stream().anyMatch(t->DIMENSIONS.stream().anyMatch(d->!equal(((Map<?,?>)t.get("before")).get(d),((Map<?,?>)t.get("after")).get(d))));
   item.put("changed",changed);item.put("effectiveRule",r);
   Map<String,Object> costs=new LinkedHashMap<>();MODEL.forEach((sale,cost)->costs.put(sale,model.get(cost)));item.put("cost",costs);
   if(!skip) {
    boolean shadowed=!r.scope().equals(rule.scope())||r.scopeId()!=rule.scopeId();
    item.put("reason",shadowed&&!rule.mode().equals("INHERIT")?"被更高优先级的 "+r.scope()+" 规则覆盖；请修改该规则或恢复继承":changed?"按采购价应用 "+r.scope()+" 规则":"售价已符合生效规则（采购价加价，不是在旧售价上累加）");
   }
   changes.add(item);
  }
  if(!fingerprint.equals(fingerprint(rule,false)))throw new ResponseStatusException(HttpStatus.CONFLICT,"价格正在变化，请重新预览");
  String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO gateway_price_previews(id,payload,fingerprint,created_at) VALUES(?,?,?,?)",id,encode(rule),fingerprint,LocalDateTime.now());
  return Map.of("id",id,"changes",changes,"rule",rule);
 }
 @Transactional public void apply(String previewId){
  var saved=jdbc.queryForList("SELECT * FROM gateway_price_previews WHERE id=? FOR UPDATE",previewId);
  if(saved.isEmpty())throw bad("预览不存在");var p=saved.get(0);
  if((Boolean.TRUE.equals(p.get("applied"))||(p.get("applied") instanceof Number applied&&applied.intValue()!=0))||(p.get("created_at") instanceof java.sql.Timestamp stamp ? stamp.toLocalDateTime() : (LocalDateTime)p.get("created_at")).isBefore(LocalDateTime.now().minusMinutes(30)))throw bad("预览已使用或过期，请重新预览");
  Rule rule;try{rule=json.readValue(p.get("payload").toString(),Rule.class);}catch(Exception e){throw bad("预览无效");}
  if(!fingerprint(rule,true).equals(p.get("fingerprint")))throw new ResponseStatusException(HttpStatus.CONFLICT,"采购价、售价或规则已变化，请重新预览");
  put(rule);
  if(rule.scope().equals("MODEL"))jdbc.update("DELETE FROM gateway_price_overrides WHERE model_mapping_id=?",rule.scopeId());
  for(var model:models(rule,true))repriceAfterSync(id(model.get("id")));
  jdbc.update("UPDATE gateway_price_previews SET applied=TRUE WHERE id=?",previewId);
 }
 private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
}
