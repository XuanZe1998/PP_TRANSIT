package com.transit.service;

import com.transit.model.VmCardProductCode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
@SpringBootTest
@EnabledIfSystemProperty(named = "vmcard.database.live", matches = "true")
class VmCardProductCodeLiveDatabaseTests {
    @Autowired
    private VmCardClientService clientService;

    @Autowired
    private VmCardProductCodeService productCodeService;

    @Autowired
    private DataSource dataSource;

    @Test
    void synchronizesProviderProductsIntoTheConfiguredMysqlDatabase() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:mysql:");
        }

        Map<String, Object> result = clientService.execute("productCodes", Map.of());
        List<Map<String, Object>> products = productCodeService.list(clientService.currentEnvironment());

        assertThat(result.get("synchronizedProducts")).isEqualTo(products.size());
        assertThat(products).isNotEmpty().allSatisfy(product -> {
            assertThat(product)
                    .containsKeys("bin", "productCode", "type", "network", "media",
                            "issuingArea", "remainingOpenCardNum", "available", "usable");
            assertThat(product.get("productCode")).isNotEqualTo("");
        });
        VmCardProductCode selected = productCodeService
                .findUsable(clientService.currentEnvironment(), "")
                .orElseThrow();
        assertThat(selected.getAvailable()).isTrue();
        assertThat(selected.getRemainingOpenCardNum()).isPositive();
        System.out.println("VMCARD_PRODUCT_DATABASE_SYNC=PASS count=" + products.size()
                + " selected=" + selected.getProductCode());
    }
}
