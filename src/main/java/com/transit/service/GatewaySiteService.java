package com.transit.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service @RequiredArgsConstructor
public class GatewaySiteService {
 private final JdbcTemplate jdbc;
 @EventListener(ApplicationReadyEvent.class)
 @Transactional
 public synchronized void reconcile() {
  for(var row:jdbc.queryForList("SELECT id,name,source_code FROM channels WHERE source_code<>'nvidia' AND id NOT IN (SELECT channel_id FROM upstream_site_channels)")) {
   long id=((Number)row.get("id")).longValue();String source=Objects.toString(row.get("source_code"),"other");
   boolean known=Set.of("aiapibank","haoee").contains(source);
   String key=known?source:"channel-"+id;
   var existing=jdbc.queryForList("SELECT id FROM upstream_sites WHERE site_key=?",key);
   if(existing.isEmpty()) jdbc.update("INSERT INTO upstream_sites(site_key,name,adapter) VALUES(?,?,?)",key,
       known?(source.equals("aiapibank")?"AiAPIBank":"好易智算"):row.get("name"),source);
   long site=jdbc.queryForObject("SELECT id FROM upstream_sites WHERE site_key=?",Long.class,key);
   jdbc.update("INSERT INTO upstream_site_channels(channel_id,site_id) VALUES(?,?)",id,site);
  }
 }
 public record Display(String name,String publicCode,String publicName,String badgeText,String badgeColor) {}
 @Transactional public void update(long site,Display input) {
  if(input.name()==null||input.name().isBlank()||input.name().length()>120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"站点名称为1–120个字符");
  if(jdbc.update("UPDATE upstream_sites SET name=? WHERE id=?",input.name().trim(),site)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"站点不存在");
  if(input.publicName()!=null&&!input.publicName().isBlank()) {
   String code=input.publicCode()==null||input.publicCode().isBlank()?"site-"+site:input.publicCode().trim();
   if(!code.matches("[a-z0-9][a-z0-9_-]{0,79}")||input.publicName().length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"公开名称或代号不正确");
   String color=input.badgeColor()==null?"#2563eb":input.badgeColor();
   if(!color.matches("#[a-fA-F0-9]{6}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"颜色格式不正确");
   jdbc.update("UPDATE upstream_sites SET public_code=?,public_name=?,badge_text=?,badge_color=? WHERE id=?",code,input.publicName(),input.badgeText(),color,site);
   deleteRedundantGroupDisplays(site,input.publicName());
  }
 }
 @Transactional public void attach(long channel,long site) {
  if(jdbc.queryForObject("SELECT COUNT(*) FROM upstream_sites WHERE id=?",Integer.class,site)!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"站点不存在");
  var target=jdbc.queryForMap("SELECT adapter FROM upstream_sites WHERE id=?",site);
  var source=jdbc.queryForMap("SELECT source_code,base_url FROM channels WHERE id=?",channel);
  if(!Objects.equals(target.get("adapter"),source.get("source_code")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"接入协议与站点不匹配");
  for(var member:jdbc.queryForList("SELECT c.base_url FROM channels c JOIN upstream_site_channels sc ON sc.channel_id=c.id WHERE sc.site_id=?",site))
   if(!Objects.equals(member.get("base_url"),source.get("base_url")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分组地址必须与所选站点一致");
  jdbc.update("DELETE FROM upstream_site_channels WHERE channel_id=?",channel);
  jdbc.update("INSERT INTO upstream_site_channels(channel_id,site_id) VALUES(?,?)",channel,site);
 }
 @Transactional public void configurePublicDisplayForChannel(long channel,String publicName) {
  String name=publicName==null?"":publicName.trim();
  if(name.isBlank()||name.length()>120)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"前台公开渠道名为1–120个字符");
  List<Map<String,Object>> rows=jdbc.queryForList("""
   SELECT s.id,s.public_code FROM upstream_sites s
   JOIN upstream_site_channels sc ON sc.site_id=s.id WHERE sc.channel_id=?
   """,channel);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"渠道尚未归属站点");
  long site=((Number)rows.get(0).get("id")).longValue();
  String current=Objects.toString(rows.get(0).get("public_code"),"").trim();
  String code=current.isBlank()?"site-"+site:current;
  jdbc.update("UPDATE upstream_sites SET public_code=?,public_name=? WHERE id=?",code,name,site);
  deleteRedundantGroupDisplays(site,name);
 }
 private void deleteRedundantGroupDisplays(long site,String publicName) {
  jdbc.update("""
   DELETE FROM upstream_display_mappings
   WHERE channel_id IN (SELECT channel_id FROM upstream_site_channels WHERE site_id=?)
     AND LOWER(TRIM(public_name))=LOWER(TRIM(?))
   """,site,publicName);
 }
}
