package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class OtherServiceImageStorageService {
    public static final String PUBLIC_URL_PREFIX = "/api/public/other-service-images/";
    private static final Pattern SAFE_FILE_NAME = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");
    private static final Map<String, MediaType> MEDIA_TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG,
            "png", MediaType.IMAGE_PNG,
            "webp", MediaType.parseMediaType("image/webp"));

    private final Path storageDirectory;
    private final long maxBytes;

    public OtherServiceImageStorageService(
            @Value("${storage.other-service-images.directory:data/uploads/other-services}") String directory,
            @Value("${storage.other-service-images.max-bytes:5242880}") long maxBytes) {
        this.storageDirectory = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = Math.max(1, maxBytes);
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw badRequest("Image file is required");
        }
        if (file.getSize() > maxBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image file must not exceed " + maxBytes + " bytes");
        }
        try {
            byte[] bytes = file.getBytes();
            String extension = detectExtension(bytes);
            Files.createDirectories(storageDirectory);
            String fileName = UUID.randomUUID().toString().toLowerCase(Locale.ROOT) + "." + extension;
            Path target = safeTarget(fileName);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return PUBLIC_URL_PREFIX + fileName;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Image file could not be stored", exception);
        }
    }

    public StoredImage load(String fileName) {
        if (fileName == null || !SAFE_FILE_NAME.matcher(fileName).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        }
        Path target = safeTarget(fileName);
        if (!Files.isRegularFile(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found");
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return new StoredImage(new FileSystemResource(target), MEDIA_TYPES.get(extension));
    }

    public static boolean isManagedImageUrl(String value) {
        if (value == null || !value.startsWith(PUBLIC_URL_PREFIX)) return false;
        String fileName = value.substring(PUBLIC_URL_PREFIX.length());
        return SAFE_FILE_NAME.matcher(fileName).matches();
    }

    private Path safeTarget(String fileName) {
        Path target = storageDirectory.resolve(fileName).normalize();
        if (!target.startsWith(storageDirectory)) {
            throw badRequest("Invalid image file name");
        }
        return target;
    }

    private String detectExtension(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff
                && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return "jpg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a) {
            return "png";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        throw badRequest("Only JPEG, PNG, and WebP images are supported");
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record StoredImage(Resource resource, MediaType mediaType) {
    }
}
