package com.transit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ApiTransitStationApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void convertsPlainApplicationYamlArgumentIntoSpringConfigLocation() {
        String[] normalized = ApiTransitStationApplication.normalizeConfigArguments(new String[]{
                "config/application-local-1.yaml",
                "--server.port=0"
        });

        assertThat(normalized).contains("--server.port=0");
        assertThat(normalized).anySatisfy(argument -> assertThat(argument)
                .startsWith("--spring.config.additional-location=file:")
                .contains("application-local-1.yaml"));
        assertThat(normalized).doesNotContain("config/application-local-1.yaml");
    }

    @Test
    void doesNotRewriteArbitraryYamlOrOverrideExplicitSpringLocation() {
        assertThat(ApiTransitStationApplication.normalizeConfigArguments(new String[]{"other.yaml"}))
                .containsExactly("other.yaml");

        String explicit = "--spring.config.additional-location=file:/safe/application.yaml";
        assertThat(ApiTransitStationApplication.normalizeConfigArguments(new String[]{
                "config/application-local-1.yaml", explicit
        })).containsExactly("config/application-local-1.yaml", explicit);
    }

}
