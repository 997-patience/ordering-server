package com.wys.orderingserver;

import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.wys.orderingserver.support.UploadTestImages.image;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MockMvc 验证控制器，随机端口 Tomcat 验证真实 multipart 解析和静态图片访问。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:file-upload-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:user-login-test.sql"
})
@AutoConfigureMockMvc
class FileUploadIntegrationTests {
    @TempDir static Path temporary;
    @LocalServerPort private int port;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void uploadProperties(DynamicPropertyRegistry registry) {
        registry.add("ordering.upload.dir", () -> temporary.resolve("图片 uploads").toString());
        registry.add("ordering.upload.url-prefix", () -> "/upload/");
    }

    @AfterEach
    void publicUploadAndImageRequestsDoNotUseRedis() {
        assertNull(ThreadLocalUtil.get());
        verifyNoInteractions(redisTemplate);
    }

    @ParameterizedTest
    @CsvSource({"png,png,image/png", "jpg,JPEG,image/jpeg", "gif,gif,image/gif"})
    void anonymousUploadReturnsDirectlyReadableImageUrl(String format, String extension, String contentType) throws Exception {
        byte[] bytes = image(format, 3, 3);
        String response = mvc.perform(multipart("/admin/upload")
                        .file(new MockMultipartFile("file", "照片." + extension, contentType, bytes)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").isString()).andReturn().getResponse().getContentAsString();
        String url = (String) json(response).get("data");
        assertTrue(url.matches("/upload/[0-9a-f]{32}\\.(jpg|png|gif)"));
        assertArrayEquals(bytes, Files.readAllBytes(root().resolve(filename(url))));
        mvc.perform(get(url)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(contentType))
                .andExpect(content().bytes(bytes));
        mvc.perform(get(url).header("Authorization", "invalid-token"))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
    }

    @Test
    void missingFileAndWrongFieldNameHaveReadableErrors() throws Exception {
        mvc.perform(multipart("/admin/upload"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传文件：file"));
        mvc.perform(multipart("/admin/upload").file(new MockMultipartFile("image", "photo.png", "image/png", image("png", 2, 2))))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("缺少必传文件：file"));
    }

    @Test
    void emptyFileHasBusinessError() throws Exception {
        mvc.perform(multipart("/admin/upload").file(new MockMultipartFile("file", "empty.png", "image/png", new byte[0])))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("上传图片不能为空"));
    }

    @Test
    void multipleFilesUnderSameFieldOrDifferentFieldsAreRejected() throws Exception {
        Set<String> before = storedNames(); byte[] bytes = image("png", 2, 2);
        mvc.perform(multipart("/admin/upload").file(new MockMultipartFile("file", "one.png", "image/png", bytes))
                        .file(new MockMultipartFile("file", "two.png", "image/png", bytes)))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("每次只能上传一张图片"));
        mvc.perform(multipart("/admin/upload").file(new MockMultipartFile("file", "one.png", "image/png", bytes))
                        .file(new MockMultipartFile("other", "two.png", "image/png", bytes)))
                .andExpect(jsonPath("$.code").value(1));
        assertEquals(before, storedNames());
    }

    @Test
    void invalidImageDoesNotLeaveTemporaryFilesOrExposeDiskPath() throws Exception {
        Set<String> before = storedNames();
        String response = mvc.perform(multipart("/admin/upload")
                        .file(new MockMultipartFile("file", "fake.png", "image/png", "plain text".getBytes(StandardCharsets.UTF_8))))
                .andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains(temporary.toString()));
        assertEquals(before, storedNames());
    }

    @Test
    void jsonBodyCannotReplaceMultipartFile() throws Exception {
        mvc.perform(post("/admin/upload").contentType("application/json").content("{\"file\":\"photo.png\"}"))
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请求Content-Type不受支持，请按接口要求提交"));
    }

    @Test
    void missingPublicImageIs404AndProtectedApiStillRequiresLogin() throws Exception {
        mvc.perform(get("/upload/not-found.png")).andExpect(status().isNotFound());
        mvc.perform(get("/app/order/list").param("pageNum", "1").param("pageSize", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void parentTraversalCannotReadOutsideTheUploadDirectory() throws Exception {
        Path outside = temporary.resolve("outside.txt"); Files.writeString(outside, "outside-secret");
        var response = mvc.perform(get("/upload/../outside.txt")).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("outside-secret"));
        assertNotEquals(200, response.getStatus());
    }

    @Test
    void realHttpMultipartUploadAndAnonymousGetRoundTripPreserveBytes() throws Exception {
        byte[] bytes = image("png", 12, 12);
        try (HttpClient client = client()) {
            HttpResponse<String> response = client.send(multipartRequest(bytes, "photo.png"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            Map<?, ?> result = json(response.body()); assertEquals(0, ((Number) result.get("code")).intValue());
            String url = (String) result.get("data");
            HttpResponse<byte[]> fetched = client.send(HttpRequest.newBuilder(uri(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, fetched.statusCode()); assertArrayEquals(bytes, fetched.body());
            assertTrue(fetched.headers().firstValue("Content-Type").orElse("").startsWith("image/png"));
        }
    }

    @Test
    void realServletRejectsFileLargerThanFiveMegabytesBeforeController() throws Exception {
        Set<String> before = storedNames();
        byte[] tooLarge = new byte[5 * 1024 * 1024 + 1];
        try (HttpClient client = client()) {
            HttpResponse<String> response = client.send(multipartRequest(tooLarge, "too-large.png"), HttpResponse.BodyHandlers.ofString());
            Map<?, ?> result = json(response.body());
            assertEquals(1, ((Number) result.get("code")).intValue());
            assertEquals("上传图片过大，请选择更小的图片", result.get("message"));
            assertEquals(before, storedNames());
        }
    }

    @Test
    void realMalformedMultipartBoundaryHasReadableFailure() throws Exception {
        try (HttpClient client = client()) {
            HttpRequest request = HttpRequest.newBuilder(uri("/admin/upload")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "multipart/form-data")
                    .POST(HttpRequest.BodyPublishers.ofString("broken upload body")).build();
            Map<?, ?> result = json(client.send(request, HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(1, ((Number) result.get("code")).intValue());
            assertEquals("上传请求格式错误，请使用multipart/form-data上传图片", result.get("message"));
        }
    }

    private HttpRequest multipartRequest(byte[] bytes, String filename) throws Exception {
        String boundary = "upload-test-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename +
                "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri("/admin/upload")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
    }

    private HttpClient client() { return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private Map<?, ?> json(String value) { return objectMapper.readValue(value, Map.class); }
    private Path root() { return temporary.resolve("图片 uploads"); }
    private String filename(String url) { return url.substring(url.lastIndexOf('/') + 1); }
    private Set<String> storedNames() throws Exception {
        if (!Files.exists(root())) return Set.of();
        try (var files = Files.list(root())) { return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet()); }
    }
}
