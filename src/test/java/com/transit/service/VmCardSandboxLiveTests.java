package com.transit.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transit.model.VmCardProductCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("live")
@EnabledIfSystemProperty(named = "vmcard.sandbox.live", matches = "true")
class VmCardSandboxLiveTests {
    private ObjectMapper objectMapper;
    private VmCardClientService client;

    @BeforeEach
    void setUp() throws Exception {
        PropertySource<?> local = new YamlPropertySourceLoader()
                .load("vmcard-live", new FileSystemResource("config/application-local-1.yaml"))
                .get(0);
        objectMapper = new ObjectMapper();
        VmCardProductCodeService productCodeService = mock(VmCardProductCodeService.class);
        when(productCodeService.findUsable(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            String requested = invocation.getArgument(1);
            if (requested == null || requested.isBlank()) return Optional.empty();
            return Optional.of(VmCardProductCode.builder()
                    .environment("sandbox")
                    .productCode(requested)
                    .remainingOpenCardNum(1)
                    .available(true)
                    .build());
        });
        client = new VmCardClientService(
                WebClient.builder().build(),
                objectMapper,
                new VmCardCryptoService(),
                mock(VmCardSavedCardService.class),
                productCodeService);
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "environment", "sandbox");
        ReflectionTestUtils.setField(client, "appId", required(local, "vmcard.app-id"));
        ReflectionTestUtils.setField(client, "appSecret", required(local, "vmcard.app-secret"));
        ReflectionTestUtils.setField(client, "publicKey", required(local, "vmcard.public-key"));
        ReflectionTestUtils.setField(client, "privateKey", required(local, "vmcard.private-key"));
        ReflectionTestUtils.setField(client, "allowMutations", true);
        ReflectionTestUtils.setField(client, "allowProductionMutations", false);
        ReflectionTestUtils.setField(client, "requestTimeoutSeconds", 30L);
    }

    @Test
    void discoversSandboxAccountAndProductsWithoutExposingSecrets() throws Exception {
        Map<String, Object> token = client.checkToken();
        assertThat(token).containsEntry("ok", true).containsEntry("environment", "sandbox");

        JsonNode balance = response("accountBalance", Map.of());
        JsonNode products = response("productCodes", Map.of());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("token", "PASS");
        report.put("accountBalance", summary(balance));
        report.put("productCodes", productSummary(products));
        System.out.println("VMCARD_SANDBOX_DISCOVERY=" + objectMapper.writeValueAsString(report));
    }

    @Test
    void discoversWhetherAnExistingCreditLineCardCanBeTested() throws Exception {
        JsonNode cardList = response("cardList", Map.of("page", 1, "page_size", 100));
        int shareCards = 0;
        int saveCards = 0;
        for (JsonNode card : cardList.path("data").path("list")) {
            if ("share".equalsIgnoreCase(card.path("card_type").asText())) shareCards++;
            if ("save".equalsIgnoreCase(card.path("card_type").asText())) saveCards++;
        }
        System.out.println("VMCARD_SANDBOX_CARD_TYPES=" + objectMapper.writeValueAsString(Map.of(
                "shareCards", shareCards,
                "saveCards", saveCards,
                "total", cardList.path("data").path("total").asInt(-1))));
    }

    @Test
    void verifiesCardDetailForAnExistingSandboxCard() throws Exception {
        JsonNode cardList = response("cardList", Map.of("page", 1, "page_size", 100));
        JsonNode firstCard = cardList.path("data").path("list").path(0);
        String cardId = firstCard.path("card_id").asText("");
        assertThat(cardId).as("sandbox card list must contain a card_id").isNotBlank();

        JsonNode detail = response("cardDetail", Map.of("card_id", cardId));
        assertThat(detail.path("data").path("card_id").asText()).isEqualTo(cardId);
        System.out.println("VMCARD_CARD_DETAIL=PASS fields=" + fieldNames(detail.path("data")));
    }

    @Test
    void verifiesPrepaidCardLifecycleAndCleansUpTheTestCard() throws Exception {
        Map<String, String> report = new LinkedHashMap<>();
        String cardId = null;
        try {
            JsonNode products = response("productCodes", Map.of());
            Map<String, Object> createRequest = new LinkedHashMap<>();
            String testSuffix = Long.toString(System.currentTimeMillis(), 36);
            createRequest.put("first_name", "Test");
            createRequest.put("last_name", "User");
            createRequest.put("amount", 20);
            createRequest.put("single_limit", 0);
            createRequest.put("day_limit", 0);
            createRequest.put("month_limit", 0);
            createRequest.put("label", "codex-" + testSuffix);
            createRequest.put("card_address", Map.of(
                    "address_line_one", "123 Test Street",
                    "address_line_two", "",
                    "city", "New York",
                    "state", "NY",
                    "country", "US",
                    "post_code", "10001"));
            createRequest.put("area_code", "+1");
            createRequest.put("mobile", "2025550100");
            createRequest.put("email", "sandbox." + testSuffix + "@example.com");
            JsonNode create = null;
            List<String> createFailures = new java.util.ArrayList<>();
            for (JsonNode product : products.path("data").path("list")) {
                if (!"save".equalsIgnoreCase(product.path("type").asText())
                        || product.path("remaining_open_card_num").asInt(0) <= 0) continue;
                String productCode = product.path("product_code").asText("");
                createRequest.put("product_code", productCode);
                JsonNode attempt;
                try {
                    attempt = rawResponse("createCard", createRequest);
                } catch (ResponseStatusException exception) {
                    createFailures.add(productCode + ":" + exception.getReason());
                    continue;
                }
                if (isSuccess(attempt) && !attempt.path("data").path("card_id").asText("").isBlank()) {
                    create = attempt;
                    break;
                }
                createFailures.add(productCode + ":" + attempt.path("code").asText("missing")
                        + ":" + attempt.path("msg").asText(""));
            }
            assertThat(create)
                    .withFailMessage("No prepaid product could create a card: %s", createFailures)
                    .isNotNull();
            cardId = create.path("data").path("card_id").asText("");
            assertThat(cardId).as("createCard response card_id").isNotBlank();
            report.put("createCard", "PASS");

            JsonNode detail = response("cardDetail", Map.of("card_id", cardId));
            assertThat(detail.path("data").path("card_id").asText()).isEqualTo(cardId);
            assertThat(detail.path("data").path("card_type").asText()).isEqualTo("save");
            report.put("cardDetail", "PASS");

            response("freezeCard", Map.of("card_id", cardId, "status", "CANCELLED"));
            report.put("freezeCard", "PASS");
            response("freezeCard", Map.of("card_id", cardId, "status", "ACTIVE"));
            report.put("unfreezeCard", "PASS");

            response("rechargeCard", Map.of("card_id", cardId, "amount", 10));
            report.put("rechargeCard", "PASS");
            response("refundCard", Map.of("card_id", cardId, "amount", 10));
            report.put("refundCard", "PASS");

            response("cardTransactions", Map.of("page", 1, "page_size", 10, "card_id", cardId));
            report.put("cardTransaction", "PASS");

            JsonNode cardList = response("cardList", Map.of("page", 1, "page_size", 10, "card_id", cardId));
            assertThat(cardList.path("data").path("list").isArray()).isTrue();
            report.put("getCardList", "PASS");

            response("cardFlow", Map.of("card_id", cardId, "page", 1, "page_size", 10));
            report.put("getCardFlow", "PASS");
        } finally {
            if (cardId != null && !cardId.isBlank()) {
                deleteWithRetry(cardId);
                report.put("deleteCard", "PASS");
            }
            System.out.println("VMCARD_SANDBOX_PREPAID=" + objectMapper.writeValueAsString(report));
        }
    }

    private JsonNode response(String operation, Map<String, Object> body) {
        JsonNode response = rawResponse(operation, body);
        int code = response.path("code").asInt(Integer.MIN_VALUE);
        assertThat(code)
                .withFailMessage("VMCard %s failed: code=%s msg=%s", operation,
                        response.path("code").asText("missing"), response.path("msg").asText(""))
                .isIn(0, 200);
        return response;
    }

    private JsonNode rawResponse(String operation, Map<String, Object> body) {
        Map<String, Object> result = client.execute(operation, body);
        return (JsonNode) result.get("response");
    }

    private boolean isSuccess(JsonNode response) {
        int code = response.path("code").asInt(Integer.MIN_VALUE);
        return code == 0 || code == 200;
    }

    private void deleteWithRetry(String cardId) throws InterruptedException {
        try {
            response("deleteCard", Map.of("card_id", cardId));
            return;
        } catch (ResponseStatusException exception) {
            if (exception.getReason() == null || !exception.getReason().contains("Abnormal card status")) {
                throw exception;
            }
        }

        try {
            response("freezeCard", Map.of("card_id", cardId, "status", "CANCELLED"));
        } catch (ResponseStatusException exception) {
            if (exception.getReason() == null || !exception.getReason().contains("Card Canceled")) {
                throw exception;
            }
        }
        response("freezeCard", Map.of("card_id", cardId, "status", "ACTIVE"));

        ResponseStatusException lastFailure = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                response("deleteCard", Map.of("card_id", cardId));
                return;
            } catch (ResponseStatusException exception) {
                lastFailure = exception;
                if (exception.getReason() == null || !exception.getReason().contains("Abnormal card status")) {
                    throw exception;
                }
                if (attempt < 4) Thread.sleep(5_000L);
            }
        }
        throw lastFailure;
    }

    private Map<String, Object> summary(JsonNode response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PASS");
        result.put("code", response.path("code").asInt());
        result.put("fields", fieldNames(response.path("data")));
        return result;
    }

    private Map<String, Object> productSummary(JsonNode response) {
        Map<String, Object> result = summary(response);
        List<Map<String, Object>> products = new java.util.ArrayList<>();
        for (JsonNode product : response.path("data").path("list")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productCode", product.path("product_code").asText(""));
            item.put("type", product.path("type").asText(""));
            item.put("remainingOpenCardNum", product.path("remaining_open_card_num").asInt(-1));
            item.put("fields", fieldNames(product));
            products.add(item);
        }
        result.put("products", products);
        return result;
    }

    private List<String> fieldNames(JsonNode node) {
        if (!node.isObject()) return List.of();
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String required(PropertySource<?> source, String name) {
        Object value = source.getProperty(name);
        assertThat(value).as(name + " must be configured").isNotNull();
        String text = String.valueOf(value);
        assertThat(text).as(name + " must be configured").isNotBlank();
        return text;
    }
}
