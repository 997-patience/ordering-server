package com.wys.orderingserver;

import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.utils.FileUploadUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import static com.wys.orderingserver.support.UploadTestImages.image;
import static org.junit.jupiter.api.Assertions.*;

/** 真实临时磁盘及图片解码，验证 IO 内容完整、流关闭和失败后的文件清理。 */
class FileUploadUtilTests {
    @TempDir Path temporary;

    @ParameterizedTest
    @CsvSource({"jpg,photo.jpg,jpg", "jpg,PHOTO.JPEG,jpg", "png,照片.PNG,png", "gif,动画.gif,gif"})
    void storesActualSupportedImageWithUuidNameAndOriginalBytes(String format, String name, String suffix) throws Exception {
        Path root = root();
        byte[] bytes = image(format, 2, 2);
        String url = uploader().uploadImage(file(name, bytes));
        assertTrue(url.matches("/upload/[0-9a-f]{32}\\." + suffix));
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve(filename(url))));
        assertEquals(1, files(root).size());
        assertTrue(files(root).stream().noneMatch(path -> path.toString().endsWith(".tmp")));
    }

    @Test
    void octetStreamContentTypeDoesNotOverrideActualImageValidation() throws Exception {
        byte[] bytes = image("png", 2, 2);
        String url = uploader().uploadImage(new MockMultipartFile("file", "photo.png", "application/octet-stream", bytes));
        assertArrayEquals(bytes, Files.readAllBytes(root().resolve(filename(url))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../outside.png", "C:\\fakepath\\outside.png"})
    void originalFilenameCannotChooseDestinationOrEscapeDirectory(String name) throws Exception {
        String url = uploader().uploadImage(file(name, image("png", 2, 2)));
        assertTrue(root().resolve(filename(url)).normalize().startsWith(root()));
        assertFalse(Files.exists(temporary.resolve("outside.png")));
        assertEquals(1, files(root()).size());
    }

    @Test
    void sameOriginalNameDoesNotOverwriteEarlierUpload() throws Exception {
        byte[] firstBytes = image("png", 2, 2);
        byte[] secondBytes = image("png", 3, 3);
        String first = uploader().uploadImage(file("same.png", firstBytes));
        String second = uploader().uploadImage(file("same.png", secondBytes));
        assertNotEquals(first, second);
        assertArrayEquals(firstBytes, Files.readAllBytes(root().resolve(filename(first))));
        assertArrayEquals(secondBytes, Files.readAllBytes(root().resolve(filename(second))));
    }

    @Test
    void nullAndEmptyFileAreRejectedBeforeCreatingDirectory() {
        assertThrows(BizException.class, () -> uploader().uploadImage(null));
        assertThrows(BizException.class, () -> uploader().uploadImage(file("empty.png", new byte[0])));
        assertFalse(Files.exists(root()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void absentFilenameIsRejected(String name) throws Exception {
        assertEquals("图片文件名不能为空", assertThrows(BizException.class,
                () -> uploader().uploadImage(file(name, image("png", 2, 2)))).getMessage());
        assertFalse(Files.exists(root()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "image.svg", "image.txt", "image.png.php", "image."})
    void unsupportedExtensionIsRejectedBeforeWriting(String name) throws Exception {
        assertEquals("仅支持JPG、PNG、GIF格式的图片", assertThrows(BizException.class,
                () -> uploader().uploadImage(file(name, image("png", 2, 2)))).getMessage());
        assertFalse(Files.exists(root()));
    }

    @Test
    void renamedTextIsRejectedAndOnlyItsOwnTemporaryFileIsRemoved() throws Exception {
        Files.createDirectories(root());
        Path sentinel = root().resolve("keep.txt"); Files.writeString(sentinel, "keep existing file");
        assertThrows(BizException.class, () -> uploader().uploadImage(file("fake.png", "not an image".getBytes())));
        assertEquals("keep existing file", Files.readString(sentinel));
        assertEquals(List.of(sentinel), files(root()));
    }

    @Test
    void imageContentAndExtensionMustMatch() throws Exception {
        assertEquals("图片内容与文件扩展名不匹配", assertThrows(BizException.class,
                () -> uploader().uploadImage(file("wrong.jpg", image("png", 2, 2)))).getMessage());
        assertTrue(files(root()).isEmpty());
    }

    @Test
    void truncatedImageIsRejectedAndTemporaryFileIsDeleted() throws Exception {
        byte[] signatureOnly = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        assertThrows(BizException.class, () -> uploader().uploadImage(file("broken.png", signatureOnly)));
        assertTrue(files(root()).isEmpty());
    }

    @Test
    void metadataSizeLimitRejectsBeforeOpeningStream() {
        FileUploadUtil uploader = new FileUploadUtil(root().toString(), "/upload/", "1KB");
        AtomicBoolean opened = new AtomicBoolean();
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", new byte[1025]) {
            @Override public InputStream getInputStream() { opened.set(true); return new ByteArrayInputStream(new byte[0]); }
        };
        assertEquals("图片大小不能超过1KB", assertThrows(BizException.class, () -> uploader.uploadImage(file)).getMessage());
        assertFalse(opened.get()); assertFalse(Files.exists(root()));
    }

    @Test
    void realByteCounterStopsOversizeStreamEvenIfReportedSizeIsWrong() throws Exception {
        byte[] bytes = image("png", 64, 64);
        AtomicBoolean closed = new AtomicBoolean();
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", bytes) {
            @Override public long getSize() { return 1; }
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes) {
                    @Override public void close() throws IOException { closed.set(true); super.close(); }
                };
            }
        };
        FileUploadUtil uploader = new FileUploadUtil(root().toString(), "/upload/", "1KB");
        assertEquals("图片大小不能超过1KB", assertThrows(BizException.class, () -> uploader.uploadImage(file)).getMessage());
        assertTrue(closed.get()); assertTrue(files(root()).isEmpty());
    }

    @Test
    void emptyActualStreamIsRejectedAndClosed() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", image("png", 2, 2)) {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[0]) {
                    @Override public void close() throws IOException { closed.set(true); super.close(); }
                };
            }
        };
        assertEquals("上传图片不能为空", assertThrows(BizException.class, () -> uploader().uploadImage(file)).getMessage());
        assertTrue(closed.get()); assertTrue(files(root()).isEmpty());
    }

    @Test
    void shortReadsWriteOnlyTheActualLengthAndCloseInputStream() throws Exception {
        byte[] bytes = image("png", 128, 128);
        assertTrue(bytes.length > 8192);
        AtomicBoolean closed = new AtomicBoolean(); AtomicInteger reads = new AtomicInteger();
        MockMultipartFile file = new MockMultipartFile("file", "chunks.png", "image/png", bytes) {
            @Override public InputStream getInputStream() {
                return new ByteArrayInputStream(bytes) {
                    @Override public synchronized int read(byte[] buffer, int offset, int length) {
                        reads.incrementAndGet(); return super.read(buffer, offset, Math.min(length, 257));
                    }
                    @Override public void close() throws IOException { closed.set(true); super.close(); }
                };
            }
        };
        String url = uploader().uploadImage(file);
        assertArrayEquals(bytes, Files.readAllBytes(root().resolve(filename(url))));
        assertTrue(reads.get() > 10); assertTrue(closed.get());
    }

    @Test
    void midStreamFailureClosesStreamsAndRemovesPartialFile() throws Exception {
        byte[] bytes = image("png", 2, 2);
        AtomicBoolean closed = new AtomicBoolean();
        MockMultipartFile file = new MockMultipartFile("file", "broken.png", "image/png", bytes) {
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    private boolean first = true;
                    @Override public int read() throws IOException { throw new IOException("模拟读取失败"); }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (!first) throw new IOException("模拟复制中断");
                        first = false; System.arraycopy(bytes, 0, buffer, offset, 8); return 8;
                    }
                    @Override public void close() { closed.set(true); }
                };
            }
        };
        assertEquals("图片上传失败，请稍后重试", assertThrows(BizException.class, () -> uploader().uploadImage(file)).getMessage());
        assertTrue(closed.get()); assertTrue(files(root()).isEmpty());
    }

    @Test
    void directoryCreationFailureKeepsExistingFileAndDoesNotExposePath() throws Exception {
        Path blocked = temporary.resolve("blocked"); Files.writeString(blocked, "existing data");
        FileUploadUtil uploader = new FileUploadUtil(blocked.toString(), "/upload/", "5MB");
        BizException error = assertThrows(BizException.class, () -> uploader.uploadImage(file("photo.png", image("png", 2, 2))));
        assertEquals("图片上传失败，请稍后重试", error.getMessage());
        assertFalse(error.getMessage().contains(blocked.toString()));
        assertEquals("existing data", Files.readString(blocked));
    }

    @Test
    void hugeDeclaredDimensionsAreRejectedBeforePixelAllocation() throws Exception {
        byte[] bytes = image("png", 2, 2);
        ByteBuffer.wrap(bytes).putInt(16, 20000).putInt(20, 20000);
        CRC32 crc = new CRC32(); crc.update(bytes, 12, 17);
        ByteBuffer.wrap(bytes).putInt(29, (int) crc.getValue());
        assertEquals("图片尺寸过大，请使用2000万像素以内的图片", assertThrows(BizException.class,
                () -> uploader().uploadImage(file("huge.png", bytes))).getMessage());
        assertTrue(files(root()).isEmpty());
    }

    @Test
    void directoryUriHandlesSpacesChineseAndMissingTrailingSlash() throws Exception {
        FileUploadUtil uploader = new FileUploadUtil(root().toString(), "pictures/menu", "5MB");
        assertEquals("/pictures/menu/", uploader.getUrlPrefix());
        assertTrue(uploader.getResourceLocation().endsWith("/"));
        assertEquals(root(), Path.of(URI.create(uploader.getResourceLocation())));
        assertTrue(uploader.uploadImage(file("photo.png", image("png", 2, 2))).startsWith("/pictures/menu/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "../", "/admin/", "/app/images/", "/upload/**", "/upload/?x"})
    void invalidPrefixCannotExposeProtectedApiPaths(String prefix) {
        assertThrows(IllegalArgumentException.class, () -> new FileUploadUtil(root().toString(), prefix, "5MB"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0B", "-1KB", "not-a-size"})
    void invalidSizeConfigurationFailsClearly(String size) {
        assertThrows(IllegalArgumentException.class, () -> new FileUploadUtil(root().toString(), "/upload/", size));
    }

    @Test
    void concurrentSameNameUploadsHaveIndependentFiles() throws Exception {
        byte[] bytes = image("png", 2, 2);
        FileUploadUtil uploader = uploader();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) tasks.add(pool.submit(() -> uploader.uploadImage(file("same.png", bytes))));
            List<String> urls = new ArrayList<>();
            for (Future<String> task : tasks) urls.add(task.get(5, TimeUnit.SECONDS));
            assertEquals(6, urls.stream().distinct().count());
            assertEquals(6, files(root()).size());
            for (String url : urls) assertArrayEquals(bytes, Files.readAllBytes(root().resolve(filename(url))));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }

    private Path root() { return temporary.resolve("图片 uploads").toAbsolutePath().normalize(); }
    private FileUploadUtil uploader() { return new FileUploadUtil(root().toString(), "/upload/", "5MB"); }
    private MockMultipartFile file(String name, byte[] bytes) { return new MockMultipartFile("file", name, "application/octet-stream", bytes); }
    private String filename(String url) { return url.substring(url.lastIndexOf('/') + 1); }
    private List<Path> files(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (var paths = Files.list(root)) { return paths.sorted().toList(); }
    }
}
