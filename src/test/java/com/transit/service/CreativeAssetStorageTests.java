package com.transit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreativeAssetStorageTests {
    @TempDir Path directory;

    @Test
    void storesAndLoadsVerifiedPng() throws Exception {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
        CreativeAssetStorage storage = new CreativeAssetStorage(directory.toString(), "https://cdn.example.test", 1024);

        String url = storage.storeImage(new MockMultipartFile("file", "portrait.png", "image/png", png));
        String name = url.substring(url.lastIndexOf('/') + 1);

        assertThat(url).startsWith("https://cdn.example.test/public/creative-assets/");
        assertThat(storage.load(name).resource().contentLength()).isEqualTo(png.length);
    }

    @Test
    void rejectsSpoofedOrOversizedImages() {
        CreativeAssetStorage storage = new CreativeAssetStorage(directory.toString(), "", 8);

        assertThatThrownBy(() -> storage.storeImage(new byte[]{1, 2, 3}))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> storage.storeImage(new byte[9]))
                .isInstanceOf(ResponseStatusException.class);
    }
}
