package com.transit.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
@Service @RequiredArgsConstructor
public class GatewaySyncJobs {
 private final JdbcTemplate jdbc;
 private final TransactionTemplate tx;
 private final NewApiCatalogManagementService catalogs;
 private final GatewaySiteService sites;
 private final ExecutorService executor=Executors.newFixedThreadPool(4);
 private final Set<String> dispatched=ConcurrentHashMap.newKeySet();
 @jakarta.annotation.PreDestroy public void close(){executor.shutdown();}
 public String enqueueGroups(long site) {
  if(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_sites WHERE id=? AND adapter='aiapibank'",Integer.class,site)!=1)
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"该站点尚不支持分组目录同步");
  String id=UUID.randomUUID().toString();
  try {return tx.execute(status->{
   jdbc.update("INSERT INTO gateway_site_sync_locks(site_id,job_id) VALUES(?,?)",site,id);
   jdbc.update("INSERT INTO gateway_sync_jobs(id,channel_id,site_id,job_type,status,phase,created_at) VALUES(?,NULL,?,'GROUPS','QUEUED','GROUPS',?)",id,site,LocalDateTime.now());
   return id;
  });}catch(org.springframework.dao.DuplicateKeyException conflict){return jdbc.queryForObject("SELECT job_id FROM gateway_site_sync_locks WHERE site_id=?",String.class,site);}
 }
 public String enqueue(long channel) {
  sites.reconcile();
  if(jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE id=? AND source_code<>'nvidia'",Integer.class,channel)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"渠道不存在");
  String id=UUID.randomUUID().toString();
  try {
   return tx.execute(status->{
    jdbc.update("INSERT INTO gateway_sync_locks(channel_id,job_id) VALUES(?,?)",channel,id);
    jdbc.update("INSERT INTO gateway_sync_jobs(id,channel_id,site_id,status,created_at) SELECT ?,?,site_id,'QUEUED',? FROM upstream_site_channels WHERE channel_id=?",id,channel,LocalDateTime.now(),channel);
    if(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_sync_jobs WHERE id=?",Integer.class,id)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"站点关联尚未完成，请刷新后重试");
    return id;
   });
  } catch(org.springframework.dao.DuplicateKeyException conflict) {
   return jdbc.queryForObject("SELECT job_id FROM gateway_sync_locks WHERE channel_id=?",String.class,channel);
  }
 }
 @Scheduled(fixedDelay=1000)
 public void dispatch() {
  for(var row:jdbc.queryForList("SELECT id,channel_id FROM gateway_sync_jobs WHERE status='RUNNING' AND started_at<?",LocalDateTime.now().minusMinutes(10))) {
   String id=row.get("id").toString();if(dispatched.contains(id))continue;
   finish(id,"ERROR",new Failure("WORKER_INTERRUPTED",null,"执行进程中断，历史任务未完成","重新同步此分组"));
  }
  if(dispatched.size()>=4)return;
  for(var row:jdbc.queryForList("SELECT id,channel_id,site_id,job_type FROM gateway_sync_jobs WHERE status='QUEUED' ORDER BY created_at LIMIT 4")) {
   String id=row.get("id").toString();if(!dispatched.add(id))continue;
   if(jdbc.update("UPDATE gateway_sync_jobs SET status='RUNNING',phase='CATALOG',started_at=? WHERE id=? AND status='QUEUED'",LocalDateTime.now(),id)!=1){dispatched.remove(id);continue;}
   boolean groupJob="GROUPS".equals(row.get("job_type"));
   executor.submit(()->{
    try {
     GatewaySyncProgress.bind(phase->jdbc.update("UPDATE gateway_sync_jobs SET phase=? WHERE id=?",phase,id));
     var result=groupJob?catalogs.synchronizeGroups(((Number)row.get("site_id")).longValue()):catalogs.synchronize(((Number)row.get("channel_id")).longValue(),true);
     String state=Objects.toString(result.get("status"),"SUCCESS");
     finish(id,state,new Failure(null,null,Objects.toString(result.get("message"),"同步完成"),state.equals("PARTIAL")?"查看分组待配置项":""));
    } catch(Exception error){finish(id,"ERROR",classify(error));}
    finally{GatewaySyncProgress.clear();dispatched.remove(id);}
   });
  }
 }
 public record Failure(String code,Integer httpStatus,String message,String suggestion) {}
 public static Failure classify(Throwable error) {
  Integer http=null;StringBuilder text=new StringBuilder();
  for(Throwable e=error;e!=null;e=e.getCause()) {
   if(e instanceof WebClientResponseException response)http=response.getStatusCode().value();
   text.append(e.getClass().getSimpleName()).append(' ').append(Objects.toString(e.getMessage(),"")).append(' ');
  }
  String t=text.toString().toLowerCase(Locale.ROOT);
  if(http==null){var matcher=java.util.regex.Pattern.compile("http (\\d{3})").matcher(t);if(matcher.find())http=Integer.valueOf(matcher.group(1));}
  if(http!=null&&http>=300&&http<400)return new Failure("UPSTREAM_REDIRECT",http,"上游接口返回 HTTP "+http+" 重定向，请核对请求路径","好易智算模型目录路径应为 /v1/models/；检查路径末尾斜杠和站点地址");
  if(t.contains("unable to decrypt")||t.contains("master key"))return new Failure("CREDENTIAL_DECRYPTION_FAILED",http,"已保存的 Key 无法解密","检查服务加密配置，或重新保存分组 Key 后重试");
  if(Objects.equals(http,401)||t.contains("凭据无效"))return new Failure("INVALID_CREDENTIAL",401,"上游凭据无效或已过期","更新该分组 Key 后重试");
  if(Objects.equals(http,403))return new Failure("FORBIDDEN",403,"上游拒绝访问该分组","检查 Key 的分组和模型权限");
  if(Objects.equals(http,429))return new Failure("RATE_LIMITED",429,"上游请求频率受限","稍后重试或调整上游额度");
  if(t.contains("timeout")||t.contains("超时"))return new Failure("TIMEOUT",http,"上游响应超时","检查上游状态后重试");
  if(t.contains("incomplete")||t.contains("不完整")||t.contains("invalid model"))return new Failure("INCOMPLETE_CATALOG",http,"上游目录不完整或结构无效","检查接口兼容性，原有目录已保留");
  if(t.contains("conflict")||t.contains("409")||t.contains("同步已"))return new Failure("CONFIG_CONFLICT",409,"同步冲突或配置已变化","等待当前任务完成并刷新设置");
  if(Objects.equals(http,404)||t.contains("decoding")||t.contains("invalid pricing"))return new Failure("INCOMPATIBLE_API",http,"上游接口不存在或格式不兼容","检查站点地址和适配器");
  if(t.contains("pricing")||t.contains("价格"))return new Failure("UNSUPPORTED_PRICE",http,"上游价格格式不受支持","配置价格接口或手工核验采购价");
  if(t.contains("credential")||t.contains("key"))return new Failure("CREDENTIAL_MISSING",http,"分组凭据未配置或不可用","为该分组配置有效 Key");
  return new Failure("NETWORK_OR_UPSTREAM",http,"无法完成上游请求","检查上游服务、网络及分组配置后重试");
 }
 private void finish(String id,String state,Failure failure) {
  tx.executeWithoutResult(status->{
   jdbc.update("UPDATE gateway_sync_jobs SET status=?,error_code=?,http_status=?,message=?,suggestion=?,finished_at=? WHERE id=?",state,failure.code(),failure.httpStatus(),failure.message(),failure.suggestion(),LocalDateTime.now(),id);
   jdbc.update("DELETE FROM gateway_sync_locks WHERE job_id=?",id);
   jdbc.update("DELETE FROM gateway_site_sync_locks WHERE job_id=?",id);
  });
 }
}
