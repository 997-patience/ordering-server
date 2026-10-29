package com.wys.orderingserver.utils;

import com.wys.orderingserver.Exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 本地图片存储：分块复制、内容校验、UUID 命名和失败清理。 */
@Component
public class FileUploadUtil {
    private static final Logger log = LoggerFactory.getLogger(FileUploadUtil.class);
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif");
    private static final long MAX_PIXELS = 20_000_000;
    private final Path uploadDir;
    private final String urlPrefix;
    private final long maxBytes;
    private final String sizeLimit;

    public FileUploadUtil(@Value("${ordering.upload.dir}") String directory,
                          @Value("${ordering.upload.url-prefix:/upload/}") String prefix,
                          @Value("${spring.servlet.multipart.max-file-size:5MB}") String maxFileSize) {
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("上传目录不能为空");
        }
        uploadDir = Path.of(directory.strip()).toAbsolutePath().normalize();
        String normalized = prefix == null ? "" : prefix.strip();
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (!normalized.endsWith("/")) normalized += "/";
        if (!normalized.matches("/(?:[A-Za-z0-9_-]+/)+")
                || normalized.startsWith("/admin/") || normalized.startsWith("/app/")) {
            throw new IllegalArgumentException("图片访问前缀必须是独立路径，例如 /upload/");
        }
        urlPrefix = normalized;
        sizeLimit = maxFileSize.strip().toUpperCase(Locale.ROOT);
        maxBytes = DataSize.parse(sizeLimit).toBytes();
        if (maxBytes < 1) throw new IllegalArgumentException("图片大小上限必须大于0");
    }

    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("上传图片不能为空");
        if (file.getSize() > maxBytes) throw tooLarge();
        String suffix = suffix(file.getOriginalFilename());
        Path temporary = null;
        try {
            Files.createDirectories(uploadDir);
            // 先写临时文件，确认复制完整且确为图片，再生成可返回给前端的正式文件。
            temporary = Files.createTempFile(uploadDir, "upload-", ".tmp");
            copyToTemporaryFile(file, temporary);
            validateImage(temporary, suffix);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + suffix;
            Path target = uploadDir.resolve(filename);
            Files.move(temporary, target); // 不使用 REPLACE_EXISTING，不覆盖已有文件。
            temporary = null;
            return urlPrefix + filename;
        } catch (IOException e) {
            log.error("图片保存失败", e);
            throw new BizException("图片上传失败，请稍后重试", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.error("清理上传临时文件失败：{}", temporary, e);
                }
            }
        }
    }

    private void copyToTemporaryFile(MultipartFile file, Path target) throws IOException {
        // 输入流读取上传内容，输出流写入磁盘；离开 try 后两个流都会关闭。
        try (InputStream input = file.getInputStream();
             OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int length;
            while ((length = input.read(buffer)) != -1) {
                total += length;
                if (total > maxBytes) throw tooLarge(); // 同时限制真正读到的字节数。
                output.write(buffer, 0, length);
            }
            if (total == 0) throw new BizException("上传图片不能为空");
        }
    }

    private void validateImage(Path file, String suffix) {
        try (ImageInputStream input = new FileImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BizException("文件内容不是有效的JPG、PNG或GIF图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (format.equals("jpeg")) format = "jpg";
                if (!suffix.equals(format)) throw new BizException("图片内容与文件扩展名不匹配");
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
                    throw new BizException("图片尺寸过大，请使用2000万像素以内的图片");
                }
                // 验证首帧可解码；原文件不重新编码，保留 GIF 原始内容。
                BufferedImage image = reader.read(0);
                if (image == null) throw new BizException("图片内容损坏或格式不正确");
                image.flush();
            } finally {
                reader.dispose();
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BizException("图片内容损坏或格式不正确", e);
        }
    }

    private String suffix(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BizException("图片文件名不能为空");
        }
        String name = originalFilename.strip();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXTENSIONS.contains(extension)) throw new BizException("仅支持JPG、PNG、GIF格式的图片");
        return extension.equals("jpeg") ? "jpg" : extension;
    }

    private BizException tooLarge() {
        return new BizException("图片大小不能超过" + sizeLimit);
    }

    public String getUrlPrefix() {
        return urlPrefix;
    }

    public String getResourceLocation() {
        String location = uploadDir.toUri().toString();
        return location.endsWith("/") ? location : location + "/";
    }
}
