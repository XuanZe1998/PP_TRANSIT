package com.transit.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.IntStream;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ListPageAdviceTests {
 MockMvc mvc;
 @RestController static class Fixture {
  @GetMapping("/rows")public List<Map<String,Integer>> rows(){return IntStream.range(0,205).mapToObj(i->Map.of("id",i)).toList();}
  @GetMapping("/nested")public Map<String,Object> nested(){return Map.of("wallet",Map.of("plans",List.of(Map.of("name","a"),Map.of("name","b"))));}
  record Secret(String name,@JsonProperty(access=JsonProperty.Access.WRITE_ONLY)String key){}
  @GetMapping("/safe")public List<Secret> safe(){return List.of(new Secret("a","secret-not-public"));}
 }
 @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new ListPageAdvice(new ObjectMapper().findAndRegisterModules())).build();}
 @Test void returnsCorrectWindowAndTotal()throws Exception{mvc.perform(get("/rows").param("listPage","true").param("listCurrent","2").param("listSize","10")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(205)).andExpect(jsonPath("$.items.length()").value(10)).andExpect(jsonPath("$.items[0].id").value(10));}
 @Test void rejectsAllAboveBoundAndNegativePages()throws Exception{mvc.perform(get("/rows").param("listPage","true").param("listAll","true")).andExpect(status().isBadRequest());mvc.perform(get("/rows").param("listPage","true").param("listCurrent","-1")).andExpect(status().isBadRequest());}
 @Test void paginatesNestedCollectionsAndKeepsWriteOnlyFieldsPrivate()throws Exception{mvc.perform(get("/nested").param("listPage","true").param("listPath","wallet.plans").param("listAll","true")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));mvc.perform(get("/safe").param("listPage","true")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].key").doesNotExist());}
 @Test void legacyClientsStillReceiveTheirOriginalArray()throws Exception{mvc.perform(get("/rows")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(205));}
}
