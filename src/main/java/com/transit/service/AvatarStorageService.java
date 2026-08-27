package com.transit.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@Service
public class AvatarStorageService {
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/webp");
    private final Path root;

    public AvatarStorageService(@Value("${uploads.avatars-directory:data/avatars}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
        try { Files.createDirectories(root); } catch (Exception e) { throw new IllegalStateException("无法创建头像存储目录", e); }
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES || !ALLOWED.contains(String.valueOf(file.getContentType()).toLowerCase()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像只支持 2MB 以内的 JPEG、PNG 或 WebP");
        try (InputStream input = file.getInputStream()) {
            return reencode(input);
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法处理头像", e); }
    }

    public String storeRemote(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第三方头像过大");
        try (InputStream input = new ByteArrayInputStream(bytes)) { return reencode(input); }
        catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法处理头像", e); }
    }

    private String reencode(InputStream input) {
        try {
            BufferedImage source = ImageIO.read(input);
            if (source == null || source.getWidth() < 1 || source.getHeight() < 1 || source.getWidth() > 8192 || source.getHeight() > 8192)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片内容无效");
            BufferedImage output = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale = Math.max(256d / source.getWidth(), 256d / source.getHeight());
            int width = (int)Math.ceil(source.getWidth() * scale), height = (int)Math.ceil(source.getHeight() * scale);
            graphics.drawImage(source, (256 - width) / 2, (256 - height) / 2, width, height, null); graphics.dispose();
            String name = UUID.randomUUID().toString().replace("-", "") + ".png";
            if (!ImageIO.write(output, "png", root.resolve(name).toFile())) throw new IllegalStateException("头像编码失败");
            return "/api/public/avatars/" + name;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法处理头像", e); }
    }

    public Resource load(String name) {
        if (name == null || !name.matches("[a-f0-9]{32}\\.png")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try { Path file=root.resolve(name).normalize(); if(!file.startsWith(root)||!Files.isRegularFile(file))throw new ResponseStatusException(HttpStatus.NOT_FOUND); return new UrlResource(file.toUri()); }
        catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND);}
    }
}
