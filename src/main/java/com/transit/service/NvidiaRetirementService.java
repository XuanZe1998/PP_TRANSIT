package com.transit.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
@Component @Order(12) @RequiredArgsConstructor
public class NvidiaRetirementService implements ApplicationRunner {
 private final JdbcTemplate jdbc;
 private final AdminChannelService channels;
 @Value("${nvidia.enabled:false}") private boolean enabled;
 private volatile boolean ready;
 public void run(ApplicationArguments args){ready=true;retire();}
 @Scheduled(fixedDelay=300000) public void retire(){
  if(enabled||!ready)return;
  for(Long id:jdbc.queryForList("SELECT id FROM channels WHERE source_code='nvidia'",Long.class)){
   jdbc.update("UPDATE channels SET enabled=FALSE WHERE id=?",id);
   if(jdbc.queryForObject("SELECT COUNT(*) FROM model_tasks WHERE channel_id=? AND completed_at IS NULL",Integer.class,id)>0)continue;
   channels.delete(id);
  }
  if(jdbc.queryForObject("SELECT COUNT(*) FROM channels WHERE source_code='nvidia'",Integer.class)==0){
   jdbc.update("DELETE FROM provider_models WHERE source_code='nvidia'");
   jdbc.update("DELETE FROM upstream_sites WHERE adapter='nvidia' AND id NOT IN (SELECT site_id FROM upstream_site_channels)");
  }
 }
}
