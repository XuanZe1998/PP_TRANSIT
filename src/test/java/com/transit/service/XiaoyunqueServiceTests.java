package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class XiaoyunqueServiceTests {
    JdbcTemplate jdbc;
    XiaoyunqueService service;
    ObjectMapper json = new ObjectMapper();
    ChannelSecretService secrets = new ChannelSecretService("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    List<String> authorization = new ArrayList<>();
    AtomicInteger calls = new AtomicInteger();
    String response;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V38__xiaoyunque_marketing.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet(); authorization.add(request.headers().getFirst("Authorization"));
            assertThat(request.url().getHost()).isEqualTo("xyq.jianying.com");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(response).build());
        }).build();
        service = new XiaoyunqueService(jdbc, secrets, json, client);
    }
    User user(long id) { User user = new User(); user.setId(id); return user; }
    com.fasterxml.jackson.databind.JsonNode request() throws Exception {
        return json.readTree("""
                {"message":"商品广告","asset_ids":[],"general_agent_settings":{"video_model":"Seedance_2.0_mini","ratio":3,"duration_start":15,"duration_end":30,"show_subtitle":false,"video_resolution":"720p"}}
                """);
    }
    void save() { service.saveKey(user(1), "private-access-key"); }

    @Test void keysAreEncryptedAndIsolatedAndZeroBalanceIsPreserved() {
        save();
        assertThat(jdbc.queryForObject("SELECT access_key FROM creative_xyq_credentials WHERE user_id=1",String.class)).startsWith("enc:v1:").doesNotContain("private-access-key");
        assertThat(service.settings(user(1))).containsExactlyEntriesOf(Map.of("configured",true));
        assertThat(service.settings(user(2))).containsEntry("configured",false);
        assertThatThrownBy(()->service.balance(user(2))).isInstanceOf(ResponseStatusException.class);
        response="{\"ret\":\"0\",\"data\":{\"total_remain_amount\":\"0\"}}";
        assertThat(service.balance(user(1)).path("total_remain_amount").asText()).isEqualTo("0");
        assertThat(authorization).containsExactly("Bearer private-access-key");
    }
    @Test void taskOwnershipAndCredentialSnapshotsSurviveKeyReplacement() throws Exception {
        save(); response="{\"ret\":0,\"data\":{\"run\":{\"thread_id\":\"thread-1\",\"run_id\":\"run-1\",\"state\":1}}}";
        var submitted=service.submit(user(1),request()); String id=(String)submitted.get("id");
        assertThat(submitted).doesNotContainKeys("access_key","user_id");
        assertThatThrownBy(()->service.refresh(user(2),id)).isInstanceOf(ResponseStatusException.class);
        service.saveKey(user(1),"replacement-key");
        response="{\"ret\":0,\"data\":{\"run_state\":3,\"video_urls\":[\"https://cdn.example.com/a.mp4\",\"javascript:alert(1)\"],\"api_key\":\"SHOULD_NOT_BE_EXPOSED\"}}";
        var completed=service.refresh(user(1),id);
        assertThat(authorization).containsExactly("Bearer private-access-key","Bearer private-access-key");
        assertThat(completed.toString()).doesNotContain("SHOULD_NOT_BE_EXPOSED","private-access-key","replacement-key");
        assertThat(service.media(user(1),id,1,10,false).get("total")).isEqualTo(1);
        assertThatThrownBy(()->service.media(user(2),id,1,10,false)).isInstanceOf(ResponseStatusException.class);
        service.deleteKey(user(1));
        service.refresh(user(1),id);
        assertThat(service.settings(user(1))).containsEntry("configured",false);
    }
    @Test void rejectsInvalidSettingsAndForeignAssetsBeforeSpendingCredits() throws Exception {
        save(); var input=(com.fasterxml.jackson.databind.node.ObjectNode)request();
        input.putArray("asset_ids").add("foreign-asset");
        assertThatThrownBy(()->service.submit(user(1),input)).isInstanceOf(ResponseStatusException.class);
        input.putArray("asset_ids");
        ((com.fasterxml.jackson.databind.node.ObjectNode)input.get("general_agent_settings")).put("duration_start",1.5);
        assertThatThrownBy(()->service.submit(user(1),input)).isInstanceOf(ResponseStatusException.class);
        assertThat(calls.get()).isZero();
    }
    @Test void businessFailureIsNotMistakenForHttpSuccessAndDoesNotRetry() throws Exception {
        save();response="{\"ret\":2,\"errmsg\":\"private-access-key\"}";
        assertThatThrownBy(()->service.submit(user(1),request())).isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("private-access-key");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM creative_xyq_runs",String.class)).isEqualTo("UNKNOWN");
    }
    @Test void listsCountAndPageUnderTheSameUserAndSearchAndLimitAll() {
        for(int i=0;i<205;i++)jdbc.update("INSERT INTO creative_xyq_runs(id,user_id,access_key,message,state) VALUES (?,?,?,?,?)","run-"+i,1,"encrypted",i<11?"needle":"other","1");
        jdbc.update("INSERT INTO creative_xyq_runs(id,user_id,access_key,message,state) VALUES ('foreign',2,'encrypted','needle','1')");
        var first=service.list(user(1),1,10,false,"needle");
        assertThat(first.get("total")).isEqualTo(11L);
        assertThat((List<?>)first.get("items")).hasSize(10);
        assertThat((List<?>)service.list(user(1),2,10,false,"needle").get("items")).hasSize(1);
        assertThatThrownBy(()->service.list(user(1),1,10,true,"")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.list(user(1),1,200,false,"")).isInstanceOf(ResponseStatusException.class);
        assertThat(service.list(user(2),1,10,false,"").get("total")).isEqualTo(1L);
    }
    @Test void uploadReturnsOnlyAssetIdentity() {
        save();response="{\"ret\":0,\"data\":{\"pippit_asset_id\":\"asset-1\"}}";
        assertThat(service.upload(user(1),new MockMultipartFile("file","photo.png","image/png",new byte[]{1,2}))).containsEntry("id","asset-1");
        assertThatThrownBy(()->service.upload(user(1),new MockMultipartFile("file",new byte[0]))).isInstanceOf(ResponseStatusException.class);
    }
}
