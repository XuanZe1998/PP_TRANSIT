package com.transit.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DujiaoNextClientTests {
    @Test
    void signsTheExactMethodPathTimestampAndBodyDigest() {
        String signature = DujiaoNextClient.signature("test-secret", "GET",
                "/api/v1/upstream/products", "1700000000", new byte[0]);

        assertThat(signature).isEqualTo("e915cf3fd1b9d663133928b036a0d5e3f69a2ee5895dbae3a54a629a139b5502");
    }
}
