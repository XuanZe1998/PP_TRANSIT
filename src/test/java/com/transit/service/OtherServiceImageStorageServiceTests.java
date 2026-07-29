package com.transit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtherServiceImageStorageServiceTests {

    @TempDir
    Path tempDirectory;

    @Test
    void storesDetectedImageTypeUnderGeneratedName() {
        OtherServiceImageStorageService service = new OtherServiceImageStorageService(
                tempDirectory.toString(), 5 * 1024 * 1024);
        MockMultipartFile file = new MockMultipartFile("file", "misleading.txt", "text/plain",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01});

        String url = service.store(file);

        assertThat(url).startsWith(OtherServiceImageStorageService.PUBLIC_URL_PREFIX).endsWith(".png");
        String fileName = url.substring(OtherServiceImageStorageService.PUBLIC_URL_PREFIX.length());
        OtherServiceImageStorageService.StoredImage stored = service.load(fileName);
        assertThat(stored.resource().exists()).isTrue();
        assertThat(stored.mediaType().toString()).isEqualTo("image/png");
    }

    @Test
    void rejectsContentThatIsNotAnAllowedImage() {
        OtherServiceImageStorageService service = new OtherServiceImageStorageService(
                tempDirectory.toString(), 5 * 1024 * 1024);
        MockMultipartFile file = new MockMultipartFile("file", "script.svg", "image/svg+xml",
                "<svg><script>alert(1)</script></svg>".getBytes());

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void rejectsFilesOverConfiguredLimit() {
        OtherServiceImageStorageService service = new OtherServiceImageStorageService(
                tempDirectory.toString(), 4);
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});

        assertThatThrownBy(() -> service.store(file))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(413));
    }
}
