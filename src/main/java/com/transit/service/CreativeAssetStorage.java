package com.transit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CreativeAssetStorage {
    public static final String PUBLIC_PREFIX = "/public/creative-assets/";
    private static final Pattern SAFE = Pattern.compile("^[0-9a-f-]{36}\\.(jpg|png|webp|mp4)$");
    private static final Map<String, MediaType> TYPES = Map.of(
            "jpg", MediaType.IMAGE_JPEG, "png", MediaType.IMAGE_PNG,
            "webp", MediaType.parseMediaType("image/webp"), "mp4", MediaType.parseMediaType("video/mp4"));
    private final CreativePlatformConfigService configs;
    private final Path legacyDirectory;
    private final String legacyPublicBaseUrl;
    private final long legacyMaxImageBytes;

    @Autowired
    public CreativeAssetStorage(CreativePlatformConfigService configs) {
        this.configs = configs; this.legacyDirectory = null; this.legacyPublicBaseUrl = ""; this.legacyMaxImageBytes = 10 * 1024 * 1024L;
    }

    /** Kept for isolated storage tests. Runtime writes always use the database-backed S3 configuration. */
    public CreativeAssetStorage(String directory, String publicBaseUrl, long maxImageBytes) {
        this.configs = null; this.legacyDirectory = Path.of(directory).toAbsolutePath().normalize();
        this.legacyPublicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
        this.legacyMaxImageBytes = Math.max(1, maxImageBytes);
    }

    public String storeImage(MultipartFile file) { return storeImage(0, 0, file); }
    public String storeImage(long userId, long projectId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw badRequest("图片文件不能为空");
        if (file.getSize() > maxImageBytes()) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "图片超过后台配置的大小限制");
        try { return storeImage(userId, projectId, file.getBytes()); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "图片读取失败", e); }
    }
    public String storeImage(byte[] bytes) { return storeImage(0, 0, bytes); }
    public String storeImage(long userId, long projectId, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > maxImageBytes()) throw badRequest("图片内容无效或超过限制");
        return store(userId, projectId, bytes, detectImage(bytes));
    }
    public String storeVideo(Path source) { return storeVideo(0, 0, source); }
    public String storeVideo(long userId, long projectId, Path source) {
        try { return store(userId, projectId, Files.readAllBytes(source), "mp4"); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "视频保存失败", e); }
    }

    public Map<String, Object> testConnection() {
        CreativePlatformConfigService.StorageConfig c = requireS3();
        try (S3Client client = client(c)) {
            client.headBucket(HeadBucketRequest.builder().bucket(c.bucket()).build());
            return Map.of("ok", true, "message", "S3 Bucket 连接成功", "bucket", c.bucket());
        } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "S3 连接测试失败：" + safeMessage(e), e); }
    }
    public Map<String, Object> diagnostics() {
        if (configs == null) return Map.of("configured", false, "mode", "LEGACY_TEST");
        CreativePlatformConfigService.StorageConfig c = configs.storage(false);
        boolean complete = c.enabled() && StringUtils.hasText(c.endpoint()) && StringUtils.hasText(c.bucket())
                && StringUtils.hasText(c.accessKey()) && StringUtils.hasText(c.secretKey());
        return Map.of("configured", complete, "enabled", c.enabled(), "type", "S3",
                "httpsPublicUrl", StringUtils.hasText(c.publicBaseUrl()) && c.publicBaseUrl().startsWith("https://"));
    }

    public StoredAsset load(String fileName) {
        if (legacyDirectory == null || fileName == null || !SAFE.matcher(fileName).matches()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在");
        Path target = safeLegacy(fileName);
        if (!Files.isRegularFile(target)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "素材不存在");
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return new StoredAsset(new FileSystemResource(target), TYPES.get(extension));
    }

    private String store(long userId, long projectId, byte[] bytes, String extension) {
        if (configs == null) return storeLegacy(bytes, extension);
        CreativePlatformConfigService.StorageConfig c = requireS3();
        String prefix = "users/" + Math.max(0, userId) + "/projects/" + Math.max(0, projectId) + "/";
        String fileName = UUID.randomUUID().toString().toLowerCase(Locale.ROOT) + "." + extension;
        String temporaryKey = "tmp/" + prefix + fileName, finalKey = prefix + fileName;
        String contentType = TYPES.get(extension).toString();
        try (S3Client client = client(c)) {
            client.putObject(PutObjectRequest.builder().bucket(c.bucket()).key(temporaryKey).contentType(contentType).build(), RequestBody.fromBytes(bytes));
            try {
                client.copyObject(CopyObjectRequest.builder().bucket(c.bucket()).copySource(c.bucket() + "/" + temporaryKey)
                        .key(finalKey).contentType(contentType).metadataDirective(MetadataDirective.REPLACE).build());
            } finally { client.deleteObject(DeleteObjectRequest.builder().bucket(c.bucket()).key(temporaryKey).build()); }
            return url(c, finalKey);
        } catch (RuntimeException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "素材写入 S3 失败：" + safeMessage(e), e); }
    }

    private String url(CreativePlatformConfigService.StorageConfig c, String key) {
        if (StringUtils.hasText(c.publicBaseUrl())) return c.publicBaseUrl().replaceAll("/+$", "") + "/" + key;
        try (S3Presigner presigner = presigner(c)) {
            GetObjectRequest get = GetObjectRequest.builder().bucket(c.bucket()).key(key).build();
            return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofSeconds(c.signedUrlSeconds())).getObjectRequest(get).build()).url().toString();
        }
    }
    private CreativePlatformConfigService.StorageConfig requireS3() {
        CreativePlatformConfigService.StorageConfig c = configs.storage(true);
        if (!c.enabled() || !StringUtils.hasText(c.endpoint()) || !StringUtils.hasText(c.region()) || !StringUtils.hasText(c.bucket())
                || !StringUtils.hasText(c.accessKey()) || !StringUtils.hasText(c.secretKey())) throw new ResponseStatusException(HttpStatus.CONFLICT, "S3 素材存储尚未完整配置");
        return c;
    }
    private S3Client client(CreativePlatformConfigService.StorageConfig c) {
        return S3Client.builder().endpointOverride(URI.create(c.endpoint())).region(Region.of(c.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(c.pathStyle()).build()).build();
    }
    private S3Presigner presigner(CreativePlatformConfigService.StorageConfig c) {
        return S3Presigner.builder().endpointOverride(URI.create(c.endpoint())).region(Region.of(c.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(c.accessKey(), c.secretKey())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(c.pathStyle()).build()).build();
    }
    private long maxImageBytes() { if (configs == null) return legacyMaxImageBytes; Object v=configs.settings().get("maxImageBytes"); return v instanceof Number n?n.longValue():10*1024*1024L; }
    private String storeLegacy(byte[] bytes, String extension) {
        try { Files.createDirectories(legacyDirectory); String name=UUID.randomUUID().toString().toLowerCase(Locale.ROOT)+"."+extension;
            Files.write(safeLegacy(name),bytes,StandardOpenOption.CREATE_NEW); String path=PUBLIC_PREFIX+name; return legacyPublicBaseUrl.isBlank()?path:legacyPublicBaseUrl+path;
        } catch(IOException e){ throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"素材保存失败",e); }
    }
    private Path safeLegacy(String name) { Path target=legacyDirectory.resolve(name).normalize(); if(!target.startsWith(legacyDirectory)) throw badRequest("素材文件名无效"); return target; }
    private String detectImage(byte[] b) {
        if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "jpg";
        if(b.length>=8&&(b[0]&255)==137&&b[1]==80&&b[2]==78&&b[3]==71)return "png";
        if(b.length>=12&&b[0]=='R'&&b[1]=='I'&&b[2]=='F'&&b[3]=='F'&&b[8]=='W'&&b[9]=='E'&&b[10]=='B'&&b[11]=='P')return "webp";
        throw badRequest("只支持 JPEG、PNG、WebP 图片");
    }
    private String safeMessage(Throwable e){String v=e.getMessage();return v==null||v.isBlank()?e.getClass().getSimpleName():v.substring(0,Math.min(300,v.length()));}
    private ResponseStatusException badRequest(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    public record StoredAsset(Resource resource, MediaType mediaType) { }
}
