package com.transit.service;
import com.transit.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service @RequiredArgsConstructor
public class GatewayPageService {
 private final JdbcTemplate jdbc;
 public PageResponse<Map<String,Object>> query(String sql, List<Object> args, int page, int size, boolean all) {
  if (page < 1 || !Set.of(10,20,50,100).contains(size)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数不正确");
  long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM ("+sql+") page_count",Long.class,args.toArray()));
  if (all && total>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"超过200条，请分页或缩小筛选范围");
  int limit=all?200:size;
  int current=Math.min(page,Math.max(1,(int)((total+limit-1)/limit)));
  List<Object> parameters=new ArrayList<>(args);parameters.add(limit);parameters.add((current-1)*limit);
  PageResponse<Map<String,Object>> result=new PageResponse<>();result.setTotal(total);result.setPage(current);result.setSize(limit);
  result.setItems(jdbc.queryForList(sql+" LIMIT ? OFFSET ?",parameters.toArray()));return result;
 }
}
