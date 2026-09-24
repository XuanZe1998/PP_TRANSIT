package com.transit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionServiceClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void signatureUsesAsciiKeyOrderAndRecursivelySortedObjects() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("timestamp", "1710000000");
        request.put("ids", List.of(3, 1));
        request.put("filters", Map.of("b", 2, "a", 1));
        request.put("goods_no", "G1");
        request.put("app_id", "app");
        request.put("sign", "ignored");

        assertThat(SubscriptionServiceClient.signature(request, "secret", objectMapper))
                .isEqualTo("429ff7ff9b83cb455f69b75c91b8d9ba");
    }

    @Test
    void operationCatalogCoversEveryDocumentedEndpoint() {
        assertThat(SubscriptionServiceOperation.values()).hasSize(43);
        assertThat(java.util.Arrays.stream(SubscriptionServiceOperation.values())
                .map(SubscriptionServiceOperation::path)).doesNotHaveDuplicates();
    }
}
