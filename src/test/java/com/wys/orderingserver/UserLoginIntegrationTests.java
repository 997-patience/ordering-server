package com.wys.orderingserver;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import com.wys.orderingserver.Exception.BizException;
import com.wys.orderingserver.pojo.Result;
import com.wys.orderingserver.utils.JwtUtil;
import com.wys.orderingserver.utils.Md5Util;
import com.wys.orderingserver.utils.ThreadLocalUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实执行 Controller、Service、MyBatis SQL 与拦截器；Redis 使用带状态的模拟对象。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-login-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:store-page-test.sql,classpath:user-login-test.sql"
})
@AutoConfigureMockMvc
@Import(UserLoginIntegrationTests.TestEndpoints.class)
class UserLoginIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> tokenOperations;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepareUsersAndRedis() {
        ThreadLocalUtil.remove();
        tokens.clear();
        jdbcTemplate.update("delete from t_user");
        jdbcTemplate.update("insert into t_user(id, phone, password, nickname) values (?, ?, ?, ?)",
                1L, "13800000001", Md5Util.getMD5String("123456"), "小明");
        jdbcTemplate.update("insert into t_user(id, phone, password, nickname) values (?, ?, ?, ?)",
                2L, "13900000002", Md5Util.getMD5String("123456"), "小红");
        jdbcTemplate.update("update t_employee set password = ?, name = username, status = 1",
                Md5Util.getMD5String("123456"));
        tokenOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(tokenOperations);
        when(tokenOperations.get(anyString())).thenAnswer(call -> tokens.get(call.getArgument(0)));
        doAnswer(call -> {
            tokens.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(tokenOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @AfterEach
    void requestMustNotLeaveIdentityOnWorkerThread() {
        assertNull(ThreadLocalUtil.get());
    }

    @Test
    void successfulLoginReturnsJwtAndStoresItInRedisForTwelveHours() throws Exception {
        Instant before = Instant.now();
        String token = loginUser("13800000001");
        Map<String, Object> claims = JwtUtil.parseToken(token);
        assertEquals(1L, ((Number) claims.get("id")).longValue());
        assertEquals("13800000001", claims.get("phone"));
        assertEquals("小明", claims.get("nickname"));
        assertEquals("user", claims.get("type"));
        assertEquals(Set.of("id", "phone", "nickname", "type"), claims.keySet());
        assertEquals(token, tokens.get(token));
        verify(tokenOperations).set(token, token, JwtUtil.EXPIRE_SECONDS, TimeUnit.SECONDS);
        Instant expires = JWT.decode(token).getExpiresAtAsInstant();
        assertTrue(expires.isAfter(before.plusSeconds(JwtUtil.EXPIRE_SECONDS - 2)));
        assertTrue(expires.isBefore(Instant.now().plusSeconds(JwtUtil.EXPIRE_SECONDS + 1)));
    }

    @Test
    void submittedIdAndNicknameCannotReplaceDatabaseIdentity() throws Exception {
        String response = mockMvc.perform(post("/app/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"password\":\"123456\",\"id\":999,\"nickname\":\"冒充他人\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        Map<String, Object> claims = JwtUtil.parseToken(JsonPath.read(response, "$.data"));
        assertEquals(1L, ((Number) claims.get("id")).longValue());
        assertEquals("小明", claims.get("nickname"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"phone\":\"13800000001\"}", "{\"password\":\"123456\"}",
            "{\"phone\":null,\"password\":\"123456\"}",
            "{\"phone\":\"13800000001\",\"password\":null}",
            "{\"phone\":\"   \",\"password\":\"123456\"}",
            "{\"phone\":\"13800000001\",\"password\":\"   \"}",
            "{\"phone\":\"13800000001\",\"password\":\"wrong\"}",
            "{\"phone\":\"13700000009\",\"password\":\"123456\"}"
    })
    void rejectedCredentialsUseDocumentedMessageAndNeverIssueToken(String body) throws Exception {
        mockMvc.perform(post("/app/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("手机号或密码错误"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        verify(tokenOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        assertTrue(tokens.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{broken-json"})
    void invalidRequestBodyUsesExistingGlobalErrorHandler(String body) throws Exception {
        mockMvc.perform(post("/app/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("请求参数格式错误，请检查请求体是否为合法 JSON"));
        assertTrue(tokens.isEmpty());
    }

    @Test
    void nextRequestRestoresIdentityFromTokenAndClearsItAfterwards() throws Exception {
        String token = loginUser("13800000001");
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.phone").value("13800000001"))
                .andExpect(jsonPath("$.data.type").value("user"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void twoUsersKeepIndependentTokensAndIdentities() throws Exception {
        String first = loginUser("13800000001");
        String second = loginUser("13900000002");
        assertNotEquals(first, second);
        for (int index = 1; index <= 2; index++) {
            mockMvc.perform(get("/app/_login-test/identity")
                            .header("Authorization", index == 1 ? first : second))
                    .andExpect(jsonPath("$.data.id").value(index));
        }
    }

    @Test
    void customerAndAdminWithSameNumericIdCannotUseEachOthersEndpoints() throws Exception {
        String customer = loginUser("13800000001");
        String admin = loginAdmin();
        assertNotEquals(customer, admin);
        assertEquals("admin", JwtUtil.parseToken(admin).get("type"));
        mockMvc.perform(get("/admin/info").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value("admin"));
        mockMvc.perform(get("/admin/info").header("Authorization", customer))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", admin))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void previouslyIssuedAdminTokenWithoutTypeStillWorksOnAdminSide() throws Exception {
        String token = cacheToken(Map.of("id", 1L, "role", 1, "username", "admin"));
        mockMvc.perform(get("/admin/info").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removingRedisEntryImmediatelyInvalidatesAnOtherwiseValidJwt() throws Exception {
        String token = loginUser("13800000001");
        tokens.remove(token);
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", token))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void deletedUserCannotContinueUsingOldToken() throws Exception {
        String token = loginUser("13800000001");
        jdbcTemplate.update("delete from t_user where id = 1");
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledEmployeeCannotContinueUsingAdminToken() throws Exception {
        String token = loginAdmin();
        jdbcTemplate.update("update t_employee set status = 0 where id = 1");
        mockMvc.perform(get("/admin/info").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cachedTokenStillRequiresValidJwtSignature() throws Exception {
        String valid = loginUser("13800000001");
        int signatureStart = valid.lastIndexOf('.') + 1;
        String tampered = valid.substring(0, signatureStart)
                + (valid.charAt(signatureStart) == 'A' ? 'B' : 'A') + valid.substring(signatureStart + 1);
        tokens.put(tampered, tampered);
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", tampered))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @MethodSource("invalidUserIdentities")
    void invalidIdentityClaimsCannotEnterProtectedAppEndpoint(Map<String, Object> claims) throws Exception {
        mockMvc.perform(get("/app/_login-test/identity").header("Authorization", cacheToken(claims)))
                .andExpect(status().isUnauthorized());
    }

    static Stream<Map<String, Object>> invalidUserIdentities() {
        return Stream.of(
                Map.of("type", "user", "id", "1", "phone", "13800000001"),
                Map.of("type", "user", "id", 0, "phone", "13800000001"),
                Map.of("type", "user", "phone", "13800000001"),
                Map.of("type", "user", "id", 1),
                Map.of("type", "user", "id", 2, "phone", "13800000001"),
                Map.of("type", "user", "id", 1, "phone", "13800000001", "role", 1),
                Map.of("type", "admin", "id", 1),
                Map.of("type", "unknown", "id", 1),
                Map.of("id", 1, "phone", "13800000001")
        );
    }

    @Test
    void missingTokenClearsAnyPreviouslyLeftThreadLocalIdentity() throws Exception {
        ThreadLocalUtil.set(Map.of("id", 999L, "type", "user"));
        mockMvc.perform(get("/app/_login-test/identity"))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
    }

    @Test
    void protectedBusinessFailureAlsoClearsThreadLocal() throws Exception {
        mockMvc.perform(get("/app/_login-test/failure").header("Authorization", loginUser("13800000001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("模拟业务异常"));
    }

    @Test
    void redisFailureDoesNotReturnAnUnusableTokenOrLeaveAuthenticatedContext() throws Exception {
        doThrow(new RedisConnectionFailureException("test outage")).when(tokenOperations)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        mockMvc.perform(post("/app/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.message").value("登录服务暂不可用，请稍后重试"))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
        assertTrue(tokens.isEmpty());
    }

    @Test
    void newlyRegisteredUserCanLogInWithTheOriginalPassword() throws Exception {
        mockMvc.perform(post("/app/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13700000003\",\"password\":\"new123\",\"nickname\":\"新顾客\"}"))
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(Md5Util.getMD5String("new123"), jdbcTemplate.queryForObject(
                "select password from t_user where phone = ?", String.class, "13700000003"));
        mockMvc.perform(post("/app/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13700000003\",\"password\":\"new123\"}"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").isString());
    }

    private String loginUser(String phone) throws Exception {
        String response = mockMvc.perform(post("/app/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data").isString()).andReturn().getResponse().getContentAsString();
        assertNull(ThreadLocalUtil.get());
        return JsonPath.read(response, "$.data");
    }

    private String loginAdmin() throws Exception {
        String response = mockMvc.perform(post("/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.data");
    }

    private String cacheToken(Map<String, Object> claims) {
        String token = JwtUtil.genToken(claims);
        tokens.put(token, token);
        return token;
    }

    /** 仅测试上下文中的入口，用于观察身份恢复；没有实现任何 8.x 接口。 */
    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class TestEndpoints {
        @GetMapping("/app/_login-test/identity")
        Result<Map<String, Object>> identity() {
            return Result.success(ThreadLocalUtil.get());
        }

        @GetMapping("/app/_login-test/failure")
        Result<Void> failure() {
            throw new BizException("模拟业务异常");
        }
    }
}
