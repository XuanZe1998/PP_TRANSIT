package com.transit.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
/** Opt-in pagination for existing collection endpoints; their authorization and DTOs stay authoritative. */
@ControllerAdvice @RequiredArgsConstructor
public class ListPageAdvice implements ResponseBodyAdvice<Object> {
 private final ObjectMapper json;
 public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter){return true;}
 public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest request,ServerHttpResponse response){
  if(!(request instanceof ServletServerHttpRequest servlet)||!"GET".equals(request.getMethod().name())||body==null)return body;
  if(body instanceof org.springframework.http.ProblemDetail)return body;
  var params=servlet.getServletRequest();
  boolean collectionPage="true".equals(params.getParameter("listPage"));
  boolean boundedAll="200".equals(params.getParameter("size"))||"200".equals(params.getParameter("pageSize"));
  if(!collectionPage&&!boundedAll)return body;
  JsonNode node=json.valueToTree(body);
  if(boundedAll){long total=node.path("total").asLong(node.path("transactionTotal").asLong(0));if(total>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"超过200条，请分页或缩小筛选");}
  if(!collectionPage)return body;
  String path=params.getParameter("listPath");if(path!=null&&!path.isBlank())for(String key:path.split("\\.")){if(!key.matches("[A-Za-z0-9_]+"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"列表路径无效");node=node.path(key);}
  if(!node.isArray())return body;
  int page=parse(params.getParameter("listCurrent"),1),size=parse(params.getParameter("listSize"),20);
  if(page<1||!Set.of(10,20,50,100).contains(size))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数无效");
  String search=Objects.toString(params.getParameter("listQuery"),"").strip().toLowerCase(Locale.ROOT);
  List<JsonNode> rows=new ArrayList<>();for(JsonNode row:node)if(search.isBlank()||row.toString().toLowerCase(Locale.ROOT).contains(search))rows.add(row);
  boolean all="true".equals(params.getParameter("listAll"));if(all&&rows.size()>200)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"超过200条，请分页或缩小筛选");
  int limit=all?200:size,current=Math.min(page,Math.max(1,(rows.size()+limit-1)/limit));int from=Math.min((current-1)*limit,rows.size());
  return Map.of("items",rows.subList(from,Math.min(from+limit,rows.size())),"total",rows.size(),"page",current,"size",limit);
 }
 private int parse(String value,int fallback){try{return value==null?fallback:Integer.parseInt(value);}catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"分页参数无效");}}
}
