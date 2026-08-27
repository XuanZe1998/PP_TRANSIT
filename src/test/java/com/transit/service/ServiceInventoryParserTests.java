package com.transit.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceInventoryParserTests {

    @Test
    void recognizesAllSupportedSeparatorsAndRemovesDuplicates() {
        assertThat(ServiceCommerceService.parseInventoryItems(
                "CARD-1,CARD-2，CARD-3、CARD-4\nCARD-5\tCARD-6 CARD-1"))
                .containsExactly("CARD-1", "CARD-2", "CARD-3", "CARD-4", "CARD-5", "CARD-6");
    }

    @Test
    void ignoresEmptyInputAndRepeatedSeparators() {
        assertThat(ServiceCommerceService.parseInventoryItems(" ,，、 \r\n\t ")).isEmpty();
        assertThat(ServiceCommerceService.parseInventoryItems(null)).isEmpty();
    }
}
